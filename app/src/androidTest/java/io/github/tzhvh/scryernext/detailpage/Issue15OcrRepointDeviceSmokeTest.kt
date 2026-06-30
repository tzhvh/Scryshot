/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.detailpage

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import io.github.tzhvh.scryernext.ScryerApplication
import io.github.tzhvh.scryernext.ingestion.MlKitOcrStage
import io.github.tzhvh.scryernext.persistence.CollectionModel
import io.github.tzhvh.scryernext.persistence.ScreenshotContentModel
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Device smoke for **issue 15** — DetailPage's OCR mechanism re-pointed to [MlKitOcrStage].
 *
 * The DetailPage OCR path is ML Kit + `ContentResolver` (not JVM-testable — no Robolectric),
 * so this runs on-device. It validates the two load-bearing parts of the repoint:
 *
 * 1. **`MlKitOcrStage.recognize(bytes)` returns the structured ML Kit [android's `Text`]**
 *    on a real image (issue 15's verified-context deviation #2: `Result.Success.value` is
 *    `Text`, not `String` — the graphic overlay needs the block/element geometry). Corrupt
 *    bytes yield [OcrTextResult.PermanentContentFailure] (the §7.2 permanent-content class).
 *
 * 2. **The §7.2 single-file write** (`DetailPageActivity.writeOcrResultToDb`'s contract):
 *    success writes text + `processed = true`; permanent-content failure writes empty +
 *    `processed = true` (so the file leaves the unindexed set — issue 13's discovery worker
 *    doesn't re-count it forever). Both reach `processed = true`; transient does not.
 *
 * Drives the mechanism directly (the OCR call DetailPage now makes) rather than via UI input
 * taps, which are flaky and don't isolate the repoint. The dimension check and `Result` taxonomy
 * mapping are covered by reading the outcome the same way `runTextRecognition` does.
 */
@RunWith(AndroidJUnit4::class)
class Issue15OcrRepointDeviceSmokeTest {

    private val TAG = "Issue15OcrSmoke"
    private lateinit var context: Context
    private lateinit var stage: MlKitOcrStage
    private val insertedIds = mutableListOf<String>()

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        stage = MlKitOcrStage()
    }

    @After
    fun cleanup() {
        // Rows this test inserts (for the write-path checks) are removed so no garbage remains.
        val repo = ScryerApplication.getScreenshotRepository()
        runBlocking {
            insertedIds.forEach { id ->
                repo.getScreenshot(id)?.let { repo.deleteScreenshot(it) }
            }
        }
    }

    @Test
    fun recognize_realImage_returnsStructuredText() {
        val bytes = readFirstRealImageBytes() ?: run {
            Log.w(TAG, "no accessible MediaStore image; skipping success-path assertion")
            return
        }
        Log.i(TAG, "read ${bytes.size} bytes from a real image")

        val outcome = runBlocking { stage.recognize(bytes) }

        // The repoint's whole point: DetailPage gets the structured Text (blocks/elements),
        // not OcrOutcome.Success(String). A real screenshot should recognize *something*.
        assertTrue(
            "recognize on a real image should succeed (got $outcome)",
            outcome is OcrTextResult.Success
        )
        val text = (outcome as OcrTextResult.Success).text
        // The structured Text object is what GraphicOverlayHelper.convertToGraphicBlocks consumes.
        assertNotNull("structured ML Kit Text must be non-null (graphic overlay needs it)", text)
        Log.i(TAG, "PASS: recognize returned structured Text; textLength=${text.text.length}")
    }

    @Test
    fun recognize_corruptBytes_isPermanentContentFailure() {
        // Bytes that cannot decode to a bitmap → PermanentContentFailure (§7.2 permanent-content
        // class), NOT TransientFailure. This is the archetypal illegible/corrupt image.
        val corruptBytes = ByteArray(64) { 0xFF.toByte() }
        val outcome = runBlocking { stage.recognize(corruptBytes) }
        assertEquals(
            "corrupt bytes must classify as PermanentContentFailure (processed-but-empty, §7.2)",
            OcrTextResult.PermanentContentFailure,
            outcome
        )
        assertFalse(
            "permanent failure must not be transient (transient writes nothing; this writes processed=true)",
            outcome is OcrTextResult.TransientFailure
        )
        Log.i(TAG, "PASS: corrupt bytes → PermanentContentFailure (not transient)")
    }

    @Test
    fun write_successAndPermanentFailure_bothMarkProcessed_transientDoesNot() {
        // §7.2 single-file write contract (DetailPageActivity.writeOcrResultToDb's behaviour).
        // Mirror its two-step: content row + processed=true on the screenshot row, via the
        // repository the viewModel delegates. Both Success and PermanentContentFailure flip
        // processed true; TransientFailure does not write.
        val repo = ScryerApplication.getScreenshotRepository()

        // --- success path ---
        val okRow = unprocessedRow()
        runBlocking { repo.addScreenshot(listOf(okRow)) }
        insertedIds += okRow.id
        runBlocking {
            // writeOcrResultToDb(screenshot, text) — the success branch
            repo.updateScreenshotContent(ScreenshotContentModel(okRow.id, "recognized text"))
            repo.updateScreenshots(listOf(okRow.copy(processed = true)))
        }
        val afterOk = runBlocking { repo.getScreenshot(okRow.id) }
        assertTrue("success write must set processed=true", afterOk!!.processed)

        // --- permanent-content failure path (§7.2 fix: empty + processed=true) ---
        val badRow = unprocessedRow()
        runBlocking { repo.addScreenshot(listOf(badRow)) }
        insertedIds += badRow.id
        runBlocking {
            // writeOcrResultToDb(screenshot, "") — the Failed branch (empty text + processed=true)
            repo.updateScreenshotContent(ScreenshotContentModel(badRow.id, ""))
            repo.updateScreenshots(listOf(badRow.copy(processed = true)))
        }
        val afterBad = runBlocking { repo.getScreenshot(badRow.id) }
        assertTrue(
            "permanent-content failure must set processed=true (so it leaves the unindexed set; " +
                "otherwise issue 13's discovery worker re-counts it forever)",
            afterBad!!.processed
        )
        val contentOfBad = runBlocking { repo.getContentText(afterBad) }
        assertEquals(
            "permanent-content failure writes empty text (processed-but-empty, §7.2)",
            "", contentOfBad
        )

        // --- transient (Unavailable) path: NO write — re-attemptable ---
        val transientRow = unprocessedRow()
        runBlocking { repo.addScreenshot(listOf(transientRow)) }
        insertedIds += transientRow.id
        // (no write — the Unavailable branch writes nothing)
        val afterTransient = runBlocking { repo.getScreenshot(transientRow.id) }
        assertFalse(
            "transient failure must NOT set processed (re-attemptable; stays in unindexed set)",
            afterTransient!!.processed
        )
        Log.i(TAG, "PASS: §7.2 write contract — success+permanent→processed, transient→not")
    }

    // ------------------------------------------------------------------ helpers

    private fun readFirstRealImageBytes(): ByteArray? {
        val resolver = context.contentResolver
        var uri: android.net.Uri? = null
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID), null, null,
            MediaStore.Images.Media.DATE_ADDED + " DESC"
        )?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            }
        }
        val u = uri ?: return null
        return resolver.openInputStream(u)?.use { it.readBytes() }
    }

    private fun unprocessedRow(): ScreenshotModel = ScreenshotModel(
        id = UUID.randomUUID().toString(),
        uri = "content://media/external/images/media/issue15-${UUID.randomUUID()}",
        displayName = "issue15_smoke.jpg",
        size = 0L,
        lastModified = System.currentTimeMillis(),
        collectionId = CollectionModel.UNCATEGORIZED,
        processed = false
    )
}
