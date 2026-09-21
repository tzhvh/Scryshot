/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion.bronze

/**
 * The bronze query set (issue 02 B2) — mechanically derived from the harvested
 * corpus. **No human curation anywhere**: queries are sampled from the corpus's own
 * vocabulary by token shape, and the expected result set for every query is defined
 * by case-insensitive substring containment over the corpus text (computable, checkable,
 * regenerable). This is the "bronze" standard the maintainer chose over hand-labeled
 * golden: containment is exactly the contract ngram is supposed to serve
 * (character-level findability), so it is the *right* label for the B3 decision —
 * opinion-free and stable across phases.
 *
 * Two arms, stratified by token shape (the only designed-in choice):
 *  - **ngram-sensitive**: PARTIAL-token queries — interior fragments of long tokens
 *    (the "type part of an identifier" case) and whole part-number-like tokens
 *    (≥4 chars with a digit). A word tokenizer structurally cannot recall the
 *    fragments; character-level matching can — this arm arbitrates B3.
 *  - **general**: common natural-language words — lowercase-only, length 5–9,
 *    present in ≥ 3 distinct documents. Both sane configs should do well here; it
 *    guards the ≤2pp-regression side of the B3 decision rule.
 *
 * Labels use case-insensitive containment (never stemmed/normalized match), so the
 * same labels judge every config.
 */
object BronzeQueryDeriver {

    data class CorpusDoc(val pk: String, val content: String)

    data class BronzeQuery(
        val id: String,
        val arm: String, // "ngram" | "general"
        val query: String,
        val expectedPks: List<String>,
    )

    private val tokenRe = Regex("[A-Za-z0-9]+")

    private fun tokens(content: String): List<String> = tokenRe.findAll(content).map { it.value }.toList()

    private fun hasCaseFlip(token: String): Boolean {
        val letters = token.filter { it.isLetter() }
        return letters.any { it.isUpperCase() } && letters.any { it.isLowerCase() }
    }

    /**
     * An interior window of a longer token — a **partial-token query**. Strictly interior
     * (never the whole token, never touching either edge), which is the point: a word
     * tokenizer structurally cannot recall it, only character-level matching can.
     */
    private fun fragment(token: String): String {
        val window = minOf(6, token.length - 2)
        val start = 1 + (token.length - 2 - window) / 2
        return token.substring(start, start + window)
    }

    private fun isPartNumberLike(token: String): Boolean =
        token.any { it.isDigit() } && token.length >= 4

    /**
     * Derive the query set. `perArm` caps each arm (evenly-spaced sample over the
     * frequency-sorted candidates — deterministic, no RNG). Tokens are sampled with
     * their containing-document set; expected = every doc whose content contains the
     * token case-insensitively (superset of the sampling docs).
     */
    fun derive(docs: List<CorpusDoc>, perArm: Int = 25): List<BronzeQuery> {
        require(docs.isNotEmpty()) { "empty corpus" }
        val lowerContents = docs.map { it.pk to it.content.lowercase() }

        // docFreq per lowered token + the docs that contain it (sample source).
        data class Cand(val token: String, val samplingDocs: Set<String>)

        val seen = HashMap<String, MutableSet<String>>() // lowered token -> pks whose TOKENS contain it
        docs.forEach { doc ->
            tokens(doc.content).map { it.lowercase() }.toSet().forEach { tok ->
                seen.getOrPut(tok) { mutableSetOf() }.add(doc.pk)
            }
        }

        val candidates = seen.map { (tok, pks) -> Cand(tok, pks) }

        fun label(token: String): List<String> =
            lowerContents.filter { (_, c) -> c.contains(token) }.map { (pk, _) -> pk }

        /**
         * ngram arm: **partial-token queries** — interior fragments of long tokens (the
         * "type part of an identifier" case), plus whole part-number-like tokens (≥4 chars
         * with a digit — typing a number whole is the real user behavior). Long plain words
         * are excluded: a standard tokenizer handles them, so they measure nothing here.
         * A word tokenizer structurally scores ~0 on the fragments; ngram's lift shows here.
         */
        fun buildNgramArm(): List<BronzeQuery> {
            data class NG(val query: String, val samplingDocs: Set<String>)

            val picked = candidates.mapNotNull { c ->
                when {
                    isPartNumberLike(c.token) -> NG(c.token, c.samplingDocs)
                    c.token.length >= 8 -> NG(fragment(c.token), c.samplingDocs)
                    else -> null
                }
            }.distinctBy { it.query }
            val filtered = picked.sortedWith(compareByDescending<NG> { it.samplingDocs.size }.thenBy { it.query })
            val chosen = if (filtered.size <= perArm) filtered
            else (0 until perArm).map { idx -> filtered[idx * filtered.size / perArm] }
            return chosen.map { cand ->
                BronzeQuery(
                    id = "ngram-${cand.query}",
                    arm = "ngram",
                    query = cand.query,
                    expectedPks = label(cand.query).sorted(),
                )
            }
        }

        fun buildGeneralArm(): List<BronzeQuery> {
            val filtered = candidates
                .filter { c -> c.token.length in 5..9 && c.token.all { it.isLowerCase() } && c.samplingDocs.size >= 3 }
                .sortedWith(compareByDescending<Cand> { it.samplingDocs.size }.thenBy { it.token })
            val chosen = if (filtered.size <= perArm) filtered
            else (0 until perArm).map { idx -> filtered[idx * filtered.size / perArm] }
            return chosen.map { cand ->
                BronzeQuery(
                    id = "general-${cand.token}",
                    arm = "general",
                    query = cand.token,
                    expectedPks = label(cand.token).sorted(),
                )
            }
        }

        return buildNgramArm() + buildGeneralArm()
    }
}
