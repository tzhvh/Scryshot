/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2.1 step 5 — JVM unit tests for the [SearchFilters] chip state + filter-expression
 * builder. Pins the ZVEC_PHASE2.1.md Layer-1 list: collection `IN (...)` and date-range
 * expressions are well-formed, every interpolated value is escaped via
 * `ZvecFilters.escapeFilterValue`, and the empty filter set yields `null` (no push-down — the
 * store's `filter = null` default path).
 */
class SearchFiltersTest {

    @Test
    fun emptyFilters_yieldNullExpression_noPushDown() {
        assertTrue(SearchFilters().isEmpty)
        assertNull(SearchFilters().toFilterExpression())
    }

    @Test
    fun singleCollection_buildsInExpression() {
        val f = SearchFilters(collectionIds = linkedSetOf("col-7"))

        assertEquals("collection_id IN ('col-7')", f.toFilterExpression())
    }

    @Test
    fun multipleCollections_commaJoin_inInsertionOrder() {
        val f = SearchFilters(collectionIds = linkedSetOf("a", "b", "c"))

        assertEquals("collection_id IN ('a', 'b', 'c')", f.toFilterExpression())
    }

    @Test
    fun collectionValues_areEscapedViaEscapeFilterValue() {
        // A quote-carrying collection name must land as one closed literal (SQUOTA_STRING escape).
        val f = SearchFilters(collectionIds = linkedSetOf("O'Brien"))

        assertEquals("collection_id IN ('O\\'Brien')", f.toFilterExpression())
    }

    @Test
    fun fromOnly_buildsSingleLowerBoundClause() {
        val f = SearchFilters(fromMillis = 1_700_000_000_000)

        assertEquals("last_modified >= 1700000000000", f.toFilterExpression())
    }

    @Test
    fun bothBounds_buildRangeClause() {
        val f = SearchFilters(fromMillis = 100, toMillis = 200)

        assertEquals("last_modified >= 100 AND last_modified <= 200", f.toFilterExpression())
    }

    @Test
    fun collectionAndDate_andCompose_intoOneExpression() {
        val f = SearchFilters(collectionIds = linkedSetOf("col-1", "col-2"), fromMillis = 5, toMillis = 9)

        assertEquals(
            "collection_id IN ('col-1', 'col-2') AND last_modified >= 5 AND last_modified <= 9",
            f.toFilterExpression(),
        )
    }

    @Test
    fun withCollection_togglesMembership_withoutDuplicates() {
        var f = SearchFilters()
        f = f.withCollection("col-1", selected = true)
        assertEquals(setOf("col-1"), f.collectionIds)

        // Re-selecting an active id must not duplicate it.
        f = f.withCollection("col-1", selected = true)
        assertEquals(setOf("col-1"), f.collectionIds)

        f = f.withCollection("col-2", selected = true)
        f = f.withCollection("col-1", selected = false)
        assertEquals(setOf("col-2"), f.collectionIds)
        assertFalse(f.isEmpty)
    }

    @Test
    fun withDateRange_setsAndClearsBounds() {
        var f = SearchFilters().withDateRange(fromMillis = 10, toMillis = 20)
        assertEquals(10L, f.fromMillis)
        assertEquals(20L, f.toMillis)

        f = f.withDateRange(fromMillis = null, toMillis = null)
        assertNull(f.fromMillis)
        assertNull(f.toMillis)
        assertTrue(f.isEmpty)
    }
}
