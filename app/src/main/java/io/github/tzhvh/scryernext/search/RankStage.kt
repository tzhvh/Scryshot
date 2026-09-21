/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.search

import io.github.tzhvh.scryernext.persistence.ScreenshotModel

/**
 * Phase 2.1 — the sort seam between the search bridge and the UI (2.1-D1; design in
 * `docs/ZVEC_PHASE2.1.md` §The RankStage seam).
 *
 * Today BM25 rank is computed by the engine, bridged through
 * [io.github.tzhvh.scryernext.repository.ZvecScreenshotRepository], and then thrown away by a
 * hard-coded recency re-sort in the fragments. This stage is where ordering is decided instead:
 * the repository threads the engine score map out of the bridge (2.1-D7 — the score rides beside
 * the gallery rows, never on [ScreenshotModel]), applies the active [RankPolicy], and the fragments
 * render the list as returned. The seam is deliberately pure Kotlin — no native calls, no
 * dispatcher (the caller owns threading, ADR 0007) — so every policy is JVM-testable, and P4's
 * reranker/fusion extends it by feeding it a better score map, not a new UI.
 */
sealed interface RankPolicy {
    /**
     * Engine score only, strongest match first. The v0.7.0 pinned contract
     * (`ZvecFtsScoreAndNgramTest.bm25OrdersStrongerFtsMatchFirst`) is that `ZvecDoc.score` orders
     * **descending** — higher score = stronger BM25/fused match. Rows whose hash carries no score
     * (a null doc.score bridged to 0f) sink below scored ones.
     */
    data object Relevance : RankPolicy

    /**
     * Capture time only, newest first — the legacy fragment sort, kept as an explicit option for
     * regression parity rather than left as tyranny over every result set (2.1-D6).
     */
    data object Recency : RankPolicy

    /**
     * Score × time-decay, descending — the default. The product answer to "relevance vs recency"
     * that a binary toggle forces users to choose between: a match's contribution decays by half
     * every [halfLifeDays] since the screenshot's capture time, so fresher shots win near-ties and
     * strongly-matching older shots stay findable.
     *
     * The 30-day default is an uncalibrated guess (accepted residual risk in `ZVEC_PHASE2.1.md`);
     * calibrate against the bronze set's rank-bias metric rather than by feel.
     */
    data class Blended(val halfLifeDays: Float = DEFAULT_HALF_LIFE_DAYS) : RankPolicy {
        companion object {
            const val DEFAULT_HALF_LIFE_DAYS = 30f
        }
    }
}

/**
 * Applies a [RankPolicy] to bridged search results. [scores] is keyed by `content_hash` (the zvec
 * doc PK — the same key the repository bridges gallery rows by); rows sharing a hash (duplicates)
 * share a score. Implementations must return a new list, never mutate [results], and order
 * stably: rows the policy cannot distinguish (equal score, equal score-after-decay) keep their
 * incoming order — which is the engine's rank order upstream.
 */
interface RankStage {
    fun apply(
        results: List<ScreenshotModel>,
        scores: Map<String, Float>,
        policy: RankPolicy,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<ScreenshotModel>
}

/** The shipped [RankStage]. Three policies, each a single stable sort — nothing else. */
object DefaultRankStage : RankStage {

    override fun apply(
        results: List<ScreenshotModel>,
        scores: Map<String, Float>,
        policy: RankPolicy,
        nowMillis: Long,
    ): List<ScreenshotModel> = when (policy) {
        RankPolicy.Recency -> results.sortedByDescending { it.lastModified }
        RankPolicy.Relevance -> results.sortedByDescending { scores[it.contentHash] ?: 0f }
        is RankPolicy.Blended -> {
            val halfLife = policy.halfLifeDays
            results.sortedByDescending { row ->
                val score = scores[row.contentHash] ?: 0f
                val ageDays = ((nowMillis - row.lastModified).coerceAtLeast(0L)) / MILLIS_PER_DAY
                score * DECAY_BASE.pow(ageDays / halfLife)
            }
        }
    }

    private const val MILLIS_PER_DAY = 24f * 60f * 60f * 1000f
    private const val DECAY_BASE = 0.5f

    private fun Float.pow(exp: Float): Float = Math.pow(this.toDouble(), exp.toDouble()).toFloat()
}
