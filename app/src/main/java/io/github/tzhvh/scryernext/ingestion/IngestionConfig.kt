/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

/**
 * Shared knobs for the ingestion triggers (issues `11`, `12`, `13`) — a single
 * source of truth so the on-open/bulk/discovery thresholds can't drift apart.
 *
 * ADR 0004 Consequences pins v1 at a fixed `12`; the adaptive threshold
 * (`unindexedCount × rollingAvgPerFile < ~3s`, ADR 0004 §7.3) replaces it once
 * `StageLatencyRollingAverage` has real per-file latency data — at which point
 * the consumer sites become a one-line change here.
 */
object IngestionConfig {
    /**
     * The unindexed-backlog threshold (ADR 0004 Consequences: v1 fixed 12).
     *
     * - **On-open** (issue `11`): backlog at or below this → run silently in an
     *   app-scope coroutine; backlog above this → not on-open's job (it is the
     *   bulk worker's).
     * - **Discovery** (issue `13`): backlog above this → post the count-and-notify
     *   notification ("Index now" / "Snooze"); at or below → the in-app banner
     *   reads the count without a notification.
     *
     * `>` (strict) at both sites: `count == 12` is on-open territory, not a notify
     * trigger, and is not worth a daily notification (ADR 0003 §4: "Not a fixed
     * daily nag" — carried by ADR 0004 §4).
     */
    const val BACKLOG_THRESHOLD = 12
}
