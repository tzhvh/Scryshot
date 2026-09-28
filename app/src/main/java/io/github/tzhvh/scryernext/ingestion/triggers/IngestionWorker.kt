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
import androidx.work.WorkInfo
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
import io.github.tzhvh.scryernext.ingestion.ZvecWriteSink
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
 * ### Issue 02 — recoverability: no death may permanently break "Index now"
 *
 * The fd-exhaustion incident (harness doc §1) diagnosed a three-link chain that turns
 * *any* terminal ingestion failure into a permanent dead end, all fixed here:
 *
 * 1. **The zombie** — `bulkResultDecision(Progress.Error)` → `Result.retry()` leaves
 *    this unique work `ENQUEUED` in exponential backoff; [ExistingWorkPolicy.KEEP]
 *    alone then made every "Index now" tap a silent no-op (WorkInfo survives process
 *    death). [enqueue] is now state-aware via the pure [enqueuePolicyDecision]:
 *    `ENQUEUED` with `runAttemptCount > 0` (sitting in retry backoff) is
 *    cancel-and-requeued; healthy `RUNNING`/first-attempt-`ENQUEUED` is never replaced.
 * 2. **The dead cosmetic fallback** — `saveCosmetic(0, 0)` was written once and never
 *    updated, so `HomeFragment`'s `sessionStartTotal > 0` gate could never pass through
 *    the fallback. The first [Progress.Indexing] emission now re-seeds
 *    `(sessionStartTotal = total, doneCount = current)` (the engine emits an early
 *    `Indexing(0, total)` before any OCR — §7.4), so the cross-process fallback shows
 *    determinate progress instead of hiding the line ("indeterminate" forever).
 * 3. **Invisible backoff** — `Result.retry()` retries forever by default. Every retry
 *    site now maps through the pure [cappedResultDecision]: a RETRY at
 *    `runAttemptCount ≥ [MAX_RUN_ATTEMPTS]` gives up as `failure()` — a terminal
 *    WorkInfo the next tap can replace, never an invisible infinite loop.
 *
 * **Fence (load-bearing, ADR 0004 §5):** none of this is continuation-retry. The cap
 * bounds *whole-run re-attempts*; dedup remains the checkpoint and re-derives the
 * unindexed set per attempt. The healthy-path policy is unchanged (`KEEP`); only the
 * ENQUEUED-in-backoff zombie is replaced, and the §7.5 CAS — not enqueue policy —
 * remains the double-engine lock.
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
 * See: [.scratch/ingestion-cliff/issues/02-ingestion-recoverability.md](file:///.scratch/ingestion-cliff/issues/02-ingestion-recoverability.md)
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
        val session = ScryerApplication.getIngestionSession()
        val producer = MediaStoreProducer(repository, ScryerApplication.getContentResolver())
        // zvec Phase 2, issue 03 — the engine's sink is ZvecWriteSink (write-side cutover). The store
        // + cache-DAO provider come from the app scope, mirroring ScryerApplication.onCreate. The
        // cache DAO is now read via ScryerApplication.getMetadataCacheDao() — the same app-scope
        // field onCreate uses — so this wiring site is cast-free and symmetric with onCreate's.
        val zvecContentStore = ScryerApplication.getZvecContentStore()
        val engine = IngestionEngine(
            repository,
            MlKitOcrStage(),
            ZvecWriteSink(
                repository = repository,
                zvecContentStore = zvecContentStore,
                metadataCacheDaoProvider = { ScryerApplication.getMetadataCacheDao() },
            ),
            // Ingestion Inspector — same recorder instance as the on-open engine (app-scope
            // singleton), so the debug event feed covers both trigger kinds. Gated internally
            // by `enabled`; release builds pay one branch per candidate.
            events = ScryerApplication.getIngestionEventRecorder(),
        )

        // 1. Promote to a dataSync foreground service BEFORE any long work (avoids ANR).
        //    Initial notification is indeterminate — total is unknown until the engine's
        //    early emit arrives. Issue 09 confirmed setForeground(dataSync) works on
        //    targetSdk = 35 via WM 2.10's merged SystemForegroundService declaration.
        setForeground(buildForegroundInfo(current = 0, total = 0, indeterminate = true))

        // 2. §7.5 guard + the resurrection coexistence window (bounded-wait preferred).
        if (!acquireGuardOrRetry(store)) {
            // retry-to-BEGIN (resurrection window): the run never started, so this is NOT
            // the continuation-retry §5 forbids. Distinguished explicitly per issue 12.
            // Capped per issue 02 (link 3): a begin that can never happen must surface as a
            // terminal failure, not back off invisibly forever (the squatter is in-memory
            // and dies with the process, so >MAX_RUN_ATTEMPTS here is pathological anyway).
            logger.log("doWork: guard not acquired after bounded wait; retry-to-begin (attempt $runAttemptCount).")
            return retryOrGiveUp()
        }

        // Issue 14a — cosmetic continuity. The guard is now held (a genuine session start):
        // (1) register the clear-on-terminal hook so a finished/aborted session's numerics
        //     don't bleed into the next. The store fires this hook on complete()/fail()/abort(),
        //     which all release the guard (issue 10.5) — so the clear is automatic, not manual.
        // (2) reset the persisted numerics to (sessionStartTotal=0, doneCount=0) — the
        //     "total unknown yet" placeholder that keeps HomeFragment's `sessionStartTotal > 0`
        //     gate closed until real numbers exist. Issue 02 (link 2) completes the loop: the
        //     FIRST Progress.Indexing emission below re-seeds (total, current), so the reset
        //     is no longer a write-once-dead value. Cosmetic ONLY — never the guard's Indexing
        //     state (poison-pill trap; see IngestionSession KDoc).
        store.onTerminalClear { session.clearCosmetic() }
        session.saveCosmetic(sessionStartTotal = 0, doneCount = 0)

        // 3. Single long collection of the engine's cold Flow (§5 — no mid-run checkpoint).
        var terminal: Progress = Progress.Idle
        var cosmeticSeeded = false
        try {
            engine.process(producer.candidates()).collect { progress ->
                when (progress) {
                    is Progress.Indexing -> {
                        store.publish(progress)
                        // Issue 02 (link 2) — seed the cosmetic fallback ONCE from the first
                        // Indexing emission. The engine's early emit (§7.4) carries the full
                        // total before any OCR, so a UI that re-opens while the live in-process
                        // surface is unavailable (resurrection gap, cross-process pending)
                        // renders DETERMINATE continuity instead of a hidden progress line —
                        // the incident's "indeterminate forever" symptom. First-emission-only
                        // (not per-tick): the fallback serves the pre-live window; once the
                        // store publishes, HomeFragment reads the live numerics, and per-tick
                        // Prefs writes at 18k files would be churn for a value nobody reads.
                        // Re-seeded per attempt; cleared on terminal by the hook above.
                        if (!cosmeticSeeded && progress.total > 0) {
                            session.saveCosmetic(
                                sessionStartTotal = progress.total,
                                doneCount = progress.current
                            )
                            cosmeticSeeded = true
                        }
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
            // Capped per issue 02 (link 3): see retryOrGiveUp below.
            logger.log("doWork: collection error — ${t.javaClass.simpleName}: ${t.message}")
            store.fail(Progress.Error(t))
            return retryOrGiveUp()
        }

        // 4. Map the terminal Progress to a WorkManager Result per the §5 contract, with
        //    the issue-02 retry cap applied: RETRY at runAttemptCount ≥ MAX_RUN_ATTEMPTS
        //    becomes a VISIBLE failure (WM FAILED is terminal → the next "Index now" tap
        //    re-enqueues fresh; the banner leaves ACTIVE and returns to the actionable
        //    nudge) instead of an invisible infinite backoff loop.
        val decision = cappedResultDecision(bulkResultDecision(terminal), runAttemptCount)
        logger.log("doWork: terminal=$terminal → $decision (attempt $runAttemptCount)")
        // Returning from doWork lets WM stop the SystemForegroundService and remove the
        // foreground notification — no explicit cancel is required for terminal teardown.
        return when (decision) {
            WorkResultDecision.SUCCESS -> Result.success()
            WorkResultDecision.RETRY -> Result.retry()
            WorkResultDecision.FAILURE -> Result.failure()
        }
    }

    /**
     * The retry-cap application for the two early retry sites (retry-to-begin and the
     * collection-throw path): RETRY unless [cappedResultDecision] has given up, in which
     * case a visible `failure()`. Issue 02 (link 3) — an environment that can never
     * succeed must surface, not back off invisibly forever.
     */
    private fun retryOrGiveUp(): Result =
        if (cappedResultDecision(WorkResultDecision.RETRY, runAttemptCount) == WorkResultDecision.FAILURE) {
            Result.failure()
        } else {
            Result.retry()
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

        // Direct executor for the enqueue lookup's listener: the body runs only once the
        // future is complete (inline on the caller if already done — get() is then instant;
        // else on WM's query thread), so it never blocks main and needs no dedicated thread.
        private val DIRECT_EXECUTOR = java.util.concurrent.Executor { it.run() }

        /**
         * Enqueue the bulk job as unique work, **state-aware** (issue 02, link 1 — the
         * zombie fix). The old unconditional [ExistingWorkPolicy.KEEP] made every tap a
         * no-op whenever unique work existed in *any* non-terminal state — including the
         * zombie: a failed attempt sitting `ENQUEUED` in retry backoff, where it survives
         * process death and silently swallows taps forever (harness doc §1).
         *
         * The (state, runAttemptCount) → policy mapping is the pure [enqueuePolicyDecision]:
         * a healthy `RUNNING` session or a cleanly-queued first attempt keeps `KEEP`
         * (a second tap while one runs is still a no-op — two bulk jobs can never coexist;
         * replacing a *running* session would be the double-engine regression, fence per
         * issue 02); an `ENQUEUED` attempt with `runAttemptCount > 0` (the zombie in
         * backoff) is cancel-and-requeued so the tap always produces a live run —
         * semantically clean because dedup *is* the resumption story (ADR 0004 §5).
         *
         * The §7.5 CAS, not this policy, remains the double-engine lock: under the
         * ENQUEUED→RUNNING race (the attempt starts between query and enqueue) the
         * just-started retry is cancelled (its `CancellationException` path releases the
         * guard) and the fresh run enters cleanly — never two engines over one producer.
         *
         * Fire-and-forget, synchronous-signature: the WorkInfo lookup rides a
         * [com.google.common.util.concurrent.ListenableFuture] listener (direct executor
         * — the body runs only once the future is complete: inline on the caller if
         * already done, else on WM's query thread; never blocking main), preserving
         * [DiscoveryActionReceiver]'s no-ANR contract. A failed lookup degrades to a
         * plain `KEEP` enqueue (absent → fresh) so a tap is never lost to the query.
         * No constraints: the job is user-initiated, runs on-device OCR, and should
         * start promptly. Phase 3's banner and issue `13`'s notification action call
         * this via [IngestionSession.startBulk].
         */
        fun enqueue(context: Context) {
            val wm = WorkManager.getInstance(context)
            val request = OneTimeWorkRequestBuilder<IngestionWorker>().build()
            val future = wm.getWorkInfosForUniqueWork(UNIQUE_NAME)
            future.addListener({
                val info = try {
                    future.get().firstOrNull()
                } catch (_: Throwable) {
                    null   // lookup failed → treat as absent → plain fresh enqueue
                }
                val decision = enqueuePolicyDecision(info?.state, info?.runAttemptCount ?: 0)
                android.util.Log.d(
                    TAG,
                    "enqueue: state=${info?.state} attempt=${info?.runAttemptCount ?: 0} → $decision"
                )
                wm.enqueueUniqueWork(
                    UNIQUE_NAME,
                    when (decision) {
                        // KEEP for both: WM re-evaluates liveness atomically at enqueue
                        // time, so a run that went terminal in the query→enqueue race
                        // still yields a fresh job, and KEEP is a no-op iff still pending.
                        EnqueuePolicyDecision.KEEP_ACTIVE,
                        EnqueuePolicyDecision.ENQUEUE_FRESH -> ExistingWorkPolicy.KEEP
                        // WM 2.10 has no CANCEL_AND_REQUEUE for one-time work; REPLACE is
                        // that semantics — cancel-and-delete pending work, then insert.
                        EnqueuePolicyDecision.CANCEL_AND_ENQUEUE -> ExistingWorkPolicy.REPLACE
                    },
                    request
                )
            }, DIRECT_EXECUTOR)
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

/**
 * The issue-02 retry cap (link 3 — no terminal failure may retry invisibly forever).
 *
 * `Result.retry()` retries without bound by default; the incident's `Progress.Error`
 * (fd exhaustion) sat in exponential backoff forever with no user-facing give-up. This
 * pure wrapper maps a RETRY decision at `runAttemptCount ≥ [MAX_RUN_ATTEMPTS]` to a
 * visible [FAILURE][WorkResultDecision.FAILURE] — WM marks the work `FAILED` (terminal),
 * the store already surfaced [Progress.Error] via `store.fail()`, the banner leaves
 * ACTIVE for the actionable IDLE_BACKLOG nudge, and the next "Index now" tap enqueues
 * fresh ([enqueuePolicyDecision] maps terminal → [ENQUEUE_FRESH][EnqueuePolicyDecision.ENQUEUE_FRESH]).
 *
 * Applied to **every** retry site — the terminal mapping, retry-to-begin (resurrection
 * window), and the collection-throw path — so no path can loop invisibly. This is NOT
 * continuation-retry (ADR 0004 §5): each attempt is a whole fresh run over a re-derived
 * unindexed set; the cap bounds *re-attempts*, and dedup remains the checkpoint.
 *
 * @param decision         the §5-contract decision ([bulkResultDecision] output, or a
 *                         literal RETRY at the early sites).
 * @param runAttemptCount  WM's 0-based attempt counter (`runAttemptCount == 0` on the
 *                         first run; incremented per retry).
 */
internal fun cappedResultDecision(
    decision: WorkResultDecision,
    runAttemptCount: Int
): WorkResultDecision =
    if (decision == WorkResultDecision.RETRY && runAttemptCount >= MAX_RUN_ATTEMPTS) {
        WorkResultDecision.FAILURE
    } else {
        decision
    }

/**
 * The whole-run re-attempt cap (issue 02, link 3). **Three** because the failure classes
 * that legitimately map RETRY (write-path error, whole-run transient) either clear within
 * WM's first backoff windows (default exponential 30s/1m/2m ≈ 3.5 min ≈ this cap's span)
 * or are persistent — the fd-exhaustion wall of the incident, where every extra retry is
 * an invisible zombie minute. Top-level (not in the worker's companion) so it sits with
 * the pure decision it parameterizes and is reachable from JVM tests.
 */
internal const val MAX_RUN_ATTEMPTS = 3

/**
 * What a user-initiated "Index now" enqueue should do with the existing unique work —
 * the issue-02 zombie decision (link 1), extracted as a pure function (house convention:
 * [bulkResultDecision]/[isPending]/[bannerMode]).
 *
 * | WorkInfo state | runAttemptCount | Decision | Why |
 * |---|---|---|---|
 * | `null` (absent/never run) | — | [ENQUEUE_FRESH] | Nothing to keep. |
 * | [RUNNING][WorkInfo.State.RUNNING] | any | [KEEP_ACTIVE] | A healthy live session. Replacing it is the double-engine regression (issue 02 fence); a tap during a real run *should* be a no-op. |
 * | [ENQUEUED][WorkInfo.State.ENQUEUED] | 0 | [KEEP_ACTIVE] | A cleanly-queued first attempt waiting on the executor — a legitimate pending run, not a zombie. |
 * | [ENQUEUED][WorkInfo.State.ENQUEUED] | > 0 | [CANCEL_AND_ENQUEUE] | **The zombie**: a post-failure attempt sitting in retry backoff. `KEEP` alone makes every tap a silent no-op against it (harness doc §1) — the exact incident symptom. |
 * | [SUCCEEDED]/[FAILED]/[CANCELLED] | — | [ENQUEUE_FRESH] | Terminal; a tap starts a new run (WM's KEEP already re-enqueues past terminal work — explicit here for the mapping's completeness). |
 *
 * [BLOCKED][WorkInfo.State.BLOCKED] is defensively mapped like [ENQUEUED][WorkInfo.State.ENQUEUED]
 * (standalone one-time work has no prerequisites, so the state is unreachable in practice).
 *
 * Replacement is semantically clean because dedup *is* the resumption story (ADR 0004 §5):
 * the re-derivation a replacement triggers is exactly what the next discovery/app-open
 * would do anyway. And the §7.5 `tryEnter` CAS — not this policy — remains the double-engine
 * lock; see [IngestionWorker.enqueue]'s KDoc for the ENQUEUED→RUNNING race analysis.
 */
internal enum class EnqueuePolicyDecision { KEEP_ACTIVE, CANCEL_AND_ENQUEUE, ENQUEUE_FRESH }

internal fun enqueuePolicyDecision(state: WorkInfo.State?, runAttemptCount: Int): EnqueuePolicyDecision =
    when (state) {
        // A healthy live run — never replaced (fence: double-engine regression).
        WorkInfo.State.RUNNING -> EnqueuePolicyDecision.KEEP_ACTIVE
        // Pending work: attempt 0 is a clean first queue (keep); attempt > 0 is the
        // zombie sitting in retry backoff (cancel-and-requeue so the tap runs). BLOCKED
        // is defensively folded in (unreachable for standalone one-time work).
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
            if (runAttemptCount > 0) {
                EnqueuePolicyDecision.CANCEL_AND_ENQUEUE
            } else {
                EnqueuePolicyDecision.KEEP_ACTIVE
            }
        // Terminal or absent: nothing to keep — a tap means "start a run".
        WorkInfo.State.SUCCEEDED,
        WorkInfo.State.FAILED,
        WorkInfo.State.CANCELLED,
        null -> EnqueuePolicyDecision.ENQUEUE_FRESH
    }
