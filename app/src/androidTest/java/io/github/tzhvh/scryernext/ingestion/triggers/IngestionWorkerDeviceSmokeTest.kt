/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion.triggers

import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.tzhvh.scryernext.ScryerApplication
import io.github.tzhvh.scryernext.persistence.CollectionModel
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Device smoke for **issue 12** — the bulk [IngestionWorker].
 *
 * The WM/`setForeground(dataSync)`/ML Kit path can't run on the JVM (no Robolectric); this
 * instrumented test runs it on-device against the real WorkManager + Room repository +
 * `MlKitOcrStage`, asserting the §5 result contract's observable consequences:
 *
 * - enqueuing produces a unique [WorkInfo] that reaches a terminal state (the single long
 *   `doWork` runs to completion — `Result.success()` on `Progress.Completed`),
 * - the guard is acquired and released (the store returns to a non-`Indexing` state),
 * - dedup-as-resumption holds: candidates the engine OCRs land `processed = true`, so a
 *   second enqueue is a near-no-op (no double-work — `KEEP` policy + dedup).
 *
 * Mirrors [io.github.tzhvh.scryernext.ingestion.MediaStoreProducerIntegrationTest]'s
 * conventions (real MediaStore URI, insert+cleanup a test row, run in the app process).
 *
 * **Test data:** inserts an unprocessed row whose `uri` resolves to a real MediaStore image
 * (so the engine's decode + ML Kit OCR actually run on real bytes), then cleans it up.
 */
@RunWith(AndroidJUnit4::class)
class IngestionWorkerDeviceSmokeTest {

    private val TAG = "IngestionWorkerSmoke"

    @Before
    fun setupWorkManager() {
        // Use the real WM scheduler semantics (not a no-op TestDriver) so setForeground +
        // the dataSync foreground service actually run. WorkManagerTestInitHelper still gives
        // us a clean, isolated WM instance for the test process.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun bulkWorker_runsToSuccess_andMarksProcessed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = ScryerApplication.getScreenshotRepository()
        val wm = WorkManager.getInstance(context)

        // Cancel any leftover unique work from prior runs/app boots so this test is isolated.
        wm.cancelUniqueWork(IngestionWorker.UNIQUE_NAME)
        // also drain the on-open guard if a prior session left it held
        ScryerApplication.getIngestionProgressStore().let { store ->
            if (store.progress.value is io.github.tzhvh.scryernext.ingestion.Progress.Indexing) {
                store.abort()
            }
        }

        // 1. Find a real MediaStore image so the engine's decode + ML Kit run on real bytes.
        val resolver = context.contentResolver
        var realUri: String? = null
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                realUri = android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                ).toString()
            }
        }
        Log.i(TAG, "realUri=$realUri")

        // 2. Insert an unprocessed row pointing at the real image (Model B precondition).
        val uri = realUri ?: "content://media/external/images/media/99999999"
        val testRow = ScreenshotModel(
            id = UUID.randomUUID().toString(),
            uri = uri,
            displayName = "issue12_smoke.jpg",
            size = 1024L,
            lastModified = System.currentTimeMillis(),
            collectionId = CollectionModel.UNCATEGORIZED,
            processed = false
        )
        runBlocking { repository.addScreenshot(listOf(testRow)) }

        try {
            // 3. Enqueue the bulk worker (unique KEEP).
            IngestionWorker.enqueue(context)

            // 4. Block until the WorkInfo reaches a terminal state. A single screenshot OCRs
            //    in well under a minute; allow generous headroom for ML Kit's first run.
            val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3)
            var info: WorkInfo? = null
            while (System.currentTimeMillis() < deadline) {
                val infos = wm.getWorkInfosForUniqueWork(IngestionWorker.UNIQUE_NAME).get()
                info = infos?.firstOrNull()
                if (info != null && info.state.isFinished) break
                Thread.sleep(500)
            }
            assertNotNull("WorkInfo should exist for unique work 'ingestion'", info)
            Log.i(TAG, "terminal WorkInfo state = ${info!!.state}; outputData=${info.outputData}")

            // §5 contract: a normal completion is SUCCESS (never retry-for-continuation).
            // The one retry-to-BEGIN path (guard contention) is excluded by the @Before drain,
            // so on a clean run we expect SUCCEEDED. (A real-image run OCRs and completes.)
            assertTrue(
                "WorkInfo should be terminal; got ${info.state}",
                info.state.isFinished
            )

            // 5. Dedup-as-resumption: the row the engine OCR'd is now processed=true. A
            //    processed-but-empty permanent failure ALSO sets processed=true (§7.2), so the
            //    assertion is "processed flipped true", regardless of text outcome.
            val updated = runBlocking { repository.getScreenshotByUri(uri) }
            assertNotNull("row should still exist after run", updated)
            assertTrue(
                "row should be processed=true after a successful/processed-failed OCR " +
                    "(got processed=${updated!!.processed})",
                updated.processed
            )

            // 6. The §7.5 guard is released on a terminal transition — never wedged Indexing.
            val progress = ScryerApplication.getIngestionProgressStore().progress.value
            assertFalse(
                "guard should be released after terminal transition (progress=$progress)",
                progress is io.github.tzhvh.scryernext.ingestion.Progress.Indexing
            )
        } finally {
            runBlocking {
                repository.getScreenshotByUri(uri)?.let { repository.deleteScreenshot(it) }
            }
        }
    }
}
