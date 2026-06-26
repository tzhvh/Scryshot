/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion.triggers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.tzhvh.scryernext.R
import io.github.tzhvh.scryernext.ScryerApplication
import io.github.tzhvh.scryernext.ingestion.IngestionEngine
import io.github.tzhvh.scryernext.ingestion.IngestionLogger
import io.github.tzhvh.scryernext.ingestion.IngestionProgressStore
import io.github.tzhvh.scryernext.ingestion.MediaStoreProducer
import io.github.tzhvh.scryernext.ingestion.MlKitOcrStage
import io.github.tzhvh.scryernext.ingestion.Progress
import io.github.tzhvh.scryernext.ingestion.RoomWriteSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * The user-initiated **bulk** trigger (ADR 0004 §4, §6) — the "Analyzing 45 of 842
 * screenshots…" path for large backlogs that need a visible, cancellable session.
 *
 * A [CoroutineWorker] that promotes itself to a `dataSync` foreground service
 * ([FOREGROUND_SERVICE_TYPE_DATA_SYNC], issue `09` confirmed the manifest pattern),
 * runs [IngestionEngine.process] over the [MediaStoreProducer]'s flow in one long
 * [doWork], updates the foreground notification in place per [Progress.Indexing]
 * emission, and returns per the **§5 result contract** (see [bulkResultDecision] —
 * the load-bearing decision, extracted as a pure unit-tested function).
 *
 * ### §5 result contract — dedup is the checkpoint; never `retry()` for continuation
 *
 * This worker runs a single long [doWork] and **never** uses `Result.retry()` for
 * intentional continuation (ADR 0004 §5, resolving ADR 0003's contradiction). The
 * mapping lives in [bulkResultDecision]; in short: normal completion → `success()`
 * (even if more files remain — dedup resumes on the next discovery/app-open);
 * whole-run transient failure → `retry()`; write/store error → `retry()`. The full
 * table and the per-branch rationale are documented on [bulkResultDecision].
 *
 * ### The resurrection coexistence window (the one allowed `retry`, and bounded-wait wins)
 *
 * Process dies mid-bulk → the in-memory §7.5 guard (issue `10.5`) dies `Idle` →
 * WorkManager resurrects this worker (it calls `tryEnter(BULK)`) → but in the
 * seconds before resurrection the app reopens, on-open (issue `11`) fires and
 * `tryEnter(ON_OPEN)` succeeds → on-open is squatting → the resurrected bulk's
 * `tryEnter(BULK)` fails. Resolution: [acquireGuardOrRetry] holds the WM slot and
 * polls `tryEnter` for a few seconds (on-open on a ≤12-file backlog finishes fast)
 * before falling back to `Result.retry()`. That fallback is **retry-to-begin**
 * (about *starting*, not *continuing*) — semantically clean, and distinct from the
 * continuation-retry §5 forbids. See issue `12`'s "retry-to-begin vs
 * retry-for-continuation" note.
 *
 * ### Cancellation = Stop, not failure
 *
 * The notification's Stop action fires [WorkManager.createCancelPendingIntent],
 * which cancels this work → the collecting coroutine is cancelled → [doWork] catches
 * the [CancellationException], calls [IngestionProgressStore.abort] (releasing the
 * guard and transitioning the surface to [Progress.Aborted] — an intentional stop,
 * **not** [Progress.Error]), and rethrows so WM marks the work `CANCELLED` and tears
 * down the foreground notification. Pause/resume/cosmetic continuity is issue `14`;
 * this issue wires only the notification shell + Stop→cancel.
 *
 * ### Wiring
 *
 * Default WorkManager init (no custom [androidx.work.WorkerFactory]); deps are pulled
 * from [ScryerApplication]'s app-scope accessors inside [doWork], mirroring how
 * [OnOpenTrigger] is wired in `ScryerApplication.onCreate`. Not yet started by any UI
 * — [enqueue] is the API Phase 3's banner (and issue `13`'s notification action) will
 * call, so it is not dead code.
 *
 * See: [.scratch/ingestion/issues/12-bulk-ingestion-worker-datasync.md](file:///.scratch/ingestion/issues/12-bulk-ingestion-worker-datasync.md)
 * See: [ADR 0004 §2, §4, §5, §6, §7.5](file:///docs/adr/0004-ingestion-engine-and-trigger-architecture-v2.md)
 */
class IngestionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val logger = IngestionLogger { msg -> android.util.Log.d(TAG, msg) }

    override suspend fun doWork(): Result {
        // Deps from the app scope, mirroring ScryerApplication.onCreate's construction.
        val repository = ScryerApplication.getScreenshotRepository()
        val store = ScryerApplication.getIngestionProgressStore()
        val producer = MediaStoreProducer(repository, ScryerApplication.getContentResolver())
        val engine = IngestionEngine(repository, MlKitOcrStage(), RoomWriteSink(repository))

        // 1. Promote to a dataSync foreground service BEFORE any long work (avoids ANR).
        //    Initial notification is indeterminate — total is unknown until the engine's
        //    early emit arrives. Issue 09 confirmed setForeground(dataSync) works on
        //    targetSdk = 35 via WM 2.10's merged SystemForegroundService declaration.
        setForeground(buildForegroundInfo(current = 0, total = 0, indeterminate = true))

        // 2. §7.5 guard + the resurrection coexistence window (bounded-wait preferred).
        if (!acquireGuardOrRetry(store)) {
            // retry-to-BEGIN (resurrection window): the run never started, so this is NOT
            // the continuation-retry §5 forbids. Distinguished explicitly per issue 12.
            logger.log("doWork: guard not acquired after bounded wait; retry-to-begin.")
            return Result.retry()
        }

        // 3. Single long collection of the engine's cold Flow (§5 — no mid-run checkpoint).
        var terminal: Progress = Progress.Idle
        try {
            engine.process(producer.candidates()).collect { progress ->
                when (progress) {
                    is Progress.Indexing -> {
                        store.publish(progress)
                        updateNotification(progress.current, progress.total)
                    }
                    is Progress.Completed -> {
                        store.complete(progress)
                        terminal = progress
                    }
                    is Progress.Error -> {
                        store.fail(progress)
                        terminal = progress
                    }
                    // Idle is the store's quiescent state (engine never emits it);
                    // Aborted/Paused are reached only via abort()/issue-14 paths, not emitted.
                    Progress.Idle, Progress.Aborted, is Progress.Paused -> Unit
                }
            }
        } catch (ce: CancellationException) {
            // Stop action / system cancel. Cancel ≠ fail: release via abort() so the surface
            // reads Aborted, then rethrow → WM marks the work CANCELLED and tears down the
            // foreground notification (no manual dismiss needed).
            logger.log("doWork: cancelled; aborting run (guard released).")
            store.abort()
            throw ce
        } catch (t: Throwable) {
            // An unexpected collection-loop failure (not an engine Error emission, not
            // cancellation). Surface as Error and re-attempt — symmetric with OnOpenTrigger.
            logger.log("doWork: collection error — ${t.javaClass.simpleName}: ${t.message}")
            store.fail(Progress.Error(t))
            return Result.retry()
        }

        // 4. Map the terminal Progress to a WorkManager Result per the §5 contract.
        val decision = bulkResultDecision(terminal)
        logger.log("doWork: terminal=$terminal → $decision")
        // Returning from doWork lets WM stop the SystemForegroundService and remove the
        // foreground notification — no explicit cancel is required for terminal teardown.
        return when (decision) {
            WorkResultDecision.SUCCESS -> Result.success()
            WorkResultDecision.RETRY -> Result.retry()
            WorkResultDecision.FAILURE -> Result.failure()
        }
    }

    /**
     * Acquire the §7.5 guard, polling for a short window on refusal to absorb the
     * resurrection coexistence window (on-open squatting the guard in the seconds
     * before this resurrected bulk re-enters). Returns `true` once acquired.
     *
     * Bounded-wait is **preferred over `Result.retry()`** here (issue 12): it holds
     * the WM slot instead of consuming the retry semantics, and an on-open run on a
     * ≤12-file backlog drains in seconds. The caller falls back to retry-to-begin
     * only if this window elapses without acquisition.
     */
    private suspend fun acquireGuardOrRetry(store: IngestionProgressStore): Boolean {
        if (store.tryEnter(IngestionProgressStore.TriggerKind.BULK)) return true
        logger.log("acquireGuardOrRetry: BULK refused; polling for resurrection window.")
        repeat(BOUNDED_WAIT_ATTEMPTS) {
            delay(BOUNDED_WAIT_POLL_MS)
            if (store.tryEnter(IngestionProgressStore.TriggerKind.BULK)) return true
        }
        return false
    }

    // ------------------------------------------------------------------ notification

    private fun ensureChannel() {
        // minSdk = 29 ≥ O, so the channel is always required; created idempotently by the
        // platform. IMPORTANCE_LOW: a long OCR run must not beep per tick.
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.ingestion_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(current: Int, total: Int, indeterminate: Boolean): Notification {
        ensureChannel()
        // WM's documented one-liner for a notification cancel action: returns a PendingIntent
        // that cancels THIS worker by id. The work is unique ("ingestion"), so id-based cancel
        // is equivalent to cancelUniqueWork("ingestion") without needing a BroadcastReceiver.
        val stopPendingIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)
        val text = if (indeterminate || total == 0) {
            applicationContext.getString(R.string.ingestion_notification_title)
        } else {
            // ADR 0003 Context's verbatim phrasing, carried by ADR 0004 §4.
            applicationContext.getString(R.string.ingestion_notification_progress, current, total)
        }
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(applicationContext.getString(R.string.ingestion_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)             // don't alert on every progress tick
            .setProgress(total, current, indeterminate)
            .addAction(
                0,
                applicationContext.getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private suspend fun buildForegroundInfo(
        current: Int,
        total: Int,
        indeterminate: Boolean
    ): ForegroundInfo {
        val notification = buildNotification(current, total, indeterminate)
        return ForegroundInfo(NOTIF_ID, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** Update the foreground notification in place (per [Progress.Indexing] emission). */
    private fun updateNotification(current: Int, total: Int) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        manager.notify(NOTIF_ID, buildNotification(current, total, indeterminate = false))
    }

    companion object {
        private const val TAG = "IngestionWorker"

        /** Unique-work name — KEEP so two bulk jobs can't coexist (issue 12 AC). */
        const val UNIQUE_NAME = "ingestion"

        private const val NOTIF_ID = 4242
        private const val CHANNEL_ID = "ingestion"

        // Resurrection-window bounded wait: ~5s of 500ms polls (on-open on ≤12 files drains
        // in seconds). Tuned conservative; idempotent regardless (dedup resumes).
        private const val BOUNDED_WAIT_POLL_MS = 500L
        private const val BOUNDED_WAIT_ATTEMPTS = 10

        /**
         * Enqueue the bulk job as unique work ([ExistingWorkPolicy.KEEP] — a second tap
         * while one runs is a no-op, so two bulk jobs can never coexist). No constraints:
         * the job is user-initiated, runs on-device OCR, and should start promptly. Phase
         * 3's banner and issue `13`'s notification action call this; nothing calls it yet.
         */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<IngestionWorker>().build()
            )
        }
    }
}

/**
 * The §5 result contract as a pure, device-free function (issue 12 AC #8). Extracted
 * from the worker because the WM + `setForeground` + ML Kit path cannot run on the JVM
 * (no Robolectric in this project); the *mapping* is the load-bearing decision and is
 * fully unit-testable here.
 *
 * ADR 0004 §5 is authoritative: **dedup is the checkpoint; the bulk worker runs a single
 * long [doWork] and NEVER uses [Result.retry] for intentional continuation.** This
 * resolves ADR 0003's internal contradiction (§5 rejected retry; the design-summary
 * appendix endorsed it — §5 wins).
 *
 * | Terminal [Progress] | Decision | Why |
 * |---|---|---|
 * | [Progress.Completed] (normal) | [SUCCESS][WorkResultDecision.SUCCESS] | Work done; dedup already excluded completed files. More-files-remain is **not** retry — dedup resumes on the next discovery/app-open. |
 * | [Progress.Completed] with *all* candidates transient-failed, none indexed | [RETRY][WorkResultDecision.RETRY] | Whole-run transient failure (e.g. ML Kit `UNAVAILABLE` across every file). See the discrepancy note below. |
 * | [Progress.Error] | [RETRY][WorkResultDecision.RETRY] | A write/store failure (the only thing the engine surfaces as [Progress.Error], §7.2) is re-attemptable — distinct from continuation. |
 * | Non-terminal / defensive | [SUCCESS][WorkResultDecision.SUCCESS] | Reached only if no terminal Progress was collected (empty edge); cancellation is handled separately in [IngestionWorker.doWork]. |
 *
 * ### Discrepancy note (engine behaviour vs issue 12's table)
 *
 * Issue 12's result-contract table says whole-run ML Kit unavailability surfaces as
 * [Progress.Error]. The **shipped** [IngestionEngine] (via [MlKitOcrStage]) maps ML Kit
 * `UNAVAILABLE` to a **per-file** [io.github.tzhvh.scryernext.ingestion.OcrOutcome.TransientFailure],
 * so a fully-unavailable run surfaces as `Completed(indexed = 0, failed = total)` — not
 * [Progress.Error]. The `Completed` all-failed branch above honours the *intent* of that
 * table row (whole-run transient → retry). Recorded in issue 12's Comments.
 *
 * ### [FAILURE][WorkResultDecision.FAILURE] is reserved (no v1 trigger)
 *
 * The issue's table lists a "permanent environment failure → `Result.failure()`" row
 * (e.g. permission revoked). In v1, permission denial is handled gracefully (empty
 * stream → processed-but-empty) and never throws, so no [Progress] signal maps to
 * [FAILURE][WorkResultDecision.FAILURE] today. The value exists so the contract's
 * vocabulary is complete and the [IngestionWorker.doWork] `when` is exhaustive; a
 * future permanent-env-failure signal (a new [Progress] variant or a pre-flight check)
 * would map to it.
 */
internal enum class WorkResultDecision { SUCCESS, RETRY, FAILURE }

internal fun bulkResultDecision(terminal: Progress): WorkResultDecision = when (terminal) {
    is Progress.Completed -> {
        // §5: dedup is the checkpoint. Normal completion → success, EVEN IF more files
        // remain — the next periodic discovery (issue 13) or app-open (issue 11) re-derives
        // the unindexed set and continues. Never retry for continuation.
        //
        // The one retry case: the WHOLE run was transient-failed across every candidate
        // (indexed == 0 && failed == total) — a whole-run environment failure that is
        // re-attemptable, distinct from continuation. See the discrepancy note above: the
        // shipped engine surfaces this as Completed(0, N, N), not as Progress.Error.
        if (terminal.total > 0 && terminal.indexed == 0 && terminal.failed == terminal.total) {
            WorkResultDecision.RETRY
        } else {
            WorkResultDecision.SUCCESS
        }
    }
    is Progress.Error -> {
        // The engine surfaces only a write/store failure as Progress.Error (§7.2 — an Insert
        // threw, disk full). Re-attemptable → retry, NOT failure(): the run is recoverable.
        WorkResultDecision.RETRY
    }
    // Non-terminal / defensive. Reached only if the collection produced no terminal Progress
    // (an empty edge); cancellation is handled separately in doWork (abort → rethrow). A
    // safe success is correct here: dedup means nothing was lost.
    Progress.Idle, Progress.Aborted, is Progress.Paused, is Progress.Indexing ->
        WorkResultDecision.SUCCESS
}
