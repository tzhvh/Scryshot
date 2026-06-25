package io.github.tzhvh.scryernext.ingestion

import kotlinx.coroutines.runBlocking
import org.junit.Test

import org.junit.Assert.*
import java.io.ByteArrayInputStream

/**
 * Compile-shape guard for issue 01 — confirms the [Candidate] and [Progress]
 * contracts construct and round-trip the fields specified in the issue. This is
 * not real logic; it exists so a future shape change to the sealed hierarchy or
 * the `suspend () -> InputStream` byteHandle fails loudly here.
 *
 * Matches the repo's plain-JUnit convention (`org.junit.Assert.*`); no kotest.
 */
class TypeContractTest {

    @Test
    fun candidate_roundTrips_locator_and_default_identity() {
        val candidate = Candidate(
            locator = "content://media/external/images/42",
            byteHandle = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
        )

        assertEquals("content://media/external/images/42", candidate.locator)
        assertNull(candidate.identity)
    }

    @Test
    fun candidate_carries_optional_precomputed_identity() {
        val candidate = Candidate(
            locator = null,                                       // nullable under some producers
            byteHandle = { ByteArrayInputStream(byteArrayOf()) },
            identity = "sha256:deadbeef"
        )

        assertNull(candidate.locator)
        assertEquals("sha256:deadbeef", candidate.identity)
    }

    @Test
    fun candidate_byteHandle_is_a_usable_provider() {
        val payload = "ocr-me".toByteArray()
        val candidate = Candidate(locator = "u", byteHandle = { ByteArrayInputStream(payload) })

        // A provider, not a consumed stream: invoke it and read the bytes.
        val bytes = runBlocking { candidate.byteHandle().use { it.readBytes() } }

        assertArrayEquals(payload, bytes)
    }

    @Test
    fun progress_indexing_roundTrips_with_null_stageTimings() {
        val progress = Progress.Indexing(current = 3, total = 842, failedCount = 1)

        assertEquals(3, progress.current)
        assertEquals(842, progress.total)
        assertEquals(1, progress.failedCount)
        assertNull(progress.stageTimings)
    }

    @Test
    fun progress_indexing_carries_stageTimings_when_provided() {
        val timings = StageTimings(readMs = 1.0, decodeMs = 4.0, ocrMs = 80.0, writeMs = 2.0)

        val progress = Progress.Indexing(current = 0, total = 0, failedCount = 0, stageTimings = timings)

        assertEquals(timings, progress.stageTimings)
    }

    @Test
    fun progress_completed_roundTrips() {
        val progress = Progress.Completed(indexed = 838, failed = 4, total = 842)

        assertEquals(838, progress.indexed)
        assertEquals(4, progress.failed)
        assertEquals(842, progress.total)
        assertNull(progress.stageTimings)
    }

    @Test
    fun progress_error_carries_throwable() {
        val cause = IllegalStateException("ML Kit unavailable")

        val progress = Progress.Error(cause)

        assertSame(cause, progress.throwable)
    }

    @Test
    fun progress_paused_roundTrips_presentation_state() {
        val paused = Progress.Paused(sessionStartTotal = 842, doneCount = 400)

        assertEquals(842, paused.sessionStartTotal)
        assertEquals(400, paused.doneCount)
    }

    @Test
    fun progress_is_a_sealed_contract_with_four_subtypes() {
        val samples: List<Progress> = listOf(
            Progress.Indexing(0, 0, 0),
            Progress.Completed(0, 0, 0),
            Progress.Error(RuntimeException()),
            Progress.Paused()
        )

        assertTrue(samples[0] is Progress.Indexing)
        assertTrue(samples[1] is Progress.Completed)
        assertTrue(samples[2] is Progress.Error)
        assertTrue(samples[3] is Progress.Paused)
    }
}
