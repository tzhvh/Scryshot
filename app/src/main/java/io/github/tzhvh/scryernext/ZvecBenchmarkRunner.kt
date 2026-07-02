/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext

import io.github.tzhvh.scryernext.ingestion.ZvecContentStore
import io.github.tzhvh.scryernext.zvec.ZvecValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * zvec FTS benchmark — measures what the Phase-2 collection actually has.
 *
 * Phase 2's zvec schema is FTS-only (the `content` field). The vector/HNSW field (`image_embedding`,
 * where zvec's headline "thousands of QPS" lives) does not exist until Phase 3, so this runner
 * deliberately measures **FTS query latency, PK fetch, and upsert throughput** — not vector search.
 * The screen surfaces this caveat verbatim so no one infers these numbers validate the vector-QPS
 * marketing claim.
 *
 * ## What it measures (all via the production [ZvecContentStore])
 *
 * 1. **Corpus size** — `docCount()`, the context every latency number needs (50 docs ≠ 5,000 docs).
 * 2. **FTS query** — `search(q, topK)` across a fixed query set + a topK sweep (10 / 50 / 200).
 * 3. **PK fetch** — `fetch(hash)` for a known doc (the `getContentText` read path).
 * 4. **Upsert + flush** — synthetic docs on known PKs, cleaned up via `deleteAll` in `finally`.
 *
 * ## Timing discipline
 *
 * The SDK exposes no `took_ms` (verified — `ZvecDoc`/`CollectionStats` carry no timing). All timings
 * are wall-clock `System.nanoTime()` wrapped around the `ZvecContentStore` method, so each captures
 * IO-dispatch + JNI + native + result-unpack together — the honest full-stack cost a user-facing
 * query pays. A JIT warmup pass precedes every measured run.
 *
 * ## Why this is pure-Kotlin + JVM-testable
 *
 * The percentile/QPS math ([percentiles], [formatLatency]) is static and tested without the `.so`.
 * The data-path calls go through [ZvecContentStore], whose methods are `open` so a JVM test fakes
 * them with canned timings. The on-device run is the only path that touches native.
 */
class ZvecBenchmarkRunner(private val store: ZvecContentStore) {

    /** A latency sample set, summarized into percentiles + a derived single-thread QPS. */
    data class LatencyResult(
        val label: String,
        val samplesMs: List<Double>,
        /**
         * The first sample, surfaced separately (PROFILING_FRAMEWORK.md §3.1 sharpened-C2): the runner
         * warms the page cache before measuring, so [samplesMs] p50/mean are page-cache-*hit* figures.
         * The very first measured call still pays whatever disk I/O the warmup didn't prime (a full
         * corpus isn't hot after 10 throwaway queries), so [coldMs] is the honest "user opens the app
         * after hours" figure. Reported alongside the warm p50 to correct the systematic
         * under-representation of cold-start cost. Empty sample set → 0.0.
         */
        val coldMs: Double = if (samplesMs.isEmpty()) 0.0 else samplesMs.first(),
    ) {
        val count: Int get() = samplesMs.size
        val meanMs: Double get() = if (samplesMs.isEmpty()) 0.0 else samplesMs.sum() / samplesMs.size
        /** Derived single-thread queries-per-second: 1000 / mean. */
        val qps: Double get() = if (meanMs == 0.0) 0.0 else 1000.0 / meanMs

        fun p(percentile: Double): Double = percentiles(samplesMs, percentile)
    }

    /**
     * The write-throughput stage, split per FW1 (PROFILING_FRAMEWORK.md §3.1 / ZVEC_BENCHMARK.md C1):
     * [upsertPerDoc] times only the bare `upsert(...)` calls (no flush) — the engine's amortized write
     * cost — and [flushMs] times a **single** `flush()` of the batch. The old single combined number
     * was fsync-dominated (50 per-doc flushes ≈ pure I/O) and hid the lever that matters (flush on
     * batch completion vs per-file). [flushMs] of 0.0 means the flush was skipped (empty corpus / error).
     */
    data class WriteResult(
        val upsertPerDoc: LatencyResult,
        val flushMs: Double,
        val flushedDocCount: Int,
    )

    /** The full suite output, formatted for the screen. */
    data class Report(
        val docCount: Long,
        val searchByTopK: List<LatencyResult>,
        val fetch: LatencyResult,
        val write: WriteResult,
    )

    /**
     * Run the full suite. [onStage] is invoked after each stage with the partial text so the screen
     * fills in live rather than freezing until completion. Runs on [Dispatchers.Default] (CPU-bound
     * percentile math + the store self-relocates its native calls to IO).
     */
    suspend fun run(onStage: (String) -> Unit): Report = withContext(Dispatchers.Default) {
        val sb = StringBuilder()
        fun stage(text: String) { sb.append(text); onStage(sb.toString()) }

        val docCount = store.docCount()
        stage(header(docCount))

        // ── FTS search across the topK sweep ────────────────────────────────────────────────
        val queries = DEFAULT_QUERIES
        val searchResults = ArrayList<LatencyResult>(TOPK_SWEEP.size)
        for (topK in TOPK_SWEEP) {
            // Warmup (JIT + page cache) — discard timings.
            repeat(WARMUP_ITERATIONS) { store.search(queries[it % queries.size], topK) }
            val samples = ArrayList<Double>(SEARCH_ITERATIONS)
            repeat(SEARCH_ITERATIONS) {
                val q = queries[it % queries.size]
                val ns = measureNs { store.search(q, topK) }
                samples.add(ns / 1_000_000.0)
            }
            val r = LatencyResult("FTS search (topK=$topK)", samples)
            searchResults.add(r)
            stage(sb.toString() + formatLatency(r) + "\n")
        }

        // ── PK fetch: need a real content_hash from the corpus ──────────────────────────────
        // Pull one from a search result (the first matched doc's pk). If the corpus is empty
        // (no matches), the fetch stage reports "skipped — empty corpus".
        val fetchResult = run {
            val seedDoc = store.search(queries.first(), TOPK_SWEEP.last()).firstOrNull()
            if (seedDoc == null) {
                LatencyResult("PK fetch (skipped — empty corpus)", emptyList())
            } else {
                repeat(WARMUP_ITERATIONS) { store.fetch(seedDoc.pk) }
                val samples = ArrayList<Double>(FETCH_ITERATIONS)
                repeat(FETCH_ITERATIONS) {
                    val ns = measureNs { store.fetch(seedDoc.pk) }
                    samples.add(ns / 1_000_000.0)
                }
                LatencyResult("PK fetch (1 doc)", samples)
            }
        }
        stage(sb.toString() + formatLatency(fetchResult) + "\n")

        // ── Upsert + flush throughput: synthetic docs, cleaned up in finally ────────────────
        // FW1 (PROFILING_FRAMEWORK.md §3.1): measure bare upsert (no flush) per doc, then a single
        // flush of the whole batch. The old combined number was fsync-dominated; this isolates the
        // engine's amortized write cost from durability cost — the lever the Phase-2 PRD names
        // ("flush on batch completion" vs per-file).
        val synthPks = (0 until UPSERT_BATCH).map { "bench-synth-%04d".format(it) }
        val writeResult = run {
            val samples = ArrayList<Double>(UPSERT_BATCH)
            var flushMs = 0.0
            try {
                repeat(UPSERT_BATCH) { i ->
                    val ns = measureNs {
                        store.upsert(
                            contentHash = synthPks[i],
                            locator = "content://bench/$i",
                            content = "benchmark synthetic doc number $i searchable text",
                            collectionId = "bench",
                        )
                    }
                    samples.add(ns / 1_000_000.0)
                }
                // One durability flush for the whole batch — this is the fsync cost, now separable.
                flushMs = measureNs { store.flush() } / 1_000_000.0
            } finally {
                runCatching { store.deleteAll(synthPks) }
            }
            WriteResult(
                upsertPerDoc = LatencyResult("Bare upsert (batch of $UPSERT_BATCH, no flush)", samples),
                flushMs = flushMs,
                flushedDocCount = UPSERT_BATCH,
            )
        }
        stage(sb.toString() + formatWrite(writeResult) + "\n")

        stage(sb.toString() + FOOTER)
        Report(docCount, searchResults, fetchResult, writeResult)
    }

    // ── formatting helpers (static, JVM-tested) ─────────────────────────────────────────────

    private fun header(docCount: Long): String =
        "zvec FTS benchmark\n" +
        "────────────────────────────\n" +
        "Corpus: $docCount docs\n\n"

    private fun formatLatency(r: LatencyResult): String =
        if (r.samplesMs.isEmpty()) {
            "  ${r.label}: (no samples)\n"
        } else {
            "  ${r.label}\n" +
            "    p50: ${fmt(r.p(0.50))} ms   p99: ${fmt(r.p(0.99))} ms   mean: ${fmt(r.meanMs)} ms\n" +
            "    cold-first: ${fmt(r.coldMs)} ms   QPS: ~${fmt(r.qps)}/s   (n=${r.count}, single-thread)\n"
        }

    private fun formatWrite(w: WriteResult): String {
        val upsertLine = formatLatency(w.upsertPerDoc).prependIndent("  ")
        val flushLine = if (w.flushMs <= 0.0) {
            "    flush: (skipped)\n"
        } else {
            "    flush (one call, ${w.flushedDocCount} docs): ${fmt(w.flushMs)} ms\n"
        }
        return "  Write throughput (FW1 split)\n" + upsertLine + flushLine
    }

    private fun fmt(v: Double): String = "%.1f".format(v)

    private companion object {
        const val WARMUP_ITERATIONS = 10
        const val SEARCH_ITERATIONS = 60
        const val FETCH_ITERATIONS = 60
        const val UPSERT_BATCH = 50

        /** The topK values to sweep — 10 (focused), 50, 200 (the production default). */
        val TOPK_SWEEP = listOf(10, 50, 200)

        /**
         * A fixed query set reusing the production `processQuery` wildcard shape ("wifi* bluetooth*").
         * Varied terms so the FTS path exercises different postings, not the same one repeatedly.
         */
        val DEFAULT_QUERIES = listOf(
            "settings*", "wifi*", "bluetooth*", "chat*", "screenshot*",
            "password*", "email*", "calendar*", "map*", "search*",
        )

        const val FOOTER =
            "\nPhase 2 measures FTS only. Vector (HNSW) search — zvec's headline QPS claim —\n" +
            "needs the Phase 3 image_embedding field and is benchmarked then.\n"
    }
}

// ── module-private timing + percentile helpers (tested via LatencyResult) ───────────────────

/** Measure wall-clock nanoseconds of [block]. Suspends are accounted for (the await is in the block). */
suspend inline fun measureNs(crossinline block: suspend () -> Unit): Long {
    val start = System.nanoTime()
    block()
    return System.nanoTime() - start
}

/**
 * Percentile via nearest-rank on a sorted copy. [p] is in [0.0, 1.0]. Empty input → 0.0.
 * Pure function; the JVM test covers the edge cases (empty, single sample, p0/p100, odd/even counts).
 */
fun percentiles(samples: List<Double>, p: Double): Double {
    if (samples.isEmpty()) return 0.0
    if (samples.size == 1) return samples[0]
    val sorted = samples.sorted()
    val rank = (p * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
    return sorted[rank]
}
