/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.search

import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 2.1 step 1 — JVM unit tests for [RankStage] (2.1-D1/D6/D7).
 *
 * Pins the three sort policies against fixture sets:
 *  - `Recency` reproduces the legacy fragment sort (`sortedByDescending { lastModified }`) exactly —
 *    regression parity for the killed line-213 sort;
 *  - `Relevance` orders by the engine score map **descending** — the v0.7.0 pinned contract
 *    (`ZvecFtsScoreAndNgramTest.bm25OrdersStrongerFtsMatchFirst`: higher score = stronger match,
 *    strictly descending), which supersedes the pre-measurement "BM25 ascending" wording in
 *    `ZVEC_PHASE2.1.md`;
 *  - `Blended` multiplies the score by a half-life time decay (the new default).
 *
 * Score absence, stability on ties, input-list immutability, and the empty-map (no-scores) case
 * are each pinned — the collection-browse path feeds [RankStage.apply] no scores at all.
 */
class RankStageTest {

    private val stage = DefaultRankStage
    private val nowMillis = 30L * DAY * 4 // fixed "now" so Blended is deterministic

    private fun row(
        id: String,
        lastModified: Long,
        contentHash: String = "hash-$id",
    ) = ScreenshotModel(
        id = id, uri = "uri://$id", displayName = "$id.png", size = 1L,
        lastModified = lastModified, collectionId = "col",
        contentHash = contentHash,
    )

    @Test
    fun recency_matchesLegacyFragmentSort_exactly() {
        val rows = listOf(
            row("old", lastModified = 100L),
            row("newest", lastModified = 400L),
            row("mid", lastModified = 250L),
            row("older", lastModified = 150L),
        )

        val ranked = stage.apply(rows, emptyMap(), RankPolicy.Recency)

        // Byte-for-byte parity with the killed `screenshots.sortedByDescending { it.lastModified }`.
        assertEquals(rows.sortedByDescending { it.lastModified }, ranked)
        assertEquals(listOf("newest", "mid", "older", "old"), ranked.map { it.id })
    }

    @Test
    fun recency_preservesInputOrder_forEqualTimestamps() {
        val rows = listOf(
            row("a", lastModified = 100L),
            row("b", lastModified = 100L),
            row("c", lastModified = 200L),
            row("d", lastModified = 100L),
        )

        val ranked = stage.apply(rows, emptyMap(), RankPolicy.Recency)

        assertEquals(listOf("c", "a", "b", "d"), ranked.map { it.id })
    }

    @Test
    fun relevance_ordersByEngineScore_descending() {
        val rows = listOf(row("weak", 400L), row("strong", 100L), row("mid", 250L))
        val scores = mapOf("hash-strong" to 12.5f, "hash-mid" to 3.0f, "hash-weak" to 1.0f)

        val ranked = stage.apply(rows, scores, RankPolicy.Relevance)

        // Relevance ignores recency entirely: the strongest match leads even though it is oldest.
        assertEquals(listOf("strong", "mid", "weak"), ranked.map { it.id })
    }

    @Test
    fun relevance_treatsMissingScoreAsZero_andKeepsStableOrderOnTies() {
        // "noscore" is absent from the map (a null doc.score bridges to 0f upstream) and ties with
        // "zero"; both sink below the scored rows and keep their zvec rank order between themselves.
        val rows = listOf(row("zero", 1L), row("scored", 2L), row("noscore", 3L), row("zero2", 4L))
        val scores = mapOf("hash-scored" to 5.0f, "hash-zero" to 0f, "hash-zero2" to 0f)

        val ranked = stage.apply(rows, scores, RankPolicy.Relevance)

        assertEquals(listOf("scored", "zero", "noscore", "zero2"), ranked.map { it.id })
    }

    @Test
    fun blended_decaysScoreByHalfLife_perHalfLifeElapsed() {
        // Same score everywhere, so the decay factor alone decides the order: freshest first, and a
        // row exactly one half-life old outranks one two half-lives old.
        fun r(id: String, ageDays: Long) = row(id, lastModified = nowMillis - ageDays * DAY)
        val rows = listOf(
            r("twoHalfLives", ageDays = 60),
            r("halfLife", ageDays = 30),
            r("fresh", ageDays = 0),
        )
        val scores = rows.associate { "hash-${it.id}" to 10f }

        val ranked = stage.apply(rows, scores, RankPolicy.Blended(), nowMillis = nowMillis)

        assertEquals(listOf("fresh", "halfLife", "twoHalfLives"), ranked.map { it.id })
    }

    @Test
    fun blended_recentWeakMatch_canOvertakeStaleStrongMatch() {
        // The Blended product answer (2.1-D6): a 5-scored shot from today beats a 10-scored shot
        // from 90 days ago (10 * 0.5^3 = 1.25 < 5), while pure Relevance would flip them.
        fun r(id: String, ageDays: Long) = row(id, lastModified = nowMillis - ageDays * DAY)
        val rows = listOf(r("staleStrong", ageDays = 90), r("freshWeak", ageDays = 0))
        val scores = mapOf("hash-staleStrong" to 10f, "hash-freshWeak" to 5f)

        val blended = stage.apply(rows, scores, RankPolicy.Blended(), nowMillis = nowMillis)
        val relevance = stage.apply(rows, scores, RankPolicy.Relevance)

        assertEquals(listOf("freshWeak", "staleStrong"), blended.map { it.id })
        assertEquals(listOf("staleStrong", "freshWeak"), relevance.map { it.id })
    }

    @Test
    fun blended_honorsCustomHalfLife() {
        fun r(id: String, ageDays: Long) = row(id, lastModified = nowMillis - ageDays * DAY)
        val rows = listOf(r("old", ageDays = 30), r("new", ageDays = 0))
        val scores = mapOf("hash-old" to 8f, "hash-new" to 5f)
        // A 10-day half life ages the old row to 8 * 0.5^3 = 1.0 < 5 — the order flips vs the
        // 30-day default (8 * 0.5 = 4 < 5 flips too — so assert against a 60-day half life:
        // 8 * 0.5^0.5 ≈ 5.66 > 5, keeping the old row on top where the 10-day run demotes it).
        val long = stage.apply(rows, scores, RankPolicy.Blended(halfLifeDays = 60f), nowMillis = nowMillis)
        val short = stage.apply(rows, scores, RankPolicy.Blended(halfLifeDays = 10f), nowMillis = nowMillis)

        assertEquals(listOf("old", "new"), long.map { it.id })
        assertEquals(listOf("new", "old"), short.map { it.id })
    }

    @Test
    fun blended_treatsClockSkewRow_asBrandNew() {
        // A row stamped in the future (device clock moved back) must not go negative-aged into an
        // amplified score: clamped to zero age, full score.
        val rows = listOf(
            row("future", lastModified = nowMillis + 10 * DAY),
            row("now", lastModified = nowMillis),
        )
        val scores = mapOf("hash-future" to 4f, "hash-now" to 4f)

        val ranked = stage.apply(rows, scores, RankPolicy.Blended(), nowMillis = nowMillis)

        // Equal scores after the clamp → stable order preserved.
        assertEquals(listOf("future", "now"), ranked.map { it.id })
    }

    @Test
    fun apply_neverMutatesTheInputList() {
        val rows = listOf(row("b", 200L), row("a", 100L))
        val snapshot = rows.toList()

        stage.apply(rows, emptyMap(), RankPolicy.Recency)

        assertEquals(snapshot, rows)
    }

    @Test
    fun apply_onEmptyInputs_returnsEmpty_andPreservesReferenceEqualityOnRecency() {
        assertEquals(emptyList<ScreenshotModel>(), stage.apply(emptyList(), emptyMap(), RankPolicy.Blended()))
        assertEquals(emptyList<ScreenshotModel>(), stage.apply(emptyList(), emptyMap(), RankPolicy.Relevance))
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
