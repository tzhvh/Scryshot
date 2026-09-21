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
 *  - runs once; one corpus walk; fills ONLY null-field docs (a doc already carrying a timestamp
 *    is skipped untouched — the upgrade pass must not rewrite the whole corpus), then marks done
 *    — the marker is written only AFTER a full pass (crash-safe);
 *  - a done marker makes every later run a no-op (zero store calls);
 *  - hashes with no surviving zvec doc skip silently, not counted;
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
        var walks = 0
        val docs = mutableListOf<ZvecDoc>()

        override suspend fun iterDocs(outputFields: List<String>?): List<ZvecDoc> {
            walks++
            return docs
        }

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

    private fun doc(hash: String, lastModified: Long? = null): ZvecDoc {
        val fields = mutableMapOf<String, ZvecValue>(
            ZvecContentStore.FIELD_CONTENT to ZvecValue.Str("ocr text for $hash"),
            ZvecContentStore.FIELD_LOCATOR to ZvecValue.Str("uri://from-zvec-$hash"),
        )
        if (lastModified != null) {
            fields[ZvecContentStore.FIELD_LAST_MODIFIED] = ZvecValue.ScalarInt(lastModified)
        }
        return ZvecDoc(pk = hash, fields = fields)
    }

    @Test
    fun firstRun_fillsOnlyNullDocs_thenMarksDone() = runBlocking {
        val store = RecordingBackfillStore()
        store.docs.add(doc("h-null"))
        store.docs.add(doc("h-set", lastModified = 999L)) // post-2.1 doc: already carries a stamp
        val marker = FakeMarker()
        val backfill = LastModifiedBackfill(
            store,
            { listOf(row("a", "h-null", 111), row("b", "h-set", 222)) },
            marker,
        )

        val count = backfill.runIfNeeded()

        assertEquals("only the null-field doc is rewritten", 1, count)
        val up = store.upserts.single()
        assertEquals("h-null", up.contentHash)
        assertEquals(111L, up.lastModified)
        assertTrue(marker.done)
        assertEquals("flush after the pass, before the marker", 1, store.flushes)
        assertEquals("one corpus walk, not N fetches", 1, store.walks)
    }

    @Test
    fun reRunAfterDone_isANoOp_withZeroStoreCalls() = runBlocking {
        val store = RecordingBackfillStore()
        val marker = FakeMarker(done = true)
        val backfill = LastModifiedBackfill(store, { listOf(row("a", "h1", 111)) }, marker)

        assertEquals(0, backfill.runIfNeeded())
        assertTrue(store.upserts.isEmpty())
        assertEquals(0, store.walks)
        assertEquals(0, store.flushes)
    }

    @Test
    fun hashesWithoutDocs_andRoomRowsWithoutHash_skipSilently() = runBlocking {
        val store = RecordingBackfillStore()
        store.docs.add(doc("h-ok"))
        val marker = FakeMarker()
        val backfill = LastModifiedBackfill(
            store,
            {
                listOf(
                    row("unindexed", null, 5L),   // never indexed — nothing to fill
                    row("stale", "h-gone", 6L),   // zvec doc absent (deleted since)
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
    fun zvecDocWithoutAnyRoomRow_isNotRewritten() = runBlocking {
        val store = RecordingBackfillStore()
        store.docs.add(doc("h-ghost"))
        val backfill = LastModifiedBackfill(store, { listOf(row("a", "h-live", 111)) }, FakeMarker())

        assertEquals("a stale doc with no Room row is unfindable — leave it alone", 0, backfill.runIfNeeded())
        assertTrue(store.upserts.isEmpty())
    }

    @Test
    fun reupsert_preservesFetchedContentAndLocator() = runBlocking {
        val store = RecordingBackfillStore()
        store.docs.add(doc("h1"))
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
        assertFalse(store.upserts.isNotEmpty())
    }

    private companion object {
        val FAKE_DIR: File = File("/tmp/last-modified-backfill-test")
    }
}
