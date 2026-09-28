#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Ingestion stall — ad-hoc tight repro harness driver
# (docs/INGESTION_ADHOC_REPRO_HARNESS.md §5.2, §7, §8 — agent-runnable arms)
#
# Builds once, installs debug + androidTest APKs, then drives ONE harness arm on
# the connected device via `am instrument`, capturing logcat to a file (§7) and
# grepping the HARNESS_SUMMARY verdict lines out of it.
#
# Usage:
#   tools/ingestion_harness.sh --arm A1 --n 500              # smoke
#   tools/ingestion_harness.sh --arm A1 --n 10000            # the cliff
#   tools/ingestion_harness.sh --arm PRESEED --preseed 8000 --n 200
#   tools/ingestion_harness.sh --arm A2 --n 8000 --budget-min 60
#   tools/ingestion_harness.sh --arm INJECT --n 200          # red-capability self-check
#   tools/ingestion_harness.sh --arm A1 --n 500 --expect-red # validation runs
#   SKIP_BUILD=1 tools/ingestion_harness.sh --arm A1 --n 10000   # APKs already current
#
# Regression gate (§16 of the harness doc — issue 04): the fast gate runs after
# EVERY write-path change (~5 min):
#   tools/ingestion_harness.sh --arm PRESEED --preseed 6000 --n 200
# and the deep gate pre-release (on demand, ~75 min):
#   tools/ingestion_harness.sh --arm A1 --n 10000
# The HARNESS_SUMMARY line ends with "gate":"GREEN|RED(…)"; a RED gate makes
# this script exit non-zero (threshold breach = regression, not a finding).
#
# Exit code: non-zero on a red arm, an assert failure, or a GATE RED — a red
# ARM is a *finding*, read HARNESS_SUMMARY / the logcat file; a red GATE is a
# regression.
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

cd "$(dirname "$0")/.."

ARM="A1"; N="500"; PRESEED="0"; BUDGET_MIN=""; EXPECT_RED="false"; RECYCLE_BITMAPS="false"; KEEP="false"; STORE_DEBUG="false"; CORPUS_COUNT=""; EXTRA_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --arm) ARM="$2"; shift 2;;
    --n) N="$2"; shift 2;;
    --preseed) PRESEED="$2"; shift 2;;
    --budget-min) BUDGET_MIN="$2"; shift 2;;
    --expect-red) EXPECT_RED="true"; shift;;
    --recycle-bitmaps) RECYCLE_BITMAPS="true"; shift;;
    --keep) KEEP="true"; shift;;
    --store-debug) STORE_DEBUG="true"; shift;;
    # Decouple the generated image corpus from N (e.g. --n 8000 with a pre-generated
    # 18k corpus). Without it the corpus regenerates at exactly N.
    --corpus-count) CORPUS_COUNT="$2"; shift 2;;
    *) EXTRA_ARGS+=("$1"); shift;;
  esac
done

PKG="io.github.tzhvh.scryernext.debug"
TEST_PKG="${PKG}.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
CLASS="io.github.tzhvh.scryernext.ingestion.harness.IngestionHarnessDeviceTest"
DEBUG_APK="app/build/outputs/apk/go/debug/app-go-debug.apk"
TEST_APK="app/build/outputs/apk/androidTest/go/debug/app-go-debug-androidTest.apk"

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  echo ">>> building goDebug + goDebugAndroidTest …"
  ./gradlew -q :app:assembleGoDebug :app:assembleGoDebugAndroidTest || exit 2
fi

echo ">>> installing APKs …"
adb install -r -t "$DEBUG_APK" >/dev/null || exit 2
adb install -r -t "$TEST_APK" >/dev/null || exit 2

STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="build/harness/${ARM}-n${N}-ps${PRESEED}-${STAMP}"
mkdir -p "$(dirname "$OUT")"
LOG="${OUT}.logcat"

echo ">>> logcat capture → $LOG (32M buffer, §7)"
adb logcat -G 32M
adb logcat -c
# The harness tags are the signal; everything else is noise at 10k docs.
adb logcat -v time IngestionHarness:V IngestionRecorder:D ZvecEventRecorder:D \
  IngestionWorker:D AndroidRuntime:E *:S > "$LOG" &
LOGCAT_PID=$!
trap 'kill $LOGCAT_PID 2>/dev/null' EXIT

echo ">>> running arm=$ARM n=$N preseed=$PRESEED budgetMin=${BUDGET_MIN:-default} …"
INSTRUMENT_OUTPUT="$(adb shell am instrument -w \
  -e class "$CLASS" \
  -e arm "$ARM" -e n "$N" -e preseed "$PRESEED" \
  -e budgetMin "${BUDGET_MIN:-0}" -e expectRed "$EXPECT_RED" \
  -e recycleBitmaps "$RECYCLE_BITMAPS" -e keep "$KEEP" \
  -e storeDebug "$STORE_DEBUG" ${CORPUS_COUNT:+-e corpusCount "$CORPUS_COUNT"} \
  "$TEST_PKG/$RUNNER" 2>&1)"
echo "$INSTRUMENT_OUTPUT" | tail -5
echo "$INSTRUMENT_OUTPUT" > "${OUT}.instrument"

sleep 1
kill $LOGCAT_PID 2>/dev/null

echo ""
echo ">>> HARNESS_SUMMARY lines:"
grep -h "HARNESS_SUMMARY\|HARNESS_PRESEED" "$LOG" | sed 's/.*HARNESS_/HARNESS_/' || echo "(none — read $LOG)"
echo ""

# `am instrument` does not propagate failure exit codes on all devices — parse
# the summary line instead (a green run prints "OK (1 test)").
INSTR_OK="false"
if echo "$INSTRUMENT_OUTPUT" | grep -q "^OK ("; then
  INSTR_OK="true"
fi

# The regression gate (§16, issue 04): belt-and-braces on the device-side
# assertion (which already fails the instrumentation on breach) — grep the
# summary's gate verdict so the exit is non-zero even where `am instrument`
# swallows the failure. Matched loosely (`"gate":"RED`) so the breach reasons
# ride along in the echoed line above.
if grep -q '"gate":"RED' "$LOG"; then
  echo ">>> GATE RED — threshold breach (fds/writeMsAmort; §16). See HARNESS_SUMMARY above."
  exit 1
fi

if [[ "$INSTR_OK" == "true" ]]; then
  echo ">>> instrumentation: OK   logcat: $LOG"
  exit 0
else
  echo ">>> instrumentation: FAILED   logcat: $LOG   full output: ${OUT}.instrument"
  exit 1
fi
