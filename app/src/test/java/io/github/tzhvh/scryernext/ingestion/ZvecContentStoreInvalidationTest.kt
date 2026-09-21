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
        val root: File = baseDir

        /** Null = always fail; set = succeed after N failures (the idmap rung healed it). */
        var openSucceedsAfter: Int? = null
        var openCalls = 0
        var createCalls = 0

        override suspend fun initializeZvec() {}

        override suspend fun openExisting(path: File, options: CollectionOptions): ZvecCollection {
            openCalls++
            val succeedAfter = openSucceedsAfter
            if (succeedAfter != null && openCalls > succeedAfter) {
                // The retry after the artifact deletion: abort at the native boundary — the
                // JVM cannot construct a ZvecCollection. Reaching THIS error proves the rung
                // deleted the artifact and re-attempted the open (the retry's distinct shape).
                throw NativeBoundary("openExisting retry after idmap removal")
            }
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
        // propagate out of openOrCreate. Ladder order: the idmap rung deleted the artifact and
        // retried the open once (the retry failed again — the fake always fails), THEN the
        // invalidation wiped and rebuilt.
        assertTrue("expected the rebuild's boundary, got $thrown", thrown is NativeBoundary)
        assertEquals("open retried once by the rung, then invalidated", 2, store.openCalls)
        assertEquals("exactly one rebuild attempt (no loop)", 1, store.createCalls)
        assertEquals("the wipe branch ran the ingestion queue reset", listOf("reset"), resets)
        assertFalse("the poisoned dir was destroyed", store.seededEngineFile().exists())
    }

    @Test
    fun idmapRecoveryRung_deletesArtifact_retriesOpen_andSucceeds() = runBlocking {
        val resets = mutableListOf<String>()
        val store = CorruptStore(tmp.newFolder(), resets)
        store.seedCorruptState()
        store.openSucceedsAfter = 1 // the SECOND open is attempted (fresh idmap recreated)

        val thrown = runCatching { store.driveOpen() }.exceptionOrNull()

        assertTrue("expected the retry's boundary, got $thrown",
            thrown is NativeBoundary && thrown.message!!.contains("idmap removal"))
        assertEquals("first open failed, the rung deleted the artifact and retried",
            2, store.openCalls)
        assertEquals("the idmap.0 artifact was deleted for the retry", false, File(
            store.root, "zvec/screenshots/idmap.0",
        ).exists())
        assertEquals("no queue reset — no invalidation happened", 0, resets.size)
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
