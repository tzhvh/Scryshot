/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

/**
 * The [IngestionEngine]'s only write path — the sink that persists an ingestion
 * result. A `fun interface` (not a bare `suspend (...) -> Unit`) so call sites
 * can name the arguments and the §7.2 branch order is self-documenting at the
 * engine's write call sites.
 *
 * The richer signature carries both concerns ADR 0004 §7.2's three-class
 * taxonomy needs:
 * - **what text** to store: the recognized [text] on [OcrOutcome.Success], or
 *   `null` (empty) on [OcrOutcome.PermanentContentFailure] (processed-but-empty);
 * - **whether processed**: always `true` for both those branches (the file is
 *   "done" — indexed *or* permanently-unreadable), so it leaves the unindexed set
 *   and does not re-poison the backlog via Phase 2.5's discovery worker.
 *
 * [OcrOutcome.TransientFailure] **never** invokes the sink (it writes nothing and
 * is re-attempted next run). A throw out of [commit] is an Insert/store failure
 * distinct from any OCR outcome — the engine surfaces it as [Progress.Error] and
 * stops the run (ADR 0004 §7.2, issue `07`).
 *
 * The trigger layer / repository owns *how* and *where* it persists (a Room
 * `updateScreenshotContent` + a `processed = true` row update under the
 * Room/MediaStore era).
 *
 * See: [ADR 0004 §7.2](../../../../../docs/adr/0004-ingestion-engine-and-trigger-architecture-v2.md)
 */
fun interface WriteSink {
    /**
     * Persist [text] for [candidate], marking it processed iff [processed].
     * Throwing here surfaces as [Progress.Error] at the engine boundary.
     */
    suspend fun commit(candidate: Candidate, text: String?, processed: Boolean)
}
