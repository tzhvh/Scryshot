/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SafProducerFakeTest {

    @Test
    fun testEmitsExactlyOneCandidatePerUriInOrder() = runBlocking {
        val uris = listOf("uri1", "uri2", "uri3")
        val fake = SafProducerFake(uris, { it.toByteArray() })
        val candidates = fake.candidates().toList()

        assertEquals(3, candidates.size)
        assertEquals("uri1", candidates[0].locator)
        assertEquals("uri2", candidates[1].locator)
        assertEquals("uri3", candidates[2].locator)
    }

    @Test
    fun testCandidatePropertiesAndByteHandle() = runBlocking {
        val uri = "testUri"
        val expectedBytes = "hello".toByteArray()
        val fake = SafProducerFake(listOf(uri), { expectedBytes })
        val candidate = fake.candidates().toList().first()

        assertEquals(uri, candidate.locator)
        assertNull(candidate.identity)

        val stream = candidate.byteHandle()
        val actualBytes = stream.readBytes()
        assertTrue(expectedBytes.contentEquals(actualBytes))
    }

    @Test
    fun testTakePersistableUriPermissionHappensBeforeEmit() = runBlocking {
        val uris = listOf("uri1", "uri2")
        val takenList = mutableListOf<String>()
        val fake = SafProducerFake(uris, { it.toByteArray() }, takenList)

        // As candidates are emitted, check that they were added to takenList
        var index = 0
        fake.candidates().collect { candidate ->
            assertTrue(takenList.contains(candidate.locator))
            // Ensure order of permission taking matches/precedes emission
            assertEquals(candidate.locator, takenList[index])
            index++
        }
    }

    @Test
    fun testLimitExceededFailsFast() {
        val largeUris = (1..129).map { "uri$it" }
        try {
            SafProducerFake(largeUris, { it.toByteArray() })
            fail("Expected constructor to throw IllegalArgumentException due to 128 limit")
        } catch (e: IllegalArgumentException) {
            assertEquals("SAF 128-URI limit exceeded", e.message)
        }
    }

    @Test
    fun testFiniteColdFlowReCollects() = runBlocking {
        val uris = listOf("uri1")
        val takenList = mutableListOf<String>()
        val fake = SafProducerFake(uris, { it.toByteArray() }, takenList)

        // First collection
        val run1 = fake.candidates().toList()
        assertEquals(1, run1.size)
        assertEquals(1, takenList.size)

        // Second collection
        val run2 = fake.candidates().toList()
        assertEquals(1, run2.size)
        assertEquals(2, takenList.size) // permissions taken again
    }
}
