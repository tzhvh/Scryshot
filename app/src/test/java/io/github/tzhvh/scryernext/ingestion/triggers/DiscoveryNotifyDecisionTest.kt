/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion.triggers

import io.github.tzhvh.scryernext.ingestion.IngestionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the discovery notify-threshold decision — [shouldNotify], the pure rule
 * extracted from [DiscoveryWorker.doWork] (issue 13 AC: "the count query and the
 * notify-threshold decision *are* unit-testable as pure logic").
 *
 * The WM + notification + SQLite path itself cannot run on the JVM (no Robolectric in this
 * project — see [IngestionWorkerResultDecisionTest]'s rationale), so only the decision is
 * unit-tested, symmetric with how [bulkResultDecision]'s §5 contract is tested.
 *
 * Plain JUnit4 — matches the repo convention (no `kotlinx-coroutines-test`, no mockk).
 *
 * The threshold under test is [IngestionConfig.BACKLOG_THRESHOLD] (12) — the shared single
 * source of truth across on-open (issue `11`) and discovery (issue `13`).
 */
class DiscoveryNotifyDecisionTest {

    // ==========================================================================
    // Boundary: count == threshold is NOT a notify trigger (strict `>`).
    // A backlog of exactly 12 is on-open territory (issue 11), not a daily nag.
    // ==========================================================================

    @Test
    fun count_at_threshold_does_not_notify() {
        // The boundary: 12 is on-open's job, not a notification (ADR 0003 §4 "not a fixed
        // daily nag", carried by ADR 0004 §4). Guards against an off-by-one (`>=` vs `>`).
        assertFalse(shouldNotify(IngestionConfig.BACKLOG_THRESHOLD, IngestionConfig.BACKLOG_THRESHOLD))
    }

    @Test
    fun count_one_above_threshold_notifies() {
        assertTrue(shouldNotify(IngestionConfig.BACKLOG_THRESHOLD + 1, IngestionConfig.BACKLOG_THRESHOLD))
    }

    // ==========================================================================
    // Well below / well above the threshold
    // ==========================================================================

    @Test
    fun empty_backlog_does_not_notify() {
        assertFalse(shouldNotify(0, IngestionConfig.BACKLOG_THRESHOLD))
    }

    @Test
    fun small_backlog_does_not_notify() {
        // On-open-sized backlog — silently handled by the on-open trigger, no notification.
        assertFalse(shouldNotify(1, IngestionConfig.BACKLOG_THRESHOLD))
        assertFalse(shouldNotify(11, IngestionConfig.BACKLOG_THRESHOLD))
    }

    @Test
    fun large_backlog_notifies() {
        // The discovery worker's reason to exist: a backlog the on-open trigger won't touch.
        assertTrue(shouldNotify(13, IngestionConfig.BACKLOG_THRESHOLD))
        assertTrue(shouldNotify(842, IngestionConfig.BACKLOG_THRESHOLD))
    }

    // ==========================================================================
    // Threshold independence: the rule holds for any threshold, not just the v1 12.
    // Documents that `threshold` is a real parameter (future adaptive threshold, §7.3).
    // ==========================================================================

    @Test
    fun rule_is_threshold_independent_at_low_threshold() {
        // A hypothetical low threshold: only 1 above is still a notify (strict `>`).
        assertFalse(shouldNotify(1, 1))
        assertTrue(shouldNotify(2, 1))
    }

    @Test
    fun rule_is_threshold_independent_at_high_threshold() {
        assertFalse(shouldNotify(99, 100))
        assertTrue(shouldNotify(101, 100))
    }
}
