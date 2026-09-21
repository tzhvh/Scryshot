/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.search

/**
 * Phase 2.1 step 8 — the client-side snippet for the search list mode: a short window over the
 * doc's OCR `content`, centered on the first matched term, plus the case-insensitive highlight
 * ranges of every match term occurrence inside the window.
 *
 * R10's resolution made this the implementation: zvec FTS surfaces no match offsets on
 * `ZvecDoc`, so the window is cut client-side over the already-projected `content` — no new
 * engine I/O, and per the spec acceptable for topK=200. Client-side matching is literal
 * substring; the engine's stemmer can match morphology the highlight misses ("pricing" queried,
 * "priced" highlighted-no) — a known, accepted imprecision (offsets would be the optimization,
 * never a blocker).
 *
 * Pure Kotlin — JVM-testable, no Android text classes; the adapter maps [Snippet.ranges] onto a
 * SpannableString.
 */
object SnippetBuilder {

    data class Snippet(val text: String, val ranges: List<IntRange>)

    /**
     * Cut a snippet of at most [maxChars] characters. With any match, the window centers on the
     * FIRST match of any term; its edges back off to whitespace (no partial words). Without a
     * match, the prefix window. Every in-window occurrence of every term (case-insensitive) is a
     * highlight range, clamped to the window.
     */
    fun build(content: String, terms: List<String>, maxChars: Int = DEFAULT_MAX_CHARS): Snippet {
        if (content.isEmpty()) return Snippet("", emptyList())
        val cleanTerms = terms.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleanTerms.isEmpty() || maxChars <= 0) return Snippet(content.take(maxChars), emptyList())

        val lower = content.lowercase()
        val lowerTerms = cleanTerms.map { it.lowercase() }

        // First occurrence of any term anchors the window.
        val first = lowerTerms.asSequence()
            .map { lower.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()

        val (start, end) = if (first == null) {
            0 to content.take(maxChars).length.coerceAtMost(content.length)
        } else {
            var s = (first - maxChars / 2).coerceAtLeast(0)
            var e = (s + maxChars).coerceAtMost(content.length)
            s = (e - maxChars).coerceAtLeast(0)
            // Back the edges off to whitespace so words are never cut mid-token.
            if (e < content.length) {
                val space = content.lastIndexOf(' ', e)
                if (space > s) e = space
            }
            if (s > 0) {
                val space = content.indexOf(' ', s)
                if (space in 1 until e) s = space + 1
            }
            s to e
        }

        val text = content.substring(start, end)
        val ranges = ArrayList<IntRange>(2)
        for (term in lowerTerms) {
            var idx = lower.indexOf(term, start)
            while (idx >= 0 && idx < end) {
                val rangeStart = (idx - start).coerceAtLeast(0)
                val rangeEnd = (idx + term.length - start).coerceAtMost(text.length)
                if (rangeStart < rangeEnd) ranges.add(rangeStart until rangeEnd)
                idx = lower.indexOf(term, idx + term.length)
            }
        }
        return Snippet(text, ranges)
    }

    private const val DEFAULT_MAX_CHARS = 220
}
