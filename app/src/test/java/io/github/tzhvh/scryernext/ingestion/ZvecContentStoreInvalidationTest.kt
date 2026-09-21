/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

import io.github.tzhvh.scryernext.zvec.CollectionOptions
import io.github.tzhvh.scryernext.zvec.CollectionSchema
import io.github.tzhvh.scryernext.zvec.ZvecCollection
import io.github.tzhvh.scryernext.zvec.ZvecErrorCode
import io.github.tzhvh.scryernext.zvec.ZvecException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 2.1 hard-cutover invalidation — JVM tests for the open-failure fallthrough.
 *
 * Maintainer doctrine (no users, no back-compat): zvec is a **derived index** over the Room
 * corpus, so an existing collection that cannot be opened — poisoned idmap, corrupt manifest,
 * unopenable LOCK — is destroyed and rebuilt, never surfaced as a permanently dead search.
 * The ladder: lock-recovery repairs first (stale-LOCK delete / missing-LOCK recreate); any
 * [ZvecException] that still escapes falls through to ONE invalidation pass (dir + marker
 * deleted → the marker-absent wipe branch: queue reset + createNew + marker write). If the
 * rebuild itself fails, that failure propagates — no loop.
 *
 * Pinned here against the exact failure shape the 2026-09-21 device gate hit
 * (`recovery idmap failed` after a LOCK repair).
 */
class ZvecContentStoreInvalidationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** The abort the fake hooks throw instead of crossing into the native world. */
    private class NativeBoundary(where: String) : RuntimeException(where)

    private class CorruptStore(
        baseDir: File,
        val resetCalls: MutableList<String>,
    ) : ZvecContentStore(baseDir, debug = false, onSchemaWipe = { resetCalls.add("reset") }) {
        private val root: File = baseDir
        var openCalls = 0
        var createCalls = 0

        override suspend fun initializeZvec() {}

        override suspend fun openExisting(path: File, options: CollectionOptions): ZvecCollection {
            openCalls++
            throw ZvecException(
                ZvecErrorCode.INTERNAL_ERROR,
                "recovery idmap failed, path: $path/idmap.0",
            )
        }

        override suspend fun createNew(
            path: File,
            schema: CollectionSchema,
            options: CollectionOptions,
        ): ZvecCollection {
            createCalls++
            throw NativeBoundary("createNew")
        }

        suspend fun driveOpen() = openOrCreate()

        fun seedCorruptState() {
            // Marker matches (v3) + a dir that "exists" but whose engine state is poison.
            val dir = File(root, "zvec/screenshots").apply { mkdirs() }
            File(dir, "idmap.0").writeText("poison")
            File(root, "zvec/screenshots.version").writeText("3")
        }

        fun seededEngineFile(): File = File(root, "zvec/screenshots/idmap.0")
    }

    @Test
    fun unrecoverableOpen_invalidates_once_andRebuildsThroughTheWipeBranch() = runBlocking {
        val resets = mutableListOf<String>()
        val store = CorruptStore(tmp.newFolder(), resets)
        store.seedCorruptState()

        val thrown = runCatching { store.driveOpen() }.exceptionOrNull()

        // The rebuild ran to the native boundary (createNew) — the open failure did NOT
        // propagate out of openOrCreate.
        assertTrue("expected the rebuild's boundary, got $thrown", thrown is NativeBoundary)
        assertEquals("one open attempt, no open retries", 1, store.openCalls)
        assertEquals("exactly one rebuild attempt (no loop)", 1, store.createCalls)
        assertEquals("the wipe branch ran the ingestion queue reset", listOf("reset"), resets)
        assertFalse("the poisoned dir was destroyed", store.seededEngineFile().exists())
    }

    @Test
    fun rebuildFailure_propagates_withoutSecondInvalidation() = runBlocking {
        val resets = mutableListOf<String>()
        val store = CorruptStore(tmp.newFolder(), resets)
        store.seedCorruptState()

        val thrown = runCatching { store.driveOpen() }.exceptionOrNull()

        // If createNew itself fails, that error surfaces — exactly one invalidation pass.
        assertTrue(thrown is NativeBoundary)
        assertEquals("no second wipe/rebuild cycle", 1, store.createCalls)
        assertEquals(1, resets.size)
    }
}
