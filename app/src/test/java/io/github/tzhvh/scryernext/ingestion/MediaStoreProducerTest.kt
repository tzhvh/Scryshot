/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

import android.content.ContentResolver
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import io.github.tzhvh.scryernext.repository.ScreenshotRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

class MediaStoreProducerTest {

    private class FakeScreenshotRepository(
        val unprocessedList: List<ScreenshotModel>
    ) : ScreenshotRepository {
        override suspend fun getUnprocessedScreenshotList(): List<ScreenshotModel> {
            return unprocessedList
        }

        // Rest are stubbed/TODO
        override suspend fun isKnown(candidate: Candidate): Boolean = TODO()
        override suspend fun addCollection(collection: io.github.tzhvh.scryernext.persistence.CollectionModel) = TODO()
        override fun getCollections(): Flow<List<io.github.tzhvh.scryernext.persistence.CollectionModel>> = TODO()
        override suspend fun getCollectionList(): List<io.github.tzhvh.scryernext.persistence.CollectionModel> = TODO()
        override suspend fun getCollection(id: String): io.github.tzhvh.scryernext.persistence.CollectionModel? = TODO()
        override fun getCollectionCovers(): Flow<Map<String, ScreenshotModel>> = TODO()
        override suspend fun updateCollection(collection: io.github.tzhvh.scryernext.persistence.CollectionModel) = TODO()
        override suspend fun updateCollectionId(collection: io.github.tzhvh.scryernext.persistence.CollectionModel, id: String) = TODO()
        override suspend fun deleteCollection(collection: io.github.tzhvh.scryernext.persistence.CollectionModel) = TODO()
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

    @Mock
    private lateinit var contentResolver: ContentResolver

    private lateinit var closeable: AutoCloseable

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
    }

    @After
    fun tearDown() {
        closeable.close()
    }

    @Test
    fun testEmitsCandidatesFromRepositoryUnprocessedList() = runBlocking {
        val row1 = ScreenshotModel(
            id = "1",
            uri = "content://media/external/images/media/1",
            displayName = "s1.jpg",
            size = 100L,
            lastModified = 1000L,
            collectionId = "col1",
            processed = false
        )
        val row2 = ScreenshotModel(
            id = "2",
            uri = "content://media/external/images/media/2",
            displayName = "s2.jpg",
            size = 200L,
            lastModified = 2000L,
            collectionId = "col1",
            processed = false
        )

        val repo = FakeScreenshotRepository(listOf(row1, row2))
        val producer = MediaStoreProducer(repo, contentResolver)

        val candidates = producer.candidates().toList()

        assertEquals(2, candidates.size)

        assertEquals("content://media/external/images/media/1", candidates[0].locator)
        assertNull(candidates[0].identity)

        assertEquals("content://media/external/images/media/2", candidates[1].locator)
        assertNull(candidates[1].identity)
    }
}
