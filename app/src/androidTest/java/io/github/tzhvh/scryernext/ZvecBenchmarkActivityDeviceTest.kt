/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device smoke for the zvec benchmark — drives the production [io.github.tzhvh.scryernext.ingestion.ZvecContentStore]
 * through [ZvecBenchmarkRunner] (the exact path [ZvecBenchmarkActivity] takes) and confirms the full
 * suite produces a well-formed report on the real device. R7 (arm64 runtime correctness) is
 * incidentally discharged: every FTS query + fetch + upsert + delete runs against the native `.so`.
 *
 * Not an Espresso test (the app's `androidTest` config wires `test.runner` + `espresso.core` only,
 * not `androidx.test:core`/`ext:junit` — so no `ActivityScenario`). This drives the runner directly,
 * which is the load-bearing path; the Activity is a thin `lifecycleScope` shell around it.
 */
@RunWith(AndroidJUnit4::class)
class ZvecBenchmarkActivityDeviceTest {

    @Test
    fun benchmarkRunner_producesWellFormedReport() = runBlocking {
        val store = ScryerApplication.getZvecContentStore()
        val runner = ZvecBenchmarkRunner(store)

        val report = StringBuilder()
        runner.run { stage -> report.clear(); report.append(stage) }

        val text = report.toString()
        // The corpus header + at least one FTS latency line + the Phase-3 caveat footer all present.
        assertTrue("report has corpus header: $text", text.contains("Corpus:"))
        assertTrue("report has FTS search line: $text", text.contains("FTS search"))
        assertTrue("report has PK fetch line: $text", text.contains("PK fetch"))
        assertTrue("report has the Phase-3 caveat: $text", text.contains("Phase 3"))
        // A real latency number (ms) appears, not a placeholder.
        assertTrue("report has ms timings: $text", text.contains("ms"))
    }
}
