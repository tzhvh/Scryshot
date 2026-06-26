/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

import io.github.tzhvh.scryernext.ingestion.triggers.OnOpenTrigger
import io.github.tzhvh.scryernext.persistence.CollectionModel
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import io.github.tzhvh.scryernext.repository.ScreenshotRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

class OnOpenTriggerTest {

    private class FakeScreenshotRepository(
        var unprocessedCount: Int,
        var unprocessedList: List<ScreenshotModel> = emptyList()
    ) : ScreenshotRepository {
        var isKnownCalls = 0

        override suspend fun getUnprocessedCount(): Int {
            return unprocessedCount
        }

        override suspend fun getUnprocessedScreenshotList(): List<ScreenshotModel> {
            return unprocessedList
        }

        override suspend fun isKnown(candidate: Candidate): Boolean {
            isKnownCalls++
            return false
        }

        // Rest are stubbed/TODO
        override suspend fun addCollection(collection: CollectionModel) = TODO()
        override fun getCollections(): Flow<List<CollectionModel>> = TODO()
        override suspend fun getCollectionList(): List<CollectionModel> = TODO()
        override suspend fun getCollection(id: String): CollectionModel? = TODO()
        override fun getCollectionCovers(): Flow<Map<String, ScreenshotModel>> = TODO()
        override suspend fun updateCollection(collection: CollectionModel) = TODO()
        override suspend fun updateCollectionId(collection: CollectionModel, id: String) = TODO()
        override suspend fun deleteCollection(collection: CollectionModel) = TODO()
        override suspend fun addScreenshot(screenshots: List<ScreenshotModel>) = TODO()
        override suspend fun updateScreenshots(screenshots: List<ScreenshotModel>) = TODO()
        override suspend fun getScreenshot(screenshotId: String): ScreenshotModel? = TODO()
        override fun getScreenshots(): Flow<List<ScreenshotModel>> = TODO()
        override suspend fun getScreenshotList(): List<ScreenshotModel> = TODO()
        override fun getScreenshots(collectionIds: List<String>): Flow<List<ScreenshotModel>> = TODO()
        override suspend fun getScreenshotList(collectionIds: List<String>): List<ScreenshotModel> = TODO()
        override suspend fun deleteScreenshot(screenshot: ScreenshotModel) = TODO()
        override fun searchScreenshots(queryText: String): Flow<List<ScreenshotModel>> = TODO()
        override suspend fun searchScreenshotList(queryText: String): List<ScreenshotModel> = TODO()
        override fun getScreenshotContent(): Flow<List<io.github.tzhvh.scryernext.persistence.ScreenshotContentModel>> = TODO()
        override suspend fun updateScreenshotContent(screenshotContent: io.github.tzhvh.scryernext.persistence.ScreenshotContentModel) = TODO()
        override suspend fun getContentText(screenshot: ScreenshotModel): String? = TODO()
        override suspend fun setupDefaultContent(context: android.content.Context) = TODO()
    }

    private lateinit var store: IngestionProgressStore
    private lateinit var testScope: CoroutineScope

    @Before
    fun setUp() {
        store = IngestionProgressStore()
        testScope = CoroutineScope(Job() + Dispatchers.Unconfined)
    }

    @Test
    fun testOnForeground_whenBacklogBelowThreshold_runsEngineAndPublishesToStore() = runBlocking {
        val repo = FakeScreenshotRepository(
            unprocessedCount = 5,
            unprocessedList = (1..5).map {
                ScreenshotModel("id$it", "uri$it", "s$it.jpg", 100, 1000, "col1", false)
            }
        )
        val contentResolver = mock(android.content.ContentResolver::class.java)
        val producer = MediaStoreProducer(repo, contentResolver)
        val ocr = OcrStage { _, _ -> OcrOutcome.Success("text") }
        var writtenCount = 0
        val write = WriteSink { _, _, _ -> writtenCount++ }
        val engine = IngestionEngine(repo, ocr, write)

        val trigger = OnOpenTrigger(repo, producer, engine, store, testScope)
        trigger.onForeground()

        // The trigger should have entered the store, run, and completed!
        assertTrue(store.progress.value is Progress.Completed)
        val completed = store.progress.value as Progress.Completed
        assertEquals(5, completed.total)
        assertEquals(5, completed.indexed)
        assertEquals(5, writtenCount)
    }

    @Test
    fun testOnForeground_whenBacklogExceedsThreshold_doesNothing() = runBlocking {
        val repo = FakeScreenshotRepository(
            unprocessedCount = 13,
            unprocessedList = (1..13).map {
                ScreenshotModel("id$it", "uri$it", "s$it.jpg", 100, 1000, "col1", false)
            }
        )
        val contentResolver = mock(android.content.ContentResolver::class.java)
        val producer = MediaStoreProducer(repo, contentResolver)
        val ocr = OcrStage { _, _ -> OcrOutcome.Success("text") }
        val write = WriteSink { _, _, _ -> }
        val engine = IngestionEngine(repo, ocr, write)

        val trigger = OnOpenTrigger(repo, producer, engine, store, testScope)
        trigger.onForeground()

        // Should remain Idle, and never entered
        assertTrue(store.progress.value is Progress.Idle)
        assertFalse(store.isActive)
    }

    @Test
    fun testOnForeground_whenBacklogIsEmpty_doesNothing() = runBlocking {
        val repo = FakeScreenshotRepository(
            unprocessedCount = 0,
            unprocessedList = emptyList()
        )
        val contentResolver = mock(android.content.ContentResolver::class.java)
        val producer = MediaStoreProducer(repo, contentResolver)
        val ocr = OcrStage { _, _ -> OcrOutcome.Success("text") }
        val write = WriteSink { _, _, _ -> }
        val engine = IngestionEngine(repo, ocr, write)

        val trigger = OnOpenTrigger(repo, producer, engine, store, testScope)
        trigger.onForeground()

        assertTrue(store.progress.value is Progress.Idle)
        assertFalse(store.isActive)
    }

    @Test
    fun testOnForeground_whenStoreAlreadyLocked_doesNothing() = runBlocking {
        val repo = FakeScreenshotRepository(
            unprocessedCount = 5,
            unprocessedList = (1..5).map {
                ScreenshotModel("id$it", "uri$it", "s$it.jpg", 100, 1000, "col1", false)
            }
        )
        val contentResolver = mock(android.content.ContentResolver::class.java)
        val producer = MediaStoreProducer(repo, contentResolver)
        val ocr = OcrStage { _, _ -> OcrOutcome.Success("text") }
        val write = WriteSink { _, _, _ -> }
        val engine = IngestionEngine(repo, ocr, write)

        // Manually enter store under BULK trigger to lock it
        assertTrue(store.tryEnter(IngestionProgressStore.TriggerKind.BULK))

        val trigger = OnOpenTrigger(repo, producer, engine, store, testScope)
        trigger.onForeground()

        // The trigger should be refused, so the active trigger should still be BULK
        // and the progress state should remain the early Indexing from BULK enter (total = 0)
        val progress = store.progress.value as Progress.Indexing
        assertEquals(0, progress.total)
    }

    @Test
    fun testCancel_cancelsEngineJobAndReleasesLock() = runBlocking {
        val repo = FakeScreenshotRepository(
            unprocessedCount = 1,
            unprocessedList = listOf(ScreenshotModel("id1", "uri1", "s1.jpg", 100, 1000, "col1", false))
        )
        val contentResolver = mock(android.content.ContentResolver::class.java)
        val producer = MediaStoreProducer(repo, contentResolver)

        val deferred = kotlinx.coroutines.CompletableDeferred<OcrOutcome>()
        val ocr = OcrStage { _, _ -> deferred.await() }
        val write = WriteSink { _, _, _ -> }
        val engine = IngestionEngine(repo, ocr, write)

        val trigger = OnOpenTrigger(repo, producer, engine, store, testScope)
        trigger.onForeground()

        // It should be running/indexing now
        assertTrue(store.isActive)
        val currentProgress = store.progress.value
        assertTrue(currentProgress is Progress.Indexing)
        assertEquals(1, (currentProgress as Progress.Indexing).total)

        // Cancel the trigger
        trigger.cancel()

        // After cancellation, the job should be cancelled and the store lock released (progress switches to Error with CancellationException)
        assertFalse(store.isActive)
        assertTrue(store.progress.value is Progress.Error)
        val errorState = store.progress.value as Progress.Error
        assertTrue(errorState.throwable is kotlinx.coroutines.CancellationException)
    }
}
