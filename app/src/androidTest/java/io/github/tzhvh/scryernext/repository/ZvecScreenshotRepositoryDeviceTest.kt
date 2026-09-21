/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.repository

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import io.github.tzhvh.scryernext.ingestion.Candidate
import io.github.tzhvh.scryernext.ingestion.ZvecContentStore
import io.github.tzhvh.scryernext.ingestion.ZvecWriteSink
import io.github.tzhvh.scryernext.persistence.ContentMetadataCacheDaoFake
import io.github.tzhvh.scryernext.persistence.ScreenshotDatabase
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import io.github.tzhvh.scryernext.repository.SearchOutcome
import io.github.tzhvh.scryernext.search.RankPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File

/**
 * zvec Phase 2, issue 04 — the Layer-2 integration test for the read-flip.
 *
 * Writes a screenshot doc via [ZvecWriteSink], then reads it back **through the façade**
 * ([ZvecScreenshotRepository]): both `searchScreenshotList` (FTS → batched gallery-row bridge) and
 * `getContentText` (fetch by the `content_hash` bridge column). Asserts the OCR text + locator
 * round-trip end-to-end across the two stores — content in zvec, the gallery row in Room — exactly
 * the shape the UI consumes.
 *
 * Distinct from `ZvecContentStoreDeviceTest.layer2_*` (which reads back via the store directly):
 * this test exercises the façade's **batched** locator bridge + rank preservation + the D13 column
 * read path, the issue-04-specific surfaces. Runs on-device because both zvec (native `.so`) and
 * real SQLite (the Room delegate) are load-bearing — R7 (arm64 runtime correctness) is incidentally
 * discharged on every assertion.
 */
@RunWith(AndroidJUnit4::class)
class ZvecScreenshotRepositoryDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: ScreenshotDatabase
    private lateinit var root: File

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ctx, ScreenshotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        root = File(ctx.cacheDir, "zvec-issue04-${System.nanoTime()}")
    }

    @After fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    private fun newRepo(): Triple<ZvecScreenshotRepository, ZvecWriteSink, ZvecContentStore> {
        val dbRepo = ScreenshotDatabaseRepository(database)
        val store = ZvecContentStore(root, debug = false)
        val repo = ZvecScreenshotRepository(delegate = dbRepo, store = store)
        val cacheDao = ContentMetadataCacheDaoFake()
        val sink = ZvecWriteSink(repo, store) { cacheDao }
        return Triple(repo, sink, store)
    }

    /**
     * Write via the sink, read back via the façade: search finds the screenshot (FTS hit on the OCR
     * text), and getContentText returns the same text. The locator bridge resolves the zvec doc back
     * to the Room gallery row; rank order is preserved (the single result is the written row).
     */
    @Test fun writeViaSink_searchAndContentText_readBackViaFacade() = runBlocking {
        val (repo, sink, store) = newRepo()
        val uri = "content://media/external/images/media/issue04-${System.nanoTime()}"
        val screenshot = ScreenshotModel(
            "id-1", uri, "shot.png", size = 9L, lastModified = 1L, "col-7",
        )
        repo.addScreenshot(listOf(screenshot))
        val bytes = "png-bytes-issue04".toByteArray()

        sink.commit(
            Candidate(locator = uri, byteHandle = { ByteArrayInputStream(bytes) }),
            text = "deploy the new search index tonight",
            processed = true,
            bytes = bytes,
        )

        // searchScreenshotList: zvec FTS → batched gallery-row bridge. The OCR text matches the query.
        val results = rows(repo.searchScreenshotList("deploy search index"))
        assertEquals("search must return exactly the one matching screenshot", 1, results.size)
        assertEquals("the bridge resolves the zvec locator back to the Room gallery row", uri, results[0].uri)

        // searchScreenshots (the Flow variant) emits the same result.
        val flowResults = rows(repo.searchScreenshots("deploy").first())
        assertEquals("the Flow variant matches the List variant", 1, flowResults.size)
        assertEquals(uri, flowResults[0].uri)

        // getContentText: fetch by the D13 content_hash bridge column → the same OCR text.
        val written = repo.getScreenshotByUri(uri)!!
        val text = repo.getContentText(written)
        assertEquals("deploy the new search index tonight", text)

        // The row was retired + the bridge hash recorded.
        assertTrue("row is retired (processed = true)", written.processed)
        assertTrue("row carries the bridge content_hash", written.contentHash != null)
    }

    /**
     * The stale-locator null policy: a zvec doc whose locator has no Room row (the screenshot was
     * deleted between indexing and search) is filtered silently — it must not surface in search.
     */
    @Test fun staleZvecDoc_notInRoom_isFilteredSilently() = runBlocking {
        val (repo, sink, store) = newRepo()
        val liveUri = "content://media/live-${System.nanoTime()}"
        val ghostUri = "content://media/ghost-${System.nanoTime()}"

        // Insert + index the live row.
        val live = ScreenshotModel("id-live", liveUri, "live.png", 1L, 1L, "col")
        repo.addScreenshot(listOf(live))
        val liveBytes = "live-content".toByteArray()
        sink.commit(
            Candidate(locator = liveUri, byteHandle = { ByteArrayInputStream(liveBytes) }),
            text = "shared keyword matchme",
            processed = true,
            bytes = liveBytes,
        )

        // Index a "ghost" doc whose locator matches a query but has NO Room row — upsert directly to
        // the SAME store (a second store against the path would contend on zvec's LOCK; the single
        // long-lived handle is the production discipline the store enforces). This simulates a
        // screenshot deleted from Room after indexing.
        val ghostBytes = "ghost-content".toByteArray()
        val ghostHash = sha256Hex(ghostBytes)
        store.upsert(ghostHash, ghostUri, "shared keyword matchme", "col")
        store.flush()

        // Both docs match the FTS query, but only the live (Room-backed) one surfaces.
        val results = rows(repo.searchScreenshotList("matchme"))
        assertEquals("the stale ghost doc must be filtered; only the live row surfaces", 1, results.size)
        assertEquals(liveUri, results[0].uri)
    }

    /**
     * Phase 2.1 step 1 — the [RankPolicy] drives the order the façade returns (the line-213 recency
     * sort is dead). Fixture: the OLDER doc matches both query terms (higher BM25 — stronger per the
     * v0.7.0 pinned `bm25OrdersStrongerFtsMatchFirst` ordering), the NEWER doc matches one. Relevance
     * and Recency must therefore disagree, each producing its documented order end-to-end through
     * bridge + rankStage — the score map never leaks to the UI, but its effect is observable here.
     */
    @Test fun rankPolicy_drivesFacadeOrder_relevanceVsRecency() = runBlocking {
        val (repo, sink, _) = newRepo()
        val oldUri = "content://media/old-${System.nanoTime()}"
        val newUri = "content://media/new-${System.nanoTime()}"
        repo.addScreenshot(
            listOf(
                ScreenshotModel(id = "id-old", uri = oldUri, displayName = "old.png",
                    size = 1L, lastModified = 1_000L, collectionId = "col"),
                ScreenshotModel(id = "id-new", uri = newUri, displayName = "new.png",
                    size = 1L, lastModified = 2_000L, collectionId = "col"),
            )
        )
        sink.commit(
            Candidate(locator = oldUri, byteHandle = { ByteArrayInputStream("old-bytes".toByteArray()) }),
            text = "receipt total invoice billing",
            processed = true,
            bytes = "old-bytes".toByteArray(),
        )
        sink.commit(
            Candidate(locator = newUri, byteHandle = { ByteArrayInputStream("new-bytes".toByteArray()) }),
            text = "receipt",
            processed = true,
            bytes = "new-bytes".toByteArray(),
        )

        val relevance = rows(repo.searchScreenshotList("receipt invoice", RankPolicy.Relevance))
        assertEquals("the two-term (stronger BM25) doc leads under Relevance", oldUri, relevance[0].uri)

        val recency = rows(repo.searchScreenshotList("receipt invoice", RankPolicy.Recency))
        assertEquals("the newer doc leads under Recency", newUri, recency[0].uri)
    }

    /**
     * Phase 2.1 step 5 — collection filter push-down: the filter narrows results to the selected
     * collection(s) BEFORE search (engine push-down, not post-filter), escaped through
     * `SearchFilters.toFilterExpression()`. Both docs match the query; only the in-collection one
     * surfaces.
     */
    @Test fun collectionFilter_pushDown_narrowsResults() = runBlocking {
        val (repo, sink, _) = newRepo()
        val uriA = "content://media/colA-${System.nanoTime()}"
        val uriB = "content://media/colB-${System.nanoTime()}"
        repo.addScreenshot(
            listOf(
                ScreenshotModel(id = "id-a", uri = uriA, displayName = "a.png",
                    size = 1L, lastModified = 1L, collectionId = "col-target"),
                ScreenshotModel(id = "id-b", uri = uriB, displayName = "b.png",
                    size = 1L, lastModified = 2L, collectionId = "col-other"),
            )
        )
        sink.commit(
            Candidate(locator = uriA, byteHandle = { ByteArrayInputStream("a-bytes".toByteArray()) }),
            text = "shared keyword matchme", processed = true, bytes = "a-bytes".toByteArray(),
        )
        sink.commit(
            Candidate(locator = uriB, byteHandle = { ByteArrayInputStream("b-bytes".toByteArray()) }),
            text = "shared keyword matchme", processed = true, bytes = "b-bytes".toByteArray(),
        )

        val unfiltered = rows(repo.searchScreenshotList("matchme"))
        assertEquals("sanity: both docs match without a filter", 2, unfiltered.size)

        val filtered = rows(repo.searchScreenshotList(
            "matchme", RankPolicy.Recency, "collection_id IN ('col-target')",
        ))
        assertEquals("push-down must narrow to the selected collection", 1, filtered.size)
        assertEquals(uriA, filtered[0].uri)
    }

    /**
     * Phase 2.1 step 4 — the `last_modified` story end-to-end on a live store:
     *  1. a pre-2.1-shape doc (indexed with a NULL `last_modified`) is EXCLUDED by a date filter
     *     (the R8-pinned three-valued-logic contract);
     *  2. [LastModifiedBackfill] re-upserts it with the row's capture time and flips the marker;
     *  3. the same filter then recalls it — backfill-before-shipping demonstrably un-hides rows.
     * The store's DDL self-heal (healSchemaDdl) is incidentally discharged on every newRepo(): the
     * fresh collection already declares the column, so the open-path add runs the ALREADY_EXISTS
     * no-op without error.
     */
    @Test fun dateRangeFilter_backfill_unhidesNullRows() = runBlocking {
        val (repo, sink, store) = newRepo()
        val oldUri = "content://media/lm-old-${System.nanoTime()}"
        val newUri = "content://media/lm-new-${System.nanoTime()}"
        val oldRow = ScreenshotModel(id = "id-lm-old", uri = oldUri, displayName = "old.png",
            size = 1L, lastModified = 1_000L, collectionId = "col")
        repo.addScreenshot(listOf(oldRow))

        // Index the old doc in the PRE-2.1 shape: upsert without last_modified (null field).
        val oldBytes = "old-bytes-lm".toByteArray()
        store.upsert(sha256Hex(oldBytes), oldUri, "shared keyword matchme", "col", lastModified = null)
        store.flush()
        repo.markContentIndexed(oldRow, sha256Hex(oldBytes))

        // The new doc via the production sink, which passes the row's capture time.
        repo.addScreenshot(listOf(ScreenshotModel(id = "id-lm-new", uri = newUri, displayName = "new.png",
            size = 1L, lastModified = 2_000_000_000L, collectionId = "col")))
        sink.commit(
            Candidate(locator = newUri, byteHandle = { ByteArrayInputStream("new-bytes-lm".toByteArray()) }),
            text = "shared keyword matchme", processed = true, bytes = "new-bytes-lm".toByteArray(),
        )

        val dateFilter = "last_modified >= 1000000000"
        assertEquals(
            "sanity: both docs match unfiltered",
            2, rows(repo.searchScreenshotList("matchme")).size,
        )
        assertEquals(
            "R8: the null-field old doc is hidden by the date filter pre-backfill",
            1, rows(repo.searchScreenshotList("matchme", RankPolicy.Recency, dateFilter)).size,
        )

        val marker = object : LastModifiedBackfill.BackfillMarker {
            var done = false
            override fun isDone() = done
            override fun markDone() { done = true }
        }
        val backfilled = LastModifiedBackfill(
            store, { repo.getScreenshotList() }, marker,
        ).runIfNeeded()
        assertEquals("the backfill fills exactly the one null doc", 1, backfilled)
        assertTrue("the marker flips only after a full pass", marker.done)

        assertEquals(
            "post-backfill the same filter recalls both docs",
            2, rows(repo.searchScreenshotList("matchme", RankPolicy.Recency, dateFilter)).size,
        )
    }

    private fun rows(outcome: SearchOutcome): List<ScreenshotModel> =
        (outcome as SearchOutcome.Results).rows

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
