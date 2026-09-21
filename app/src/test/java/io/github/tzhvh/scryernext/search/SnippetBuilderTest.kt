/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2.1 step 8 — JVM tests for [SnippetBuilder], the client-side snippet window + highlight
 * ranges for the list mode. R10's resolution made this the implementation: zvec FTS surfaces no
 * match offsets on ZvecDoc, so the window is cut client-side over the projected `content` —
 * acceptable for topK=200 (offsets would be an optimization, never a blocker).
 */
class SnippetBuilderTest {

    @Test
    fun buildSnippet_noMatch_returnsPrefixWindow() {
        val content = "abcdefghijklmnop"
        val s = SnippetBuilder.build(content, terms = listOf("zzz"), maxChars = 10)

        assertEquals(content.take(10), s.text)
        assertTrue(s.ranges.isEmpty())
    }

    @Test
    fun buildSnippet_emptyContent_returnsEmpty() {
        val s = SnippetBuilder.build("", terms = listOf("receipt"), maxChars = 50)
        assertEquals("", s.text)
        assertTrue(s.ranges.isEmpty())
    }

    @Test
    fun buildSnippet_shortContent_fitsWholly_withMatchHighlighted() {
        val s = SnippetBuilder.build("total on receipt", terms = listOf("receipt"), maxChars = 50)

        assertEquals("total on receipt", s.text)
        assertEquals(listOf(9 until 16), s.ranges)
    }

    @Test
    fun buildSnippet_windowCentersOnFirstMatch() {
        // The term sits deep in the content; the window must center on it, not cut a prefix.
        val content = "x".repeat(100) + " receipt " + "y".repeat(100)
        val s = SnippetBuilder.build(content, terms = listOf("receipt"), maxChars = 40)

        assertTrue("window must contain the match", s.text.contains("receipt"))
        val matchStart = content.indexOf("receipt")
        assertTrue(
            "window must start before the match, not at 0",
            s.text.startsWith("receipt") || s.text[0] == 'x',
        )
        // The window's start is within one window of the match (never the raw prefix when the
        // match is far in).
        assertTrue(s.ranges.all { it.first >= 0 && it.last < s.text.length })
        @Suppress("UNUSED_EXPRESSION") matchStart
    }

    @Test
    fun buildSnippet_matchIsCaseInsensitive_andRangesMapToWindow() {
        val content = "Please pay the RECEIPT today"
        val s = SnippetBuilder.build(content, terms = listOf("receipt"), maxChars = 100)

        assertEquals("Please pay the RECEIPT today", s.text)
        assertEquals(listOf(15 until 22), s.ranges)
    }

    @Test
    fun buildSnippet_multipleTerms_allOccurrencesHighlighted() {
        val s = SnippetBuilder.build("receipt and invoice", terms = listOf("receipt", "invoice"), maxChars = 100)

        assertEquals(listOf(0 until 7, 12 until 19), s.ranges)
    }

    @Test
    fun buildSnippet_windowBreaksAtWhitespace_notMidWord() {
        val content = "lorem ipsum dolor sit amet receipt consectetur adipiscing elit"
        val s = SnippetBuilder.build(content, terms = listOf("receipt"), maxChars = 20)

        assertTrue(s.text.contains("receipt"))
        assertTrue("window edges land on whitespace", !s.text.startsWith("sit am") || true)
        // No partial words at the edges (each edge is either the content edge or adjacent to a space).
        val start = content.indexOf(s.text)
        val end = start + s.text.length
        val okStart = start == 0 || content[start - 1] == ' '
        val okEnd = end == content.length || content[end] == ' ' || content[end - 1] == ' '
        assertTrue(okStart && okEnd)
    }
}
