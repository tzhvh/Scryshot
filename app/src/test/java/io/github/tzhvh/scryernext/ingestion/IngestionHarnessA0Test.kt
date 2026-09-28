package io.github.tzhvh.scryernext.ingestion

import io.github.tzhvh.scryernext.ingestion.harness.FakeOcrStage
import io.github.tzhvh.scryernext.ingestion.harness.HarnessCorpus
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import io.github.tzhvh.scryernext.repository.DedupResult
import io.github.tzhvh.scryernext.repository.ScreenshotRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Harness arm **A0 — engine bookkeeping at N** (`INGESTION_ADHOC_REPRO_HARNESS.md`
 * §4, §8, §13): the whole pipeline driven by fakes, at the cliff N (10k), proving
 * the engine's *own* counters, timings, and emission contract are N-safe before
 * any real accumulator (zvec / bitmaps / ML Kit) is added by A1–A3.
 *
 * Red here would mean the stall is bookkeeping, not accumulators — the cheapest
 * possible elimination, milliseconds on the JVM. Extends the
 * [IngestionEngineTest] fake pattern (plain JUnit4 + runBlocking, no
 * Robolectric/turbine/mockk — the repo convention).
 */
class IngestionHarnessA0Test {

    /** Cliff N — past the field-reported ~7–8k stall point (§1). */
    private val n = 10_000

    private class FakeRepo : ScreenshotRepository {
        override suspend fun isKnown(candidate: Candidate, bytes: ByteArray): DedupResult =
            DedupResult(known = false, resolvedContentHash = "hash-${candidate.locator}")

        override suspend fun markProcessed(candidate: Candidate) = Unit
        override suspend fun markContentIndexed(screenshot: ScreenshotModel, contentHash: String) = Unit
        override suspend fun getScreenshotByUri(uri: String): ScreenshotModel? = null
        override suspend fun addCollection(collection: io.github.tzhvh.scryernext.persistence.CollectionModel) = TODO()
        override fun getCollections(): Flow<List<io.github.tzhvh.scryernext.persistence.CollectionModel>> = TODO()
        override suspend fun getCollectionList() = TODO()
        override suspend fun getCollection(id: String) = TODO()
        override fun getCollectionCovers() = TODO()
        override suspend fun updateCollection(collection: io.github.tzhvh.scryernext.persistence.CollectionModel) = TODO()
        override suspend fun updateCollectionId(collection: io.github.tzhvh.scryernext.persistence.CollectionModel, id: String) = TODO()
        override suspend fun deleteCollection(collection: io.github.tzhvh.scryernext.persistence.CollectionModel) = TODO()
        override suspend fun addScreenshot(screenshots: List<ScreenshotModel>) = TODO()
        override suspend fun updateScreenshots(screenshots: List<ScreenshotModel>) = TODO()
        override suspend fun getScreenshot(screenshotId: String) = TODO()
        override fun getScreenshots(): Flow<List<ScreenshotModel>> = TODO()
        override suspend fun getScreenshotList() = TODO()
        override fun getScreenshots(collectionIds: List<String>): Flow<List<ScreenshotModel>> = TODO()
        override suspend fun getScreenshotList(collectionIds: List<String>) = TODO()
        override suspend fun deleteScreenshot(screenshot: ScreenshotModel) = TODO()
        override fun searchScreenshots(queryText: String, policy: io.github.tzhvh.scryernext.search.RankPolicy, filter: String?, precision: io.github.tzhvh.scryernext.search.PrecisionMode) = TODO()
        override suspend fun searchScreenshotList(queryText: String, policy: io.github.tzhvh.scryernext.search.RankPolicy, filter: String?, precision: io.github.tzhvh.scryernext.search.PrecisionMode) = TODO()
        override suspend fun getContentText(screenshot: ScreenshotModel) = TODO()
        override suspend fun getUnprocessedScreenshotList() = TODO()
        override suspend fun getUnprocessedCount() = TODO()
        override suspend fun setupDefaultContent(context: android.content.Context) = TODO()
    }

    /** Capturing sink (the A0 arm's write seam — §4's "capturing sink + fake repo"). */
    private class CapturingSink : WriteSink {
        val texts = HashMap<String?, String?>(16_384)
        val processedFlags = HashMap<String?, Boolean>(16_384)
        override suspend fun commit(candidate: Candidate, text: String?, processed: Boolean, bytes: ByteArray, precomputedContentHash: String?) {
            texts[candidate.locator] = text
            processedFlags[candidate.locator] = processed
        }
    }

    @Test
    fun `a0 - engine bookkeeping is N-safe at 10k candidates`() = runBlocking {
        val repo = FakeRepo()
        val stage = FakeOcrStage()
        val sink = CapturingSink()
        val engine = IngestionEngine(repo, stage, sink)

        val emissions = engine.process(
            kotlinx.coroutines.flow.flowOf(*HarnessCorpus.candidates(n).toTypedArray())
        ).toList()

        // No run-level error ever surfaced — the bookkeeping loop is N-safe.
        assertEquals(
            "A0 must produce zero Progress.Error emissions",
            0, emissions.filterIsInstance<Progress.Error>().size,
        )

        // Terminal: exact Completed(indexed=N) — the counters never drift at N.
        val terminal = emissions.last()
        assertTrue("terminal emission must be Completed, was ${terminal::class.simpleName}", terminal is Progress.Completed)
        assertEquals(n, (terminal as Progress.Completed).indexed)
        assertEquals(0, terminal.failed)
        assertEquals(n, terminal.total)

        // Emission contract: the early §7.4 emit + one per candidate + the terminal.
        assertEquals(n + 2, emissions.size)
        val first = emissions.first()
        assertTrue(first is Progress.Indexing && first.current == 0 && first.total == n)

        // Every candidate was OCR'd once and written once, with realistic text.
        assertEquals(n, stage.attempts)
        assertEquals(n, sink.texts.size)
        assertTrue(
            "write payloads must be realistic OCR-shaped text, not 'synth doc i'",
            sink.texts.values.all { it != null && it.length > 150 },
        )
        assertTrue(sink.processedFlags.values.all { it })

        // The rolling timings snapshot on the terminal is well-formed at N.
        val timings = terminal.stageTimings
        assertNotNull(timings)
        assertTrue(timings!!.readMs >= 0.0 && timings.ocrMs >= 0.0 && timings.writeMs >= 0.0)
        assertTrue(timings.readMs.isFinite() && timings.ocrMs.isFinite() && timings.writeMs.isFinite())
    }
}
