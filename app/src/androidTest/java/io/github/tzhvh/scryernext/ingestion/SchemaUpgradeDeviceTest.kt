/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import io.github.tzhvh.scryernext.ScryerApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue 02 B4 — the schema-upgrade device gate, run against the REAL app corpus
 * (gated: `-e schemaUpgrade true`, else skipped). Two stages, invoked separately:
 *
 *  - **`upgrade`** — opens the app-scope store. On a v1 install (marker `1`,
 *    `SCHEMA_VERSION=2` in code) the B0 marker machinery must fire: wipe the v1
 *    dir, reset the Room queue (processed=0 + cache cleared), create the v2
 *    schema, write marker `2`. Asserts the outcome + counters.
 *  - **`verify`** — run AFTER the re-ingest (the app's own INDEX NOW banner flow,
 *    driven by the reset queue): the corpus is back (iterDocs count) and a
 *    FRAGMENT query through the real [ZvecContentStore.search] (Balanced fused)
 *    recalls docs — impossible on the v1 schema (a word tokenizer cannot match
 *    an interior fragment; only the ngram leg can), so non-empty == v2 proven.
 *
 * Stage 1: `am instrument … -e schemaUpgrade true -e class …SchemaUpgradeDeviceTest`
 * (methods selected via annotation per stage: `-e stage upgrade|verify`).
 */
@RunWith(AndroidJUnit4::class)
class SchemaUpgradeDeviceTest {

    private fun stage(): String? = InstrumentationRegistry.getArguments().getString("stage")

    @Test fun upgrade() = runBlocking {
        assumeTrue("skipped (gated)", InstrumentationRegistry.getArguments().getString("schemaUpgrade") == "true")
        assumeTrue(stage() == "upgrade")
        val store = ScryerApplication.getZvecContentStore()
        val docs = store.iterDocs() // first data-path call: marker check fires here
        val message = "SCHEMA UPGRADE[upgrade]: outcome='${store.lastOpenOutcome}' " +
            "wipes=${store.schemaWipesCount} docsAfterWipe=${docs.size}"
        Log.i("SchemaUpgrade", message)
        println(message)
        assertEquals(
            "marker mismatch must wipe-and-recreate",
            "wipe-recreated (schema v${ZvecContentStore.SCHEMA_VERSION})",
            store.lastOpenOutcome,
        )
        assertEquals("exactly one real wipe recorded", 1, store.schemaWipesCount)
    }

    @Test fun verify() = runBlocking {
        assumeTrue("skipped (gated)", InstrumentationRegistry.getArguments().getString("schemaUpgrade") == "true")
        assumeTrue(stage() == "verify")
        val store = ScryerApplication.getZvecContentStore()
        val docs = store.iterDocs(outputFields = listOf("content"))
        val message = "SCHEMA UPGRADE[verify]: docs=${docs.size} outcome='${store.lastOpenOutcome}'"
        Log.i("SchemaUpgrade", message)
        println(message)
        assertTrue("re-ingest did not repopulate the corpus (docs=${docs.size})", docs.isNotEmpty())

        // The v2 proof: an interior fragment ("etting" ← "settings") is recalled by the
        // real Balanced fused search. The v1 schema's word tokenizer cannot match it.
        val fragmentHits = store.search("etting", topK = 50)
        val fragmentMessage = "SCHEMA UPGRADE[fused fragment 'etting']: ${fragmentHits.size} hits"
        Log.i("SchemaUpgrade", fragmentMessage)
        println(fragmentMessage)
        assertTrue(
            "fragment query returned nothing — the ngram leg is missing (schema not v2?)",
            fragmentHits.isNotEmpty(),
        )
    }
}
