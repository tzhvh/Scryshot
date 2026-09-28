/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion.harness

/**
 * The harness's verdict vocabulary — the pure, device-free half of
 * `docs/INGESTION_ADHOC_REPRO_HARNESS.md`'s three-arm differential (§4) and its
 * decision matrix (§6). Arms run on-device (native zvec / bitmaps / ML Kit); the
 * *interpretation* is pure so it is JVM-testable against every row of §6 — the
 * repo's pure-helper convention (`bulkResultDecision`, `isPending`, `bannerMode`).
 *
 * The discriminator rule the matrix encodes: each arm adds exactly one
 * accumulator, so "arm N clean, arm N+1 fails" is a root-cause **name**, and the
 * memory/latency shape splits the pairs that share an arm (H1-vs-H4 inside A1,
 * H2-vs-H3 across A2/A3).
 */

/** The differential arms, each a superset of the last (§4). */
enum class HarnessArm(val label: String) {
    A1("A1 +zvec/Room (fake OCR)"),
    A2("A2 +decode (real bitmap, canned text)"),
    A3("A3 +recognize (full ML Kit)"),
}

/**
 * One arm's terminal outcome.
 *
 * - [CLEAN] — the run reached [terminal success] with (≈) all candidates indexed.
 * - [RED_THROW] — a run-level failure surfaced (`Progress.Error`, an OOM, or any
 *   collection-loop throw): the *break* signature.
 * - [STALL_FAILED_N] — the run *completed* but with `failed ≈ N` (the H5
 *   transient-tail signature) or indexed ≈ 0.
 * - [CRAWL_TIMEOUT] — the run never reached a terminal inside the arm's budget:
 *   the H4 "a crawl, not a break" signature (the loop stays alive, per-doc cost
 *   climbs, no terminal ever emits).
 * - [SKIPPED] — the arm was not run (the sequencing stops at the first red).
 */
enum class ArmOutcomeKind { CLEAN, RED_THROW, STALL_FAILED_N, CRAWL_TIMEOUT, SKIPPED }

data class ArmResult(
    val arm: HarnessArm,
    val kind: ArmOutcomeKind,
    val indexed: Int = 0,
    val failed: Int = 0,
    val total: Int = 0,
    val errorMessage: String? = null,
) {
    companion object {
        val SKIPPED_A1 = ArmResult(HarnessArm.A1, ArmOutcomeKind.SKIPPED)
        val SKIPPED_A2 = ArmResult(HarnessArm.A2, ArmOutcomeKind.SKIPPED)
        val SKIPPED_A3 = ArmResult(HarnessArm.A3, ArmOutcomeKind.SKIPPED)
    }

    /** Anything that is not a clean success or a skip counts as "the arm fires". */
    val fires: Boolean
        get() = kind != ArmOutcomeKind.CLEAN && kind != ArmOutcomeKind.SKIPPED
}

/**
 * The memory-curve shape the side-car sampler observed during a run (§5.3) —
 * "the discriminator is the *shape* of the curve, not just 'it OOMed'":
 *
 * - [FLAT] — no accumulator climbing: failure, if any, is a cost curve (H4).
 * - [JAVA_SAWTOOTH_PEAK] — Java heap cycling to a peak (GC churn under bitmap /
 *   byte-array pressure): **H2**.
 * - [NATIVE_MONOTONIC] — native/graphics climbing per call regardless of doc
 *   size: **H3** (recognizer state) or a native leak generally.
 * - [NATIVE_TO_CAP] — native/mmap growth with doc count toward the ~512 MB
 *   `memory_limit_mb` cap with a hard failure: **H1**.
 */
enum class MemoryShape { FLAT, JAVA_SAWTOOTH_PEAK, NATIVE_MONOTONIC, NATIVE_TO_CAP }

/** Per-doc `writeMs` cost trend across a run — the H4 latency-shape axis. */
enum class WriteTrend { FLAT, RISING, SUPERLINEAR }

/**
 * The regression-gate verdict (`INGESTION_ADHOC_REPRO_HARNESS.md` §16) — issue
 * `04`: the harness that found the fd cliff becomes the write path's regression
 * gate. Pure over two numbers the arms already measure, so the thresholds are
 * JVM-pinned here and the summary/device-assertion/host-script all read the
 * same verdict.
 *
 * The two assertions guard the two halves of the 2026-09-28 root cause
 * (§15.1): a per-file flush cadence regression shows up as **amortized write
 * cost** returning to the ~0.45–0.75 s/doc fsync floor (measured §15.4; the
 * green batched path amortizes at ~26 ms/doc on the field device), while an
 * fd leak that doesn't slow writes (segments/SSTs held open without a cost
 * cliff) only shows up in the **fd count**. Either breach reds the gate; the
 * driver script then exits non-zero (a red gate is a regression, not a
 * finding — unlike a red *arm*).
 */
object HarnessGate {

    /**
     * One gate check's outcome — [pass] plus the human-readable breach
     * [reasons] (empty iff pass) and [skips] (assertions that did not apply —
     * a named skip, never a silent one). [label] is the single token the
     * `HARNESS_SUMMARY` line and the host script grep read: `GREEN`,
     * `GREEN(<skip>,…)` when an assertion was skipped, or `RED(<reason>,…)`.
     * A skip must not fail the gate (an absent sampler cannot breach a
     * threshold) — but it must be *named*, so a silent sampler can't fake a
     * clean green either.
     */
    data class Verdict(
        val pass: Boolean,
        val reasons: List<String>,
        val skips: List<String> = emptyList(),
    ) {
        val label: String
            get() = when {
                !pass -> "RED(${reasons.joinToString(",")})"
                skips.isNotEmpty() -> "GREEN(${skips.joinToString(",")})"
                else -> "GREEN"
            }
    }

    /**
     * Peak open-fd cap. Calibrated against the green fast gate on the field
     * device (SM-G950F, `--arm PRESEED --preseed 6000 --n 200`, 2026-09-28,
     * `build/harness/runs/harness-PRESEED-n200-ps6000-20260928-135041.json`):
     * green ran at a baseline of ~149 fds and peaked at **169** — the two
     * batch flushes + terminal checkpoint of a 200-doc run add ≈6 fds each
     * (§15.1's per-flush rate). The cap must also clear the GREEN deep gate
     * (`A1 --n 10000` ≈ 149 + 101 batch flushes × ~6 ≈ ~755, model-based), so
     * 1024 sits ~6× green fast-gate / ~35% above modeled deep-gate green /
     * ~3% of the 32,768 hard cap — and a regression that reverts to per-file
     * flush (~+5.5 fds/doc) crosses it within ~160 docs.
     */
    const val FD_CAP = 1024

    /**
     * Amortized per-doc write cost cap, ms/doc — mean of the per-commit
     * `writeMs` samples (batch-boundary commits carry their flush, so the mean
     * is the honest amortized durability cost; `ZvecWriteSink`'s latency
     * semantics). Measured on the field device (2026-09-28, the green fast
     * gate above): p50 upsert ≈ 10.6 ms at a 6k collection, the two
     * ~1.4–1.65 s batch flushes amortize into a **26.2 ms/doc** green mean —
     * real-hardware green is upsert-dominated, not flush-dominated (§15.4's
     * ~4.2 ms/doc was the flush cost alone). The regressed per-file cadence
     * sat at 447–530 ms/doc (§15.1, every commit = one flush). 100 ms splits
     * the two worlds ~3.8× above green and ~5× below red.
     */
    const val WRITE_MS_AMORT_CAP = 100.0

    /**
     * The verdict over a run's measured gate inputs. [fdsMax] ≤ 0 means the fd
     * sampler was unavailable (a restricted `/proc/self/fd` reports 0, never
     * null) — the fd assertion is skipped rather than trivially passed, and the
     * skip is named in the verdict's label so a silent sampler can't fake a
     * green. [writeMsAmort] null (a run that wrote nothing — zero write
     * samples) likewise skips the write assertion, unnamed (nothing measured,
     * nothing to skip-report).
     */
    fun verdict(fdsMax: Int, writeMsAmort: Double?): Verdict {
        val breaches = ArrayList<String>(2)
        val skips = ArrayList<String>(1)
        if (fdsMax > FD_CAP) {
            breaches.add("fdsMax=$fdsMax>$FD_CAP")
        } else if (fdsMax <= 0) {
            skips.add("fd-assert-skipped:no-sampler")
        }
        if (writeMsAmort != null && writeMsAmort > WRITE_MS_AMORT_CAP) {
            breaches.add(
                "writeMsAmort=${"%.1f".format(java.util.Locale.US, writeMsAmort)}>$WRITE_MS_AMORT_CAP"
            )
        }
        return Verdict(breaches.isEmpty(), breaches, skips)
    }
}

/** The §3 hypothesis space; the matrix's output vocabulary. */
enum class Hypothesis(val label: String) {
    H1("H1 — zvec native write/flush fails as the collection grows"),
    H2("H2 — OOM from full-res bitmap + full-file bytes"),
    H3("H3 — ML Kit process-singleton leak"),
    H4("H4 — per-file flush() is O(n) — a crawl, not a break"),
    H5("H5 — transient-OCR tail re-poisons processed=0"),
    NONE_AT_N("none at N — raise N, or the field device diverges"),
}

object HarnessDecisionMatrix {

    /**
     * `docs/INGESTION_ADHOC_REPRO_HARNESS.md` §6, encoded row by row. Run
     * top-to-bottom; the first row whose left columns fire **is** the verdict.
     * H1-vs-H4 (the shared zvec-size accumulator) splits on the memory shape and
     * the write trend; H5 fires only when every real-work arm stayed clean yet
     * the full-OCR run "completed" with almost nothing actually indexed.
     */
    fun decide(
        a1: ArmResult,
        a2: ArmResult,
        a3: ArmResult,
        mem: MemoryShape,
        writeTrend: WriteTrend,
    ): Hypothesis = when {
        // Row 1/2 — the zvec-at-scale arm fires. Split H1 (throw, native→cap)
        // from H4 (crawl, flat mem, rising per-doc cost).
        a1.fires -> when {
            mem == MemoryShape.NATIVE_TO_CAP -> Hypothesis.H1
            a1.kind == ArmOutcomeKind.CRAWL_TIMEOUT ||
                writeTrend != WriteTrend.FLAT -> Hypothesis.H4
            else -> Hypothesis.H1 // a hard throw near N with flat memory is still the zvec write path
        }
        // Row 3 — decode-only fires where zvec was clean: the bitmap/bytes axis.
        a2.fires -> Hypothesis.H2
        // Row 4/5 — full ML Kit fires where decode was clean: the recognizer
        // axis (H3), unless the failure is a "successful" run that indexed
        // nothing (H5 — the transient tail).
        a3.fires -> when {
            a3.kind == ArmOutcomeKind.STALL_FAILED_N && mem == MemoryShape.FLAT -> Hypothesis.H5
            else -> Hypothesis.H3
        }
        // Row 6 — clean sweep at this N.
        else -> Hypothesis.NONE_AT_N
    }
}

/**
 * One side-car memory sample (§5.3). All values MB; `doc` is the candidate count
 * the run had reached when the sample fired (the x-axis every shape reads).
 * [fds] is the process open-fd count — the accumulator behind the A1 cliff
 * (per-file flush → per-flush segment/SST files held open → the 32,768 fd cap),
 * sampled so future runs see the fd wall in-process.
 */
data class MemSample(
    val tMs: Long,
    val doc: Int,
    val javaMb: Double,
    val nativeMb: Double,
    val graphicsMb: Double,
    val totalPssMb: Double,
    val rssMb: Double,
    val fds: Int = 0,
)

object HarnessSignals {

    /**
     * Classify a run's memory curve into a [MemoryShape] (§5.3's discriminator).
     * Heuristics on the *shape*, tuned to be stable to GC jitter:
     *
     * - **sawtooth** (java): ≥ 3 rise-then-fall cycles of ≥ 15% rise followed by
     *   ≥ 25% give-back — GC-churn under allocation pressure (H2). Checked first:
     *   a sawtooth run's *mean* native line stays flat even as the allocator
     *   screams.
     * - **to-cap** (native): a climb of ≥ 128 MB ending above 384 MB (the 512 MB
     *   cap's neighbourhood) — H1's hard-failure signature.
     * - **monotonic** (native): climb ≥ 48 MB with ≥ 85% of consecutive samples
     *   non-decreasing (± 3 MB jitter allowed) — H3's per-call leak signature.
     * - else **flat**.
     *
     * Fewer than [MIN_SAMPLES] samples → [MemoryShape.FLAT] (the sampler is a
     * side-car; a smoke-length run has no curve worth naming).
     */
    fun classifyMemoryCurve(samples: List<MemSample>): MemoryShape {
        if (samples.size < MIN_SAMPLES) return MemoryShape.FLAT

        val java = samples.map { it.javaMb }
        if (countGcCycles(java) >= 3) return MemoryShape.JAVA_SAWTOOTH_PEAK

        val native = samples.map { it.nativeMb }
        val head = native.take(HEAD_TAIL_WINDOW).average()
        val tail = native.takeLast(HEAD_TAIL_WINDOW).average()
        val climb = tail - head
        if (climb >= TO_CAP_CLIMB_MB && tail >= TO_CAP_TAIL_MB) return MemoryShape.NATIVE_TO_CAP
        if (climb >= MONOTONIC_CLIMB_MB && monotonicFraction(native, jitterMb = 3.0) >= 0.85) {
            return MemoryShape.NATIVE_MONOTONIC
        }
        return MemoryShape.FLAT
    }

    /**
     * Classify the per-doc `writeMs` series (A1's H4 axis). Windowed means of the
     * first / middle / last tenth: a growing index makes every write + flush
     * costlier, so the tail window lifts above the head; acceleration across all
     * three windows marks superlinearity. Fewer than [MIN_WRITE_SAMPLES] samples
     * (smoke runs) → [WriteTrend.FLAT].
     */
    fun classifyWriteTrend(writeMs: List<Double>): WriteTrend {
        if (writeMs.size < MIN_WRITE_SAMPLES) return WriteTrend.FLAT
        val w = (writeMs.size / 10).coerceAtLeast(1)
        fun windowMean(from: Int): Double {
            val slice = writeMs.subList(from, (from + w).coerceAtMost(writeMs.size))
            return slice.filter { it > 0.0 }.ifEmpty { slice }.average()
        }
        val head = windowMean(0)
        val mid = windowMean(writeMs.size / 2)
        val tail = windowMean(writeMs.size - w)
        if (head <= 0.0) return WriteTrend.FLAT
        val rising = tail / head >= 2.0
        val accelerating = tail / mid >= 1.6 && mid / head >= 1.6
        return when {
            rising && accelerating -> WriteTrend.SUPERLINEAR
            rising -> WriteTrend.RISING
            else -> WriteTrend.FLAT
        }
    }

    /**
     * GC release events in a heap series: a sample falling ≥ 25% below the
     * running maximum (and only one event per climb to a new high). Three such
     * cycles — repeated grow-to-peak-then-release — is the sawtooth signature;
     * ±few-MB sampling jitter cannot produce it. [MIN_SAWTOOTH_HEAP_MB] keeps
     * tiny absolute heaps (a 24 MB JVM heap dropping 7 MB between GCs) from
     * reading as a sawtooth — the H2 shape needs real allocation pressure.
     */
    private fun countGcCycles(series: List<Double>): Int {
        if (series.size < 2) return 0
        var cycles = 0
        var runningMax = series.first()
        var countedThisClimb = false
        for (v in series.asSequence().drop(1)) {
            if (v >= runningMax) {
                runningMax = v
                countedThisClimb = false
            } else if (!countedThisClimb && runningMax >= MIN_SAWTOOTH_HEAP_MB &&
                v <= runningMax * SAWTOOTH_GIVEBACK
            ) {
                cycles++
                countedThisClimb = true
            }
        }
        return cycles
    }

    /** Fraction of consecutive samples that never fall by more than [jitterMb]. */
    private fun monotonicFraction(series: List<Double>, jitterMb: Double): Double {
        var nonDecreasing = 0
        for (i in 1 until series.size) {
            if (series[i] >= series[i - 1] - jitterMb) nonDecreasing++
        }
        return nonDecreasing.toDouble() / (series.size - 1)
    }

    private const val MIN_SAMPLES = 8
    private const val MIN_WRITE_SAMPLES = 40
    private const val HEAD_TAIL_WINDOW = 5
    private const val SAWTOOTH_GIVEBACK = 0.75
    private const val MIN_SAWTOOTH_HEAP_MB = 64.0
    private const val MONOTONIC_CLIMB_MB = 48.0
    private const val TO_CAP_CLIMB_MB = 128.0
    private const val TO_CAP_TAIL_MB = 384.0
}
