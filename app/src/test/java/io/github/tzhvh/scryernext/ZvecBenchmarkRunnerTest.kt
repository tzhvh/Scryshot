/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext

import io.github.tzhvh.scryernext.ingestion.ZvecContentStore
import io.github.tzhvh.scryernext.zvec.WriteResult
import io.github.tzhvh.scryernext.zvec.ZvecDoc
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the benchmark runner's math — [percentiles] + [ZvecBenchmarkRunner.LatencyResult]
 * QPS derivation — against a fake [ZvecContentStore] that returns canned timings without the `.so`.
 * The on-device path is the only thing this can't cover; the percentile/QPS math is fully exercised.
 */
class ZvecBenchmarkRunnerTest {

    // ── percentiles() edge cases ────────────────────────────────────────────────────────────

    @Test fun percentiles_empty_isZero() {
        assertEquals(0.0, percentiles(emptyList(), 0.50), 0.0)
        assertEquals(0.0, percentiles(emptyList(), 0.99), 0.0)
    }

    @Test fun percentiles_singleSample_isTheSample() {
        assertEquals(7.5, percentiles(listOf(7.5), 0.0), 0.0)
        assertEquals(7.5, percentiles(listOf(7.5), 0.50), 0.0)
        assertEquals(7.5, percentiles(listOf(7.5), 1.0), 0.0)
    }

    @Test fun percentiles_median_oddCount_isMiddleElement() {
        // sorted: [1, 2, 3, 4, 5]; rank(0.5) = 2 → element[2] = 3
        assertEquals(3.0, percentiles(listOf(3.0, 1.0, 5.0, 2.0, 4.0), 0.50), 1e-9)
    }

    @Test fun percentiles_median_evenCount_isLowerMiddle() {
        // sorted: [1,2,3,4]; rank(0.5) = (int)1.5 = 1 → element[1] = 2 (nearest-rank, truncation)
        assertEquals(2.0, percentiles(listOf(1.0, 2.0, 3.0, 4.0), 0.50), 1e-9)
    }

    @Test fun percentiles_p99_isNearMax() {
        // 100 samples 0..99; rank(0.99) = 98 → 98.0
        val samples = (0 until 100).map { it.toDouble() }
        assertEquals(98.0, percentiles(samples, 0.99), 1e-9)
        assertEquals(99.0, percentiles(samples, 1.0), 1e-9)
        assertEquals(0.0, percentiles(samples, 0.0), 1e-9)
    }

    @Test fun percentiles_pClampsToValidRange() {
        // p < 0 or p > 1 clamp to the bounds rather than throwing.
        val samples = listOf(10.0, 20.0, 30.0)
        assertEquals(10.0, percentiles(samples, -0.5), 1e-9)
        assertEquals(30.0, percentiles(samples, 1.5), 1e-9)
    }

    // ── LatencyResult mean + QPS ────────────────────────────────────────────────────────────

    @Test fun latencyResult_meanIsAverage() {
        val r = ZvecBenchmarkRunner.LatencyResult("x", listOf(2.0, 4.0, 6.0))
        assertEquals(4.0, r.meanMs, 1e-9)
    }

    @Test fun latencyResult_qpsIs1000OverMean() {
        // mean = 4.0 ms → 250 QPS single-thread
        val r = ZvecBenchmarkRunner.LatencyResult("x", listOf(2.0, 4.0, 6.0))
        assertEquals(250.0, r.qps, 1e-9)
    }

    @Test fun latencyResult_empty_hasZeroMeanAndQps() {
        val r = ZvecBenchmarkRunner.LatencyResult("x", emptyList())
        assertEquals(0.0, r.meanMs, 0.0)
        assertEquals(0.0, r.qps, 0.0)
    }

    // ── end-to-end runner against a fake store (canned timings, no .so) ─────────────────────

    @Test fun runner_runsAllStages_andReportsDocCount() = runBlocking {
        val fake = FakeStore(docCount = 1234L)
        val runner = ZvecBenchmarkRunner(fake)

        val stages = mutableListOf<String>()
        val report = runner.run { stages.add(it) }

        assertEquals(1234L, report.docCount)
        // The suite runs search(topK sweep) + fetch + upsert — at least one stage callback per.
        assertTrue("should emit multiple live stages", stages.size >= 3)
        // The footer caveat is on the final stage.
        assertTrue("final stage carries the Phase-3 caveat", stages.last().contains("Phase 3"))
        // The fake recorded the upsert + cleanup.
        assertTrue("upsert batch ran", fake.upsertCount >= 1)
        assertTrue("synthetic docs cleaned up", fake.deletedPks.isNotEmpty())
    }

    /** A fake store that records calls and returns canned data — no native. */
    private class FakeStore(private val docCount: Long) : ZvecContentStore(
        filesDir = java.io.File(System.getProperty("java.io.tmpdir"), "fake-bench-${System.nanoTime()}"),
        debug = false,
    ) {
        var upsertCount = 0
        var deletedPks: List<String> = emptyList()

        // Bypass the real ensureOpen/native path entirely.
        override suspend fun docCount(): Long = docCount
        override suspend fun search(matchString: String, topK: Int): List<ZvecDoc> =
            listOf(ZvecDoc(pk = "seed-pk", score = 1.0f, fields = mapOf()))
        override suspend fun fetch(contentHash: String): ZvecDoc? =
            ZvecDoc(pk = contentHash, score = null, fields = mapOf())
        override suspend fun upsert(contentHash: String, locator: String, content: String, collectionId: String) {
            upsertCount++
        }
        override suspend fun flush() {}
        override suspend fun deleteAll(pks: List<String>): WriteResult {
            deletedPks = pks
            return WriteResult(pks.size, emptyList())
        }
    }
}
