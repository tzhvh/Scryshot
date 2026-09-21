/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

import io.github.tzhvh.scryernext.zvec.CollectionOptions
import io.github.tzhvh.scryernext.zvec.CollectionSchema
import io.github.tzhvh.scryernext.zvec.ZvecCollection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * zvec Phase B, issue 02 B0 — JVM tests for the schema-marker mechanism in [ZvecContentStore]:
 * the pure wipe decision, the marker-file round-trip, and the open-vs-wipe branch through a
 * subclass fake (the established JVM seam — [ZvecWriteSinkTest] fakes the same store).
 *
 * The fake's [ZvecContentStore.openExisting]/[createNew] hooks record the call and throw
 * [NativeBoundary] (the real hooks need the `.so`, and `ZvecCollection`'s constructor is
 * `internal`). This pins everything up to the native boundary: the branch taken, the wipe
 * ordering (dir deleted → queue reset → create attempted), and that the marker is written only
 * *after* a successful create. The create→write tail itself is two production lines; it is
 * exercised on-device by the B4 bump validation (clean-install re-ingest).
 */
class ZvecContentStoreSchemaMarkerTest {

    /** The abort the fake hooks throw instead of crossing into the native world. */
    private class NativeBoundary(where: String) : RuntimeException(where)

    private class FakeStore(
        filesDir: File,
        val resetCalls: MutableList<String>,
    ) : ZvecContentStore(filesDir, debug = false, onSchemaWipe = { resetCalls.add("reset") }) {
        private val baseDir: File = filesDir
        var openCalls = 0
        var createCalls = 0

        override suspend fun initializeZvec() {
            // no native init on the JVM
        }

        override suspend fun openExisting(path: File, options: CollectionOptions): ZvecCollection {
            openCalls++
            throw NativeBoundary("openExisting")
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

        fun markerFile(): File = File("${baseDir.path}/zvec/screenshots.version")

        fun collectionDir(): File = File(baseDir, "zvec/screenshots")

        fun writeMarkerDirect(content: String) {
            markerFile().writeText(content)
        }

        fun seedCollectionDir() {
            collectionDir().mkdirs()
            File(collectionDir(), "engine-file.db").writeText("pretend-engine-state")
        }

        fun readMarkerRoundTrip(): String? = readSchemaMarker()

        fun writeMarkerRoundTrip() = writeSchemaMarker()
    }

    private fun newStore(): Pair<FakeStore, File> {
        val filesDir = Files.createTempDirectory("zvec-marker-test").toFile()
        val resetCalls = mutableListOf<String>()
        return FakeStore(filesDir, resetCalls) to filesDir
    }

    // ---- the pure decision (companion, disk-free) ----

    @Test fun markerStateOpensOnlyOnExactCurrentVersion() {
        val v = ZvecContentStore.SCHEMA_VERSION
        assertEquals(SchemaMarkerState.Open, ZvecContentStore.markerState(v.toString(), v))
        assertEquals(SchemaMarkerState.Open, ZvecContentStore.markerState(" $v\n", v))
        assertEquals(SchemaMarkerState.Wipe, ZvecContentStore.markerState(null, v))
        assertEquals(SchemaMarkerState.Wipe, ZvecContentStore.markerState("", v))
        assertEquals(SchemaMarkerState.Wipe, ZvecContentStore.markerState("${v + 1}", v))
        assertEquals(SchemaMarkerState.Wipe, ZvecContentStore.markerState("0", v))
        assertEquals(SchemaMarkerState.Wipe, ZvecContentStore.markerState("garbage", v))
        // The marker is written as the canonical decimal string; a padded variant is a foreign
        // format, not a match — wipe is the safe direction.
        assertEquals(SchemaMarkerState.Wipe, ZvecContentStore.markerState("0$v", v))
    }

    // ---- the file helpers round-trip ----

    @Test fun markerFileRoundTripsThroughTheRealHelpers() {
        val (store, _) = newStore()
        assertEquals(null, store.readMarkerRoundTrip())
        store.writeMarkerRoundTrip()
        assertEquals(ZvecContentStore.SCHEMA_VERSION.toString(), store.readMarkerRoundTrip())
        assertEquals(SchemaMarkerState.Open, ZvecContentStore.markerState(store.readMarkerRoundTrip(), ZvecContentStore.SCHEMA_VERSION))
    }

    // ---- branch selection through a real openOrCreate drive ----

    @Test fun legacyDirWithoutMarkerIsWipedAndQueueResetRuns() = runBlocking {
        val (store, _) = newStore()
        val resetCalls = store.resetCalls
        store.seedCollectionDir()

        try {
            store.driveOpen()
            throw AssertionError("expected NativeBoundary")
        } catch (expected: NativeBoundary) {
            assertEquals("createNew", expected.message)
        }

        assertFalse("collection dir deleted before the create boundary", store.collectionDir().exists())
        assertEquals("queue reset ran in the same operation", 1, resetCalls.size)
        assertEquals("open never attempted on an unmarked dir", 0, store.openCalls)
        assertTrue("marker written only AFTER a successful create", !store.markerFile().exists())
    }

    @Test fun matchingMarkerWithDirPresentOpensWithoutReset() = runBlocking {
        val (store, _) = newStore()
        val resetCalls = store.resetCalls
        store.seedCollectionDir()
        store.writeMarkerDirect(ZvecContentStore.SCHEMA_VERSION.toString())

        try {
            store.driveOpen()
            throw AssertionError("expected NativeBoundary")
        } catch (expected: NativeBoundary) {
            assertEquals("openExisting", expected.message)
        }

        assertEquals("wipe branch never ran", 0, store.createCalls)
        assertEquals("queue untouched on the open path", 0, resetCalls.size)
        assertTrue("collection dir intact", store.collectionDir().exists())
        assertEquals("marker untouched", ZvecContentStore.SCHEMA_VERSION.toString(), store.markerFile().readText())
    }

    @Test fun matchingMarkerWithMissingDirSelfHealsThroughTheWipeBranch() = runBlocking {
        val (store, _) = newStore()
        val resetCalls = store.resetCalls
        store.writeMarkerDirect(ZvecContentStore.SCHEMA_VERSION.toString())
        assertFalse(store.collectionDir().exists())

        try {
            store.driveOpen()
            throw AssertionError("expected NativeBoundary")
        } catch (expected: NativeBoundary) {
            assertEquals("createNew", expected.message)
        }

        assertEquals("marker-but-no-dir recreates, never opens nothing", 0, store.openCalls)
        assertEquals("reset ran with the recreate", 1, resetCalls.size)
    }

    @Test fun staleMarkerVersionWipesEvenWhenDirExists() = runBlocking {
        val (store, _) = newStore()
        val resetCalls = store.resetCalls
        store.seedCollectionDir()
        store.writeMarkerDirect("${ZvecContentStore.SCHEMA_VERSION - 1}")

        try {
            store.driveOpen()
            throw AssertionError("expected NativeBoundary")
        } catch (expected: NativeBoundary) {
            assertEquals("createNew", expected.message)
        }

        assertEquals("stale marker never opens", 0, store.openCalls)
        assertEquals("stale marker wipes + resets", 1, resetCalls.size)
        assertFalse(store.collectionDir().exists())
    }
}
