/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion.triggers

import io.github.tzhvh.scryernext.ingestion.IngestionEngine
import io.github.tzhvh.scryernext.ingestion.IngestionLogger
import io.github.tzhvh.scryernext.ingestion.IngestionProgressStore
import io.github.tzhvh.scryernext.ingestion.Producer
import io.github.tzhvh.scryernext.ingestion.Progress
import io.github.tzhvh.scryernext.repository.ScreenshotRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The "on-open / opportunistic" trigger (ADR 0004 §4).
 *
 * When the app comes to the foreground, if the unindexed backlog is at or below [THRESHOLD] (12),
 * silently runs the engine over the producer's flow in an app-scope coroutine.
 *
 * No notifications and no foreground services are started.
 *
 * It uses the [IngestionProgressStore] re-entrancy guard to coordinate runs and publishes
 * progress emissions to it.
 *
 * See: [.scratch/ingestion/issues/11-on-open-silent-trigger.md](file:///.scratch/ingestion/issues/11-on-open-silent-trigger.md)
 * See: [ADR 0004 §2, §4, §5, §7.5](file:///docs/adr/0004-ingestion-engine-and-trigger-architecture-v2.md)
 */
class OnOpenTrigger(
    private val repository: ScreenshotRepository,
    private val producer: Producer,
    private val engine: IngestionEngine,
    private val store: IngestionProgressStore,
    private val scope: CoroutineScope,
    private val logger: IngestionLogger = IngestionLogger.Noop
) {
    private var job: Job? = null

    /**
     * Cancel the active on-open trigger execution if it is running.
     */
    fun cancel() {
        job?.cancel()
        job = null
    }

    fun onForeground() {
        scope.launch {
            try {
                // 1. Cheap unindexed count (SQL COUNT).
                val count = repository.getUnprocessedCount()
                logger.log("onForeground: unprocessed count = $count")

                // 2. if (count > THRESHOLD) return — large backlog is the bulk worker's job (issue 12).
                if (count > THRESHOLD) {
                    logger.log("onForeground: count ($count) exceeds threshold ($THRESHOLD), skipping silent trigger.")
                    return@launch
                }

                if (count == 0) {
                    logger.log("onForeground: no unprocessed screenshots, skipping silent trigger.")
                    return@launch
                }

                // 3. if (!store.tryEnter(IngestionProgressStore.TriggerKind.ON_OPEN)) return — §7.5 guard (issue 10.5).
                if (!store.tryEnter(IngestionProgressStore.TriggerKind.ON_OPEN)) {
                    logger.log("onForeground: could not enter store guard (another trigger is active).")
                    return@launch
                }

                logger.log("onForeground: starting silent on-open ingestion trigger.")

                // Cancel any pre-existing job just in case
                job?.cancel()

                // 4. Collect engine.process(producer.candidates()) into the store.
                job = scope.launch {
                    try {
                        engine.process(producer.candidates()).collect { progress ->
                            when (progress) {
                                is Progress.Indexing -> store.publish(progress)
                                is Progress.Completed -> store.complete(progress)
                                is Progress.Error -> store.fail(progress)
                                else -> { /* Idle states not published directly */ }
                            }
                        }
                    } catch (ce: CancellationException) {
                        // Release store lock on cancellation
                        logger.log("Silent on-open trigger cancelled; releasing store lock.")
                        store.fail(Progress.Error(ce))
                        throw ce
                    } catch (e: Exception) {
                        logger.log("Error in silent on-open trigger collection loop: ${e.message}")
                        store.fail(Progress.Error(e))
                    }
                }
            } catch (e: Exception) {
                logger.log("Error during silent on-open trigger startup: ${e.message}")
            }
        }
    }

    companion object {
        private const val THRESHOLD = 12 // ADR 0004 Consequences: v1 fixed 12.
    }
}
