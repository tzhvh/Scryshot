/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion.triggers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.tzhvh.scryernext.R
import io.github.tzhvh.scryernext.ScryerApplication
import io.github.tzhvh.scryernext.ingestion.IngestionConfig
import io.github.tzhvh.scryernext.ingestion.IngestionLogger
import io.github.tzhvh.scryernext.ingestion.IngestionProgressStore
import java.util.concurrent.TimeUnit

/**
 * The third ADR 0004 §4 trigger — **periodic discovery**: a daily
 * [PeriodicWorkRequest] that **counts** the unindexed backlog and, if it exceeds
 * [IngestionConfig.BACKLOG_THRESHOLD], posts a count-and-notify notification.
 * **It does no OCR** — it is count-and-notify only (issue `13`).
 *
 * ### What it does, in order
 *
 * 1. `repository.getUnprocessedCount()` — a cheap SQL `COUNT(*) WHERE processed = 0`
 *    (issue `10`'s query). Never materializes the backlog to count it.
 * 2. `store.publishBacklog(count)` — publishes to [IngestionProgressStore.backlog]
 *    so the in-app banner (Phase 3) reads a single shared count rather than each
 *    consumer re-counting.
 * 3. `if (count > THRESHOLD) postNotification(count)` — "Index now" (→ enqueues
 *    [IngestionWorker] via [DiscoveryActionReceiver], issue `12`) and "Snooze"
 *    (dismissal only — WM's periodic interval handles cadence).
 * 4. `return Result.success()` — always. This worker never runs OCR and never retries.
 *
 * ### No foreground service, no engine
 *
 * This worker does **not** promote to a foreground service — it is a sub-second
 * count. If it did `setForeground`, stop. Bulk OCR is [IngestionWorker]'s job,
 * triggered by the notification's "Index now" action. (ADR 0004 §4: the periodic
 * trigger is "Tally + Index/Snooze — **no OCR**".)
 *
 * ### Structural vs. SAF
 *
 * This worker is *structurally required under SAF* (ADR 0004 §1: SAF gives no
 * change notifications to ride on, so a periodic count is how the backlog is
 * surfaced there). Under MediaStore there *is* an onChange path, but the worker
 * lands now regardless — it is the third trigger in §4's table, and counting is
 * cheap enough to be unconditional.
 *
 * ### §7.1 cost-inversion — THE load-bearing forward-looking TODO
 *
 * **This Room-era SQL count does NOT transfer to the zvec transition.** See
 * [the count call site][doWork] and ADR 0004 §7.1 ("backlog-count cost
 * inversion"): under zvec's `content_hash` identity, a file's content-hash is
 * unknowable without reading its bytes, and zvec has no key-enumeration
 * primitive — so counting "unindexed" naïvely means hashing the whole corpus.
 * The worker's **structure** (count → publish → notify) survives the zvec
 * transition unchanged; its **cost model** does not.
 *
 * At the zvec transition, the count must be re-grounded on a metadata cache
 * keyed on cheap filesystem identity (`locator + mtime + size → content_hash →
 * indexed`) so counting stays a metadata lookup instead of a full-corpus hash.
 * The `// TODO(zvec-transition)` at the count call site is the exact re-grounding
 * point; a future agent hunting "where does the discovery count live?" lands here.
 *
 * ### Wiring
 *
 * Default WorkManager init (no custom `WorkerFactory`); deps are pulled from
 * [ScryerApplication]'s app-scope accessors inside [doWork], mirroring
 * [IngestionWorker]. The periodic request is registered once in
 * `ScryerApplication.onCreate` via [enqueuePeriodic] (`KEEP` makes it idempotent
 * across process restarts). No constraints: charging-required (the legacy
 * `ForegroundAndBackgroundCharging` worker's constraint, deleted in Phase 3 issue `16`)
 * is too restrictive for a daily count; the notify-threshold gates the noise.
 *
 * See: [.scratch/ingestion/issues/13-discovery-worker.md](file:///.scratch/ingestion/issues/13-discovery-worker.md)
 * See: [ADR 0004 §4, §7.1](file:///docs/adr/0004-ingestion-engine-and-trigger-architecture-v2.md)
 */
class DiscoveryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val logger = IngestionLogger { msg -> android.util.Log.d(TAG, msg) }

    override suspend fun doWork(): Result {
        // Deps from the app scope, mirroring IngestionWorker.
        val repository = ScryerApplication.getScreenshotRepository()
        val store = ScryerApplication.getIngestionProgressStore()

        // 1. Cheap unindexed count. The repository self-relocates this query to IO.
        //
        //    TODO(zvec-transition): THIS COUNT MUST BE RE-GROUNDED. Under Room, "count
        //    unindexed" is a sub-second `SELECT COUNT(*) WHERE processed = 0` — the cheap
        //    path ADR 0004 §4's trigger table assumes. Under zvec's `content_hash` identity
        //    (ADR 0004 §7.1, "backlog-count cost inversion") this COUNT becomes infeasible:
        //    a file's content-hash is unknowable without reading its bytes, and zvec has no
        //    key-enumeration primitive — so counting unindexed naïvely means hashing the
        //    whole corpus. At the zvec transition, re-ground this on a metadata cache keyed
        //    on cheap filesystem identity (`locator + mtime + size → content_hash → indexed`)
        //    so counting stays a metadata lookup. The worker's *structure* (count → publish
        //    → notify) survives; its *cost model* does not. THIS SITE IS THE RE-GROUNDING POINT.
        val count = repository.getUnprocessedCount()
        logger.log("doWork: unprocessed count = $count")

        // 2. Publish to the shared backlog surface so Phase 3's banner reads one count.
        store.publishBacklog(count)

        // 3. Notify only when the backlog exceeds the threshold (not a fixed daily nag;
        //    ADR 0003 §4 carried by ADR 0004 §4). The notify decision is extracted as a
        //    pure, unit-tested helper.
        if (shouldNotify(count, IngestionConfig.BACKLOG_THRESHOLD)) {
            logger.log("doWork: count ($count) exceeds threshold (${IngestionConfig.BACKLOG_THRESHOLD}); notifying.")
            postNotification(count)
        }

        // 4. Always success. This worker never runs OCR and never retries — it counts and
        //    notifies, full stop.
        return Result.success()
    }

    // ------------------------------------------------------------------ notification

    private fun ensureChannel() {
        // Reuses the ingestion channel (id "ingestion") created idempotently by
        // IngestionWorker.ensureChannel(). Created here too so a discovery-only run (no
        // bulk job ever started) still has a channel to post to. minSdk = 29 ≥ O, so the
        // channel is always required; createNotificationChannel is idempotent by platform.
        // Mirrors IngestionWorker.ensureChannel() exactly.
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.ingestion_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun postNotification(count: Int) {
        ensureChannel()
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager

        // "Index now" → fire-and-forget enqueue of the bulk worker (issue 12). WM hands us a
        // cancel PendingIntent (createCancelPendingIntent) but not an enqueue one, so the
        // enqueue direction goes through DiscoveryActionReceiver — the lightest entry point
        // for "notification tap → start a Worker" (no UI, no service coupling).
        val indexNowPendingIntent = DiscoveryActionReceiver.indexNowPendingIntent(applicationContext)
        // "Snooze" → dismissal only. WM's periodic interval handles cadence; snooze just
        // dismisses the notification (ADR 0004 §4). Don't build a snooze-duration system.
        val snoozePendingIntent = DiscoveryActionReceiver.snoozePendingIntent(applicationContext)

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(applicationContext.getString(R.string.ingestion_notification_title))
            .setContentText(
                applicationContext.getString(R.string.discovery_notification_text, count)
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    applicationContext.getString(R.string.discovery_notification_text, count)
                )
            )
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0,
                applicationContext.getString(R.string.notification_action_index),
                indexNowPendingIntent
            )
            .addAction(
                0,
                applicationContext.getString(R.string.notification_action_snooze),
                snoozePendingIntent
            )
            .build()

        notificationManager.notify(NOTIF_ID, notification)
    }

    companion object {
        private const val TAG = "DiscoveryWorker"

        /** Unique-periodic-work name — KEEP so re-registration (process restart) is a no-op. */
        const val UNIQUE_NAME = "discovery"

        private const val NOTIF_ID = 4243          // distinct from IngestionWorker.NOTIF_ID (4242)
        private const val CHANNEL_ID = "ingestion" // shared with IngestionWorker

        /**
         * Enqueue the daily periodic discovery worker (ADR 0004 §4).
         *
         * [ExistingPeriodicWorkPolicy.KEEP] makes registration idempotent — re-registering on
         * every `onCreate` (process restart) is a no-op if a periodic is already scheduled, so
         * the cadence and constraints set here are the ones that stick. No constraints: the
         * count is cheap enough to run unconditionally; the notify-threshold gates the noise.
         * WM's periodic minimum interval is 15 min; 1 day is the longest practical cadence.
         */
        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<DiscoveryWorker>(
                1, TimeUnit.DAYS
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}

/**
 * The discovery notify decision as a pure, device-free function (issue 13 AC: "the count
 * query and the notify-threshold decision *are* unit-testable as pure logic"). Extracted so
 * the `count > THRESHOLD` rule has one named, documented, tested home rather than an inline
 * comparison — symmetric with how [bulkResultDecision] extracts the §5 result contract.
 *
 * Strict `>`: a backlog of exactly [threshold] is on-open territory (issue `11`), not a notify
 * trigger, and is not worth a daily notification (ADR 0003 §4: "Not a fixed daily nag").
 */
internal fun shouldNotify(count: Int, threshold: Int): Boolean = count > threshold
