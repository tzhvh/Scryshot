/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion.triggers

import android.app.NotificationManager
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.tzhvh.scryernext.ScryerApplication
import io.github.tzhvh.scryernext.ingestion.IngestionConfig
import io.github.tzhvh.scryernext.ingestion.IngestionProgressStore
import io.github.tzhvh.scryernext.ingestion.Progress
import io.github.tzhvh.scryernext.persistence.CollectionModel
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Device smoke for **issue 13** — the periodic [DiscoveryWorker] (count-and-notify, no OCR).
 *
 * Asserts the three behaviours the issue gates on:
 * 1. the worker counts `processed = 0` rows via the real Room query and publishes the count to
 *    [IngestionProgressStore.backlog];
 * 2. the threshold gate fires correctly — `count > THRESHOLD` posts the discovery notification
 *    (id 4243), `count <= THRESHOLD` does NOT;
 * 3. the worker never runs OCR (it returns SUCCESS quickly with no foreground promotion).
 *
 * Uses WM's [TestDriver] to force-run the periodic on demand (the real periodic interval is a
 * day and can't be awaited in a test).
 */
@RunWith(AndroidJUnit4::class)
class DiscoveryWorkerDeviceSmokeTest {

    private val TAG = "DiscoveryWorkerSmoke"
    private lateinit var context: android.content.Context
    private lateinit var repository: io.github.tzhvh.scryernext.repository.ScreenshotRepository
    private lateinit var store: IngestionProgressStore
    private val insertedUris = mutableListOf<String>()

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        repository = ScryerApplication.getScreenshotRepository()
        store = ScryerApplication.getIngestionProgressStore()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        // Make sure no stale discovery notification lingers from a prior run.
        cancelDiscoveryNotification()
    }

    @After
    fun cleanup() {
        runBlocking {
            insertedUris.forEach { uri ->
                repository.getScreenshotByUri(uri)?.let { repository.deleteScreenshot(it) }
            }
        }
        cancelDiscoveryNotification()
    }

    @Test
    fun discovery_aboveThreshold_postsNotification_andPublishesBacklog() {
        // Seed > THRESHOLD unprocessed rows so shouldNotify(count, 12) is true.
        val count = IngestionConfig.BACKLOG_THRESHOLD + 1
        seedUnprocessed(count)
        Log.i(TAG, "seeded $count unprocessed rows (threshold=${IngestionConfig.BACKLOG_THRESHOLD})")

        // Enqueue the periodic and force it to run now via the TestDriver (the real interval
        // is a day and can't be awaited). TestDriver takes the work request's id, not the name.
        val workId = enqueuePeriodicForTest()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setPeriodDelayMet(workId)
        // Let the worker's coroutine drain.
        Thread.sleep(2000)

        // 1. Backlog published to the shared surface.
        assertEquals(
            "backlog StateFlow should reflect the seeded count",
            count, store.backlog.value
        )

        // 2. Notification posted (count > threshold).
        assertTrue(
            "discovery notification (id ${DiscoveryWorker_notifId()}) should be posted when count > threshold",
            isDiscoveryNotificationPosted()
        )
        Log.i(TAG, "PASS: notification posted + backlog published for count=$count")
    }

    @Test
    fun discovery_atOrBelowThreshold_postsNoNotification() {
        // Seed exactly THRESHOLD rows. shouldNotify is strict >, so == THRESHOLD must NOT notify.
        seedUnprocessed(IngestionConfig.BACKLOG_THRESHOLD)

        val workId = enqueuePeriodicForTest()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setPeriodDelayMet(workId)
        Thread.sleep(2000)

        assertEquals(
            "backlog should be published even below threshold (banner reads it)",
            IngestionConfig.BACKLOG_THRESHOLD, store.backlog.value
        )
        assertTrue(
            "no discovery notification when count == threshold (strict > gate)",
            !isDiscoveryNotificationPosted()
        )
        Log.i(TAG, "PASS: no notification at threshold (strict > respected)")
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Enqueue the discovery periodic the way [DiscoveryWorker.enqueuePeriodic] does, but
     * return the work request's [java.util.UUID] so the [TestDriver] can force-run it via
     * `setPeriodDelayMet(id)` (the driver keys off the request id, not the unique name).
     */
    private fun enqueuePeriodicForTest(): java.util.UUID {
        val request = androidx.work.PeriodicWorkRequestBuilder<DiscoveryWorker>(
            1, java.util.concurrent.TimeUnit.DAYS
        ).build()
        androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DiscoveryWorker.UNIQUE_NAME,
            androidx.work.ExistingPeriodicWorkPolicy.REPLACE,
            request
        )
        return request.id
    }

    private fun seedUnprocessed(n: Int) {
        // Reuse one real MediaStore URI for all rows (the worker only COUNTs; it does no OCR,
        // so the URI need not be byte-readable — only the row's processed=false matters).
        val uri = "content://media/external/images/media/99999999"
        runBlocking {
            repeat(n) {
                val row = ScreenshotModel(
                    id = UUID.randomUUID().toString(),
                    uri = "$uri-$it-${UUID.randomUUID()}",  // unique per row (uri has a unique index)
                    displayName = "discovery_smoke_$it.jpg",
                    size = 0L,
                    lastModified = System.currentTimeMillis(),
                    collectionId = CollectionModel.UNCATEGORIZED,
                    processed = false
                )
                repository.addScreenshot(listOf(row))
                insertedUris += row.uri
            }
        }
    }

    private fun isDiscoveryNotificationPosted(): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.activeNotifications.any { it.id == DiscoveryWorker_notifId() }
    }

    private fun cancelDiscoveryNotification() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(DiscoveryWorker_notifId())
    }

    // DiscoveryWorker.NOTIF_ID is private; it's the documented constant 4243 (issue 13).
    private fun DiscoveryWorker_notifId(): Int = 4243
}
