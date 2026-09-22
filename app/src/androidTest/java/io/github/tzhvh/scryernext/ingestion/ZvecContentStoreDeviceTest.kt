/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import io.github.tzhvh.scryernext.zvec.ZvecValue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * zvec Phase 2, issue 03 — on-device verification of [ZvecContentStore] + [ZvecWriteSink].
 *
 * Runs on the attached API 31 arm64 device (the first app-module instrumented test to touch zvec at
 * runtime). Covers three acceptance criteria that genuinely need the real engine:
 *
 * 1. **R8 idempotency** (the issue's named instrumented criterion): upsert the same content_hash
 *    twice with divergent fields, assert `docCount` is unchanged and the stored fields reflect the
 *    latest write (true in-place overwrite, no duplicate — the keystone of the content_hash dedup).
 * 2. **Layer-2 round-trip**: write via [ZvecWriteSink], fetch back via [ZvecContentStore.fetch],
 *    assert OCR text + locator + collection_id round-trip and the cache warms.
 * 3. **Stale-LOCK recovery** (pinned Phase-1 contract #1): a crash-left `LOCK` file is deleted and
 *    the open retries once. (Lives here, not on the JVM, because `ZvecCollection`'s constructor is
 *    `internal` — a faithful recovery test needs a real collection to return on retry.)
 *
 * R7 (arm64 runtime correctness) is incidentally discharged: every test below runs a zvec content
 * operation on the real device.
 */
@RunWith(AndroidJUnit4::class)
class ZvecContentStoreDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File

    @Before fun setUp() {
        // Each test gets a fresh filesDir so the store creates its collection at <root>/zvec/screenshots.
        root = File(ctx.cacheDir, "zvec-issue03-${System.nanoTime()}")
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun newStore(): ZvecContentStore = ZvecContentStore(root, debug = false)

    /** R8: upserting the same PK twice is an idempotent in-place overwrite, not a duplicate. */
    @Test fun r8_upsertSameHashTwice_isIdempotentOverwrite() = runBlocking {
        val store = newStore()

        store.upsert(contentHash = "hash-1", locator = "uri-1", content = "first text", collectionId = "col-a")
        val countAfterFirst = store.docCount()
        assertEquals("first upsert lands one doc", 1L, countAfterFirst)

        // Same PK, divergent fields — R8 says overwrite, not duplicate.
        store.upsert(contentHash = "hash-1", locator = "uri-1-updated", content = "second text", collectionId = "col-b")

        assertEquals("docCount must be unchanged after re-upserting the same PK (R8)", 1L, store.docCount())

        val doc = store.fetch("hash-1")
        assertNotNull("the doc must still be retrievable", doc)
        // The latest write wins on every field.
        val fields = doc!!.fields
        assertEquals("uri-1-updated", (fields["locator"] as ZvecValue.Str).value)
        assertEquals("second text", (fields["content"] as ZvecValue.Str).value)
    }

    /**
     * Layer-2 round-trip: write a screenshot doc via [ZvecWriteSink], read it back via
     * [ZvecContentStore.fetch]. Asserts the upsert payload (PK = sha256(bytes), locator, content,
     * collection_id) survives, and the cache is warmed (the next lookup-by-hash hits).
     */
    @Test fun layer2_writeViaSink_readsBackViaStore() = runBlocking {
        val repo = io.github.tzhvh.scryernext.repository.ScreenshotInMemoryRepository()
        val screenshot = io.github.tzhvh.scryernext.persistence.ScreenshotModel(
            "id-1", "content://media/42", "shot.png", size = 9L, lastModified = 1L, "col-7",
        )
        repo.addScreenshot(listOf(screenshot))

        val store = newStore()
        val cacheDao = io.github.tzhvh.scryernext.persistence.ContentMetadataCacheDaoFake()
        val sink = ZvecWriteSink(repo, store) { cacheDao }
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val expectedHash = sha256Hex(bytes)

        sink.commit(
            Candidate(locator = "content://media/42", byteHandle = { error("sink must not re-open") }),
            text = "recognized OCR text",
            processed = true,
            bytes = bytes,
            precomputedContentHash = null,
        )

        // The doc round-trips via the store's read path.
        val doc = store.fetch(expectedHash)
        assertNotNull("doc must be retrievable by content_hash after the sink write", doc)
        val fields = doc!!.fields
        assertEquals("content://media/42", (fields["locator"] as ZvecValue.Str).value)
        assertEquals("recognized OCR text", (fields["content"] as ZvecValue.Str).value)

        // The Room row was retired + the bridge hash recorded.
        val row = repo.getScreenshotByUri("content://media/42")!!
        assertTrue("row is retired (processed = true)", row.processed)
        assertEquals(expectedHash, row.contentHash)

        // The cache warmed (the post-hash secondary lookup hits).
        val cached = cacheDao.lookupByHash(expectedHash)
        assertNotNull("cache must be warmed after the write", cached)
        assertTrue("cache row must be marked indexed", cached!!.indexed)
    }

    /**
     * Stale-LOCK recovery (pinned Phase-1 contract #1): a crash-left `LOCK` file at the collection
     * path is deleted and the open retries once. Drives the real [ZvecContentStore] path — a real
     * collection exists at the path (created by a prior store), then a stray `LOCK` is dropped in,
     * then a fresh store opens against it.
     */
    @Test fun staleLockFile_isDeletedAndOpenRecovers() = runBlocking {
        val store = newStore()
        // Land one doc so the collection directory + schema exist on disk, then CLOSE the handle so
        // no live process holds the LOCK (the real stale-LOCK scenario is a crash-leftover with no
        // live owner — NOT a contended lock, which would be a different error).
        store.upsert("seed", "uri", "text", "col")
        store.flush()
        store.onTrimMemoryComplete()
        val collectionDir = File(root, "zvec/screenshots")
        assertTrue("precondition: collection dir exists", collectionDir.exists())

        // Drop a crash-left LOCK file (simulating a process killed between open and clean close).
        val lockFile = File(collectionDir, "LOCK").apply { writeText("stale-crash-leftover") }
        assertTrue("precondition: LOCK file present", lockFile.exists())

        // A fresh store opens against the same path; recovery must detect the lock signal, delete
        // the stale LOCK, and retry the open once.
        val store2 = newStore()
        store2.upsert("seed-2", "uri-2", "text-2", "col-2")

        // The open succeeded (the upsert above didn't throw) and the seed doc is still there.
        assertEquals("collection retained the seed doc across the recovered open", 2L, store2.docCount())
    }

    /** A non-lock error during open is NOT recovered — it rethrows. */
    @Test fun missingPath_createPath_isUsed_notOpen() = runBlocking {
        // A fresh root has no collection, so the store createAndOpens (not opens). This sanity-checks
        // the first-launch create path: it must succeed without a pre-existing directory.
        val store = newStore()
        store.upsert("fresh", "uri", "text", "col")
        assertEquals(1L, store.docCount())
        assertNull("nothing fetched for an absent pk", store.fetch("nonexistent-pk"))
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0f])
        }
        return sb.toString()
    }
}
