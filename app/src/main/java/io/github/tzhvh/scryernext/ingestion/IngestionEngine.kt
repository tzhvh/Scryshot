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
 * Per candidate, the loop (ADR 0004 §3, §5, §7.4):
 * 1. [ScreenshotRepository.isKnown] → skip (no OCR, no write). Dedup *is* the
 *    resumption checkpoint (§5): completed candidates leave the unindexed set.
 * 2. else [OcrStage.attempt] → on [OcrOutcome.Success], invoke [write] and count
 *    indexed; on [OcrOutcome.PermanentContentFailure]/[OcrOutcome.TransientFailure],
 *    count failed and continue (issue `06` placeholder — issue `07` refines writes).
 * 3. emit [Progress.Indexing] after each candidate and an early `Indexing(0, total, 0)`
 *    *before* the first OCR so a UI updates immediately (§7.4).
 *
 * Completion fraction is `(indexed + failed) / total` (ADR 0004 §7.2): `failed` is
 * tracked separately from `indexed` and counted toward completion, so a single
 * permanent failure can't park the progress bar forever. [Progress.stageTimings] is
 * `null` here — issue `07` populates it via the per-stage `Stopwatch`.
 *
 * @param repository the identity/dedup authority. The engine never resolves identity.
 * @param ocr        the OCR backend seam isolating the engine from ML Kit + ContentResolver.
 * @param write      "write content for this candidate" sink — the engine's only write
 *                   path; the trigger layer / repository owns how/where it persists.
 */
class IngestionEngine(
    private val repository: ScreenshotRepository,
    private val ocr: OcrStage,
    private val write: suspend (candidate: Candidate, text: String) -> Unit,
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
     */
    fun process(candidates: Flow<Candidate>): Flow<Progress> = flow {
        val collected = candidates.toList()                 // cooperative; cancellation propagates
        val total = collected.size

        var indexed = 0
        var failed = 0
        // §7.4: emit an early progress event before any OCR runs, so the UI updates immediately.
        emit(Progress.Indexing(current = 0, total = total, failedCount = 0, stageTimings = null))

        collected.forEach { candidate ->
            if (repository.isKnown(candidate)) {
                // Dedup-as-skip (ADR 0004 §3, §5). Still emit so a UI sees the bar move past
                // already-indexed candidates; the run's completion fraction advances.
                emit(Progress.Indexing(current = indexed + failed, total = total,
                                       failedCount = failed, stageTimings = null))
                return@forEach
            }

            when (val outcome = ocr.attempt(candidate)) {
                is OcrOutcome.Success -> { write(candidate, outcome.text); indexed += 1 }
                // Issue-06 placeholder: count as processed-but-empty, continue (don't crash).
                // Issue 07 refines: write empty text + processed = true on Permanent, decide
                // throw-vs-continue on Transient.
                is OcrOutcome.PermanentContentFailure -> { failed += 1 }
                is OcrOutcome.TransientFailure -> { failed += 1 }
            }
            emit(Progress.Indexing(current = indexed + failed, total = total,
                                   failedCount = failed, stageTimings = null))
        }
        emit(Progress.Completed(indexed = indexed, failed = failed, total = total,
                                stageTimings = null))
    }
}
