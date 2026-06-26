package io.github.tzhvh.scryernext.ingestion

import android.content.Context
import io.github.tzhvh.scryernext.persistence.CollectionModel
import io.github.tzhvh.scryernext.persistence.ScreenshotContentModel
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import io.github.tzhvh.scryernext.repository.ScreenshotRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class IsKnownSeamTest {

    private class FakeScreenshotRepository(
        private val knownKeys: Set<String>
    ) : ScreenshotRepository {
        override suspend fun isKnown(candidate: Candidate): Boolean {
            val key = candidate.identity ?: candidate.locator ?: return false
            return key in knownKeys
        }

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
        override fun getScreenshotContent(): Flow<List<ScreenshotContentModel>> = TODO()
        override suspend fun updateScreenshotContent(screenshotContent: ScreenshotContentModel) = TODO()
        override suspend fun getContentText(screenshot: ScreenshotModel): String? = TODO()
        override suspend fun getUnprocessedScreenshotList(): List<ScreenshotModel> = TODO()
        override suspend fun setupDefaultContent(context: Context) = TODO()
    }

    @Test
    fun isKnown_returnsTrue_whenIdentityIsKnown() = runBlocking {
        val repo = FakeScreenshotRepository(knownKeys = setOf("sha256:123"))
        val candidate = Candidate(
            locator = "content://media/1",
            byteHandle = { ByteArrayInputStream(byteArrayOf()) },
            identity = "sha256:123"
        )
        assertTrue(repo.isKnown(candidate))
    }

    @Test
    fun isKnown_returnsTrue_whenLocatorIsKnown_andIdentityIsNull() = runBlocking {
        val repo = FakeScreenshotRepository(knownKeys = setOf("content://media/1"))
        val candidate = Candidate(
            locator = "content://media/1",
            byteHandle = { ByteArrayInputStream(byteArrayOf()) },
            identity = null
        )
        assertTrue(repo.isKnown(candidate))
    }

    @Test
    fun isKnown_returnsFalse_whenNeitherIsKnown() = runBlocking {
        val repo = FakeScreenshotRepository(knownKeys = setOf("sha256:other"))
        val candidate = Candidate(
            locator = "content://media/1",
            byteHandle = { ByteArrayInputStream(byteArrayOf()) },
            identity = "sha256:123"
        )
        assertFalse(repo.isKnown(candidate))
    }

    @Test
    fun isKnown_returnsFalse_whenCandidateFieldsAreNull() = runBlocking {
        val repo = FakeScreenshotRepository(knownKeys = setOf("content://media/1"))
        val candidate = Candidate(
            locator = null,
            byteHandle = { ByteArrayInputStream(byteArrayOf()) },
            identity = null
        )
        assertFalse(repo.isKnown(candidate))
    }
}
