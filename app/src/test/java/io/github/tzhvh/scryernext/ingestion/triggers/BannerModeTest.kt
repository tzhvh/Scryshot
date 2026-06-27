/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.

 */

package io.github.tzhvh.scryernext.ingestion.triggers

import io.github.tzhvh.scryernext.ingestion.IngestionConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the banner mode decision — [bannerMode], the pure rule extracted from the
 * HomeFragment banner controller (issue 18 AC: the visibility decision "is extractable as a pure,
 * unit-testable function, symmetric with issues 12/13/14's pure-helper convention").
 *
 * Plain JUnit4 — matches the repo convention (no Robolectric, no mockk). The view-binding /
 * `repeatOnLifecycle` plumbing cannot run on the JVM; only the decision logic is unit-tested.
 *
 * The threshold under test is [IngestionConfig.BACKLOG_THRESHOLD] (12) — the shared single source
 * of truth across on-open (issue 11), discovery (issue 13), and the banner (issue 18).
 */
class BannerModeTest {

    // ==========================================================================
    // Mode A (IDLE_BACKLOG) boundary: strict `>` on the threshold.
    // A backlog of exactly 12 is on-open territory, not a banner nudge.
    // ==========================================================================

    @Test
    fun idle_backlog_at_threshold_is_hidden() {
        // Guards against an off-by-one (`>=` vs `>`): 12 is on-open's job, not a banner nudge.
        assertEquals(
            BannerMode.HIDDEN,
            bannerMode(pending = false, backlog = IngestionConfig.BACKLOG_THRESHOLD, threshold = IngestionConfig.BACKLOG_THRESHOLD)
        )
    }

    @Test
    fun idle_backlog_one_above_threshold_shows_nudge() {
        assertEquals(
            BannerMode.IDLE_BACKLOG,
            bannerMode(pending = false, backlog = IngestionConfig.BACKLOG_THRESHOLD + 1, threshold = IngestionConfig.BACKLOG_THRESHOLD)
        )
    }

    // ==========================================================================
    // Mode B (ACTIVE) precedence: a pending session wins regardless of backlog.
    // The active status must show even when there is no backlog reading yet.
    // ==========================================================================

    @Test
    fun pending_session_shows_active_even_with_zero_backlog() {
        // active wins over both the threshold rule and the snooze flag.
        assertEquals(BannerMode.ACTIVE, bannerMode(pending = true, backlog = 0, threshold = IngestionConfig.BACKLOG_THRESHOLD))
    }

    @Test
    fun pending_session_shows_active_even_when_snoozed() {
        // Snooze suppresses the nudge, NEVER the active-session status — a running job keeps its
        // Stop affordance regardless of a prior snooze (issue 18: "Mode B must not have a snooze").
        assertEquals(BannerMode.ACTIVE, bannerMode(pending = true, backlog = 842, threshold = IngestionConfig.BACKLOG_THRESHOLD, snoozed = true))
    }

    // ==========================================================================
    // HIDDEN cases.
    // ==========================================================================

    @Test
    fun no_session_and_empty_backlog_is_hidden() {
        assertEquals(BannerMode.HIDDEN, bannerMode(pending = false, backlog = 0, threshold = IngestionConfig.BACKLOG_THRESHOLD))
    }

    @Test
    fun no_session_and_small_backlog_is_hidden() {
        // On-open-sized backlog — silently handled by the on-open trigger, no banner nudge.
        assertEquals(BannerMode.HIDDEN, bannerMode(pending = false, backlog = 1, threshold = IngestionConfig.BACKLOG_THRESHOLD))
        assertEquals(BannerMode.HIDDEN, bannerMode(pending = false, backlog = 11, threshold = IngestionConfig.BACKLOG_THRESHOLD))
    }

    // ==========================================================================
    // Snooze (in-memory, process-scoped) suppresses the nudge but is reset by process death.
    // ==========================================================================

    @Test
    fun snooze_suppresses_the_idle_nudge() {
        // Without snooze this would be IDLE_BACKLOG (842 > 12). Snooze hides it.
        assertEquals(
            BannerMode.HIDDEN,
            bannerMode(pending = false, backlog = 842, threshold = IngestionConfig.BACKLOG_THRESHOLD, snoozed = true)
        )
    }

    // ==========================================================================
    // Threshold independence: the rule holds for any threshold, not just the v1 12.
    // Documents that `threshold` is a real parameter (future adaptive threshold, ADR 0004 §7.3).
    // ==========================================================================

    @Test
    fun rule_is_threshold_independent() {
        assertEquals(BannerMode.HIDDEN, bannerMode(pending = false, backlog = 5, threshold = 5))
        assertEquals(BannerMode.IDLE_BACKLOG, bannerMode(pending = false, backlog = 6, threshold = 5))
        assertEquals(BannerMode.HIDDEN, bannerMode(pending = false, backlog = 99, threshold = 100))
        assertEquals(BannerMode.IDLE_BACKLOG, bannerMode(pending = false, backlog = 101, threshold = 100))
    }
}
