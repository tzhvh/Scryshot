/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

import android.content.ContentUris
import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import io.github.tzhvh.scryernext.ScryerApplication
import io.github.tzhvh.scryernext.persistence.CollectionModel
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Instrumented integration test that runs on a connected Android device.
 * Validates that [MediaStoreProducer] can query the Room database, emit [Candidate]s,
 * and that [ContentResolver.openInputStream] can read real/simulated content via [Candidate.byteHandle].
 */
@RunWith(AndroidJUnit4::class)
class MediaStoreProducerIntegrationTest {

    private val TAG = "MediaStoreProducerTest"

    @Test
    fun testMediaStoreProducer_readsFromDbAndOpensStream() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val resolver = context.contentResolver

            // 1. Find a real image in the system MediaStore to use as a valid URI if permission is granted.
            var realUriString: String? = null
            try {
                val cursor = resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    null, null, null
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        val id = it.getLong(idColumn)
                        realUriString = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString()
                        Log.i(TAG, "Found real image URI for test: $realUriString")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not query MediaStore (likely missing permissions). Falling back to mock URI.", e)
            }

            // Fallback: If no real images are accessible, we'll try a dummy mock content URI.
            val uriToUse = realUriString ?: "content://media/external/images/media/99999999"

            // 2. Retrieve the repository from ScryerApplication
            val repository = ScryerApplication.getScreenshotRepository()

            val testScreenshot = ScreenshotModel(
                id = UUID.randomUUID().toString(),
                uri = uriToUse,
                displayName = "test_smoke_image.jpg",
                size = 1024L,
                lastModified = System.currentTimeMillis(),
                collectionId = CollectionModel.UNCATEGORIZED,
                processed = false
            )

            // Insert the unprocessed screenshot row
            repository.addScreenshot(listOf(testScreenshot))

            try {
                // 3. Initialize MediaStoreProducer and collect candidates
                val producer = MediaStoreProducer(repository, resolver)
                val candidates = producer.candidates().toList()

                // Verify our candidate is emitted
                val testCandidate = candidates.find { it.locator == uriToUse }
                assertNotNull("Candidate with locator $uriToUse should be emitted", testCandidate)
                assertEquals(uriToUse, testCandidate?.locator)

                // 4. Invoke byteHandle and verify stream resolution
                val inputStream = testCandidate!!.byteHandle()
                assertNotNull("InputStream should not be null", inputStream)

                val bytes = inputStream.use { it.readBytes() }
                if (realUriString != null) {
                    // Real image content read check
                    assertTrue("Bytes read from real content resolver should not be empty", bytes.isNotEmpty())
                    Log.i(TAG, "Successfully read ${bytes.size} bytes from real image URI.")
                } else {
                    // If it was the dummy fallback, the resolver returns an empty stream per-candidate failure design
                    assertEquals("Unreadable fallback stream should return 0 bytes", 0, bytes.size)
                    Log.i(TAG, "Successfully validated graceful error fallback path for dummy URI.")
                }
            } finally {
                // Clean up the database row so we leave no garbage
                repository.deleteScreenshot(testScreenshot)
            }
        }
    }
}
