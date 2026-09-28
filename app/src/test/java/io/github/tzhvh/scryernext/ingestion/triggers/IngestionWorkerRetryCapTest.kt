/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion.triggers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [cappedResultDecision] — the issue-02 retry cap (link 3): a RETRY
 * decision at `runAttemptCount ≥ [MAX_RUN_ATTEMPTS]` maps to a **visible** FAILURE
 * instead of `Result.retry()`'s default infinite invisible backoff.
 *
 * The incident's link 3: an unrecoverable environment (fd exhaustion, OOM) retried
 * forever with no user-facing give-up — the UI looked busy-but-dead. The cap bounds
 * whole-run re-attempts (NOT continuation — ADR 0004 §5's fence; each attempt is a
 * fresh run over a re-derived unindexed set, and dedup remains the checkpoint).
 *
 * Plain JUnit4 — matches the repo convention (no `kotlinx-coroutines-test`, no mockk).
 */
class IngestionWorkerRetryCapTest {

    // ==========================================================================
    // The cap boundary: RETRY survives below the cap, gives up at/above it
    // ==========================================================================

    @Test
    fun retry_on_first_attempt_stays_retry() {
        // runAttemptCount is 0-based (first run). Early attempts retry — the transient
        // failure class (write-path error, whole-run ML Kit unavailability) is expected
        // to clear within WM's first backoff windows (30s/1m/2m ≈ the cap's span).
        assertEquals(WorkResultDecision.RETRY, cappedResultDecision(WorkResultDecision.RETRY, 0))
    }

    @Test
    fun retry_below_cap_stays_retry() {
        assertEquals(WorkResultDecision.RETRY, cappedResultDecision(WorkResultDecision.RETRY, 1))
        assertEquals(WorkResultDecision.RETRY, cappedResultDecision(WorkResultDecision.RETRY, 2))
    }

    @Test
    fun retry_at_cap_gives_up_visibly() {
        // The give-up: attempt 3 (the 4th entry) maps RETRY → FAILURE. WM marks the
        // work FAILED (terminal), the store already surfaced Progress.Error, and the
        // banner leaves ACTIVE for the actionable nudge — never "indeterminate forever".
        assertEquals(WorkResultDecision.FAILURE, cappedResultDecision(WorkResultDecision.RETRY, 3))
    }

    @Test
    fun retry_above_cap_gives_up_visibly() {
        assertEquals(WorkResultDecision.FAILURE, cappedResultDecision(WorkResultDecision.RETRY, 7))
    }

    @Test
    fun cap_constant_is_three_with_transient_span_rationale() {
        // Documented contract of the constant: 3 attempts span the transient failure
        // class (WM default exponential backoff 30s/1m/2m ≈ 3.5 min); beyond that the
        // failure is persistent (the fd wall) and every extra retry is zombie time.
        assertEquals(3, MAX_RUN_ATTEMPTS)
    }

    // ==========================================================================
    // Non-RETRY decisions pass through untouched (the cap never invents retries)
    // ==========================================================================

    @Test
    fun success_passes_through_at_any_attempt() {
        // A normal completion stays SUCCESS regardless of how many attempts preceded —
        // §5's keystone (dedup resumes; never continuation-retry) is not the cap's business.
        assertEquals(WorkResultDecision.SUCCESS, cappedResultDecision(WorkResultDecision.SUCCESS, 0))
        assertEquals(WorkResultDecision.SUCCESS, cappedResultDecision(WorkResultDecision.SUCCESS, 9))
    }

    @Test
    fun failure_passes_through_at_any_attempt() {
        // Reserved-permanent path (issue 12 discrepancy note) — already terminal.
        assertEquals(WorkResultDecision.FAILURE, cappedResultDecision(WorkResultDecision.FAILURE, 0))
        assertEquals(WorkResultDecision.FAILURE, cappedResultDecision(WorkResultDecision.FAILURE, 9))
    }

    @Test
    fun negative_attempt_count_cannot_lose_a_retry() {
        // Defensive: runAttemptCount is 0-based and non-negative in WM; a malformed
        // negative value must not satisfy the `>= MAX` predicate and silently give up.
        assertEquals(WorkResultDecision.RETRY, cappedResultDecision(WorkResultDecision.RETRY, -1))
    }
}
