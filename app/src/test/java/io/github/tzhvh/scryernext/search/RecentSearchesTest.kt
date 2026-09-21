/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2.1 step 9 — JVM tests for [RecentSearches], the prefs-backed last-N store shown on the
 * empty-query state (the FTS half of P6's saved-views primitive, 2.1-D5).
 *
 * Pins the ZVEC_PHASE2.1.md Layer-1 list: last-N eviction, dedup (a repeat moves the entry to
 * the front, it never duplicates), and the persistence round-trip through the string store.
 * Newlines are impossible in entries (the search field is singleLine), which is what makes the
 * newline-joined single-string persistence safe.
 */
class RecentSearchesTest {

    private class MemoryStore : RecentSearches.PrefsStore {
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) {
            this.value = value
        }
    }

    @Test
    fun record_prependsMostRecent_andRoundTripsThroughStore() {
        val store = MemoryStore()
        val recent = RecentSearches(store)

        recent.record("receipt")
        recent.record("wifi password")

        assertEquals(listOf("wifi password", "receipt"), RecentSearches(store).entries())
    }

    @Test
    fun record_dedup_movesExistingEntryToFront() {
        val store = MemoryStore()
        val recent = RecentSearches(store)
        recent.record("receipt")
        recent.record("wifi")
        recent.record("receipt")

        assertEquals(listOf("receipt", "wifi"), recent.entries())
        assertEquals(listOf("receipt", "wifi"), RecentSearches(store).entries())
    }

    @Test
    fun record_evictsBeyondLastN() {
        val store = MemoryStore()
        val recent = RecentSearches(store, maxEntries = 3)
        recent.record("a")
        recent.record("b")
        recent.record("c")
        recent.record("d")

        assertEquals(listOf("d", "c", "b"), recent.entries())
    }

    @Test
    fun record_evictionResurfacesEvictedEntriesCorrectly() {
        val store = MemoryStore()
        val recent = RecentSearches(store, maxEntries = 2)
        recent.record("a")
        recent.record("b")
        recent.record("c")
        recent.record("a") // "a" was evicted; re-recording must not resurrect "b"

        assertEquals(listOf("a", "c"), recent.entries())
    }

    @Test
    fun record_blankQueries_andNewlines_areRejected() {
        val store = MemoryStore()
        val recent = RecentSearches(store)

        recent.record("")
        recent.record("   ")
        recent.record("\n")

        assertTrue(recent.entries().isEmpty())
        assertTrue(store.value.isNullOrBlank())
    }

    @Test
    fun entries_trimsAndIgnoresGarbageSegments_fromTheStore() {
        val store = MemoryStore()
        store.value = "receipt\n\n  wifi \n"

        assertEquals(listOf("receipt", "wifi"), RecentSearches(store).entries())
    }

    @Test
    fun clear_emptiesStoreAndMemory() {
        val store = MemoryStore()
        val recent = RecentSearches(store)
        recent.record("receipt")
        recent.clear()

        assertTrue(recent.entries().isEmpty())
        assertTrue(RecentSearches(store).entries().isEmpty())
    }
}
