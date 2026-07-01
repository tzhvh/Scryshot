/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.persistence

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
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

/**
 * On-device test for [ContentMetadataCacheDao] (zvec Phase 2, issue 01).
 *
 * Why this is an instrumented test, not a JVM unit test: the codebase has no Room-on-JVM
 * convention (no Robolectric, no `room-testing` on the `test` config), and the acceptance
 * criteria hinge on real SQLite semantics — the composite-PK hit/miss behaviour, REPLACE
 * idempotency, and the `markIndexed` row-update. Those cannot be honestly asserted against a
 * hand-rolled fake. This establishes the `room-testing` + `inMemoryDatabaseBuilder` pattern on
 * the `androidTest` source set (the existing device-smoke tests already run here), and also
 * unlocks [androidx.room.testing.MigrationTestHelper] for the v4→v5 schema if needed later.
 */
@RunWith(AndroidJUnit4::class)
class ContentMetadataCacheDaoTest {

    private lateinit var database: ScreenshotDatabase
    private lateinit var dao: ContentMetadataCacheDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, ScreenshotDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.contentMetadataCacheDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun entry(
            locator: String = "content://media/external/images/1",
            mtime: Long = 1_700_000_000L,
            size: Long = 1024L,
            hash: String = "deadbeef",
            indexed: Boolean = false
    ) = ContentMetadataCache(locator, mtime, size, hash, indexed)

    @Test
    fun lookup_emptyDb_returnsNull() {
        assertNull(dao.lookup("any-locator", 0L, 0L))
    }

    @Test
    fun upsert_thenLookup_byCheapTriple_returnsCachedRow() {
        val e = entry(indexed = false)
        dao.upsert(e)

        val hit = dao.lookup(e.locator, e.mtime, e.size)

        assertNotNull(hit)
        assertEquals(e.contentHash, hit!!.contentHash)
        // indexed defaults to 0 on a fresh insert (the column default).
        assertFalse(hit.indexed)
    }

    @Test
    fun lookup_byCheapTriple_withIndexedTrue_reflectsCachedStatus() {
        // The load-bearing contract: a hit returns the cached `indexed` flag with no hashing/open.
        dao.upsert(entry(indexed = true))

        val hit = dao.lookup(entry().locator, entry().mtime, entry().size)

        assertNotNull(hit)
        assertTrue(hit!!.indexed)
    }

    @Test
    fun lookup_mtimeChanged_forcesMiss() {
        dao.upsert(entry())
        // A changed mtime means the file changed on disk → the cheap triple no longer matches.
        assertNull(dao.lookup(entry().locator, entry().mtime + 1, entry().size))
    }

    @Test
    fun lookup_sizeChanged_forcesMiss() {
        dao.upsert(entry())
        assertNull(dao.lookup(entry().locator, entry().mtime, entry().size + 1))
    }

    @Test
    fun lookup_locatorChanged_forcesMiss() {
        dao.upsert(entry())
        assertNull(dao.lookup("content://media/external/images/999", entry().mtime, entry().size))
    }

    @Test
    fun upsert_sameTriple_isIdempotent() {
        // The composite PRIMARY KEY guarantees at most one row per (locator, mtime, size); REPLACE
        // deletes-then-inserts, so re-upserting collapses rather than duplicates. Verify the single
        // row still resolves and reflects the latest values.
        dao.upsert(entry(hash = "h1", indexed = false))
        dao.upsert(entry(hash = "h1", indexed = true))

        val hit = dao.lookup(entry().locator, entry().mtime, entry().size)

        assertNotNull(hit)
        assertEquals("h1", hit!!.contentHash)
        assertTrue(hit.indexed)
    }

    @Test
    fun upsert_replacesContentHashIndex() {
        // Re-upserting the same triple with a new hash must retire the old hash index entry,
        // otherwise lookupByHash would return a stale row pointing at the changed file.
        dao.upsert(entry(hash = "old-hash"))
        dao.upsert(entry(hash = "new-hash"))

        assertNull(dao.lookupByHash("old-hash"))
        val hit = dao.lookupByHash("new-hash")
        assertNotNull(hit)
        assertEquals("new-hash", hit!!.contentHash)
    }

    @Test
    fun lookupByHash_missReturnsNull() {
        assertNull(dao.lookupByHash("never-inserted"))
    }

    @Test
    fun lookupByHash_hitsForDuplicateContentDifferentLocator() {
        // Intra-run dedup: two files (different locators) with identical content warm the cache
        // under one hash. The second file's hash lookup hits.
        dao.upsert(entry(locator = "uri-1", hash = "shared-hash"))
        dao.upsert(entry(locator = "uri-2", hash = "shared-hash"))

        val hit = dao.lookupByHash("shared-hash")

        assertNotNull(hit)
        assertEquals("shared-hash", hit!!.contentHash)
    }

    @Test
    fun markIndexed_flipsOnlyMatchingHash() {
        dao.upsert(entry(hash = "hash-a", indexed = false))
        dao.upsert(entry(locator = "uri-b", hash = "hash-b", indexed = false))

        val updated = dao.markIndexed("hash-a")

        assertEquals(1, updated)
        assertTrue(dao.lookupByHash("hash-a")!!.indexed)
        assertFalse(dao.lookupByHash("hash-b")!!.indexed)
    }

    @Test
    fun markIndexed_flipsAllRowsForHash() {
        // Duplicate files (same content, different locators) all share a hash; marking the hash
        // indexed must advance every row so the next cheap-key lookup for any of them reports true.
        dao.upsert(entry(locator = "uri-1", hash = "shared"))
        dao.upsert(entry(locator = "uri-2", hash = "shared"))
        dao.upsert(entry(locator = "uri-3", hash = "shared"))

        val updated = dao.markIndexed("shared")

        assertEquals(3, updated)
        assertTrue(dao.lookup("uri-1", entry().mtime, entry().size)!!.indexed)
        assertTrue(dao.lookup("uri-2", entry().mtime, entry().size)!!.indexed)
        assertTrue(dao.lookup("uri-3", entry().mtime, entry().size)!!.indexed)
    }

    @Test
    fun markIndexed_unknownHash_updatesNothing() {
        dao.upsert(entry(hash = "real-hash"))

        val updated = dao.markIndexed("nonexistent-hash")

        assertEquals(0, updated)
        assertFalse(dao.lookupByHash("real-hash")!!.indexed)
    }
}
