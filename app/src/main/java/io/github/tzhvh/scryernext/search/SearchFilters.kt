/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.search

import io.github.tzhvh.scryernext.ingestion.ZvecContentStore
import io.github.tzhvh.scryernext.zvec.ZvecFilters

/**
 * Phase 2.1 step 5 — the filter-chip state model + push-down expression builder
 * (`ZVEC_PHASE2.1.md` §Query model / §Filter UX). Pure Kotlin, JVM-testable; one instance per
 * search surface, held by the UI and composed into the single SQL-subset string that
 * [io.github.tzhvh.scryernext.ingestion.ZvecContentStore.search] pushes down.
 *
 * Chips AND-compose (spec: "chips AND together into one expression"). The collection chip is the
 * first production caller of `ZvecFilters.escapeFilterValue` — collection ids are user-derived
 * strings, interpolated raw into a filter, so every value MUST go through the escaper. Timestamps
 * interpolate as bare numeric literals (unparseable into anything but a number). An empty state
 * builds `null`, which maps to the store's no-push-down default — "no filters" must be
 * indistinguishable from "filters never existed" to the engine.
 *
 * The date-range state lives here from the start (step 6 consumes it), but the date chip's UI is
 * gated on the `last_modified` add + COMPLETED backfill (R8: a filter silently hides null rows —
 * the one hard ordering in this phase).
 */
data class SearchFilters(
    val collectionIds: Set<String> = emptySet(),
    val fromMillis: Long? = null,
    val toMillis: Long? = null,
) {

    /** True when nothing is selected — the "chips row hidden / no push-down" state. */
    val isEmpty: Boolean
        get() = collectionIds.isEmpty() && fromMillis == null && toMillis == null

    /** Toggle one collection chip. Re-selecting an active id must not duplicate it. */
    fun withCollection(id: String, selected: Boolean): SearchFilters = copy(
        collectionIds = if (selected) collectionIds + id else collectionIds - id,
    )

    /** Set both date bounds at once (null = unbounded on that side); clears when both are null. */
    fun withDateRange(fromMillis: Long?, toMillis: Long?): SearchFilters = copy(
        fromMillis = fromMillis,
        toMillis = toMillis,
    )

    /**
     * The push-down filter expression for this state, or `null` when nothing is selected.
     *
     * Clause order is fixed (collections, then bounds) so the same state always builds the same
     * string — tests and logs can pin it. Bounds are NOT checked for `from <= to`: an inverted
     * range reaches the engine and matches nothing, which surfaces through the zero-results-with-
     * filters state (the one-tap relax), not a validation error.
     */
    fun toFilterExpression(): String? {
        if (isEmpty) return null
        val clauses = ArrayList<String>(3)
        if (collectionIds.isNotEmpty()) {
            val values = collectionIds.joinToString(SEPARATOR) { ZvecFilters.escapeFilterValue(it) }
            clauses.add("${ZvecContentStore.FIELD_COLLECTION_ID} IN ($values)")
        }
        fromMillis?.let { clauses.add("${ZvecContentStore.FIELD_LAST_MODIFIED} >= $it") }
        toMillis?.let { clauses.add("${ZvecContentStore.FIELD_LAST_MODIFIED} <= $it") }
        return clauses.joinToString(" AND ")
    }

    private companion object {
        const val SEPARATOR = ", "
    }
}
