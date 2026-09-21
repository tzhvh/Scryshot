/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext

import io.github.tzhvh.scryernext.ingestion.ZvecContentStore
import io.github.tzhvh.scryernext.persistence.ScreenshotDao
import io.github.tzhvh.scryernext.zvec.ZvecDoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * zvec Runtime Inspector (ZVEC_INSPECTOR.md §3-6) — Runner.
 *
 * Collects runtime state, database drift/consistency metrics, and recent events.
 * Pure-Kotlin, JVM-testable.
 *
 * @param queryPreShaper transforms the raw inspector input into the production FTS query shape
 *   (the trailing-`*` wildcard transform, see [io.github.tzhvh.scryernext.repository.ZvecScreenshotRepository.processQuery]).
 *   Defaults to identity so tests can pass a raw string; production passes the real shaper so the
 *   inspector sees exactly what users see (O3 — query parity).
 */
class ZvecInspectorRunner(
    private val store: ZvecContentStore,
    private val screenshotDao: ScreenshotDao,
    private val queryPreShaper: (String) -> String = { it }
) {
    data class StateSnapshot(
        val isOpen: Boolean,
        val docCount: Long,
        val indexCompleteness: Map<String, Float>,
        val lastOpenOutcome: String,
        val lockRecoveriesCount: Int,
        val lastFlushTimestamp: Long,
        val lastCloseTimestamp: Long,
        /** Non-null when the last `stats()` read failed; null on success. See [ZvecContentStore.lastStatsError]. */
        val statsError: String?
    )

    data class DriftMeter(
        val zvecDocCount: Long,
        val roomIndexedCount: Int,
        /** D4 — distinct content_hash count; the dedup-correct peer of [roomIndexedCount]. */
        val roomDistinctIndexedCount: Int,
        val roomProcessedCount: Int,
        val roomUnprocessedCount: Int,
        /**
         * D4 — rows marked `processed = 1` but with no `content_hash` (the legacy dedup-skip-without-
         * hash case D2 fixes going forward). NOT a hole (no zvec doc is missing); a bridging gap. The
         * duplicate-screenshot case (N rows, 1 hash) shows up here and is benign — those rows share a
         * hash via the expanded row, not their own.
         */
        val unbridgedProcessedCount: Int,
        val orphansCount: Long,
        val holesCount: Long,
        /**
         * False when `stats()` failed (docCount defaults to 0 → meaningless orphans/holes). The
         * Activity renders "⚠️ unavailable — see Event Log" instead of the (fake) PASS verdict.
         */
        val statsReadable: Boolean
    )

    data class Report(
        val state: StateSnapshot,
        val drift: DriftMeter,
        val events: List<ZvecEventRecorder.Event>
    )

    suspend fun getReport(): Report = withContext(Dispatchers.Default) {
        val isOpen = store.isCollectionOpen()
        val stats = store.getCollectionStats()
        val docCount = stats?.docCount ?: 0L
        val indexCompleteness = stats?.indexes?.associate { it.name to it.completeness } ?: emptyMap()

        val state = StateSnapshot(
            isOpen = isOpen,
            docCount = docCount,
            indexCompleteness = indexCompleteness,
            lastOpenOutcome = store.lastOpenOutcome,
            lockRecoveriesCount = store.lockRecoveriesCount,
            lastFlushTimestamp = store.lastFlushTimestamp,
            lastCloseTimestamp = store.lastCloseTimestamp,
            statsError = store.lastStatsError
        )

        // O5: one batched Room hop for the four counts instead of sequential dispatcher hops.
        // D4: fetch DISTINCT content_hash count too — the dedup-correct index. Raw indexedCount
        // overcounts under duplicates (N rows share one hash), producing permanent false-positive holes.
        val (roomIndexed, roomDistinctIndexed, roomProcessed, roomUnprocessed) = withContext(Dispatchers.IO) {
            Quad(
                screenshotDao.getIndexedCount(),
                screenshotDao.getDistinctIndexedCount(),
                screenshotDao.getProcessedCount(),
                screenshotDao.getUnprocessedCount()
            )
        }

        // Orphans: zvec has a doc Room doesn't know about (docCount > distinct indexed).
        // D4: compare against DISTINCT hashes — one zvec doc per hash, so the 1:1 mapping is
        // docCount ↔ distinctIndexedCount, not docCount ↔ raw indexedCount.
        var orphans = (docCount - roomDistinctIndexed).coerceAtLeast(0L)

        // O1: benchmark synth docs (`bench-synth-NNNN`, locator `content://bench/...`) have no Room
        // row, so a killed benchmark run leaves them as false-positive orphans. Detect and subtract
        // via the distinctive synth content string, so the meter doesn't cry wolf after a killed run.
        // Only probes when there's orphan pressure (avoids an extra FTS round-trip on every refresh).
        var synthDocs = 0
        if (orphans > 0L) {
            synthDocs = runCatching {
                store.search(BENCH_SYNTH_PROBE, topK = SYNTH_PROBE_TOPK).size
            }.getOrDefault(0)
            orphans = (orphans - synthDocs).coerceAtLeast(0L)
        }

        // Holes: Room has a DISTINCT hash zvec doesn't (distinctIndexed > docCount). D4: previously
        // this also counted (processedCount > docCount), but that conflates duplicates (N rows, 1 hash
        // — NOT a hole) with genuinely-missing zvec docs. The processed-vs-distinct gap is a separate
        // signal: rows marked done with no hash — the dedup-skip-without-hash case D2 fixes going
        // forward, and the legacy rows that predate D2. Reported as unbridged, not as holes.
        val holes = (roomDistinctIndexed - docCount).coerceAtLeast(0L)
        val unbridgedProcessed = (roomProcessed - roomDistinctIndexed).coerceAtLeast(0)

        val drift = DriftMeter(
            zvecDocCount = docCount,
            roomIndexedCount = roomIndexed,
            roomDistinctIndexedCount = roomDistinctIndexed,
            roomProcessedCount = roomProcessed,
            roomUnprocessedCount = roomUnprocessed,
            unbridgedProcessedCount = unbridgedProcessed,
            orphansCount = orphans,
            holesCount = holes,
            statsReadable = state.statsError == null
        )

        val eventsList = ZvecEventRecorder.getEvents()

        Report(state, drift, eventsList)
    }

    private companion object {
        /**
         * The distinctive substring in the benchmark's synthetic-doc content
         * (ZvecBenchmarkRunner writes `content = "benchmark synthetic doc number $i ..."`). Used to
         * detect leftover synth docs from a killed benchmark run so they don't inflate the orphan
         * count (O1). A whitespace term so the production query shaper (`benchmark* synthetic*`) hits.
         */
        const val BENCH_SYNTH_PROBE = "benchmark synthetic"
        const val SYNTH_PROBE_TOPK = 100
    }

    suspend fun inspectQuery(queryText: String): List<ZvecDoc> = withContext(Dispatchers.Default) {
        val trimmed = queryText.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        // Apply the production query shape (O3) so the inspector matches what users see — without
        // this, a developer typing `wifi` sees fewer raw results than production (which sends `wifi*`).
        store.search(queryPreShaper(trimmed))
    }
}

/** Kotlin stdlib ships Pair/Triple but not Quad; private for the four-count batched read (O5/D4). */
private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
