/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 2.1 step 7 — JVM tests for the FTS query pre-shape (`processFtsQuery` / `parseFtsQueryParts`).
 *
 * Pins the R9-resolved split contract as UI-reachable syntax (2.1-D3):
 *  - **Exclusion ships**: a whitespace token starting with `-` becomes an engine exclusion
 *    (`-term`, no wildcard) appended after the positive terms — honored on the match-string path
 *    per `ZvecR8R9ContractProbesTest`. A dash must be the token's first char to exclude; mid-token
 *    dashes keep splitting terms exactly as the Room-era pre-shape did.
 *  - **Default path unchanged** (regression): quotes and asterisks are still stripped, terms are
 *    space-joined with the legacy single trailing wildcard (the shipped `joinToString(" ", "", "*")`
 *    shape — last positive term carries the `*`). The positive shape is NOT touched here: the B5
 *    bronze floor was measured through it.
 *  - Separator-only input (`"---"`) shapes to EMPTY (not the legacy `"*"`, which matched the whole
 *    corpus) — the repository short-circuits blank shapes to a zero-results submission.
 */
class ProcessFtsQueryTest {

    // ---- default path (regression parity with the Room-era pre-shape) ----

    @Test
    fun plainTerms_joinWithSpace_trailingWildcardOnly_legacyShape() {
        assertEquals("hello world*", processFtsQuery("hello world"))
    }

    @Test
    fun singleTerm_getsTheWildcard() {
        assertEquals("receipt*", processFtsQuery("receipt"))
    }

    @Test
    fun quotesAndAsterisks_areStrippedOnThePositivePath() {
        assertEquals("total $53.42*", processFtsQuery("\"total $53.42\""))
        assertEquals("wifi*", processFtsQuery("wifi*"))
    }

    @Test
    fun midTokenDash_splitsTerms_asBefore() {
        assertEquals("well known*", processFtsQuery("well-known"))
    }

    @Test
    fun whitespaceRuns_collapse() {
        assertEquals("a b*", processFtsQuery("  a   b  "))
    }

    // ---- exclusion path (new; R9-pinned engine contract) ----

    @Test
    fun attachedDashToken_becomesWildcardlessExclusion() {
        assertEquals("receipt* -invoice", processFtsQuery("receipt -invoice"))
    }

    @Test
    fun multipleExclusions_appendInOrder() {
        assertEquals("receipt* -invoice -total", processFtsQuery("receipt -invoice -total"))
    }

    @Test
    fun exclusionToken_isCleanedOfQuotesAsterisksAndInnerDashes() {
        assertEquals("receipt* -inv -oice", processFtsQuery("receipt -inv-oice"))
        assertEquals("quick* -brown", processFtsQuery("quick -\"brown\""))
        assertEquals("quick* -foxy", processFtsQuery("quick -foxy*"))
    }

    @Test
    fun bareDashToken_isSkipped_notAnExclusion() {
        // "receipt - invoice" reads as a dash separator, not an exclusion: the exclusion syntax is
        // the ATTACHED form (the shape R9 probed). invoice stays positive.
        assertEquals("receipt invoice*", processFtsQuery("receipt - invoice"))
    }

    @Test
    fun bareExclusion_passesThrough_engineReturnsNothing_perR9() {
        // Exclusion needs a positive anchor (R9 pinned): the bare exclusion reaches the engine
        // verbatim and matches nothing — the zero-results state explains, the engine stays
        // authoritative.
        assertEquals("-invoice", processFtsQuery("-invoice"))
    }

    // ---- degenerate input ----

    @Test
    fun separatorOnlyInput_shapesToEmpty_notWildcardAll() {
        // Legacy behavior shaped "---" to "*" (match everything); a term-free submission now
        // shapes to blank, which the repository short-circuits to zero results.
        assertEquals("", processFtsQuery("---"))
        assertEquals("", processFtsQuery("\" * \""))
    }

    @Test
    fun blankInput_shapesToEmpty() {
        assertEquals("", processFtsQuery(""))
        assertEquals("", processFtsQuery("   "))
    }

    // ---- the parsed-parts view (same shapes, structured) ----

    @Test
    fun parse_parts_splitPositivesFromExclusions() {
        val parts = parseFtsQueryParts("receipt -invoice extra -total")
        assertEquals(listOf("receipt", "extra"), parts.positives)
        assertEquals(listOf("invoice", "total"), parts.exclusions)
    }
}
