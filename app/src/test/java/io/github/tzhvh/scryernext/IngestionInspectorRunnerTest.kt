/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext

import io.github.tzhvh.scryernext.ingestion.IngestionEventRecorder
import io.github.tzhvh.scryernext.ingestion.IngestionProgressStore
import io.github.tzhvh.scryernext.ingestion.Progress
import io.github.tzhvh.scryernext.ingestion.ZvecContentStore
import io.github.tzhvh.scryernext.persistence.ContentMetadataCache
import io.github.tzhvh.scryernext.persistence.ContentMetadataCacheDao
import io.github.tzhvh.scryernext.persistence.ScreenshotDao
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import io.github.tzhvh.scryernext.search.PrecisionMode
import io.github.tzhvh.scryernext.zvec.CollectionStats
import io.github.tzhvh.scryernext.zvec.ZvecDoc
import io.github.tzhvh.scryernext.zvec.ZvecValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit tests for [IngestionInspectorRunner] — the Ingestion Inspector's report builder. Plain
 * JUnit4 + `runBlocking` (repo convention): the runner is pure Kotlin fed by fakes, so every
 * panel's data derivation is verifiable without Room, WorkManager, or ML Kit.
 */
class IngestionInspectorRunnerTest {

    private lateinit var fakeDao: FakeScreenshotDao
    private lateinit var fakeCacheDao: FakeCacheDao
    private lateinit var fakeStore: FakeStore
    private lateinit var recorder: IngestionEventRecorder
    private lateinit var store: IngestionProgressStore
    private lateinit var runner: IngestionInspectorRunner

    private var sessionPendingStub = false

    @Before
    fun setUp() {
        recorder = IngestionEventRecorder().apply { init(enabled = true) }
        recorder.clear()
        fakeDao = FakeScreenshotDao()
        fakeCacheDao = FakeCacheDao()
        fakeStore = FakeStore()
        store = IngestionProgressStore()
        sessionPendingStub = false
        runner = IngestionInspectorRunner(
            store = store,
            screenshotDao = fakeDao,
            metadataCacheDao = fakeCacheDao,
            contentStore = fakeStore,
            events = recorder,
            sessionPending = { sessionPendingStub },
            mlKitState = { IngestionInspectorRunner.MlKitState(recognizerCreated = true, createdAtMs = 1234L) },
        )
    }

    private fun counts(
        unprocessed: Int = 0,
        processed: Int = 0,
        indexed: Int = processed,
        distinctIndexed: Int = indexed,
        cacheRows: Int = 0,
        docCount: Long = distinctIndexed.toLong(),
    ) {
        fakeDao.fakeUnprocessedCount = unprocessed
        fakeDao.fakeProcessedCount = processed
        fakeDao.fakeIndexedCount = indexed
        fakeDao.fakeDistinctIndexedCount = distinctIndexed
        fakeCacheDao.fakeCount = cacheRows
        fakeStore.stats = CollectionStats(docCount = docCount, indexes = emptyList())
    }

    @Test
    fun report_live_reflects_guard_backlog_and_sessionPending() = runBlocking {
        counts()
        assertTrue(store.tryEnter(IngestionProgressStore.TriggerKind.ON_OPEN))
        store.publishBacklog(42)
        sessionPendingStub = true

        val report = runner.getReport()

        assertTrue(report.live.isActive)
        assertEquals(IngestionProgressStore.TriggerKind.ON_OPEN, report.live.activeKind)
        assertEquals(42, report.live.backlog)
        assertTrue(report.live.sessionPending)
        assertTrue(report.live.progress is Progress.Indexing)   // tryEnter's early Indexing
    }

    @Test
    fun report_live_is_idle_when_no_run() = runBlocking {
        counts()
        val report = runner.getReport()
        assertFalse(report.live.isActive)
        assertEquals(null, report.live.activeKind)
        assertFalse(report.live.sessionPending)
        assertTrue(report.live.progress is Progress.Idle)
    }

    @Test
    fun report_drift_computes_unbridged_cache_orphans_and_holes() = runBlocking {
        // 3 duplicate rows share 1 hash (D4): processed=3, distinct=1, zvec docCount=1 → no holes.
        // Queue still holds 4 unprocessed; cache warmed 7 rows.
        counts(unprocessed = 4, processed = 3, indexed = 1, distinctIndexed = 1, cacheRows = 7, docCount = 1)

        val drift = runner.getReport().drift

        assertEquals(4, drift.roomUnprocessedCount)
        assertEquals(3, drift.roomProcessedCount)
        assertEquals(1, drift.roomDistinctIndexedCount)
        // processed − distinct = 2 unbridged (the duplicate rows), NOT holes.
        assertEquals(2, drift.unbridgedProcessedCount)
        assertEquals(7, drift.cacheRowCount)
        assertEquals(0L, drift.orphansCount)
        assertEquals(0L, drift.holesCount)
        assertTrue(drift.statsReadable)
    }

    @Test
    fun report_drift_flags_raw_orphans_and_holes() = runBlocking {
        counts(processed = 10, indexed = 10, distinctIndexed = 10, docCount = 12)

        val drift = runner.getReport().drift
        assertEquals(2L, drift.orphansCount)
        assertEquals(0L, drift.holesCount)

        counts(processed = 10, indexed = 10, distinctIndexed = 10, docCount = 8)
        val drift2 = runner.getReport().drift
        assertEquals(0L, drift2.orphansCount)
        assertEquals(2L, drift2.holesCount)
    }

    @Test
    fun report_drift_unreadable_when_stats_fail() = runBlocking {
        counts()
        fakeStore.lastStatsError = "lock unavailable"

        val drift = runner.getReport().drift

        assertFalse(drift.statsReadable)
        assertEquals(0L, drift.zvecDocCount)
    }

    @Test
    fun report_stage_timings_come_from_the_last_progress_emission() = runBlocking {
        counts()
        store.tryEnter(IngestionProgressStore.TriggerKind.BULK)
        store.publish(
            Progress.Indexing(
                current = 1, total = 2, failedCount = 0,
                stageTimings = StageTimingsForTest,
            )
        )

        val report = runner.getReport()
        assertEquals(StageTimingsForTest, report.stageTimings)
    }

    @Test
    fun report_history_carries_retained_runs() = runBlocking {
        counts()
        store.tryEnter(IngestionProgressStore.TriggerKind.ON_OPEN)
        store.complete(Progress.Completed(indexed = 5, failed = 1, total = 6))

        val report = runner.getReport()
        assertEquals(1, report.history.size)
        assertEquals(IngestionProgressStore.TriggerKind.ON_OPEN, report.history.single().kind)
        assertEquals(5, (report.history.single().terminal as Progress.Completed).indexed)
    }

    @Test
    fun report_events_and_mlKit_and_handoff_are_carried_through() = runBlocking {
        counts()
        recorder.record { "per-item event" }
        fakeStore.openOutcome = "success"
        fakeStore.recoveryCount = 2
        fakeStore.flushTs = 99L

        val report = runner.getReport()

        assertEquals(listOf("per-item event"), report.events.map { it.message })
        assertTrue(report.mlKit.recognizerCreated)
        assertEquals(1234L, report.mlKit.createdAtMs)
        assertEquals("success", report.handoff.lastOpenOutcome)
        assertEquals(2, report.handoff.lockRecoveriesCount)
        assertEquals(99L, report.handoff.lastFlushTimestamp)
    }

    private companion object {
        val StageTimingsForTest = io.github.tzhvh.scryernext.ingestion.StageTimings(
            readMs = 1.0, decodeMs = 0.0, ocrMs = 2.0, writeMs = 3.0
        )
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

        // Stubs for remaining methods to satisfy ScreenshotDao
        override fun getScreenshotsByContentHash(hashes: List<String>): List<ScreenshotModel> = TODO()
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

    private class FakeCacheDao : ContentMetadataCacheDao {
        var fakeCount = 0
        override fun getCount(): Int = fakeCount
        override fun lookup(locator: String, mtime: Long, size: Long): ContentMetadataCache? = TODO()
        override fun lookupByHash(contentHash: String): ContentMetadataCache? = TODO()
        override fun upsert(entry: ContentMetadataCache): Unit = TODO()
        override fun markIndexed(contentHash: String): Int = TODO()
        override fun clearAll(): Unit = TODO()
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

        override fun isCollectionOpen(): Boolean = false
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

        override suspend fun search(matchString: String, topK: Int, filter: String?, precision: PrecisionMode): List<ZvecDoc> {
            return listOf(ZvecDoc(pk = "hash123", score = 1.0f, fields = mapOf("content" to ZvecValue.Str("test content"))))
        }
    }
}
