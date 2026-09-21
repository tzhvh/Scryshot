/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext

import io.github.tzhvh.scryernext.ingestion.ZvecContentStore
import io.github.tzhvh.scryernext.persistence.ScreenshotDao
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import io.github.tzhvh.scryernext.zvec.CollectionStats
import io.github.tzhvh.scryernext.zvec.IndexStat
import io.github.tzhvh.scryernext.zvec.ZvecDoc
import io.github.tzhvh.scryernext.zvec.ZvecValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ZvecInspectorRunnerTest {

    private lateinit var fakeDao: FakeScreenshotDao
    private lateinit var fakeStore: FakeStore
    private lateinit var runner: ZvecInspectorRunner

    @Before
    fun setUp() {
        // The recorder is gated behind `enabled` (set from ScryerApplication in prod). Tests opt in.
        ZvecEventRecorder.init(enabled = true)
        ZvecEventRecorder.clear()
        fakeDao = FakeScreenshotDao()
        fakeStore = FakeStore()
        runner = ZvecInspectorRunner(fakeStore, fakeDao)
    }

    @Test
    fun report_computesDriftCorrectly_noDrift() = runBlocking {
        fakeStore.stats = CollectionStats(docCount = 10, indexes = emptyList())
        fakeDao.fakeIndexedCount = 10
        fakeDao.fakeDistinctIndexedCount = 10
        fakeDao.fakeProcessedCount = 10
        fakeDao.fakeUnprocessedCount = 0

        val report = runner.getReport()
        assertEquals(10L, report.drift.zvecDocCount)
        assertEquals(10, report.drift.roomIndexedCount)
        assertEquals(10, report.drift.roomProcessedCount)
        assertEquals(0, report.drift.roomUnprocessedCount)
        assertEquals(0L, report.drift.orphansCount)
        assertEquals(0L, report.drift.holesCount)
    }

    @Test
    fun report_computesDriftCorrectly_withOrphans() = runBlocking {
        // zvec docCount = 12, roomDistinctIndexed = 10 -> orphans = 2, holes = 0
        fakeStore.stats = CollectionStats(docCount = 12, indexes = emptyList())
        fakeDao.fakeIndexedCount = 10
        fakeDao.fakeDistinctIndexedCount = 10
        fakeDao.fakeProcessedCount = 10
        fakeDao.fakeUnprocessedCount = 0

        val report = runner.getReport()
        assertEquals(12L, report.drift.zvecDocCount)
        assertEquals(2L, report.drift.orphansCount)
        assertEquals(0L, report.drift.holesCount)
    }

    @Test
    fun report_computesDriftCorrectly_withHoles() = runBlocking {
        // zvec docCount = 8, roomDistinctIndexed = 10 -> orphans = 0, holes = 2
        fakeStore.stats = CollectionStats(docCount = 8, indexes = emptyList())
        fakeDao.fakeIndexedCount = 10
        fakeDao.fakeDistinctIndexedCount = 10
        fakeDao.fakeProcessedCount = 10
        fakeDao.fakeUnprocessedCount = 0

        val report = runner.getReport()
        assertEquals(8L, report.drift.zvecDocCount)
        assertEquals(0L, report.drift.orphansCount)
        assertEquals(2L, report.drift.holesCount)
    }

    @Test
    fun report_duplicatesDoNotProduceFalseHoles() = runBlocking {
        // D4: 3 Room rows share 1 content_hash (duplicates). zvec has 1 doc (one per hash).
        // Old math: processedCount(3) - docCount(1) = 2 false holes. New math uses DISTINCT hashes.
        fakeStore.stats = CollectionStats(docCount = 1, indexes = emptyList())
        fakeDao.fakeIndexedCount = 3        // 3 rows with a hash
        fakeDao.fakeDistinctIndexedCount = 1 // but only 1 distinct hash
        fakeDao.fakeProcessedCount = 3
        fakeDao.fakeUnprocessedCount = 0

        val report = runner.getReport()
        assertEquals(0L, report.drift.orphansCount)
        assertEquals(0L, report.drift.holesCount) // NOT 2 — duplicates aren't holes
    }

    @Test
    fun report_capturesOpenAndCloseTimestamps() = runBlocking {
        fakeStore.stats = CollectionStats(docCount = 5, indexes = listOf(IndexStat("image_embedding", 0.75f)))
        fakeStore.openOutcome = "success"
        fakeStore.recoveryCount = 1
        fakeStore.flushTs = 12345L
        fakeStore.closeTs = 67890L
        fakeStore.isOpenState = true

        val report = runner.getReport()
        assertTrue(report.state.isOpen)
        assertEquals("success", report.state.lastOpenOutcome)
        assertEquals(1, report.state.lockRecoveriesCount)
        assertEquals(12345L, report.state.lastFlushTimestamp)
        assertEquals(67890L, report.state.lastCloseTimestamp)
        assertEquals(0.75f, report.state.indexCompleteness["image_embedding"] ?: 0.0f)
    }

    @Test
    fun report_capturesLoggedEvents() = runBlocking {
        ZvecEventRecorder.record { "Event A" }
        ZvecEventRecorder.record { "Event B" }

        val report = runner.getReport()
        assertEquals(2, report.events.size)
        assertEquals("Event A", report.events[0].message)
        assertEquals("Event B", report.events[1].message)
    }

    @Test
    fun query_searchWorks() = runBlocking {
        val matchedDocs = runner.inspectQuery("test")
        assertEquals(1, matchedDocs.size)
        assertEquals("hash123", matchedDocs[0].pk)
        assertEquals("test content", (matchedDocs[0].fields["content"] as ZvecValue.Str).value)
    }

    @Test
    fun report_subtractsBenchmarkSynthDocsFromOrphans() = runBlocking {
        // O1: a killed benchmark run leaves synth docs in the production collection (no Room row).
        // They show as orphans; the runner probes for the distinctive synth content and subtracts.
        fakeStore.stats = CollectionStats(docCount = 12, indexes = emptyList())
        fakeDao.fakeIndexedCount = 10
        fakeDao.fakeDistinctIndexedCount = 10  // D4: orphan math uses distinct hashes
        fakeDao.fakeProcessedCount = 10
        fakeDao.fakeUnprocessedCount = 0
        fakeStore.synthProbeHits = 2  // 2 leftover synth docs detected

        val report = runner.getReport()
        // 12 zvec - 10 distinct indexed = 2 raw orphans; 2 synth detected → 0 real orphans reported.
        assertEquals(0L, report.drift.orphansCount)
    }

    private class FakeScreenshotDao : ScreenshotDao {
        var fakeIndexedCount = 0
        var fakeDistinctIndexedCount = 0
        var fakeProcessedCount = 0
        var fakeUnprocessedCount = 0

        override fun getIndexedCount(): Int = fakeIndexedCount
        override fun getDistinctIndexedCount(): Int = fakeDistinctIndexedCount
        override fun getProcessedCount(): Int = fakeProcessedCount
        override fun getUnprocessedCount(): Int = fakeUnprocessedCount

        override fun getScreenshotsByContentHash(hashes: List<String>): List<ScreenshotModel> = TODO()

        // Stubs for remaining methods to satisfy ScreenshotDao
        override fun getScreenshot(screenshotId: String): ScreenshotModel = TODO()
        override fun getScreenshots(): androidx.lifecycle.LiveData<List<ScreenshotModel>> = TODO()
        override fun getScreenshotList(): List<ScreenshotModel> = TODO()
        override fun getScreenshots(collectionIds: List<String>): androidx.lifecycle.LiveData<List<ScreenshotModel>> = TODO()
        override fun getScreenshotList(collectionIds: List<String>): List<ScreenshotModel> = TODO()
        override fun addScreenshot(screenshot: List<ScreenshotModel>): Unit = TODO()
        override fun updateScreenshot(screenshots: List<ScreenshotModel>): Unit = TODO()
        override fun deleteScreenshot(screenshot: ScreenshotModel): Unit = TODO()
        override fun getCollectionCovers(): androidx.lifecycle.LiveData<List<ScreenshotModel>> = TODO()
        override fun markContentIndexed(id: String, contentHash: String): Unit = TODO()
        override fun resetProcessedForReingest(): Int = TODO()
        override fun getUnprocessed(): List<ScreenshotModel> = TODO()
        override fun getScreenshotByUri(uri: String): ScreenshotModel? = TODO()
        override fun getScreenshotsByUri(uris: List<String>): List<ScreenshotModel> = TODO()
    }

    private class FakeStore : ZvecContentStore(
        filesDir = File(System.getProperty("java.io.tmpdir") ?: "tmp"),
        debug = false
    ) {
        var stats: CollectionStats? = null
        var openOutcome: String = "never_opened"
        var recoveryCount: Int = 0
        var flushTs: Long = 0L
        var closeTs: Long = 0L
        var isOpenState: Boolean = false
        /** How many docs the benchmark-synth probe (O1) reports as present in zvec. */
        var synthProbeHits: Int = 0

        override fun isCollectionOpen(): Boolean = isOpenState
        override suspend fun getCollectionStats(): CollectionStats? = stats
        override var lastOpenOutcome: String
            get() = openOutcome
            set(value) { openOutcome = value }
        override var lockRecoveriesCount: Int
            get() = recoveryCount
            set(value) { recoveryCount = value }
        override var lastFlushTimestamp: Long
            get() = flushTs
            set(value) { flushTs = value }
        override var lastCloseTimestamp: Long
            get() = closeTs
            set(value) { closeTs = value }
        override var lastStatsError: String? = null

        override suspend fun search(matchString: String, topK: Int, filter: String?): List<ZvecDoc> {
            // The benchmark-synth probe (O1) returns a configurable count; other queries return the
            // single hardcoded doc the inspectQuery test asserts against.
            if (matchString.contains("benchmark synthetic")) {
                return (0 until synthProbeHits).map {
                    ZvecDoc(pk = "bench-$it", score = 1.0f, fields = emptyMap())
                }
            }
            return listOf(
                ZvecDoc(
                    pk = "hash123",
                    score = 1.0f,
                    fields = mapOf("content" to ZvecValue.Str("test content"))
                )
            )
        }
    }
}
