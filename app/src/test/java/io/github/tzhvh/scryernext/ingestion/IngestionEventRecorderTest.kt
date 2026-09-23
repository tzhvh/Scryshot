/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [IngestionEventRecorder] — the debug per-item event ring behind the Ingestion
 * Inspector's event feed. Plain JUnit4, matching the ingestion test convention (the class has no
 * android dependency; the logcat mirror arrives as a lambda).
 */
class IngestionEventRecorderTest {

    private lateinit var recorder: IngestionEventRecorder

    @Before
    fun setUp() {
        recorder = IngestionEventRecorder()
    }

    @Test
    fun disabled_by_default_record_is_a_noop() {
        recorder.record { "should not appear" }
        assertTrue(recorder.getEvents().isEmpty())
    }

    @Test
    fun lazy_message_is_not_evaluated_when_disabled() {
        // The load-bearing gate property: the inline record skips string construction entirely
        // when the gate is off — this is what makes the release-build call sites free.
        var evaluated = false
        recorder.record { evaluated = true; "x" }
        assertFalse("message lambda must not run when disabled", evaluated)
    }

    @Test
    fun enabled_records_messages_in_order() {
        recorder.init(enabled = true)
        recorder.record { "A" }
        recorder.record { "B" }
        assertEquals(listOf("A", "B"), recorder.getEvents().map { it.message })
    }

    @Test
    fun ring_caps_at_100_and_evicts_the_oldest() {
        recorder.init(enabled = true)
        repeat(105) { recorder.record { "event-$it" } }
        val events = recorder.getEvents()
        assertEquals(100, events.size)
        assertEquals("event-5", events.first().message)
        assertEquals("event-104", events.last().message)
    }

    @Test
    fun clear_empties_the_ring() {
        recorder.init(enabled = true)
        recorder.record { "one" }
        recorder.clear()
        assertTrue(recorder.getEvents().isEmpty())
    }

    @Test
    fun mirror_receives_every_message_when_enabled() {
        val mirrored = mutableListOf<String>()
        recorder.init(enabled = true, mirror = { mirrored += it })
        recorder.record { "one" }
        recorder.record { "two" }
        assertEquals(listOf("one", "two"), mirrored)
    }

    @Test
    fun mirror_is_not_called_when_disabled() {
        val mirrored = mutableListOf<String>()
        recorder.init(enabled = false, mirror = { mirrored += it })
        recorder.record { "invisible" }
        assertTrue(mirrored.isEmpty())
    }

    @Test
    fun init_false_after_true_disables_again() {
        recorder.init(enabled = true)
        recorder.record { "kept" }
        recorder.init(enabled = false)
        recorder.record { "dropped" }
        assertEquals(listOf("kept"), recorder.getEvents().map { it.message })
    }

    @Test
    fun event_timestamps_are_captured() {
        recorder.init(enabled = true)
        val before = System.currentTimeMillis()
        recorder.record { "timed" }
        val event = recorder.getEvents().single()
        assertTrue(event.timestamp in before..System.currentTimeMillis())
        assertTrue(event.toString().contains("timed"))
    }
}
