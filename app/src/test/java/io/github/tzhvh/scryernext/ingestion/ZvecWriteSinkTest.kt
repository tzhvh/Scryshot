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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * zvec Phase 2, issue 03 + issue 01 (checkpoint-batched flush) — JVM unit tests for [ZvecWriteSink].
 *
 * Issue 03's write-cutover contract, still asserted without the `.so`: the upsert payload
 * (PK = sha256(bytes), locator, content, collection_id), the `processed` flip + bridge-hash record,
 * the metadata-cache warming, the `processed=false` no-op, and the missing-row skip.
 *
 * Issue 01 changes the durability cadence and adds the batch-ordering acceptance tests:
 * - **the load-bearing invariant (D13 at batch granularity): a Room mark NEVER precedes the zvec
 *   flush covering that doc** — asserted over one shared call trace across all three stores, at
 *   the batch boundary, at the terminal checkpoint, and over an abandoned partial batch (the
 *   crash window);
 * - commit upserts immediately but defers flush + Room mark + cache warm to the checkpoint;
 * - one flush per [ZvecWriteSink.CHECKPOINT_BATCH_SIZE] commits + one at the terminal
 *   [ZvecWriteSink.checkpoint] — exactly one for an on-open-sized small run;
 * - a mid-drain failure leaves the failed mark + tail pending, and a re-drain completes them
 *   without a second flush.
 *
 * Uses a fake [ZvecContentStore] that records call order + payload, the real
 * [ScreenshotInMemoryRepository] (its `markContentIndexed` sets the hash + flips processed), and
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

        fun flushCount(): Int = events.count { it == "flush" }
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
        override fun getCount(): Int = upserts.size
    }

    private fun row(id: String): ScreenshotModel = ScreenshotModel(
        id = id, uri = "content://media/$id", displayName = id, size = 5L, lastModified = 100L,
        collectionId = "collection-none",
    )

    private fun candidate(id: String): Candidate =
        Candidate(locator = "content://media/$id", byteHandle = { error("must not re-open") })

    @Test
    fun commit_upsertsImmediately_defersFlushRoomMarkAndCacheWarmUntilCheckpoint() = runBlocking {
        val repo = ScreenshotInMemoryRepository()
        repo.addScreenshot(listOf(row("1")))
        val store = RecordingZvecContentStore()
        val cacheDao = RecordingCacheDao()
        val sink = ZvecWriteSink(repo, store) { cacheDao }
        val bytes = byteArrayOf(1, 2, 3, 4)

        sink.commit(candidate("1"), text = "hello ocr", processed = true, bytes = bytes, precomputedContentHash = null)

        val expectedHash = sha256Hex(bytes)

        // Immediate: the zvec upsert landed with the full payload (zvec-first, D13 step 1).
        val upsert = store.events.filterIsInstance<RecordingZvecContentStore.Upsert>().single()
        assertEquals(expectedHash, upsert.contentHash)
        assertEquals("content://media/1", upsert.locator)
        assertEquals("hello ocr", upsert.content)
        assertEquals("collection-none", upsert.collectionId)

        // Deferred (issue 01): no flush yet, the row is NOT processed, the cache is NOT warmed —
        // the mark sits behind the flush that must cover it.
        assertEquals(0, store.flushCount())
        assertFalse(repo.getScreenshotByUri("content://media/1")!!.processed)
        assertNull(repo.getScreenshotByUri("content://media/1")!!.contentHash)
        assertTrue(cacheDao.upserts.isEmpty())

        // The terminal checkpoint performs the batch's single flush, THEN the Room mark + warm.
        sink.checkpoint()

        assertEquals(1, store.flushCount())
        val updated = repo.getScreenshotByUri("content://media/1")!!
        assertTrue(updated.processed)
        assertEquals(expectedHash, updated.contentHash)
        assertEquals(1, cacheDao.upserts.size)
        assertEquals(expectedHash, cacheDao.upserts.single().contentHash)
        assertEquals("content://media/1", cacheDao.upserts.single().locator)
        assertEquals(listOf(expectedHash), cacheDao.markIndexedHashes)
    }

    @Test
    fun commit_nullTextWritesEmptyContent() = runBlocking {
        val repo = ScreenshotInMemoryRepository()
        repo.addScreenshot(listOf(row("1")))
        val store = RecordingZvecContentStore()
        val sink = ZvecWriteSink(repo, store) { RecordingCacheDao() }
        val bytes = byteArrayOf(9)

        sink.commit(candidate("1"), text = null, processed = true, bytes = bytes, precomputedContentHash = null)

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

        sink.commit(candidate("1"), text = "x", processed = false, bytes = byteArrayOf(1), precomputedContentHash = null)

        // TransientFailure never reaches the sink; a processed=false call writes nothing.
        assertTrue(store.events.isEmpty())
        assertNull(repo.getScreenshotByUri("content://media/1"))
    }

    @Test
    fun commit_missingRow_skipsWriteWithoutTouchingZvec() = runBlocking {
        val repo = ScreenshotInMemoryRepository() // empty — no row for the locator
        val store = RecordingZvecContentStore()
        val sink = ZvecWriteSink(repo, store) { RecordingCacheDao() }

        sink.commit(candidate("missing"), text = "x", processed = true, bytes = byteArrayOf(1), precomputedContentHash = null)

        // Model B invariant violation — logged + skipped; zvec is untouched.
        assertTrue(store.events.isEmpty())
    }

    @Test
    fun commit_doesNotReOpenTheFile() = runBlocking {
        // The whole point of issue 02's READ→DEDUP reorder: the sink hashes the bytes the engine
        // already read, never re-opening Candidate.byteHandle. The byteHandle throws if called.
        val repo = ScreenshotInMemoryRepository()
        repo.addScreenshot(listOf(row("1")))
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
        repo.addScreenshot(listOf(row("1")))
        val store = RecordingZvecContentStore()
        val cacheDao = RecordingCacheDao()
        val sink = ZvecWriteSink(repo, store) { cacheDao }
        val sentinel = "precomputed-sentinel-not-the-digest"

        sink.commit(candidate("1"), text = "x", processed = true, bytes = byteArrayOf(1, 2, 3),
                    precomputedContentHash = sentinel)
        sink.checkpoint()

        assertEquals(sentinel, store.events.filterIsInstance<RecordingZvecContentStore.Upsert>().single().contentHash)
        assertEquals(sentinel, repo.getScreenshotByUri("content://media/1")!!.contentHash)
        assertEquals(sentinel, cacheDao.upserts.single().contentHash)
        assertEquals(listOf(sentinel), cacheDao.markIndexedHashes)
    }

    // ==========================================================================
    // Issue 01 — checkpoint-batched flush: the D13-at-batch-granularity invariant
    // ==========================================================================

    /**
     * **The acceptance-critical test (issue 01):** a Room row must NEVER be marked `processed = 1`
     * before the zvec flush that covers its doc — asserted as a property of the GLOBAL call order
     * across all three stores (one shared trace: upserts, flushes, Room marks), at the batch
     * boundary, at the terminal checkpoint, and over an abandoned partial batch (crash window 1:
     * upserted-but-unflushed docs keep `processed = 0`; nothing marks them).
     */
    @Test
    fun markNeverPrecedesItsFlush_acrossBatchBoundaryTerminalCheckpointAndAbandonedBatch() = runBlocking {
        val n = ZvecWriteSink.CHECKPOINT_BATCH_SIZE
        val trace = mutableListOf<String>()
        val store = object : ZvecContentStore(FAKE_DIR, debug = false) {
            override suspend fun upsert(contentHash: String, locator: String, content: String, collectionId: String, lastModified: Long?) {
                trace += "upsert:$contentHash"
            }
            override suspend fun flush() { trace += "flush" }
        }
        val repo = object : ScreenshotInMemoryRepository() {
            override suspend fun markContentIndexed(screenshot: ScreenshotModel, contentHash: String) {
                trace += "mark:$contentHash"
                super.markContentIndexed(screenshot, contentHash)
            }
        }
        val cacheDao = RecordingCacheDao()
        val sink = ZvecWriteSink(repo, store) { cacheDao }

        val ids = (0 until 2 * n + 50).map { it.toString() }
        repo.addScreenshot(ids.map { row(it) })

        // A local `suspend fun` is not legal; a suspend-typed lambda is (and plain for-loops
        // invoke it — forEach's lambda is non-suspend).
        val commit: suspend (String) -> Unit = { id ->
            sink.commit(
                candidate(id), text = "text-$id", processed = true,
                // Distinct bytes per doc → distinct content hashes → each mark is attributable in the trace.
                bytes = byteArrayOf(id.toInt().toByte()), precomputedContentHash = "hash-$id",
            )
        }

        /** For every recorded mark: its upsert exists, precedes it, and a flush sits strictly between. */
        fun assertMarkNeverPrecedesCoveringFlush() {
            trace.forEachIndexed { i, event ->
                if (!event.startsWith("mark:")) return@forEachIndexed
                val hash = event.removePrefix("mark:")
                val upsertAt = trace.indexOfFirst { it == "upsert:$hash" }
                assertTrue("mark for $hash must follow its upsert", upsertAt in 0 until i)
                assertTrue(
                    "D13 batch invariant violated: mark#$i for $hash has no flush between upsert#$upsertAt and the mark",
                    (upsertAt + 1 until i).any { trace[it] == "flush" },
                )
            }
        }

        // ── batch boundaries: 2n + 50 commits → flush (and drain) on commits #n and #2n, 50 pending ──
        for (id in ids) commit(id)

        assertMarkNeverPrecedesCoveringFlush()
        assertEquals(2 * n + 50, trace.count { it.startsWith("upsert:") })
        assertEquals("exactly one flush per full batch (at commits #n and #2n)", 2, trace.count { it == "flush" })
        assertEquals("both full batches drained their marks", 2 * n, trace.count { it.startsWith("mark:") })
        assertEquals(2 * n, cacheDao.markIndexedHashes.size)

        // ── terminal checkpoint: drains the 50-doc tail with its own flush ──
        sink.checkpoint()

        assertMarkNeverPrecedesCoveringFlush()
        assertEquals(3, trace.count { it == "flush" })
        assertEquals(2 * n + 50, trace.count { it.startsWith("mark:") })
        assertTrue("all rows processed after the terminal checkpoint",
                   repo.getUnprocessedScreenshotList().isEmpty())

        // ── crash window 1 (abandoned partial batch): 30 more commits, then the "process dies" —
        //    no checkpoint. The unflushed docs must keep processed = 0; nothing marks them.
        val tailIds = (2 * n + 50 until 2 * n + 80).map { it.toString() }
        repo.addScreenshot(tailIds.map { row(it) })
        for (id in tailIds) commit(id)

        assertMarkNeverPrecedesCoveringFlush()   // still true — no new marks ran at all
        assertEquals("no flush for the abandoned batch", 3, trace.count { it == "flush" })
        assertEquals("no mark for the abandoned batch", 2 * n + 50, trace.count { it.startsWith("mark:") })
        assertEquals("abandoned rows stay unprocessed → next run re-reads, re-OCRs, re-upserts (bounded by N)",
                     tailIds.size, repo.getUnprocessedScreenshotList().size)
        assertEquals(tailIds.size, repo.getUnprocessedCount())
    }

    @Test
    fun smallRun_flushesExactlyOnceAtItsTerminalCheckpoint() = runBlocking {
        // The on-open shape (≤ 12 files): no batch ever fills, so the run must perform exactly
        // ONE flush — at the terminal checkpoint — and still retire every row.
        val repo = ScreenshotInMemoryRepository()
        val ids = (0 until 12).map { it.toString() }
        repo.addScreenshot(ids.map { row(it) })
        val store = RecordingZvecContentStore()
        val sink = ZvecWriteSink(repo, store) { RecordingCacheDao() }

        ids.forEach {
            sink.commit(candidate(it), text = "t", processed = true, bytes = byteArrayOf(it.toInt().toByte()),
                        precomputedContentHash = null)
        }
        assertEquals("no flush during a sub-batch run's commits", 0, store.flushCount())

        sink.checkpoint()

        assertEquals("exactly one flush, at the terminal checkpoint", 1, store.flushCount())
        assertTrue(repo.getUnprocessedScreenshotList().isEmpty())
        assertEquals(12, store.events.filterIsInstance<RecordingZvecContentStore.Upsert>().size)
    }

    @Test
    fun checkpoint_isNoOpWhenNothingIsPending() = runBlocking {
        // A run of exactly N docs drains itself on the Nth commit; the engine's terminal
        // checkpoint must add no second flush (and must be harmless on an empty run).
        val n = ZvecWriteSink.CHECKPOINT_BATCH_SIZE
        val repo = ScreenshotInMemoryRepository()
        val ids = (0 until n).map { it.toString() }
        repo.addScreenshot(ids.map { row(it) })
        val store = RecordingZvecContentStore()
        val sink = ZvecWriteSink(repo, store) { RecordingCacheDao() }

        ids.forEach {
            sink.commit(candidate(it), text = "t", processed = true, bytes = byteArrayOf(it.toInt().toByte()),
                        precomputedContentHash = null)
        }
        assertEquals("the Nth commit flushed its own full batch", 1, store.flushCount())
        assertTrue(repo.getUnprocessedScreenshotList().isEmpty())

        sink.checkpoint()

        assertEquals("terminal checkpoint after a fully-drained batch is a no-op", 1, store.flushCount())

        sink.checkpoint()   // and on a never-used sink
        assertEquals(1, store.flushCount())
    }

    @Test
    fun drainFailure_leavesFailedMarkAndTailPending_reDrainCompletesWithoutASecondFlush() = runBlocking {
        // Crash window 2, and the mid-drain failure contract: the flush lands, a Room mark
        // throws → the failed mark AND the tail stay pending (rows unprocessed, self-healing),
        // and the error-path checkpoint's re-drain completes them idempotently — without
        // re-flushing and with the mark still strictly after the flush that covers it.
        val store = RecordingZvecContentStore()
        var failMarks = true
        val repo = object : ScreenshotInMemoryRepository() {
            override suspend fun markContentIndexed(screenshot: ScreenshotModel, contentHash: String) {
                store.events += "mark-attempt"   // order-observable even on the throwing call
                if (failMarks) throw RuntimeException("crash after flush, before mark")
                super.markContentIndexed(screenshot, contentHash)
            }
        }
        repo.addScreenshot(listOf(row("1"), row("2")))
        val sink = ZvecWriteSink(repo, store) { RecordingCacheDao() }

        sink.commit(candidate("1"), text = "a", processed = true, bytes = byteArrayOf(1), precomputedContentHash = null)
        sink.commit(candidate("2"), text = "b", processed = true, bytes = byteArrayOf(2), precomputedContentHash = null)

        val threw = runCatching { sink.checkpoint() }
        assertTrue("the mid-drain Room failure surfaces (run-level error semantics)", threw.isFailure)

        // The flush happened BEFORE the attempted mark — the lossy case stays the recoverable one.
        assertTrue(store.events.indexOf("flush") in 0 until store.events.indexOf("mark-attempt"))
        assertFalse(repo.getScreenshotByUri("content://media/1")!!.processed)
        assertFalse(repo.getScreenshotByUri("content://media/2")!!.processed)

        // Recovery: the engine's error-path checkpoint re-drains (Room UPDATE + cache upsert are
        // idempotent) and completes the batch — still exactly one flush.
        failMarks = false
        sink.checkpoint()
        assertTrue(repo.getScreenshotByUri("content://media/1")!!.processed)
        assertTrue(repo.getScreenshotByUri("content://media/2")!!.processed)
        assertEquals(1, store.flushCount())
        // One throwing mark-attempt (the simulated crash) + two successful re-drain marks.
        assertEquals(3, store.events.count { it == "mark-attempt" })
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
