/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

import io.github.tzhvh.scryernext.persistence.ContentMetadataCache
import io.github.tzhvh.scryernext.persistence.ContentMetadataCacheDao
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import io.github.tzhvh.scryernext.repository.ScreenshotInMemoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * zvec Phase 2, issue 03 — JVM unit tests for [ZvecWriteSink].
 *
 * Asserts the write-cutover contract without the `.so`: the upsert payload (PK = sha256(bytes),
 * locator, content, collection_id), the zvec-first-then-Room ordering (D13), flush-on-commit, the
 * `processed` flip + bridge-hash record, the metadata-cache warming, the `processed=false` no-op,
 * and the missing-row skip. Uses a fake [ZvecContentStore] that records call order + payload, the
 * real [ScreenshotInMemoryRepository] (its `markContentIndexed` sets the hash + flips processed), and
 * an in-memory [ContentMetadataCacheDao] fake.
 */
class ZvecWriteSinkTest {

    /** Records upsert payloads + flush events in call order, so the test asserts ordering + payload. */
    private class RecordingZvecContentStore : ZvecContentStore(FAKE_DIR, debug = false) {
        data class Upsert(val contentHash: String, val locator: String, val content: String, val collectionId: String)
        val events: MutableList<Any> = mutableListOf()

        override suspend fun upsert(contentHash: String, locator: String, content: String, collectionId: String, lastModified: Long?) {
            events += Upsert(contentHash, locator, content, collectionId)
        }

        override suspend fun flush() {
            events += "flush"
        }
    }

    /** An in-memory [ContentMetadataCacheDao] capturing upsert + markIndexed calls. */
    private class RecordingCacheDao : ContentMetadataCacheDao {
        val upserts: MutableList<ContentMetadataCache> = mutableListOf()
        val markIndexedHashes: MutableList<String> = mutableListOf()

        override fun lookup(locator: String, mtime: Long, size: Long) = null
        override fun lookupByHash(contentHash: String): ContentMetadataCache? = upserts.find { it.contentHash == contentHash }
        override fun upsert(entry: ContentMetadataCache) { upserts += entry }
        override fun markIndexed(contentHash: String): Int {
            markIndexedHashes += contentHash
            return upserts.count { it.contentHash == contentHash }
        }
        override fun clearAll() { upserts.clear() }
    }

    @Test
    fun commit_upsertsToZvecFirst_thenRecordsHashAndFlushes() = runBlocking {
        val repo = ScreenshotInMemoryRepository()
        val screenshot = ScreenshotModel(
            uri = "content://media/1",
            displayName = "shot.png",
            size = 5L,
            lastModified = 100L,
            collectionId = "collection-none",
        )
        repo.addScreenshot(listOf(screenshot))
        val store = RecordingZvecContentStore()
        val cacheDao = RecordingCacheDao()
        val sink = ZvecWriteSink(repo, store) { cacheDao }
        val bytes = byteArrayOf(1, 2, 3, 4)
        val candidate = Candidate(locator = "content://media/1", byteHandle = { error("must not re-open") })

        sink.commit(candidate, text = "hello ocr", processed = true, bytes = bytes, precomputedContentHash = null)

        val expectedHash = sha256Hex(bytes)

        // Ordering: upsert FIRST, then flush (zvec-first, before Room).
        val upsert = store.events.filterIsInstance<RecordingZvecContentStore.Upsert>().single()
        assertEquals(expectedHash, upsert.contentHash)
        assertEquals("content://media/1", upsert.locator)
        assertEquals("hello ocr", upsert.content)
        assertEquals("collection-none", upsert.collectionId)

        val flushIndex = store.events.indexOf("flush")
        assertTrue("flush must be called", flushIndex >= 0)
        val upsertIndex = store.events.indexOf(upsert)
        assertTrue("upsert must precede flush (zvec-first)", upsertIndex in 0 until flushIndex)

        // Room side: the bridge hash is recorded + the row is retired.
        val updated = repo.getScreenshotByUri("content://media/1")!!
        assertTrue(updated.processed)
        assertEquals(expectedHash, updated.contentHash)

        // Cache warmed: upsert + markIndexed with the hash.
        assertEquals(1, cacheDao.upserts.size)
        assertEquals(expectedHash, cacheDao.upserts.single().contentHash)
        assertEquals("content://media/1", cacheDao.upserts.single().locator)
        assertEquals(listOf(expectedHash), cacheDao.markIndexedHashes)
    }

    @Test
    fun commit_nullTextWritesEmptyContent() = runBlocking {
        val repo = ScreenshotInMemoryRepository()
        repo.addScreenshot(listOf(ScreenshotModel("id-1", "content://media/1", "n", 5L, 100L, "col")))
        val store = RecordingZvecContentStore()
        val sink = ZvecWriteSink(repo, store) { RecordingCacheDao() }
        val bytes = byteArrayOf(9)

        sink.commit(Candidate("content://media/1", byteHandle = { error("must not re-open") }), text = null, processed = true, bytes = bytes, precomputedContentHash = null)

        // PermanentContentFailure path: text is null → empty content (the processed-but-empty case).
        val upsert = store.events.filterIsInstance<RecordingZvecContentStore.Upsert>().single()
        assertEquals("", upsert.content)
        assertEquals(sha256Hex(bytes), upsert.contentHash)
    }

    @Test
    fun commit_processedFalse_isNoOp() = runBlocking {
        val repo = ScreenshotInMemoryRepository()
        val store = RecordingZvecContentStore()
        val sink = ZvecWriteSink(repo, store) { RecordingCacheDao() }

        sink.commit(Candidate("content://media/1", byteHandle = { error("must not re-open") }), text = "x", processed = false, bytes = byteArrayOf(1), precomputedContentHash = null)

        // TransientFailure never reaches the sink; a processed=false call writes nothing.
        assertTrue(store.events.isEmpty())
        assertNull(repo.getScreenshotByUri("content://media/1"))
    }

    @Test
    fun commit_missingRow_skipsWriteWithoutTouchingZvec() = runBlocking {
        val repo = ScreenshotInMemoryRepository() // empty — no row for the locator
        val store = RecordingZvecContentStore()
        val sink = ZvecWriteSink(repo, store) { RecordingCacheDao() }

        sink.commit(Candidate("content://missing", byteHandle = { error("must not re-open") }), text = "x", processed = true, bytes = byteArrayOf(1), precomputedContentHash = null)

        // Model B invariant violation — logged + skipped; zvec is untouched.
        assertTrue(store.events.isEmpty())
    }

    @Test
    fun commit_doesNotReOpenTheFile() = runBlocking {
        // The whole point of issue 02's READ→DEDUP reorder: the sink hashes the bytes the engine
        // already read, never re-opening Candidate.byteHandle. The byteHandle throws if called.
        val repo = ScreenshotInMemoryRepository()
        repo.addScreenshot(listOf(ScreenshotModel("id-1", "content://media/1", "n", 5L, 100L, "col")))
        val store = RecordingZvecContentStore()
        val sink = ZvecWriteSink(repo, store) { RecordingCacheDao() }

        val candidate = Candidate(locator = "content://media/1", byteHandle = { error("sink must not re-open the file") })

        sink.commit(candidate, text = "x", processed = true, bytes = byteArrayOf(1, 2, 3), precomputedContentHash = null)

        // Commit completed without the byteHandle ever being invoked (no throw propagated).
        assertEquals(1, store.events.filterIsInstance<RecordingZvecContentStore.Upsert>().size)
    }

    @Test
    fun commit_precomputedHashIsAuthoritative_acrossAllThreeStores() = runBlocking {
        // The §0.4 threading: the engine passes the hash `isKnown`'s miss path already
        // computed, so the sink must NOT re-hash [bytes]. A deliberately-wrong sentinel
        // (not sha256Hex(bytes)) proves precedence — if the sink re-derived the hash this
        // test fails, and if the sentinel ever fails to fan out to ALL THREE stores
        // (zvec PK, Room content_hash, cache) the cross-store identity splits.
        val repo = ScreenshotInMemoryRepository()
        repo.addScreenshot(listOf(ScreenshotModel("id-1", "content://media/1", "n", 5L, 100L, "col")))
        val store = RecordingZvecContentStore()
        val cacheDao = RecordingCacheDao()
        val sink = ZvecWriteSink(repo, store) { cacheDao }
        val sentinel = "precomputed-sentinel-not-the-digest"

        sink.commit(
            Candidate("content://media/1", byteHandle = { error("must not re-open") }),
            text = "x", processed = true, bytes = byteArrayOf(1, 2, 3),
            precomputedContentHash = sentinel,
        )

        assertEquals(sentinel, store.events.filterIsInstance<RecordingZvecContentStore.Upsert>().single().contentHash)
        assertEquals(sentinel, repo.getScreenshotByUri("content://media/1")!!.contentHash)
        assertEquals(sentinel, cacheDao.upserts.single().contentHash)
        assertEquals(listOf(sentinel), cacheDao.markIndexedHashes)
    }

    @Test
    fun zvecFirstOrdering_upsertBeforeMarkContentIndexed() = runBlocking {
        // D13: a crash between the zvec upsert and the Room update must leave the row un-`processed`
        // (self-healing), NOT a processed=1 row pointing at a missing zvec doc. Assert the order by
        // failing the Room write between the two and observing the upsert already happened.
        val store = RecordingZvecContentStore()
        // A repo whose markContentIndexed throws — the upsert must already have landed.
        val throwingRepo = object : ScreenshotInMemoryRepository() {
            override suspend fun markContentIndexed(screenshot: ScreenshotModel, contentHash: String) =
                throw RuntimeException("crash between zvec and Room")
        }
        throwingRepo.addScreenshot(listOf(ScreenshotModel("id-1", "content://media/1", "n", 5L, 100L, "col")))
        val sink = ZvecWriteSink(throwingRepo, store) { RecordingCacheDao() }
        val bytes = byteArrayOf(7)

        val threw = runCatching {
            sink.commit(Candidate("content://media/1", byteHandle = { error("must not re-open") }), "x", processed = true, bytes, null)
        }

        assertTrue("Room write threw (simulating the mid-write crash)", threw.isFailure)
        // The zvec upsert landed BEFORE the throw → the lossy case is the recoverable one.
        assertNotNull(store.events.filterIsInstance<RecordingZvecContentStore.Upsert>().singleOrNull())
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0f])
        }
        return sb.toString()
    }

    private companion object {
        // ZvecContentStore's constructor takes a filesDir; the fake overrides every method that
        // touches it, so the path is never read. A throw-placeholder keeps the intent explicit.
        val FAKE_DIR = java.io.File("/tmp/zvec-write-sink-test-unused")
    }
}
