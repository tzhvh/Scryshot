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
 * silently runs the engine over the producer's flow in an app-scope coroutine — no notifications,
 * no foreground services. Large backlogs are the bulk worker's job (issue 12).
 *
 * Coordinates run entry via [IngestionProgressStore.tryEnter] (the §7.5 guard) and routes every
 * terminal path — completion, failure, **and cancellation** — out of the guard symmetrically in a
 * single [launch]. Cancellation surfaces as [Progress.Aborted] (via [IngestionProgressStore.abort]),
 * **not** [Progress.Error]: a user/system stop is an intentional stop, not a failed run.
 *
 * ## Cancellability — the load-bearing precondition (honest status)
 *
 * The engine is a cold, cooperative-cancellable `flow`: cancelling the collecting `Job` propagates
 * through `isKnown` / `read` / `attempt` suspend points and the run stops promptly. **However** the
 * in-flight ML Kit recognition inside [io.github.tzhvh.scryernext.ingestion.MlKitOcrStage] cannot
 * itself be cancelled mid-flight: `TextRecognizer.process(InputImage)` has no `CancellationToken`
 * overload and `com.google.android.gms.tasks.Task` exposes no public `cancel()`. So on abort the
 * coroutine yields immediately (the run stops, the guard releases) but at most one in-flight native
 * recognition finishes in the background (a bounded battery/CPU cost). This is an ML Kit limitation,
 * not a defect of this trigger — see [MlKitOcrStage] and the issue 11 Comments.
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
    @Volatile
    private var job: Job? = null

    /**
     * Cancel the active on-open run if one is in flight. Used by the app's `onStop`
     * lifecycle hook (issue 11 wiring) and by issue 14a's abort. Safe to call when
     * no run is active. The guard release happens inside [onForeground]'s collector.
     */
    fun cancel() {
        job?.cancel()
    }

    /**
     * Entry point: called on `ProcessLifecycle` foreground.
     *
     * Single [launch]: the count + threshold gate + `tryEnter` + collect all share one coroutine,
     * so enter and release are symmetric and there is no "entered but never collected" guard leak.
     */
    fun onForeground() {
        job = scope.launch {
            try {
                // 1. Cheap unindexed count (SQL COUNT) — don't materialize the backlog to gate.
                val count = repository.getUnprocessedCount()
                logger.log("onForeground: unprocessed count = $count")

                // 2. Threshold gate (ADR 0004 §4). count == 0 or count > THRESHOLD → not our job.
                if (count == 0 || count > THRESHOLD) {
                    if (count > THRESHOLD) {
                        logger.log("onForeground: count ($count) exceeds threshold ($THRESHOLD); bulk worker's job.")
                    }
                    return@launch
                }

                // 3. §7.5 guard. tryEnter is an atomic CAS; refusal means a peer (bulk/on-open) is active.
                if (!store.tryEnter(IngestionProgressStore.TriggerKind.ON_OPEN)) {
                    logger.log("onForeground: guard refused (another trigger active); skipping.")
                    return@launch
                }

                logger.log("onForeground: starting silent on-open ingestion.")
                try {
                    // 4. Collect the engine's cold Flow into the store. Terminal Progress states are
                    //    routed through the store's terminal methods (complete/fail), which release
                    //    the guard and fire the onTerminal hook. publish() is Indexing-only.
                    engine.process(producer.candidates()).collect { progress ->
                        when (progress) {
                            is Progress.Indexing -> store.publish(progress)
                            is Progress.Completed -> store.complete(progress)
                            is Progress.Error -> store.fail(progress)
                            Progress.Idle, Progress.Aborted, is Progress.Paused -> Unit
                        }
                    }
                } catch (ce: CancellationException) {
                    // Cancel ≠ fail: a user/system stop is intentional. Release the guard via
                    // abort() (Aborted, not Error) before letting cancellation finish the coroutine.
                    logger.log("onForeground: cancelled; aborting run (guard released).")
                    store.abort()
                    throw ce
                } catch (e: Throwable) {
                    // A genuine collection-loop failure (not cancellation). Surface as Error.
                    logger.log("onForeground: error during collection — ${e.javaClass.simpleName}: ${e.message}")
                    store.fail(Progress.Error(e))
                }
            } catch (e: Throwable) {
                // Startup-phase failure (count query threw, etc.) — log only; no guard was taken.
                logger.log("onForeground: startup error — ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    companion object {
        private const val THRESHOLD = 12 // ADR 0004 Consequences: v1 fixed 12.
    }
}
