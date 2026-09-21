/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.search

/**
 * Phase 2.1 step 9 — the persisted last-N list of submitted queries, shown on the search
 * screen's empty-query state (`ZVEC_PHASE2.1.md` §Recent searches; folded-in per 2.1-D5).
 *
 * This is the ancestor primitive of P6's saved deterministic views — a named query, re-runnable
 * — minus the pin and the feed. Shipping it now means P6 extends an existing surface rather than
 * introducing one.
 *
 * **Persistence shape:** one newline-joined string in SharedPreferences (the spec's "prefs —
 * cheap, no schema" pick; a Room table would be the fourth persistence system for metadata no
 * current UI reads). Newlines cannot occur inside an entry because the search field is
 * singleLine — and entries are re-trimmed and garbage-segments dropped on read, so a legacy or
 * hand-edited store degrades to fewer entries, never a crash.
 *
 * Pure Kotlin + a one-method storage seam ([PrefsStore]) so eviction/dedup is JVM-testable
 * without Robolectric; the production store wraps SharedPreferences.
 */
class RecentSearches(
    private val store: PrefsStore,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    /** The storage seam: one nullable string. Production: SharedPreferences-backed. */
    interface PrefsStore {
        fun read(): String?
        fun write(value: String)
    }

    /**
     * Record a submitted query: newest-first, deduped (a repeat moves to the front — it never
     * duplicates), evicting beyond [maxEntries]. Blank entries are rejected: the search field is
     * singleLine, so a newline here is corrupt input, not a query.
     */
    fun record(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || trimmed.contains('\n')) return
        val updated = listOf(trimmed) + entries().filter { it != trimmed }
        store.write(updated.take(maxEntries).joinToString(SEPARATOR))
    }

    /** The current entries, newest first. Tolerant of blank/garbage segments in the store. */
    fun entries(): List<String> =
        store.read()
            ?.split(SEPARATOR)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    /** Drop every entry (the empty-query view's Clear affordance). */
    fun clear() {
        store.write("")
    }

    private companion object {
        const val SEPARATOR = "\n"
        const val DEFAULT_MAX_ENTRIES = 8
    }
}
