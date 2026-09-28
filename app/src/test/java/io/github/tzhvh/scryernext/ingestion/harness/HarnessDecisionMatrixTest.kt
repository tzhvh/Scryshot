package io.github.tzhvh.scryernext.ingestion.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The §6 decision matrix (`INGESTION_ADHOC_REPRO_HARNESS.md`) as a pure JVM test —
 * each row of the doc's table is one test case, plus the shape classifiers that
 * feed it (memory curve per §5.3, per-doc writeMs trend per H4). Mirrors the
 * repo's pure-helper convention (`bulkResultDecision`, `isPending`, `bannerMode`).
 */
class HarnessDecisionMatrixTest {

    private fun clean(arm: HarnessArm) = ArmResult(arm, ArmOutcomeKind.CLEAN, indexed = 10_000, total = 10_000)
    private fun red(arm: HarnessArm, msg: String = "OutOfMemoryError") =
        ArmResult(arm, ArmOutcomeKind.RED_THROW, errorMessage = msg)
    private fun crawl(arm: HarnessArm) = ArmResult(arm, ArmOutcomeKind.CRAWL_TIMEOUT, indexed = 7_500, total = 10_000)
    private fun stalled(arm: HarnessArm) = ArmResult(arm, ArmOutcomeKind.STALL_FAILED_N, failed = 9_900, total = 10_000)
    private val skipped2 = ArmResult.SKIPPED_A2
    private val skipped3 = ArmResult.SKIPPED_A3

    // ── §6 rows ──────────────────────────────────────────────────────────────────

    @Test
    fun `row 1 - a1 fails with native-to-cap is H1`() {
        val v = HarnessDecisionMatrix.decide(red(HarnessArm.A1), skipped2, skipped3, MemoryShape.NATIVE_TO_CAP, WriteTrend.FLAT)
        assertEquals(Hypothesis.H1, v)
    }

    @Test
    fun `row 2 - a1 crawl with flat mem and rising writeMs is H4`() {
        assertEquals(
            Hypothesis.H4,
            HarnessDecisionMatrix.decide(crawl(HarnessArm.A1), skipped2, skipped3, MemoryShape.FLAT, WriteTrend.RISING),
        )
        assertEquals(
            Hypothesis.H4,
            HarnessDecisionMatrix.decide(crawl(HarnessArm.A1), skipped2, skipped3, MemoryShape.FLAT, WriteTrend.SUPERLINEAR),
        )
    }

    @Test
    fun `a1 hard throw with flat memory and flat cost still names the zvec write path (H1)`() {
        assertEquals(
            Hypothesis.H1,
            HarnessDecisionMatrix.decide(red(HarnessArm.A1, "ZvecException: buffer"), skipped2, skipped3, MemoryShape.FLAT, WriteTrend.FLAT),
        )
    }

    @Test
    fun `row 3 - a1 clean, a2 fails with java sawtooth is H2`() {
        assertEquals(
            Hypothesis.H2,
            HarnessDecisionMatrix.decide(clean(HarnessArm.A1), red(HarnessArm.A2), skipped3, MemoryShape.JAVA_SAWTOOTH_PEAK, WriteTrend.FLAT),
        )
    }

    @Test
    fun `row 4 - a1 a2 clean, a3 fails with monotonic native climb is H3`() {
        assertEquals(
            Hypothesis.H3,
            HarnessDecisionMatrix.decide(clean(HarnessArm.A1), clean(HarnessArm.A2), red(HarnessArm.A3), MemoryShape.NATIVE_MONOTONIC, WriteTrend.FLAT),
        )
    }

    @Test
    fun `row 5 - all arms run clean but a3 completes with failed~N on flat memory is H5`() {
        assertEquals(
            Hypothesis.H5,
            HarnessDecisionMatrix.decide(clean(HarnessArm.A1), clean(HarnessArm.A2), stalled(HarnessArm.A3), MemoryShape.FLAT, WriteTrend.FLAT),
        )
    }

    @Test
    fun `row 6 - clean sweep is none-at-N`() {
        assertEquals(
            Hypothesis.NONE_AT_N,
            HarnessDecisionMatrix.decide(clean(HarnessArm.A1), clean(HarnessArm.A2), clean(HarnessArm.A3), MemoryShape.FLAT, WriteTrend.FLAT),
        )
    }

    // ── memory-shape classifier (§5.3) ───────────────────────────────────────────

    private fun samples(java: List<Double>, native: List<Double>): List<MemSample> =
        java.indices.map { i ->
            MemSample(
                tMs = i * 1_000L, doc = i * 100,
                javaMb = java[i], nativeMb = native[i],
                graphicsMb = 0.0, totalPssMb = java[i] + native[i], rssMb = java[i] + native[i],
            )
        }

    @Test
    fun `flat curve classifies flat`() {
        val flat = samples(List(40) { 60.0 }, List(40) { 90.0 })
        assertEquals(MemoryShape.FLAT, HarnessSignals.classifyMemoryCurve(flat))
    }

    @Test
    fun `too-few samples degrade to flat`() {
        val short = samples(List(4) { 60.0 }, List(4) { 300.0 })
        assertEquals(MemoryShape.FLAT, HarnessSignals.classifyMemoryCurve(short))
    }

    @Test
    fun `tiny-heap gc jitter does not read as sawtooth (the A1@10k misclassification)`() {
        // A ~24 MB heap dropping ~30% between GCs is noise at that scale, even
        // repeated — the run's real signal was the native climb, not the heap.
        val java = mutableListOf<Double>()
        repeat(5) {
            java += List(4) { 18.0 + it * 3.0 }
            java += listOf(13.0)
        }
        val native = List(java.size) { 150.0 + it * 6.0 }
        assertEquals(
            MemoryShape.NATIVE_MONOTONIC,
            HarnessSignals.classifyMemoryCurve(samples(java, native)),
        )
    }

    @Test
    fun `three gc give-back cycles on java heap classify sawtooth (H2)`() {
        // Three climbs each ending in a ≥25% drop (a GC release), native flat.
        val java = mutableListOf<Double>()
        repeat(3) {
            java += List(6) { 40.0 + it * 25.0 }   // climb 40 → 165
            java += listOf(110.0)                   // the give-back (−33%)
        }
        java += List(6) { 60.0 + it * 10.0 }
        val curve = samples(java, List(java.size) { 90.0 })
        assertEquals(MemoryShape.JAVA_SAWTOOTH_PEAK, HarnessSignals.classifyMemoryCurve(curve))
    }

    @Test
    fun `monotonic native climb to 160mb classifies monotonic (H3)`() {
        val native = List(100) { 50.0 + it * 1.1 }
        val curve = samples(List(100) { 60.0 }, native)
        assertEquals(MemoryShape.NATIVE_MONOTONIC, HarnessSignals.classifyMemoryCurve(curve))
    }

    @Test
    fun `native growth ending near the 512mb cap classifies to-cap (H1)`() {
        val native = List(60) { 200.0 + it * 4.2 } // 200 → ~450
        val curve = samples(List(60) { 60.0 }, native)
        assertEquals(MemoryShape.NATIVE_TO_CAP, HarnessSignals.classifyMemoryCurve(curve))
    }

    // ── write-trend classifier (H4's latency-shape axis) ─────────────────────────

    @Test
    fun `constant writeMs classifies flat`() {
        assertEquals(WriteTrend.FLAT, HarnessSignals.classifyWriteTrend(List(200) { 2.0 }))
    }

    @Test
    fun `single step-up classifies rising`() {
        // 1ms for half the run, 3ms after — cost grew, but not accelerating.
        val trend = List(50) { 1.0 } + List(50) { 3.0 }
        assertEquals(WriteTrend.RISING, HarnessSignals.classifyWriteTrend(trend))
    }

    @Test
    fun `geometric growth classifies superlinear`() {
        val trend = List(100) { 1.0 * Math.pow(1.07, it.toDouble()) }
        assertEquals(WriteTrend.SUPERLINEAR, HarnessSignals.classifyWriteTrend(trend))
    }

    @Test
    fun `short smoke runs degrade to flat`() {
        assertEquals(WriteTrend.FLAT, HarnessSignals.classifyWriteTrend(List(10) { 5.0 }))
    }

    // ── regression gate (§16, issue 04) ─────────────────────────────────────────

    @Test
    fun `gate green at fast-gate-shaped inputs (fds and amortized write well under cap)`() {
        // The measured green fast gate's shape (field device, 2026-09-28):
        // fdsMax=169, writeMsAmort=26.19 — p50 upsert ~10.6 ms plus the two
        // ~1.5 s batch flushes amortized over 200 docs.
        val v = HarnessGate.verdict(fdsMax = 169, writeMsAmort = 26.19)
        assertTrue("green inputs must pass: ${v.reasons}", v.pass)
        assertEquals("GREEN", v.label)
    }

    @Test
    fun `gate reds on fd breach alone (a leak that never slows writes)`() {
        val v = HarnessGate.verdict(fdsMax = HarnessGate.FD_CAP + 1, writeMsAmort = 26.19)
        assertEquals(false, v.pass)
        assertTrue(v.reasons.single().startsWith("fdsMax="))
    }

    @Test
    fun `gate reds on the regressed per-file flush cadence (~500 ms doc)`() {
        // §15.1's red shape: every commit carries its own flush, 447–530 ms/doc.
        val v = HarnessGate.verdict(fdsMax = 169, writeMsAmort = 521.85)
        assertEquals(false, v.pass)
        assertTrue(v.reasons.single().startsWith("writeMsAmort="))
    }

    @Test
    fun `both breaches report both reasons`() {
        val v = HarnessGate.verdict(fdsMax = 31_200, writeMsAmort = 633.0)
        assertEquals(false, v.pass)
        assertEquals(2, v.reasons.size)
    }

    @Test
    fun `unavailable fd sampler skips the fd assertion without failing or faking a green`() {
        // /proc/self/fd unreadable → every sample 0 → the skip must not fail the
        // gate, and must be NAMED in the label — never a silent green on the fd axis.
        val v = HarnessGate.verdict(fdsMax = 0, writeMsAmort = 26.19)
        assertTrue(v.pass)
        assertEquals(listOf("fd-assert-skipped:no-sampler"), v.skips)
        assertEquals("GREEN(fd-assert-skipped:no-sampler)", v.label)
    }

    @Test
    fun `a run that wrote nothing skips the write assertion`() {
        val v = HarnessGate.verdict(fdsMax = 169, writeMsAmort = null)
        assertTrue(v.pass)
        assertEquals(emptyList<String>(), v.reasons)
        assertEquals("GREEN", v.label)
    }

    // ── corpus factory sanity (the §10 realism caveats, JVM-side) ────────────────

    @Test
    fun `synthetic corpus is deterministic unique and realistically sized`() {
        val a = SyntheticOcrText.document(7, Random(42))
        val b = SyntheticOcrText.document(7, Random(42))
        assertEquals("same seed → identical text", a, b)

        val docs = (0 until 5_000).map { SyntheticOcrText.document(it, Random(1_000L + it)) }
        assertEquals("every doc must be content-unique (dedup must not collapse the corpus)", 5_000, docs.toSet().size)
        assertTrue(
            "word counts must sit in the realistic screenshot-OCR band",
            docs.all { it.split(Regex("\\s+")).size in 30..180 },
        )
    }

    @Test
    fun `corpus locators round-trip and rows align`() {
        assertEquals(123, HarnessCorpus.indexFor(HarnessCorpus.locatorFor(123)))
        assertEquals(-1, HarnessCorpus.indexFor("content://media/42"))
        assertEquals(-1, HarnessCorpus.indexFor(null))

        val rows = HarnessCorpus.screenshotRows(10)
        assertEquals(10, rows.size)
        rows.forEachIndexed { i, row ->
            assertEquals(HarnessCorpus.locatorFor(i), row.uri)
            assertEquals(false, row.processed)
        }
        assertEquals(10, HarnessCorpus.candidates(10).size)
    }
}
