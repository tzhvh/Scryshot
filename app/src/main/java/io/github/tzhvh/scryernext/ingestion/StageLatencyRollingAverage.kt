/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

/**
 * Per-stage rolling-average latency for one ingestion run (ADR 0004 §7.3). Each
 * stage (read / decode / OCR / write) is timed with `System.nanoTime()` deltas
 * by [IngestionEngine] and folded into this average after every candidate, so
 * each `Progress` emission carries a current per-stage estimate
 * (`Progress.stageTimings`).
 *
 * This is **how 0003's fixed-12 threshold becomes adaptive**
 * (`unindexedCount × rollingAvgPerFile < ~3s`) without a retrofit: build the
 * instrumentation in now (issue `07`), and the adaptive threshold is a one-line
 * change once real per-file latency data exists (Phase 5).
 *
 * **Scope (issue `07`):** the average persists across candidates *within a run*;
 * it is *not* persisted across runs. Cross-run persistence — so the threshold
 * is sensible on a cold start — is Phase 5.2, deferred here. Don't add a
 * SharedPreferences / DataStore write in this class.
 *
 * Implementation: a single exponential moving average (EMA) per stage. An EMA is
 * the lightest possible online average — O(1), bounded, no history retention,
 * monotonically reasonable — and is all Phase 5 needs to read "average per-file
 * cost." [alpha] trades responsiveness for stability; the default of `0.5` keeps
 * the first few samples meaningful on a small fake-driven test run while still
 * smoothing a real 842-file batch.
 *
 * Thread-safety: confined to the single collector that drives [IngestionEngine];
 * not synchronized. The engine is not internally parallel today (ADR 0004 §2
 * permits internal concurrency but the v1 loop is sequential).
 *
 * See: [ADR 0004 §7.3](../../../../../docs/adr/0004-ingestion-engine-and-trigger-architecture-v2.md)
 */
internal class StageLatencyRollingAverage(
    private val alpha: Double = 0.5,
) {
    private var readMs: Double = 0.0
    private var decodeMs: Double = 0.0
    private var ocrMs: Double = 0.0
    private var writeMs: Double = 0.0
    private var samples: Int = 0

    /**
     * Observe one candidate's stage durations (ms). [writeMs] is nullable: a candidate
     * whose OCR result was [OcrOutcome.TransientFailure] writes nothing, so it records
     * **no write sample** — `null` leaves the write EMA untouched (rather than seeding it
     * with a bogus near-zero latency, which would depress ADR 0004's Phase 5 adaptive
     * threshold). The asymmetry (read/decode/ocr non-null, write nullable) is principled:
     * read + OCR run for every non-dedup candidate, while write only runs for
     * Success / PermanentContentFailure. The same "record only what ran" rule will cover
     * the Phase 3 `EMBED` stage and Phase 2's READ→DEDUP relocation (a dedup-skip records
     * readMs but null ocr/write).
     */
    fun record(readMs: Double, decodeMs: Double, ocrMs: Double, writeMs: Double?) {
        this.readMs = ema(this.readMs, readMs)
        this.decodeMs = ema(this.decodeMs, decodeMs)
        this.ocrMs = ema(this.ocrMs, ocrMs)
        if (writeMs != null) this.writeMs = ema(this.writeMs, writeMs)
        samples += 1
    }

    /** Current rolling average as a [StageTimings] snapshot; all-zero before any sample. */
    fun snapshot(): StageTimings =
        StageTimings(readMs = readMs, decodeMs = decodeMs, ocrMs = ocrMs, writeMs = writeMs)

    /** Number of candidates observed. */
    fun sampleCount(): Int = samples

    private fun ema(prev: Double, observed: Double): Double =
        if (samples == 0) observed else prev + alpha * (observed - prev)
}
