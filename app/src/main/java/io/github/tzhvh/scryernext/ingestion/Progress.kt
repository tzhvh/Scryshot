/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

/**
 * The [IngestionEngine]'s structured output: a sealed view of an ingestion
 * run's state (CONTEXT.md Glossary). v1 renders a single progress bar; the
 * sealed shape keeps the emission contract stable as Phase 1 instrumentation
 * and Phase 3 UI work arrive.
 *
 * Completion fraction is `(indexed + failed) / total`: the `failedCount` is
 * tracked separately from `indexedCount` and counted toward completion, so a
 * single permanent failure can't park the progress bar forever (ADR 0004 §7.2).
 *
 * See: [ADR 0004 §2, §7.2, §7.3](../../../../../docs/adr/0004-ingestion-engine-and-trigger-architecture-v2.md)
 */
sealed interface Progress {
    /**
     * In-flight state. [current] is the count *processed this run*
     * (`(indexed + failed) so far`), **not** just indexed — a single failure
     * must advance the bar (ADR 0004 §7.2). [stageTimings] is null until
     * Phase 1 instruments the per-stage Stopwatch (task 1.4).
     */
    data class Indexing(
        val current: Int,                          // (indexed + failed) so far in this run
        val total: Int,
        val failedCount: Int,
        val stageTimings: StageTimings? = null     // null until Phase 1 instruments stages
    ) : Progress

    /** Terminal success. Failures are included in completion. */
    data class Completed(
        val indexed: Int,
        val failed: Int,
        val total: Int,
        val stageTimings: StageTimings? = null
    ) : Progress

    /** Terminal failure. */
    data class Error(val throwable: Throwable) : Progress

    /**
     * Presentation state for pause/resume continuity — cosmetic, with no
     * correctness impact (ADR 0004 §5): persist `(sessionStartTotal, doneCount)`
     * so the bar shows continuity across pause/resume; if lost, the worst case
     * is a cosmetic reset.
     */
    data class Paused(
        val sessionStartTotal: Int = 0,
        val doneCount: Int = 0
    ) : Progress
}
