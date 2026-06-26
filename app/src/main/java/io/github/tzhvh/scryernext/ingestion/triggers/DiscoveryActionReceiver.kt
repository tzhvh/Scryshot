/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion.triggers

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The entry point for [DiscoveryWorker]'s notification actions (issue `13`) — the lightest
 * component for "notification tap → enqueue a WorkManager job."
 *
 * WorkManager hands us a cancel `PendingIntent` (`createCancelPendingIntent`, used by
 * [IngestionWorker]'s Stop action) but **no enqueue helper** — so the *enqueue* direction
 * needs an explicit entry-point component. A [BroadcastReceiver] is the right fit: the
 * "Index now" action must call [IngestionWorker.enqueue] (a WM call, not a UI start), and
 * neither an Activity (steals focus) nor coupling to `ScryerService` (the capture service,
 * not an ingestion owner) is appropriate. This is a new pattern for the app — the existing
 * service-tap precedent (`PendingIntent.getService` + `ScryerService.dispatchOnStartCommandAction`)
 * only reuses an already-running foreground service; this receiver is fire-and-forget.
 *
 * Manifest-registered, `exported="false"`. Two actions (both cancel the notification as their
 * shared cleanup; "Index now" additionally enqueues the bulk job):
 *
 * - **[ACTION_INDEX_NOW]** → [IngestionWorker.enqueue] (issue `12`'s bulk job starts), then
 *   cancel the discovery notification. The `KEEP` policy on `enqueueUniqueWork("ingestion")`
 *   makes a double-tap a no-op (no second bulk job).
 * - **[ACTION_SNOOZE]** → cancel only. "Snooze is dismissal, not scheduling" (ADR 0004 §4):
 *   WM's periodic interval handles cadence, so snooze just dismisses the notification. There
 *   is no snooze-duration system.
 *
 * The [BroadcastReceiver] runs on the main thread briefly (an enqueue + a notification cancel);
 * `enqueueUniqueWork` is async and returns immediately, so no ANR risk.
 *
 * See: [.scratch/ingestion/issues/13-discovery-worker.md](file:///.scratch/ingestion/issues/13-discovery-worker.md)
 * See: [ADR 0004 §4](file:///docs/adr/0004-ingestion-engine-and-trigger-architecture-v2.md)
 */
class DiscoveryActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_INDEX_NOW -> {
                IngestionWorker.enqueue(context)
                cancelDiscoveryNotification(context)
            }
            ACTION_SNOOZE -> {
                // Dismissal only — WM's periodic interval handles cadence (ADR 0004 §4).
                cancelDiscoveryNotification(context)
            }
        }
    }

    companion object {
        const val ACTION_INDEX_NOW = "io.github.tzhvh.scryernext.ingestion.action.INDEX_NOW"
        const val ACTION_SNOOZE = "io.github.tzhvh.scryernext.ingestion.action.SNOOZE"

        /** Notification id — MUST match [DiscoveryWorker.NOTIF_ID] (the posting side). */
        private const val NOTIF_ID = 4243

        private fun cancelDiscoveryNotification(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            manager.cancel(NOTIF_ID)
        }

        /**
         * Build the "Index now" action's [PendingIntent] — a broadcast to this
         * receiver. `requestCode` and `FLAG_IMMUTABLE` follow the project convention
         * (`ScryerService`'s PendingIntents use a 0 requestCode + `FLAG_IMMUTABLE`).
         */
        fun indexNowPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, DiscoveryActionReceiver::class.java).apply {
                action = ACTION_INDEX_NOW
            }
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_INDEX_NOW,
                intent,
                PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Build the "Snooze" action's [PendingIntent] — a broadcast to this
         * receiver that cancels the notification. Distinct request code from Index now so
         * the two PendingIntents don't alias.
         */
        fun snoozePendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, DiscoveryActionReceiver::class.java).apply {
                action = ACTION_SNOOZE
            }
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_SNOOZE,
                intent,
                PendingIntent.FLAG_IMMUTABLE
            )
        }

        private const val REQUEST_CODE_INDEX_NOW = 4243
        private const val REQUEST_CODE_SNOOZE = 4244
    }
}
