/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

/**
 * Rolling-average per-stage latency for one ingestion run (ADR 0004 §7.3).
 *
 * Populated by [IngestionEngine] (issue `07`) from [StageLatencyRollingAverage]
 * snapshots — read/decode/OCR/write are timed with `System.nanoTime()` deltas
 * and an EMA is maintained per stage, so each [Progress] emission carries a
 * current estimate.
 *
 * **Stage boundary:** `readMs` = the [Candidate.byteHandle] invocation (owned by
 * the engine); `ocrMs` = decode+OCR inside the injected [OcrStage] over the
 * already-read bytes (issue `08`'s ML Kit adapter may later expose decodeMs
 * separately); `writeMs` = the write sink. `decodeMs` is currently folded into
 * `ocrMs` and stays `0.0` until the stage splits it out.
 *
 * See: [ADR 0004 §7.3](../../../../../docs/adr/0004-ingestion-engine-and-trigger-architecture-v2.md)
 */
data class StageTimings(
    val readMs: Double = 0.0,
    val decodeMs: Double = 0.0,
    val ocrMs: Double = 0.0,
    val writeMs: Double = 0.0
)
