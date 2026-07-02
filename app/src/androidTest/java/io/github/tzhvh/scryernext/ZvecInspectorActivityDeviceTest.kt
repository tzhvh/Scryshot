/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0 with a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import io.github.tzhvh.scryernext.repository.processFtsQuery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device smoke for the zvec Runtime Inspector — drives the production
 * [io.github.tzhvh.scryernext.ingestion.ZvecContentStore] + production Room [ScreenshotDao] through
 * [ZvecInspectorRunner] (the exact path [ZvecInspectorActivity] takes) and confirms the report is
 * well-formed on the real device. R7 (arm64 runtime correctness) is incidentally discharged: the
 * `stats()` read + the Room count queries all run against the native `.so` / SQLite on-device.
 *
 * Not an Espresso test (the app's `androidTest` config wires `test.runner` + `espresso.core` only,
 * not `androidx.test:core`/`ext:junit` — so no `ActivityScenario`). This drives the runner directly,
 * which is the load-bearing path; the Activity is a thin `lifecycleScope` shell around it.
 *
 * Mirrors [ZvecBenchmarkActivityDeviceTest]'s structure deliberately — same instrumentation pattern,
 * same "drive the runner, assert on the report" shape.
 */
@RunWith(AndroidJUnit4::class)
class ZvecInspectorActivityDeviceTest {

    @Test
    fun inspectorRunner_producesWellFormedReport() = runBlocking {
        val store = ScryerApplication.getZvecContentStore()
        val dao = ScryerApplication.getScreenshotDao()
        // O3: pass the production query shaper so the inspector matches what users see.
        val runner = ZvecInspectorRunner(store, dao, queryPreShaper = ::processFtsQuery)

        val report = runner.getReport()

        // State snapshot: the store opened (or stats failed loudly — B2 makes that visible, not silent).
        // Either stats read cleanly (docCount present) or statsError is non-null (surfaced failure).
        assertTrue(
            "state must be readable or report a stats error, not silently null",
            report.state.statsError != null || report.state.docCount >= 0L
        )

        // Drift meter: the four counts are present (Room queries ran on-device).
        assertTrue("drift has roomIndexedCount: ${report.drift}", report.drift.roomIndexedCount >= 0)
        assertTrue("drift has distinctIndexedCount: ${report.drift}", report.drift.roomDistinctIndexedCount >= 0)
        assertTrue("drift has processedCount: ${report.drift}", report.drift.roomProcessedCount >= 0)
        assertTrue("drift has unprocessedCount: ${report.drift}", report.drift.roomUnprocessedCount >= 0)

        // statsReadable agrees with state.statsError — the two views of "did stats() succeed" match.
        assertTrue(
            "drift.statsReadable must agree with state.statsError",
            report.drift.statsReadable == (report.state.statsError == null)
        )

        // Event recorder is wired (the recorder is gated; ScryerApplication.init enabled it on debug).
        // No assertion on event count — a fresh process may have zero events; just confirm it doesn't throw.
        assertNotNull("events list is non-null", report.events)
    }
}
