/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

/**
 * Placeholder for the per-stage rolling-average latency (ADR 0004 §7.3).
 *
 * Phase 0 lands this only so [Progress.stageTimings] has a concrete type and
 * Phase 1 (task 1.4) can extend it without a breaking change to [Progress].
 * Resist filling in the rolling-average logic here — Phase 1 owns that; the
 * fields default to zero and the engine does not emit them yet.
 *
 * See: [ADR 0004 §7.3](../../../../../docs/adr/0004-ingestion-engine-and-trigger-architecture-v2.md)
 */
data class StageTimings(
    val readMs: Double = 0.0,
    val decodeMs: Double = 0.0,
    val ocrMs: Double = 0.0,
    val writeMs: Double = 0.0
)
