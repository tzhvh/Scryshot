package io.github.tzhvh.scryernext.ingestion.harness

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import io.github.tzhvh.scryernext.ingestion.Candidate
import io.github.tzhvh.scryernext.ingestion.MlKitOcrStage
import io.github.tzhvh.scryernext.ingestion.OcrStage
import io.github.tzhvh.scryernext.ingestion.WriteSink
import io.github.tzhvh.scryernext.ingestion.ZvecContentStore
import io.github.tzhvh.scryernext.ingestion.ZvecWriteSink
import io.github.tzhvh.scryernext.measureNs
import io.github.tzhvh.scryernext.persistence.ContentMetadataCacheDaoFake
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The ingestion repro harness's device entry point (`INGESTION_ADHOC_REPRO_HARNESS.md`
 * §5.2, §13): one [Test] that dispatches on the `arm` instrumentation extra, so the
 * host script drives every arm from a single `am instrument` invocation with full
 * per-arm parameters instead of a gradle round-trip each time.
 *
 * ```
 * adb shell am instrument -w \
 *   -e class io.github.tzhvh.scryernext.ingestion.harness.IngestionHarnessDeviceTest \
 *   -e arm A1 -e n 10000 -e preseed 0 -e budgetMin 30 \
 *   io.github.tzhvh.scryernext.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * Arms (§4 — real `ZvecWriteSink` in every one; only `OcrStage` varies):
 * - **A1** — [FakeOcrStage], synthetic byte payloads: the zvec/Room-at-scale question (H1/H4).
 * - **A2** — [DecodeOnlyOcrStage] over the real two-letter JPEG corpus: + bitmap churn (H2).
 * - **A3** — [MlKitOcrStage] over the same corpus: + recognizer state (H3/H5) — last resort.
 * - **PRESEED** — §5.1: bulk-seed [preseed] docs and keep them, then run the real sink over
 *   a small live batch against that inflated collection; red = writes failing / cost climbing
 *   near the cliff. Seeded docs are cleaned (C4) in `finally`.
 * - **INJECT** — the §8 red-capability self-check: a sink that throws mid-run must surface as
 *   `Progress.Error` → [ArmOutcomeKind.RED_THROW]; the test passes only when the harness sees red.
 *
 * Every run emits one `HARNESS_SUMMARY {…}` logcat line (greppable) and a full-curve JSON
 * file under `<cacheDir>/harness-runs/` (pull via `run-as`). The test **fails** on a red arm —
 * a red is a finding, and `am instrument`'s non-zero exit is how the host script sees it —
 * unless `expectRed=true` is passed.
 *
 * Isolation: each run gets a fresh `cacheDir/harness-<ts>` collection directory and an
 * in-memory repository — nothing here can touch the installed app's production zvec
 * collection or Room DB (the `ZvecContentStoreDeviceTest` fixture pattern); the whole
 * directory is deleted in `finally` (C4, §10).
 */
@RunWith(AndroidJUnit4::class)
class IngestionHarnessDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun harness() = runBlocking {
        val armName = args.getString("arm") ?: DEFAULT_ARM
        val n = args.getString("n")?.toIntOrNull() ?: DEFAULT_N
        val preseed = args.getString("preseed")?.toIntOrNull() ?: 0
        val expectRed = args.getString("expectRed") == "true"
        val recycleBitmaps = args.getString("recycleBitmaps") == "true"
        // The store's `debug` flag selects the NATIVE log level (DEBUG writes every
        // op to the rotating zvec log files — significant per-op disk I/O on this
        // device). Default false = the release-like INFO logging the field build ran.
        val storeDebug = args.getString("storeDebug") == "true"
        // Forensics mode: keep the test-scoped run directory (zvec native logs live
        // under <runRoot>/logs/zvec/) for post-run inspection instead of deleting it.
        val keepRunDir = args.getString("keep") == "true"
        // Image-corpus size for the GEN arm; A2/A3 default their corpus to N.
        val corpusCount = args.getString("corpusCount")?.toIntOrNull() ?: n
        val budgetMin = args.getString("budgetMin")?.toLongOrNull()
        val runRoot = File(ctx.cacheDir, "harness-run-${System.nanoTime()}")
        var summaryJson = "unset"
        var seeder: BulkZvecSeeder? = null

        try {
            val store = ZvecContentStore(runRoot, debug = storeDebug)
            val repo = HarnessInMemoryRepository()
            val driver = HarnessDifferentialDriver(store, repo)
            val realSink = ZvecWriteSink(
                repository = repo,
                zvecContentStore = store,
                metadataCacheDaoProvider = { ContentMetadataCacheDaoFake() },
            )

            var output: HarnessDifferentialDriver.RunOutput

            when (armName) {
                "A1" -> {
                    // One repo row per locator — without it the real sink's Model-B
                    // missing-row guard skips every write and the arm runs fake-green.
                    repo.addScreenshot(HarnessCorpus.screenshotRows(n))
                    output = driver.run(
                        HarnessArm.A1, FakeOcrStage(), realSink,
                        HarnessCorpus.candidates(n), budget(budgetMin, DEFAULT_BUDGET_MIN_FAST),
                    )
                }
                "A2", "A3", "GEN" -> {
                    // corpusCount (default = n) decouples the generated corpus from
                    // the run's N, so one 18k corpus (field scale) serves every arm.
                    val corpusDir = HarnessImageCorpus.ensure(
                        ctx, HarnessImageCorpus.Spec(count = corpusCount),
                    ) { done, total -> Log.i(TAG, "corpus $done/$total") }
                    if (armName == "GEN") {
                        Log.w(TAG, "HARNESS_SUMMARY {\"arm\":\"GEN\",\"corpus\":\"$corpusDir\",\"count\":$corpusCount}")
                        return@runBlocking
                    }
                    repo.addScreenshot(
                        HarnessCorpus.screenshotRows(n) { HarnessImageCorpus.fileSize(corpusDir, it) },
                    )
                    val stage: OcrStage = if (armName == "A2") {
                        DecodeOnlyOcrStage(recycle = recycleBitmaps)
                    } else {
                        MlKitOcrStage()
                    }
                    output = driver.run(
                        if (armName == "A2") HarnessArm.A2 else HarnessArm.A3,
                        stage, realSink,
                        HarnessImageCorpus.candidates(corpusDir, n),
                        budget(budgetMin, DEFAULT_BUDGET_MIN_SLOW),
                    )
                }
                "PRESEED" -> {
                    require(preseed > 0) { "PRESEED needs -e preseed N" }
                    seeder = BulkZvecSeeder(store)
                    // §5.1's per-doc signal, split: upsert vs flush at size 0 and at
                    // the seeded size — the H1-vs-H4 discriminator at micro scale.
                    writeProbe(store, "before-seed")
                    val seedStart = System.currentTimeMillis()
                    seeder.seed(preseed, { HarnessCorpus.ocrTextFor(it) }) { done, total ->
                        Log.i(TAG, "seeding $done/$total")
                    }
                    repo.addScreenshot(HarnessCorpus.screenshotRows(n))
                    val preSeedDocCount = store.docCount()
                    writeProbe(store, "after-seed(preseed=$preseed)")
                    Log.i(
                        TAG,
                        "HARNESS_PRESEED seeded=$preseed in ${(System.currentTimeMillis() - seedStart) / 1000.0}s " +
                            "docCountAfterSeed=$preSeedDocCount; running live batch of $n"
                    )
                    output = driver.run(
                        HarnessArm.A1, FakeOcrStage(), realSink,
                        HarnessCorpus.candidates(n), budget(budgetMin, DEFAULT_BUDGET_MIN_FAST),
                    )
                    summaryJson = output.toSummaryJson()
                    logAndPersist(armName, n, preseed, output, summaryJson, store.docCount())
                    reportAndMaybeFail(armName, expectRed, output)
                    return@runBlocking
                }
                "INJECT" -> {
                    val injectAt = args.getString("injectAt")?.toIntOrNull() ?: (n / 2)
                    repo.addScreenshot(HarnessCorpus.screenshotRows(n))
                    output = driver.run(
                        HarnessArm.A1, FakeOcrStage(),
                        ThrowingSink(realSink, injectAt),
                        HarnessCorpus.candidates(n), budget(budgetMin, DEFAULT_BUDGET_MIN_FAST),
                    )
                    summaryJson = output.toSummaryJson()
                    logAndPersist(armName, n, preseed, output, summaryJson, null)
                    assertEquals(
                        "INJECT: the harness must see red when the sink throws mid-run (§8 red-capability)",
                        ArmOutcomeKind.RED_THROW, output.kind,
                    )
                    assertTrue(
                        "INJECT: the throw must be reported alongside (the §1 recoverability input)",
                        output.errorMessage?.contains(ThrowingSink.MSG) == true,
                    )
                    return@runBlocking
                }
                else -> error("unknown arm '$armName' (A1|A2|A3|PRESEED|INJECT)")
            }

            summaryJson = output.toSummaryJson()
            logAndPersist(armName, n, preseed, output, summaryJson, store.docCount())
            reportAndMaybeFail(armName, expectRed, output)
        } finally {
            // C4 (§10): delete the seeded docs through the engine surface first, then
            // take the whole test-scoped directory — a killed run cannot leak orphans.
            runCatching { seeder?.cleanup() }
            if (keepRunDir) {
                Log.w(TAG, "HARNESS_KEEP run dir kept for forensics: $runRoot")
            } else {
                runRoot.deleteRecursively()
            }
        }
    }

    /** PRESEED keeps its own path (it logs the seeder's numbers); arms A1–A3 land here. */
    private fun reportAndMaybeFail(
        armName: String,
        expectRed: Boolean,
        output: HarnessDifferentialDriver.RunOutput,
    ) {
        if (output.kind == ArmOutcomeKind.CLEAN) {
            assertEquals(
                "$armName: a clean arm must index every candidate — a dedup-skip here means the " +
                    "corpus is byte-colliding and the arm measured nothing",
                output.total, output.indexed,
            )
            assertEquals("$armName: zero dedup skips on a fresh corpus", 0, output.dedupSkips)
            if (output.docCountEnd != null) {
                assertTrue(
                    "$armName: docCount ${output.docCountEnd} < total ${output.total} — the sink wrote " +
                        "nothing (missing repo rows / Model-B guard); the arm would be fake-green",
                    output.docCountEnd!! >= output.total,
                )
            }
            // The regression gate (issue `04`, §16): a clean arm must ALSO clear the
            // fd / amortized-write thresholds — enforced only on clean arms because a
            // red arm already fails (unless expectRed, where the gate verdict is
            // still reported in the summary but the red IS the run's point).
            assertTrue(
                "$armName: GATE RED — ${output.gate.label} (fd cap ${HarnessGate.FD_CAP}, " +
                    "writeMsAmort cap ${HarnessGate.WRITE_MS_AMORT_CAP} ms/doc; §16 thresholds)",
                output.gate.pass,
            )
            if (expectRed) fail("$armName: expectRed was set but the arm came back clean")
        } else if (!expectRed) {
            fail("ARM $armName RED (${output.kind}) — a finding, not a harness failure:\n${output.toSummaryJson()}")
        }
    }

    private fun logAndPersist(
        armName: String,
        n: Int,
        preseed: Int,
        output: HarnessDifferentialDriver.RunOutput,
        summaryJson: String,
        docCount: Long?,
    ) {
        Log.w(TAG, "HARNESS_SUMMARY $summaryJson")
        val outDir = File(ctx.cacheDir, "harness-runs").apply { mkdirs() }
        val file = File(outDir, "harness-${armName}-n$n-ps$preseed-${System.currentTimeMillis()}.json")
        file.writeText(fullReport(output, summaryJson, docCount))
        Log.i(TAG, "full curve written: ${file.absolutePath}")
    }

    /** The full report: the summary plus the complete memory curve + writeMs samples. */
    private fun fullReport(
        output: HarnessDifferentialDriver.RunOutput,
        summaryJson: String,
        docCount: Long?,
    ): String {
        val root = JSONObject()
        root.put("summary", JSONObject(summaryJson))
        root.put("docCountAtReport", docCount ?: JSONObject.NULL)
        val mem = JSONArray()
        output.memSamples.forEach {
            mem.put(
                JSONObject()
                    .put("tSec", it.tMs / 1000.0)
                    .put("doc", it.doc)
                    .put("javaMb", it.javaMb)
                    .put("nativeMb", it.nativeMb)
                    .put("graphicsMb", it.graphicsMb)
                    .put("totalPssMb", it.totalPssMb)
                    .put("rssMb", it.rssMb)
                    // The fd axis rides the persisted curve too (issue `04`): the
                    // summary line carries final+max, the curve carries the shape a
                    // future calibration cites (§16 threshold provenance).
                    .put("fds", it.fds)
            )
        }
        root.put("memCurve", mem)
        val writes = JSONArray()
        output.writeMsSamples.forEach { writes.put(it) }
        root.put("writeMs", writes)
        return root.toString(2)
    }

    private fun budget(configured: Long?, default: Long): Long =
        if (configured == null || configured <= 0) default * 60_000L else configured * 60_000L

    /**
     * The §5.1 write probe, with the FW1 upsert/flush split: one doc through the
     * store's raw surfaces, each stage timed. Run before and after seeding — if
     * either stage's cost grows with collection size, that's the H4 crawl (or the
     * H1 pressure) visible at micro scale, minutes before a full arm shows it.
     */
    private suspend fun writeProbe(store: ZvecContentStore, label: String) {
        val pk = "harness-probe-${System.nanoTime()}"
        val text = HarnessCorpus.ocrTextFor(0)
        val upsertMs = measureNs {
            store.upsert(
                contentHash = pk, locator = "content://harness-probe/$pk",
                content = text, collectionId = HarnessCorpus.COLLECTION_ID,
                lastModified = 1_700_000_000_000L,
            )
        } / 1_000_000.0
        val flushMs = measureNs { store.flush() } / 1_000_000.0
        val fetchMs = measureNs { store.fetch(pk) } / 1_000_000.0
        Log.w(
            TAG, String.format(
                java.util.Locale.US, "HARNESS_WRITEPROBE %s upsert=%.1fms flush=%.1fms fetch=%.1fms",
                label, upsertMs, flushMs, fetchMs,
            )
        )
        runCatching { store.deleteAll(listOf(pk)) }
    }

    /**
     * The red-capability injection: the real sink wrapped with one that throws at
     * a chosen doc — a stand-in for the H1/H2 write-path failure, proving the
     * harness sees a run-level `Progress.Error` and records the terminal +
     * `bulkResultDecision` alongside (§1: the recoverability bug's input).
     */
    private class ThrowingSink(
        private val inner: WriteSink,
        private val throwAtDoc: Int,
    ) : WriteSink {
        var committed = 0
            private set

        override suspend fun commit(
            candidate: Candidate,
            text: String?,
            processed: Boolean,
            bytes: ByteArray,
            precomputedContentHash: String?,
        ) {
            if (committed >= throwAtDoc) {
                throw IllegalStateException("$MSG (injected at doc ${committed + 1})")
            }
            inner.commit(candidate, text, processed, bytes, precomputedContentHash)
            committed++
        }

        // Delegate or the engine's error-path `write.checkpoint()` hits the
        // interface's no-op default and the real sink's pending batch never
        // drains (same wiring gap TimedWriteSink's override closes).
        override suspend fun checkpoint() = inner.checkpoint()

        companion object {
            const val MSG = "harness-injected write failure"
        }
    }

    private companion object {
        const val TAG = "IngestionHarness"
        const val DEFAULT_ARM = "A1"
        const val DEFAULT_N = 500
        const val DEFAULT_BUDGET_MIN_FAST = 30L
        const val DEFAULT_BUDGET_MIN_SLOW = 90L
    }
}
