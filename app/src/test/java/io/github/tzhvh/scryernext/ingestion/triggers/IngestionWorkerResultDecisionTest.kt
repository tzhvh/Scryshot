/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion.triggers

import io.github.tzhvh.scryernext.ingestion.Progress
import io.github.tzhvh.scryernext.ingestion.StageTimings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the §5 result contract — [bulkResultDecision], the load-bearing
 * mapping from a terminal [Progress] to a [WorkResultDecision] (issue 12 AC #8).
 *
 * The WM + `setForeground` + ML Kit path cannot run on the JVM (no Robolectric in this
 * project), so only the *pure* decision function is unit-testable — which is exactly
 * the part that resolves ADR 0003's contradiction (§5 rejected retry-for-continuation).
 * A pure function makes the contract's branches explicit and documented, rather than
 * buried in a worker's `doWork`.
 *
 * Plain JUnit4 — matches the repo convention (no `kotlinx-coroutines-test`, no mockk).
 */
class IngestionWorkerResultDecisionTest {

    // ==========================================================================
    // §5: normal completion → SUCCESS (the keystone rule)
    // ==========================================================================

    @Test
    fun completed_normal_returns_success() {
        // All candidates processed (indexed), some may have failed but work is done.
        val decision = bulkResultDecision(
            Progress.Completed(indexed = 5, failed = 0, total = 5, stageTimings = StageTimings())
        )
        assertEquals(WorkResultDecision.SUCCESS, decision)
    }

    @Test
    fun completed_with_some_failures_still_returns_success() {
        // §7.2: transient failures are counted toward completion, not parked forever.
        val decision = bulkResultDecision(
            Progress.Completed(indexed = 4, failed = 1, total = 5, stageTimings = StageTimings())
        )
        assertEquals(WorkResultDecision.SUCCESS, decision)
    }

    @Test
    fun completed_with_zero_candidates_returns_success() {
        // Empty backlog: nothing to do, not an error.
        val decision = bulkResultDecision(
            Progress.Completed(indexed = 0, failed = 0, total = 0, stageTimings = StageTimings())
        )
        assertEquals(WorkResultDecision.SUCCESS, decision)
    }

    // --------------------------------------------------------------------------
    // The §5 keystone: "more files remain" is NOT retry — dedup resumes.
    // (There is no "moreFilesRemain" flag on Progress; this test documents the
    // invariant that a Completed is a success regardless of absolute counts — the
    // *next* discovery/app-open re-derives the unindexed set and continues.)
    // --------------------------------------------------------------------------
    @Test
    fun completed_with_partially_indexed_large_run_returns_success_not_retry() {
        // A run that completed but indexed fewer than the original backlog (dedup skipped
        // some, or transient failures left some unprocessed). §5: dedup-as-resumption means
        // this is a SUCCESS — NOT a checkpoint-style continuation retry.
        val decision = bulkResultDecision(
            Progress.Completed(indexed = 40, failed = 2, total = 42, stageTimings = StageTimings())
        )
        assertEquals(WorkResultDecision.SUCCESS, decision)
    }

    // ==========================================================================
    // §5: whole-run transient failure → RETRY (the one retry case for completion)
    // ==========================================================================

    @Test
    fun completed_with_every_candidate_transient_failed_returns_retry() {
        // The shipped engine maps ML Kit UNAVAILABLE to a per-file TransientFailure, so a
        // fully-unavailable run surfaces as Completed(indexed=0, failed=total) — NOT as
        // Progress.Error. This branch honours the *intent* of issue 12's "whole-run
        // transient → retry" table row. See the discrepancy note on bulkResultDecision.
        val decision = bulkResultDecision(
            Progress.Completed(indexed = 0, failed = 842, total = 842, stageTimings = StageTimings())
        )
        assertEquals(WorkResultDecision.RETRY, decision)
    }

    @Test
    fun completed_with_single_indexed_out_of_all_failed_returns_success() {
        // One success breaks the "whole-run failed" condition → normal success. Guards
        // against an off-by-one in the all-failed predicate (strict `<`, not `<=`).
        val decision = bulkResultDecision(
            Progress.Completed(indexed = 1, failed = 841, total = 842, stageTimings = StageTimings())
        )
        assertEquals(WorkResultDecision.SUCCESS, decision)
    }

    // ==========================================================================
    // Progress.Error → RETRY (write/store failure is re-attemptable, §7.2)
    // ==========================================================================

    @Test
    fun error_from_write_sink_returns_retry() {
        // The engine surfaces a write/store failure (Insert threw, disk full) as
        // Progress.Error. It is re-attemptable → retry, NOT failure().
        val decision = bulkResultDecision(
            Progress.Error(java.io.IOException("disk full"))
        )
        assertEquals(WorkResultDecision.RETRY, decision)
    }

    @Test
    fun error_from_any_throwable_returns_retry() {
        val decision = bulkResultDecision(
            Progress.Error(RuntimeException("transient binder death"))
        )
        assertEquals(WorkResultDecision.RETRY, decision)
    }

    // ==========================================================================
    // Defensive / non-terminal → SUCCESS (cancellation is handled in doWork, not here)
    // ==========================================================================

    @Test
    fun idle_returns_success_defensively() {
        // No terminal Progress collected (empty edge). A safe success is correct: dedup
        // means nothing was lost. Cancellation (Aborted) is handled separately in doWork.
        assertEquals(WorkResultDecision.SUCCESS, bulkResultDecision(Progress.Idle))
    }

    @Test
    fun aborted_returns_success_defensively() {
        // Aborted is handled in doWork's CancellationException branch (store.abort() then
        // rethrow → WM CANCELLED). It should never reach bulkResultDecision in practice;
        // the defensive SUCCESS documents that the decision fn is not the cancel path.
        assertEquals(WorkResultDecision.SUCCESS, bulkResultDecision(Progress.Aborted))
    }

    @Test
    fun paused_returns_success_defensively() {
        // Paused (issue 14) is presentation state, not a terminal; handled elsewhere.
        assertEquals(
            WorkResultDecision.SUCCESS,
            bulkResultDecision(Progress.Paused(sessionStartTotal = 100, doneCount = 30))
        )
    }

    @Test
    fun indexing_returns_success_defensively() {
        // Indexing is mid-run, not terminal; reached only via an interrupted collection.
        assertEquals(
            WorkResultDecision.SUCCESS,
            bulkResultDecision(Progress.Indexing(current = 5, total = 10, failedCount = 1))
        )
    }
}
