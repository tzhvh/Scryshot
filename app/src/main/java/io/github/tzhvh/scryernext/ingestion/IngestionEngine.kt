/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

import io.github.tzhvh.scryernext.repository.ScreenshotRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList

/**
 * The pure, identity-agnostic, source-agnostic ingestion pipeline — ADR 0004 §2's
 * keystone. Consumes `Flow<Candidate>`, emits `Flow<Progress>`, and owns processing
 * and **nothing else**.
 *
 * What it deliberately is *not*:
 * - It holds no `CoroutineScope` and exposes no `cancel()` method. Cancellation is
 *   **caller-owned**: collect [process] on the trigger layer's scope (Phase 2's
 *   `CoroutineWorker` or app-scope coroutine) and cancel that scope / `Job` to stop
 *   a run. The `flow` builder is cooperative-cancellable, so a suspended
 *   `isKnown`/`attempt`/[write] call resumes into cancellation and the run stops
 *   without throwing anything beyond the expected `CancellationException` into the
 *   caller. ADR 0004 §2 ("knows nothing about coroutine scopes") forbids a `cancel()`
 *   here — do not add one.
 * - It knows nothing of WorkManager or foreground services (Phase 2's trigger layer).
 * - It calls no Room DAO, ML Kit, or `ContentResolver`: dedup is delegated to
 *   [repository] ([ScreenshotRepository.isKnown]) and OCR to the injected [ocr]
 *   ([OcrStage]). This is what makes it JVM-unit-testable without Robolectric
 *   (Phase 0 issues `03`/`04` established ML Kit can't run on the JVM).
 *
 * Per candidate, the loop (ADR 0004 §3, §5, §7.2, §7.4):
 * 1. [ScreenshotRepository.isKnown] → skip (no OCR, no write). Dedup *is* the
 *    resumption checkpoint (§5): completed candidates leave the unindexed set.
 * 2. else [OcrStage.attempt] → branch on [OcrOutcome] per the three-class failure
 *    taxonomy (ADR 0004 §7.2, issue `07`):
 *    - [OcrOutcome.Success] → [write] the recognized text with `processed = true`,
 *      count **indexed**.
 *    - [OcrOutcome.PermanentContentFailure] → [write] **empty text** with
 *      `processed = true` ("processed-but-empty"), count **indexed** — counted toward
 *      completion so it leaves the unindexed set and does **not** re-poison the
 *      backlog via Phase 2.5's discovery worker.
 *    - [OcrOutcome.TransientFailure] → **write nothing** (re-attemptable on the next
 *      run), count **failed**; the run continues.
 *    - the [write] sink itself throwing (an Insert/store failure — e.g. Room insert
 *      threw, disk full) is a *run-level* error distinct from OCR failure: it is
 *      surfaced as [Progress.Error] and the run stops, **not** swallowed per-file.
 * 3. emit [Progress.Indexing] after each candidate and an early `Indexing(0, total, …)`
 *    *before* the first OCR so a UI updates immediately (§7.4).
 *
 * Completion fraction is `(indexed + failed) / total` (ADR 0004 §7.2): permanent-content
 * failures sit in the `indexed` bucket (they are "processed-but-empty," so they have
 * *left the backlog*) while transient failures sit in the `failed` bucket. Both count
 * toward completion, so a single failure can't park the progress bar forever.
 *
 * Per-stage latency (read / decode / OCR / write) is timed with `System.nanoTime()`
 * deltas and folded into a [StageLatencyRollingAverage]; its snapshot populates
 * [Progress.stageTimings] on every emit (issue `07`, ADR 0004 §7.3). This is the data
 * the Phase 5 adaptive threshold (`unindexedCount × rollingAvgPerFile < ~3s`) needs.
 *
 * **Stage boundaries** (issue `07` notes): the *read* stage is the [Candidate.byteHandle]
 * invocation owned by the engine; *decode + OCR* both happen inside the injected
 * [OcrStage], so they are timed together as the `ocrMs` field (issue `08`, the ML Kit
 * adapter, may split them inside the stage later — at which point `decodeMs` is
 * populated from inside the stage; see the OcrStage KDoc). `writeMs` is the [write]
 * invocation. This is documented so Phase 5's single combined per-file number is
 * derivable and Phase 2's notification can break it down.
 *
 * @param repository the identity/dedup authority. The engine never resolves identity.
 * @param ocr        the OCR backend seam isolating the engine from ML Kit + ContentResolver.
 * @param write      the content sink — the engine's only write path. [WriteSink] (a
 *                   `fun interface`, not a bare lambda type) carries both the text and the
 *                   `processed` flag so it can express "write text" (success) and "write
 *                   empty + processed" (permanent-content failure). The trigger layer /
 *                   repository owns how/where it persists.
 */
class IngestionEngine(
    private val repository: ScreenshotRepository,
    private val ocr: OcrStage,
    private val write: WriteSink,
) {
    /**
     * Process [candidates] into a cold [Flow] of [Progress].
     *
     * Cold: nothing runs until a collector collects. Materializes the candidate
     * list once to know `total` (ADR 0004 §7.4 early-progress emit needs it before
     * the first OCR); this is the engine's only list materialization.
     *
     * Cancellation: caller-owned — cancel the collecting scope (see class KDoc).
     * Cancelling surfaces as a `CancellationException` out of [collect], nothing else.
     *
     * Errors: a thrown [write] (Insert/store failure) is emitted as [Progress.Error]
     * and terminates the flow — it is not swallowed per-file. An [OcrStage.attempt]
     * throwing is wrapped into an [OcrOutcome.TransientFailure] by the stage adapter
     * (issue `08`), so the engine's per-candidate branch never sees a raw stage throw.
     */
    fun process(candidates: Flow<Candidate>): Flow<Progress> = flow {
        val collected = candidates.toList()                 // cooperative; cancellation propagates
        val total = collected.size

        var indexed = 0
        var failed = 0
        val timings = StageLatencyRollingAverage()
        // §7.4: emit an early progress event before any OCR runs, so the UI updates immediately.
        // stageTimings is the all-zero snapshot before any sample.
        emit(Progress.Indexing(current = 0, total = total, failedCount = 0, stageTimings = timings.snapshot()))

        for (candidate in collected) {
            if (repository.isKnown(candidate)) {
                // Dedup-as-skip (ADR 0004 §3, §5). Still emit so a UI sees the bar move past
                // already-indexed candidates; the run's completion fraction advances.
                // No timing recorded — dedup-skipped candidates did no OCR work this run.
                emit(Progress.Indexing(current = indexed + failed, total = total,
                                       failedCount = failed, stageTimings = timings.snapshot()))
                continue
            }

            // ----- stage timing boundaries (issue 07; see class KDoc) -----
            // The engine owns the READ (byteHandle, called exactly once — honouring
            // Candidate's "at most once" contract and avoiding double-I/O); the injected
            // OcrStage owns DECODE+OCR over the already-read bytes (collapsing into ocrMs
            // until issue 08 may split decode out inside the stage); WRITE is the sink.
            // System.nanoTime() is the JVM/Android primitive — no Guava Stopwatch dependency.
            val readStart = System.nanoTime()
            val bytes = candidate.byteHandle().use { it.readBytes() }
            val readMs = msSince(readStart)

            val ocrStart = System.nanoTime()
            val outcome = ocr.attempt(candidate, bytes)
            val ocrMs = msSince(ocrStart)

            val writeStart = System.nanoTime()
            // Branch order is load-bearing for the §7.2 taxonomy — keep it explicit.
            //   Success              → write text + processed=true,  count indexed
            //   PermanentContentFail → write null  + processed=true,  count indexed (processed-but-empty)
            //   TransientFailure     → write nothing,                  count failed (re-attemptable)
            //
            // A thrown write sink is an Insert/store failure (distinct from any OcrOutcome):
            // it surfaces as Progress.Error and STOPS the run, rather than being swallowed
            // per-file (ADR 0004 §7.2). CancellationException is rethrown so the caller-owned
            // cancel contract is unaffected — a write throw is *not* cancellation.
            try {
                when (outcome) {
                    is OcrOutcome.Success -> {
                        write.commit(candidate, outcome.text, processed = true)
                        indexed += 1
                    }
                    is OcrOutcome.PermanentContentFailure -> {
                        write.commit(candidate, null, processed = true)
                        indexed += 1
                    }
                    is OcrOutcome.TransientFailure -> {
                        failed += 1
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                emit(Progress.Error(t))
                return@flow
            }
            val writeMs = msSince(writeStart)

            timings.record(readMs = readMs, decodeMs = 0.0, ocrMs = ocrMs, writeMs = writeMs)

            emit(Progress.Indexing(current = indexed + failed, total = total,
                                   failedCount = failed, stageTimings = timings.snapshot()))
        }
        emit(Progress.Completed(indexed = indexed, failed = failed, total = total,
                                stageTimings = timings.snapshot()))
    }

    private fun msSince(startNanos: Long): Double =
        (System.nanoTime() - startNanos) / NANOS_PER_MS

    private companion object {
        const val NANOS_PER_MS = 1_000_000.0
    }
}
