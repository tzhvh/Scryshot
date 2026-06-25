package io.github.tzhvh.scryernext.ingestion

import android.content.Context
import io.github.tzhvh.scryernext.persistence.CollectionModel
import io.github.tzhvh.scryernext.persistence.ScreenshotContentModel
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import io.github.tzhvh.scryernext.repository.ScreenshotRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Unit tests for [IngestionEngine] — ADR 0004 §2's pure pipeline, tested in
 * isolation against a fake repository, a fake [OcrStage], and a capturing write
 * sink. The engine touches no Room DAO, ML Kit, or `ContentResolver`, so this is
 * a plain JVM test.
 *
 * Plain JUnit4 + `runBlocking` — matches the repo convention (no
 * `kotlinx-coroutines-test`, no `turbine`, no `mockk`, no Robolectric). A
 * `Flow<Progress>` is collected to a `List` with `toList()`, as the Phase 0
 * ingestion tests do.
 *
 * The fake [ScreenshotRepository] reuses the `IsKnownSeamTest` pattern (keyed on
 * a `Set<String>`, resolving identity-then-locator), so the engine is exercised
 * against the real `isKnown` contract without a database. A known-set that
 * *mutates on write* ([MutableKnownRepo]) lets the §7.2 "permanent-content
 * failure lands in the known set after the write" test assert through the real
 * `isKnown` predicate.
 */
class IngestionEngineTest {

    /**
     * Fake repo: "known" iff the candidate's identity (or locator) is in [knownKeys].
     * The set is mutable so tests can assert "after a permanent-content write, a
     * re-scan of the same candidate reports isKnown == true" (issue 07 acceptance).
     */
    private class FakeScreenshotRepository(
        private val knownKeys: MutableSet<String>,
    ) : ScreenshotRepository {
        val isKnownCalls: MutableList<Candidate> = mutableListOf()

        override suspend fun isKnown(candidate: Candidate): Boolean {
            isKnownCalls += candidate
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
        override suspend fun setupDefaultContent(context: Context) = TODO()
    }

    /** Fake stage: maps a candidate's locator → a canned [OcrOutcome], recording attempts. */
    private class FakeOcrStage(
        private val outcomesByLocator: Map<String, OcrOutcome>,
    ) : OcrStage {
        val attempts: MutableList<Candidate> = mutableListOf()

        override suspend fun attempt(candidate: Candidate, bytes: ByteArray): OcrOutcome {
            attempts += candidate
            return outcomesByLocator[candidate.locator] ?: OcrOutcome.Success("default")
        }
    }

    /**
     * A write sink that records what it was asked to persist, keyed by locator.
     * [writtenProcessed] mirrors the `processed` flag so the §7.2 taxonomy asserts
     * both *what text* and *whether processed* landed.
     */
    private class CapturingWriteSink {
        val written: MutableMap<String?, String?> = mutableMapOf()
        val writtenProcessed: MutableMap<String?, Boolean> = mutableMapOf()
        val write = WriteSink { candidate, text, processed ->
            written[candidate.locator] = text
            writtenProcessed[candidate.locator] = processed
        }
    }

    private fun candidate(locator: String, identity: String? = null): Candidate = Candidate(
        locator = locator,
        byteHandle = { ByteArrayInputStream(byteArrayOf()) },
        identity = identity,
    )

    // --------------------------------------------------------------------------
    // (a) known candidates are skipped and not OCR'd/written
    // --------------------------------------------------------------------------

    @Test
    fun known_candidates_are_skipped_not_ocrd_or_written() = runBlocking {
        val repo = FakeScreenshotRepository(knownKeys = mutableSetOf("content://media/1"))
        val ocr = FakeOcrStage(outcomesByLocator = mapOf("content://media/1" to OcrOutcome.Success("should-not-happen")))
        val sink = CapturingWriteSink()
        val engine = IngestionEngine(repo, ocr, sink.write)

        engine.process(flowOf(candidate("content://media/1"))).toList()

        // isKnown was consulted for every candidate...
        assertEquals(1, repo.isKnownCalls.size)
        // ...but neither OCR nor write happened for the known one.
        assertTrue(ocr.attempts.isEmpty())
        assertTrue(sink.written.isEmpty())
    }

    // --------------------------------------------------------------------------
    // (b) unknown→Success candidates are written and counted as indexed
    // --------------------------------------------------------------------------

    @Test
    fun unknown_success_candidates_are_written_and_counted_as_indexed() = runBlocking {
        val repo = FakeScreenshotRepository(knownKeys = mutableSetOf())
        val ocr = FakeOcrStage(outcomesByLocator = mapOf(
            "content://media/1" to OcrOutcome.Success("hello"),
            "content://media/2" to OcrOutcome.Success("world"),
        ))
        val sink = CapturingWriteSink()
        val engine = IngestionEngine(repo, ocr, sink.write)

        val progress = engine.process(flowOf(
            candidate("content://media/1"),
            candidate("content://media/2"),
        )).toList()

        assertEquals(mapOf("content://media/1" to "hello", "content://media/2" to "world"), sink.written)
        assertEquals(mapOf("content://media/1" to true, "content://media/2" to true), sink.writtenProcessed)
        assertEquals(2, ocr.attempts.size)

        // Completion counts both as indexed, with zero failed.
        val completed = progress.last() as Progress.Completed
        assertEquals(2, completed.indexed)
        assertEquals(0, completed.failed)
        assertEquals(2, completed.total)
        assertNotNull(completed.stageTimings)   // issue 07: no longer null
    }

    // --------------------------------------------------------------------------
    // (c) early-progress fires before any OCR
    // --------------------------------------------------------------------------

    @Test
    fun early_progress_fires_before_any_ocr() = runBlocking {
        val repo = FakeScreenshotRepository(knownKeys = mutableSetOf())
        val ocr = OcrStage { _, _ -> OcrOutcome.Success("x") }
        val engine = IngestionEngine(repo, ocr, WriteSink { _, _, _ -> })

        val first = engine.process(flowOf(candidate("content://media/1"))).toList().first()

        // The first emission is the early-progress Indexing(0, …) — emitted before
        // the first OCR runs (current = 0 could not be 0 after OCR incremented it).
        assertTrue(first is Progress.Indexing)
        val indexing = first as Progress.Indexing
        assertEquals(0, indexing.current)
        assertEquals(1, indexing.total)
        assertEquals(0, indexing.failedCount)
        // issue 07: stageTimings is the all-zero snapshot before any sample, NOT null.
        assertNotNull(indexing.stageTimings)
        assertEquals(StageTimings(), indexing.stageTimings)
    }

    // --------------------------------------------------------------------------
    // (d) exact Progress sequence for a small flow (3 candidates, 1 known)
    // --------------------------------------------------------------------------

    @Test
    fun exact_progress_sequence_for_three_candidates_one_known() = runBlocking {
        // locators: 1 known (skip), 2 unknown→Success, 3 unknown→PermanentContentFailure.
        val repo = FakeScreenshotRepository(knownKeys = mutableSetOf("content://media/1"))
        val ocr = FakeOcrStage(outcomesByLocator = mapOf(
            "content://media/2" to OcrOutcome.Success("ok"),
            "content://media/3" to OcrOutcome.PermanentContentFailure,
        ))
        val sink = CapturingWriteSink()
        val engine = IngestionEngine(repo, ocr, sink.write)

        val progress = engine.process(flowOf(
            candidate("content://media/1"),   // known → skip
            candidate("content://media/2"),   // unknown → Success → write text, indexed
            candidate("content://media/3"),   // unknown → PermanentContentFailure → write empty+processed, indexed
        )).toList()

        // 1 early-emit + 1 per-candidate (3) + 1 Completed = 5 emissions.
        assertEquals(5, progress.size)

        val e0 = progress[0] as Progress.Indexing          // early, before any OCR
        assertEquals(0, e0.current); assertEquals(3, e0.total); assertEquals(0, e0.failedCount)

        val e1 = progress[1] as Progress.Indexing          // after known skip: bar still 0
        assertEquals(0, e1.current); assertEquals(0, e1.failedCount)

        val e2 = progress[2] as Progress.Indexing          // after Success: indexed+failed = 1
        assertEquals(1, e2.current); assertEquals(0, e2.failedCount)

        // §7.2: a permanent-content failure advances the bar via the *indexed* bucket
        // (processed-but-empty), NOT the failed bucket.
        val e3 = progress[3] as Progress.Indexing
        assertEquals(2, e3.current); assertEquals(0, e3.failedCount)

        val e4 = progress[4] as Progress.Completed
        assertEquals(2, e4.indexed); assertEquals(0, e4.failed); assertEquals(3, e4.total)

        // The known candidate was not written; BOTH unknown candidates were — Success
        // with text, PermanentContentFailure with null + processed=true.
        assertEquals(mapOf(
            "content://media/2" to "ok",
            "content://media/3" to null,        // §7.2: empty text for permanent-content
        ), sink.written)
        assertEquals(mapOf("content://media/2" to true, "content://media/3" to true), sink.writtenProcessed)
    }

    // --------------------------------------------------------------------------
    // (e) cancellation mid-flow stops further emissions
    // --------------------------------------------------------------------------

    @Test
    fun cancellation_mid_flow_stops_further_emissions() = runBlocking {
        // Deterministic, race-free cancellation proof. The OCR stage gates candidate 1 on
        // a CompletableDeferred: it suspends until the test releases it, giving a real
        // suspension point at which cancellation takes effect. The engine is caller-owned-
        // cancel: cancelling the collecting Job must stop the run before candidate 2 runs.
        val repo = FakeScreenshotRepository(knownKeys = mutableSetOf())
        val firstReached = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseFirst = kotlinx.coroutines.CompletableDeferred<Unit>()
        val secondAttempted = java.util.concurrent.atomic.AtomicBoolean(false)
        val ocr = OcrStage { c, _ ->
            if (c.locator == "content://media/1") {
                firstReached.complete(Unit)       // signal we're suspended inside candidate 1
                releaseFirst.await()              // suspension point — cancellation lands here
                OcrOutcome.Success("first")
            } else {
                secondAttempted.set(true)         // must never happen after cancel
                OcrOutcome.Success("second-never-reached")
            }
        }
        val sink = CapturingWriteSink()
        val engine = IngestionEngine(repo, ocr, sink.write)

        val collected = mutableListOf<Progress>()
        val scope = kotlinx.coroutines.CoroutineScope(coroutineContext)
        val job = scope.launch {
            engine.process(flow {
                emit(candidate("content://media/1"))
                emit(candidate("content://media/2"))
            }).collect { collected += it }
        }

        firstReached.await()             // engine is now suspended inside candidate 1's OCR
        job.cancel()                     // caller-owned: cancel the collecting Job
        releaseFirst.complete(Unit)      // let the suspended stage resume → into cancellation
        job.join()

        // Candidate 2 was never reached: not OCR'd, not written.
        assertFalse("second candidate should not have been attempted", secondAttempted.get())
        assertFalse("second candidate should not have been written", "second-never-reached" in sink.written.values)
        // The run was interrupted (no Completed), and candidate 1 never completed its write
        // either — it was cancelled mid-OCR, before write ran.
        assertTrue("no Completed should have been emitted", collected.none { it is Progress.Completed })
    }

    // --------------------------------------------------------------------------
    // extra: empty flow still emits early-progress + Completed
    // --------------------------------------------------------------------------

    @Test
    fun empty_flow_emits_early_progress_then_completed() = runBlocking {
        val repo = FakeScreenshotRepository(knownKeys = mutableSetOf())
        val ocr = FakeOcrStage(outcomesByLocator = emptyMap())
        val sink = CapturingWriteSink()
        val engine = IngestionEngine(repo, ocr, sink.write)

        val progress = engine.process(flowOf()).toList()

        assertEquals(2, progress.size)
        val early = progress[0] as Progress.Indexing
        assertEquals(0, early.current); assertEquals(0, early.total); assertEquals(0, early.failedCount)
        val done = progress[1] as Progress.Completed
        assertEquals(0, done.indexed); assertEquals(0, done.failed); assertEquals(0, done.total)
        assertTrue(sink.written.isEmpty())
    }

    // ==========================================================================
    // §7.2 three-class failure taxonomy (issue 07a)
    // ==========================================================================

    // --------------------------------------------------------------------------
    // (a) Permanent-content failure writes empty+processed and advances *indexed*
    // --------------------------------------------------------------------------

    @Test
    fun permanent_content_failure_writes_empty_and_processed_and_counts_as_indexed() = runBlocking {
        val repo = FakeScreenshotRepository(knownKeys = mutableSetOf())
        val ocr = FakeOcrStage(outcomesByLocator = mapOf(
            "content://media/1" to OcrOutcome.PermanentContentFailure,
        ))
        val sink = CapturingWriteSink()
        val engine = IngestionEngine(repo, ocr, sink.write)

        val progress = engine.process(flowOf(candidate("content://media/1"))).toList()

        // §7.2: permanent-content writes a row — null text + processed=true.
        assertEquals(mapOf<String?, String?>("content://media/1" to null), sink.written)
        assertEquals(mapOf("content://media/1" to true), sink.writtenProcessed)

        val done = progress.last() as Progress.Completed
        // Counted as INDEXED (processed-but-empty), NOT failed — the whole point of §7.2.
        assertEquals(1, done.indexed)
        assertEquals(0, done.failed)
        assertEquals(1, done.total)
    }

    // --------------------------------------------------------------------------
    // (a cont.) a permanent-content write lands the locator in isKnown's set
    // --------------------------------------------------------------------------

    @Test
    fun permanent_content_failure_write_makes_rescan_return_isKnown_true() = runBlocking {
        // The write sink mirrors what the real Room repo does: a processed write adds the
        // locator to the "indexed" set. After the run, isKnown for that locator flips true.
        val knownKeys = mutableSetOf<String>()
        val repo = FakeScreenshotRepository(knownKeys)
        val ocr = FakeOcrStage(outcomesByLocator = mapOf(
            "content://media/1" to OcrOutcome.PermanentContentFailure,
        ))
        // The sink mutates the repo's known set exactly as a processed Room insert would
        // surface in dbKeysByLocator (ScreenshotDao.getIndexedUris selects processed = 1).
        val write = WriteSink { c, _, processed ->
            if (processed) {
                val key = c.identity ?: c.locator
                if (key != null) knownKeys += key
            }
        }
        val engine = IngestionEngine(repo, ocr, write)

        // Before the run: unknown.
        assertFalse(repo.isKnown(candidate("content://media/1")))
        engine.process(flowOf(candidate("content://media/1"))).toList()
        // After the permanent-content write: known — it left the unindexed set.
        assertTrue(repo.isKnown(candidate("content://media/1")))
    }

    // --------------------------------------------------------------------------
    // (b) Transient failure writes nothing and advances *failed*
    // --------------------------------------------------------------------------

    @Test
    fun transient_failure_writes_nothing_and_counts_as_failed() = runBlocking {
        val repo = FakeScreenshotRepository(knownKeys = mutableSetOf())
        val cause = IllegalStateException("model still downloading")
        val ocr = FakeOcrStage(outcomesByLocator = mapOf(
            "content://media/1" to OcrOutcome.TransientFailure(cause),
            "content://media/2" to OcrOutcome.Success("after-retry"),
        ))
        val sink = CapturingWriteSink()
        val engine = IngestionEngine(repo, ocr, sink.write)

        val progress = engine.process(flowOf(
            candidate("content://media/1"),
            candidate("content://media/2"),
        )).toList()

        // Transient wrote nothing; Success did. Run continued (did not crash).
        assertEquals(mapOf("content://media/2" to "after-retry"), sink.written)
        assertFalse(sink.writtenProcessed.containsKey("content://media/1"))

        val done = progress.last() as Progress.Completed
        assertEquals(1, done.indexed); assertEquals(1, done.failed); assertEquals(2, done.total)
    }

    // --------------------------------------------------------------------------
    // (c) a write-sink throw surfaces as Progress.Error and stops the run
    // --------------------------------------------------------------------------

    @Test
    fun write_sink_throw_surfaces_as_error_and_stops_run() = runBlocking {
        val repo = FakeScreenshotRepository(knownKeys = mutableSetOf())
        val ocr = OcrStage { _, _ -> OcrOutcome.Success("ok") }
        val boom = java.io.IOException("disk full")
        val write = WriteSink { _, _, _ -> throw boom }
        val engine = IngestionEngine(repo, ocr, write)

        val progress = engine.process(flowOf(
            candidate("content://media/1"),
            candidate("content://media/2"),
        )).toList()

        // The write throw propagates as Progress.Error, and the run stops before
        // candidate 2 (no Completed — the flow terminated on the error).
        assertTrue("last emission should be Error", progress.last() is Progress.Error)
        assertSame(boom, (progress.last() as Progress.Error).throwable)
        assertTrue("no Completed should have been emitted", progress.none { it is Progress.Completed })
    }

    // ==========================================================================
    // §7.3 per-stage Stopwatch instrumentation (issue 07b)
    // ==========================================================================

    // --------------------------------------------------------------------------
    // (d) StageTimings is non-null and reflects the fake stage's induced latency
    // --------------------------------------------------------------------------

    @Test
    fun stage_timings_are_populated_and_reflect_induced_latency() = runBlocking {
        val repo = FakeScreenshotRepository(knownKeys = mutableSetOf())
        // A fake stage that genuinely sleeps so read/ocr/write each cost real wall-clock
        // time — a coarse but deterministic-enough proof that the stages are timed and
        // rolled into StageTimings. System.nanoTime() resolution is ample for a few ms.
        val ocrSleepMs = 12L
        val ocr = OcrStage { _, _ ->
            Thread.sleep(ocrSleepMs)
            OcrOutcome.Success("slept")
        }
        val writeSleepMs = 3L
        val write = WriteSink { _, _, _ -> Thread.sleep(writeSleepMs) }
        val engine = IngestionEngine(repo, ocr, write)

        val progress = engine.process(flowOf(candidate("content://media/1"))).toList()

        val done = progress.last() as Progress.Completed
        val timings = done.stageTimings
        assertNotNull("StageTimings must be populated (issue 07b)", timings)
        // OCR stage slept the longest of the in-stage work — ocrMs must reflect it.
        assertTrue("ocrMs should reflect the induced sleep (~${ocrSleepMs}ms), was ${timings!!.ocrMs}",
                   timings.ocrMs >= ocrSleepMs * 0.5)
        assertTrue("writeMs should reflect the induced sleep (~${writeSleepMs}ms), was ${timings.writeMs}",
                   timings.writeMs >= writeSleepMs * 0.5)
        // decodeMs is folded into ocrMs until the stage splits it out (see StageTimings KDoc).
        assertEquals(0.0, timings.decodeMs, 0.0)
    }

    // --------------------------------------------------------------------------
    // (d cont.) StageTimings is non-null on EVERY Indexing emit, incl. the early one
    // --------------------------------------------------------------------------

    @Test
    fun every_indexing_emit_carries_non_null_stageTimings() = runBlocking {
        val repo = FakeScreenshotRepository(knownKeys = mutableSetOf())
        val ocr = FakeOcrStage(outcomesByLocator = mapOf(
            "content://media/2" to OcrOutcome.Success("ok"),
        ))
        val engine = IngestionEngine(repo, ocr, WriteSink { _, _, _ -> })

        val progress = engine.process(flowOf(
            candidate("content://media/1"),   // unknown→Success (default)
            candidate("content://media/2"),
        )).toList()

        // Early-emit's snapshot is the all-zero StageTimings (before any sample); the
        // post-OCR emits carry a non-zero snapshot. Both are NON-null — issue 07b.
        progress.filterIsInstance<Progress.Indexing>().forEach { idx ->
            assertNotNull("Indexing emit must carry StageTimings", idx.stageTimings)
        }
        val early = progress.first() as Progress.Indexing
        assertEquals(StageTimings(), early.stageTimings)   // all-zero before any OCR
        val afterFirst = progress[1] as Progress.Indexing
        // read+ocr+write each took some wall-clock; ocrMs should be non-zero.
        assertTrue(afterFirst.stageTimings!!.ocrMs >= 0.0)
    }
}
