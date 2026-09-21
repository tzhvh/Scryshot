/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.repository

import io.github.tzhvh.scryernext.ingestion.ZvecContentStore
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import io.github.tzhvh.scryernext.zvec.ZvecDoc
import io.github.tzhvh.scryernext.zvec.ZvecValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 2.1 step 4 — JVM unit tests for [LastModifiedBackfill], the one-shot pass that fills
 * `last_modified` into zvec for docs written before the column existed.
 *
 * Pins the contract the R8 ruling makes mandatory-before-shipping (a `last_modified` filter
 * silently hides null rows, so the date chip may render only after this pass completes):
 *  - runs once, backfills every hashed Room row (fetch → upsert with the row's timestamp), then
 *    marks done — the marker is written only AFTER a full pass (crash-safe);
 *  - a done marker makes every later run a no-op (zero store calls);
 *  - rows without a content_hash and hashes with no surviving zvec doc are skipped, not counted;
 *  - the re-upsert preserves the fetched content/locator (the OCR text is never clobbered).
 */
class LastModifiedBackfillTest {

    private class RecordingBackfillStore : ZvecContentStore(FAKE_DIR, debug = false) {
        data class Upsert(
            val contentHash: String,
            val locator: String,
            val content: String,
            val collectionId: String,
            val lastModified: Long?,
        )

        val upserts = mutableListOf<Upsert>()
        var flushes = 0
        val docs = mutableMapOf<String, ZvecDoc>()

        override suspend fun fetch(contentHash: String): ZvecDoc? = docs[contentHash]

        override suspend fun upsert(
            contentHash: String,
            locator: String,
            content: String,
            collectionId: String,
            lastModified: Long?,
        ) {
            upserts += Upsert(contentHash, locator, content, collectionId, lastModified)
        }

        override suspend fun flush() {
            flushes++
        }
    }

    private class FakeMarker(var done: Boolean = false) : LastModifiedBackfill.BackfillMarker {
        override fun isDone() = done
        override fun markDone() {
            done = true
        }
    }

    private fun row(id: String, hash: String?, lastModified: Long) = ScreenshotModel(
        id = id, uri = "uri://$id", displayName = "$id.png", size = 1L,
        lastModified = lastModified, collectionId = "col", contentHash = hash,
    )

    private fun doc(hash: String) = ZvecDoc(
        pk = hash,
        fields = mapOf(
            ZvecContentStore.FIELD_CONTENT to ZvecValue.Str("ocr text for $hash"),
            ZvecContentStore.FIELD_LOCATOR to ZvecValue.Str("uri://from-zvec-$hash"),
        ),
    )

    @Test
    fun firstRun_backfillsEveryHashedRow_thenMarksDone() = runBlocking {
        val store = RecordingBackfillStore()
        store.docs["h1"] = doc("h1")
        store.docs["h2"] = doc("h2")
        val marker = FakeMarker()
        val backfill = LastModifiedBackfill(store, { listOf(row("a", "h1", 111), row("b", "h2", 222)) }, marker)

        val count = backfill.runIfNeeded()

        assertEquals(2, count)
        assertEquals(listOf(111L, 222L), store.upserts.map { it.lastModified })
        assertEquals("h1", store.upserts[0].contentHash)
        assertTrue(marker.done)
        assertEquals("flush after the pass, before the marker", 1, store.flushes)
    }

    @Test
    fun reRunAfterDone_isANoOp_withZeroStoreCalls() = runBlocking {
        val store = RecordingBackfillStore()
        val marker = FakeMarker(done = true)
        val backfill = LastModifiedBackfill(store, { listOf(row("a", "h1", 111)) }, marker)

        assertEquals(0, backfill.runIfNeeded())
        assertTrue(store.upserts.isEmpty())
        assertEquals(0, store.flushes)
    }

    @Test
    fun rowsWithoutHash_andHashesWithoutDocs_areSkipped_notCounted() = runBlocking {
        val store = RecordingBackfillStore()
        store.docs["h-ok"] = doc("h-ok")
        val marker = FakeMarker()
        val backfill = LastModifiedBackfill(
            store,
            {
                listOf(
                    row("unindexed", null, 5L),      // never indexed — nothing to backfill
                    row("stale", "h-gone", 6L),      // zvec doc absent (deleted since)
                    row("ok", "h-ok", 7L),
                )
            },
            marker,
        )

        assertEquals(1, backfill.runIfNeeded())
        assertEquals(listOf("h-ok"), store.upserts.map { it.contentHash })
        assertTrue(marker.done)
    }

    @Test
    fun reupsert_preservesFetchedContentAndLocator() = runBlocking {
        val store = RecordingBackfillStore()
        store.docs["h1"] = doc("h1")
        val backfill = LastModifiedBackfill(store, { listOf(row("a", "h1", 111)) }, FakeMarker())

        backfill.runIfNeeded()

        val up = store.upserts.single()
        assertEquals("ocr text for h1", up.content)
        assertEquals("uri://from-zvec-h1", up.locator)
    }

    @Test
    fun emptyCorpus_firstRunMarksDone_zeroCount() = runBlocking {
        val store = RecordingBackfillStore()
        val marker = FakeMarker()
        val backfill = LastModifiedBackfill(store, { emptyList() }, marker)

        assertEquals(0, backfill.runIfNeeded())
        assertTrue("a fresh install is backfill-complete at zero docs", marker.done)
    }

    private companion object {
        val FAKE_DIR: File = File("/tmp/last-modified-backfill-test")
    }
}
