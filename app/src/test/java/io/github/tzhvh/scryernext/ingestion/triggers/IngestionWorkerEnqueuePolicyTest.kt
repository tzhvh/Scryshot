/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion.triggers

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [enqueuePolicyDecision] — the issue-02 zombie decision (link 1):
 * what a user-initiated "Index now" enqueue does with the existing unique work
 * `"ingestion"`.
 *
 * The incident chain (harness doc §1): `Result.retry()` leaves the unique work
 * `ENQUEUED` in exponential backoff → `ExistingWorkPolicy.KEEP` alone makes every
 * tap a silent no-op against that zombie → "Index now" stops working until an
 * app-data wipe. This matrix is the fix: healthy pending/running work is kept
 * (never replaced — the double-engine fence), the retry-backoff zombie is
 * cancel-and-requeued, and terminal/absent always means a fresh run.
 *
 * The [WorkInfo.State] input is a plain enum, so this runs on the JVM without
 * Robolectric/mockk — matching the repo convention (issue 12's `bulkResultDecision`,
 * issue 14's `isPending`). The WM query/enqueue plumbing itself is device-side
 * (issue 02's INJECT-arm red test).
 */
class IngestionWorkerEnqueuePolicyTest {

    // ==========================================================================
    // The zombie: ENQUEUED with runAttemptCount > 0 — the incident's exact state
    // ==========================================================================

    @Test
    fun enqueued_after_first_failure_is_cancelled_and_requeued() {
        // The zombie: a failed attempt sitting in exponential backoff (ENQUEUED,
        // runAttemptCount = 1). KEEP alone would silently swallow every tap — this is
        // the fix's whole point. Dedup makes replacement clean (ADR 0004 §5).
        assertEquals(
            EnqueuePolicyDecision.CANCEL_AND_ENQUEUE,
            enqueuePolicyDecision(WorkInfo.State.ENQUEUED, runAttemptCount = 1)
        )
    }

    @Test
    fun enqueued_deep_in_backoff_is_cancelled_and_requeued() {
        // A zombie several attempts deep (e.g. past the retry cap's neighbourhood) is
        // still a zombie — the mapping keys off "in retry backoff", not the depth.
        assertEquals(
            EnqueuePolicyDecision.CANCEL_AND_ENQUEUE,
            enqueuePolicyDecision(WorkInfo.State.ENQUEUED, runAttemptCount = 5)
        )
    }

    // ==========================================================================
    // Healthy work: never replaced (the double-engine fence, issue 02)
    // ==========================================================================

    @Test
    fun running_first_attempt_is_kept_active() {
        // A healthy live session. Replacing a *running* session would be the
        // double-engine regression; a tap during a real run should be a no-op.
        assertEquals(
            EnqueuePolicyDecision.KEEP_ACTIVE,
            enqueuePolicyDecision(WorkInfo.State.RUNNING, runAttemptCount = 0)
        )
    }

    @Test
    fun running_retry_attempt_is_kept_active() {
        // Even a retry that IS actively running is kept: a run is genuinely in
        // progress (notification + banner Mode B show it); the zombie is specifically
        // the ENQUEUED-in-backoff case, where nothing is running and the tap is a
        // silent no-op. Keys off observed state, not assumptions (issue 02 Notes).
        assertEquals(
            EnqueuePolicyDecision.KEEP_ACTIVE,
            enqueuePolicyDecision(WorkInfo.State.RUNNING, runAttemptCount = 2)
        )
    }

    @Test
    fun enqueued_first_attempt_is_kept_active() {
        // A cleanly-queued first attempt (runAttemptCount = 0): waiting on the WM
        // executor/constraints, not in retry backoff. A legitimate pending run.
        assertEquals(
            EnqueuePolicyDecision.KEEP_ACTIVE,
            enqueuePolicyDecision(WorkInfo.State.ENQUEUED, runAttemptCount = 0)
        )
    }

    // ==========================================================================
    // Terminal / absent: a tap always means "start a run"
    // ==========================================================================

    @Test
    fun absent_work_enqueues_fresh() {
        // Never enqueued (or pruned): nothing to keep.
        assertEquals(EnqueuePolicyDecision.ENQUEUE_FRESH, enqueuePolicyDecision(null, 0))
    }

    @Test
    fun succeeded_enqueues_fresh() {
        assertEquals(
            EnqueuePolicyDecision.ENQUEUE_FRESH,
            enqueuePolicyDecision(WorkInfo.State.SUCCEEDED, runAttemptCount = 0)
        )
    }

    @Test
    fun failed_enqueues_fresh() {
        // The issue-02 retry cap lands here: a capped RETRY returns failure() →
        // WorkInfo FAILED (terminal) → the next tap must start a real run, not no-op.
        assertEquals(
            EnqueuePolicyDecision.ENQUEUE_FRESH,
            enqueuePolicyDecision(WorkInfo.State.FAILED, runAttemptCount = 3)
        )
    }

    @Test
    fun cancelled_enqueues_fresh() {
        // Stop-action abort leaves CANCELLED; re-tapping afterwards must work.
        assertEquals(
            EnqueuePolicyDecision.ENQUEUE_FRESH,
            enqueuePolicyDecision(WorkInfo.State.CANCELLED, runAttemptCount = 0)
        )
    }

    // ==========================================================================
    // Defensive: BLOCKED (unreachable for standalone one-time work)
    // ==========================================================================

    @Test
    fun blocked_folds_into_the_enqueued_rule() {
        // The ingestion request has no prerequisites, so BLOCKED is unreachable in
        // practice; defensively it follows the ENQUEUED rule — attempt 0 kept, a
        // retrying attempt treated as the zombie.
        assertEquals(
            EnqueuePolicyDecision.KEEP_ACTIVE,
            enqueuePolicyDecision(WorkInfo.State.BLOCKED, runAttemptCount = 0)
        )
        assertEquals(
            EnqueuePolicyDecision.CANCEL_AND_ENQUEUE,
            enqueuePolicyDecision(WorkInfo.State.BLOCKED, runAttemptCount = 2)
        )
    }
}
