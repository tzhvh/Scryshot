package io.github.tzhvh.scryernext.ingestion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic unit tests for [StageLatencyRollingAverage] — the per-stage EMA
 * that feeds [Progress.stageTimings] (issue `07`, ADR 0004 §7.3). No clock
 * dependency: samples are fed directly, so the EMA math is asserted exactly.
 *
 * Plain JUnit4 — matches the repo convention.
 */
class StageLatencyRollingAverageTest {

    @Test
    fun snapshot_is_all_zero_before_any_sample() {
        val avg = StageLatencyRollingAverage()
        assertEquals(StageTimings(), avg.snapshot())
        assertEquals(0, avg.sampleCount())
    }

    @Test
    fun first_sample_is_taken_as_is_under_alpha_half() {
        // EMA with alpha=0.5: the first observed value seeds the average verbatim.
        val avg = StageLatencyRollingAverage(alpha = 0.5)
        avg.record(readMs = 10.0, decodeMs = 20.0, ocrMs = 100.0, writeMs = 5.0)
        assertEquals(StageTimings(readMs = 10.0, decodeMs = 20.0, ocrMs = 100.0, writeMs = 5.0),
                     avg.snapshot())
        assertEquals(1, avg.sampleCount())
    }

    @Test
    fun ema_with_alpha_half_moves_halfway_toward_the_new_sample() {
        // prev=100, observed=200, alpha=0.5 → 100 + 0.5*(200-100) = 150.
        val avg = StageLatencyRollingAverage(alpha = 0.5)
        avg.record(readMs = 100.0, decodeMs = 100.0, ocrMs = 100.0, writeMs = 100.0)
        avg.record(readMs = 200.0, decodeMs = 200.0, ocrMs = 200.0, writeMs = 200.0)
        val snap = avg.snapshot()
        assertEquals(150.0, snap.readMs, 1e-9)
        assertEquals(150.0, snap.ocrMs, 1e-9)
        assertEquals(2, avg.sampleCount())
    }

    @Test
    fun each_stage_is_averaged_independently() {
        val avg = StageLatencyRollingAverage(alpha = 0.5)
        avg.record(readMs = 1.0, decodeMs = 2.0, ocrMs = 3.0, writeMs = 4.0)
        avg.record(readMs = 5.0, decodeMs = 10.0, ocrMs = 15.0, writeMs = 20.0)
        val snap = avg.snapshot()
        // read: 1 → 1 + 0.5*(5-1) = 3
        assertEquals(3.0, snap.readMs, 1e-9)
        // ocr:  3 → 3 + 0.5*(15-3) = 9
        assertEquals(9.0, snap.ocrMs, 1e-9)
        // write: 4 → 4 + 0.5*(20-4) = 12
        assertEquals(12.0, snap.writeMs, 1e-9)
        // decode: 2 → 2 + 0.5*(10-2) = 6
        assertEquals(6.0, snap.decodeMs, 1e-9)
    }

    @Test
    fun ema_smooths_a_noisy_series_toward_its_level() {
        // A long run oscillating around 100ms should converge near 100ms.
        val avg = StageLatencyRollingAverage(alpha = 0.3)
        repeat(100) {
            avg.record(readMs = if (it % 2 == 0) 80.0 else 120.0,
                       decodeMs = 0.0, ocrMs = if (it % 2 == 0) 80.0 else 120.0, writeMs = 0.0)
        }
        val snap = avg.snapshot()
        assertTrue("ocrMs should smooth toward ~100ms, was ${snap.ocrMs}",
                   snap.ocrMs in 95.0..105.0)
    }

    @Test
    fun null_writeMs_leaves_write_ema_untouched_but_still_counts_a_sample() {
        // A TransientFailure writes nothing, so the engine records writeMs = null. That must
        // NOT touch the write EMA (a bogus near-zero would depress ADR 0004's Phase 5
        // adaptive threshold) — but it DOES still advance `samples`, because read + OCR
        // genuinely ran and were averaged in. This pins the new nullable-writeMs contract.
        val avg = StageLatencyRollingAverage(alpha = 0.5)
        avg.record(readMs = 10.0, decodeMs = 0.0, ocrMs = 20.0, writeMs = null)
        val snap = avg.snapshot()
        assertEquals(0.0, snap.writeMs, 1e-9)        // untouched — stayed at the all-zero default
        assertEquals(10.0, snap.readMs, 1e-9)        // still recorded: read ran
        assertEquals(20.0, snap.ocrMs, 1e-9)         // still recorded: OCR ran
        assertEquals(1, avg.sampleCount())           // a sample was still counted
    }

    @Test
    fun null_writeMs_does_not_pollute_a_prior_write_ema() {
        // The real hazard: a real write latency is already in the EMA, then a TransientFailure
        // arrives. `writeMs = null` must leave that prior average intact, not blend it toward 0.
        val avg = StageLatencyRollingAverage(alpha = 0.5)
        avg.record(readMs = 5.0, decodeMs = 0.0, ocrMs = 5.0, writeMs = 40.0)  // seed write EMA at 40
        avg.record(readMs = 5.0, decodeMs = 0.0, ocrMs = 5.0, writeMs = null)  // transient — must not touch write
        assertEquals(40.0, avg.snapshot().writeMs, 1e-9)
        assertEquals(2, avg.sampleCount())
    }
}
