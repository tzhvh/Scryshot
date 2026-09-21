/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion.triggers

import androidx.work.WorkInfo
import io.github.tzhvh.scryernext.ingestion.Progress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the cross-process "is a session pending?" decision — [isPending], the pure
 * combination rule extracted from [IngestionSession.isSessionPending] (issue 14b AC: "the
 * `isSessionPending` combination logic … is unit-testable as pure functions").
 *
 * The [Progress]/[WorkInfo.State] inputs are plain data/enums, so this runs on the JVM without
 * Robolectric/mockk — matching the repo convention (issue 12's `bulkResultDecision`, issue 13's
 * `shouldNotify`). The Flow/Prefs/WorkInfo plumbing itself is deferred to Phase 4 device smoke.
 *
 * ### What the rule says
 *
 * [isPending] is the **union** of two non-wedgeable sources (ADR 0004 §7.6):
 * - in-memory `progress is Indexing` — a session active *now, in this process* (on-open case).
 * - WorkInfo `RUNNING`/`ENQUEUED` — a bulk job pending *across process death* (bulk case).
 *
 * The matrix below covers both halves, the resurrection gap, and the terminal combinations
 * (a terminal Progress with a terminal WorkInfo state must read *not* pending).
 */
class IngestionSessionPendingTest {

    // ==========================================================================
    // In-memory half: progress is Indexing → pending regardless of WorkInfo
    // (the on-open case — active now, in this process, no WM job required).
    // ==========================================================================

    @Test
    fun indexing_progress_with_null_workinfo_is_pending() {
        // The on-open case: in-process, no bulk job ever enqueued. The union's in-memory half
        // alone makes it pending.
        assertTrue(isPending(Progress.Indexing(1, 5, 0), null))
    }

    @Test
    fun indexing_progress_with_terminal_workinfo_is_pending() {
        // During the resurrection window the in-memory half is Indexing (worker re-entered) while
        // WorkInfo may briefly read a stale state — the in-memory half wins (active now).
        assertTrue(isPending(Progress.Indexing(2, 5, 0), WorkInfo.State.SUCCEEDED))
    }

    // ==========================================================================
    // WorkInfo half: Idle progress + RUNNING/ENQUEUED → pending (the bulk case,
    // cross-process — survives process death via WorkInfo, §7.6).
    // ==========================================================================

    @Test
    fun idle_progress_with_running_workinfo_is_pending() {
        // The cross-process bulk case: process died and resurrected, in-memory reset to Idle,
        // but WorkInfo still RUNNING — the banner must still show pending (no stuck state).
        assertTrue(isPending(Progress.Idle, WorkInfo.State.RUNNING))
    }

    @Test
    fun idle_progress_with_enqueued_workinfo_is_pending() {
        // Bulk job queued (e.g. waiting for constraints) — pending even though not yet active.
        assertTrue(isPending(Progress.Idle, WorkInfo.State.ENQUEUED))
    }

    // ==========================================================================
    // Quiescent / terminal: nothing pending.
    // ==========================================================================

    @Test
    fun idle_progress_with_null_workinfo_is_not_pending() {
        // Nothing ever ran. The system's natural resting state.
        assertFalse(isPending(Progress.Idle, null))
    }

    @Test
    fun completed_progress_with_succeeded_workinfo_is_not_pending() {
        // Normal terminal: the bulk job succeeded. Banner clears.
        assertFalse(isPending(Progress.Completed(5, 0, 5), WorkInfo.State.SUCCEEDED))
    }

    @Test
    fun aborted_progress_with_cancelled_workinfo_is_not_pending() {
        // User hit Stop: worker cancelled (WorkInfo CANCELLED) + store Aborted. Banner clears.
        assertFalse(isPending(Progress.Aborted, WorkInfo.State.CANCELLED))
    }

    @Test
    fun error_progress_with_failed_workinfo_is_not_pending() {
        // Genuine failure: both halves terminal. (In practice a retried job may flip back to
        // ENQUEUED — that's covered by idle+ENQUEUED above; here we assert the terminal pair.)
        assertFalse(isPending(Progress.Error(RuntimeException()), WorkInfo.State.FAILED))
    }

    @Test
    fun idle_progress_with_terminal_workinfo_states_is_not_pending() {
        // In-memory half reset to Idle after resurrection; WorkInfo terminal. Both at rest.
        // Catches a regression where a terminal WorkInfo state would falsely read pending.
        assertFalse(isPending(Progress.Idle, WorkInfo.State.SUCCEEDED))
        assertFalse(isPending(Progress.Idle, WorkInfo.State.FAILED))
        assertFalse(isPending(Progress.Idle, WorkInfo.State.CANCELLED))
    }
}
