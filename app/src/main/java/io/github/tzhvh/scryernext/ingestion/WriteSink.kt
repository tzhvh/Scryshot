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
     * Persist [text] for [candidate], marking it processed iff [processed]. [bytes] is the file
     * content the engine already read once (issue 02's READ→DEDUP reorder) — without re-opening
     * the file. Throwing here surfaces as [Progress.Error] at the engine boundary.
     *
     * Content identity: [precomputedContentHash] is the SHA-256 the repository's `isKnown` miss
     * path already computed over the SAME [bytes] (zvec roadmap V2 §0.4 — one digest per file,
     * not two). When present it is **authoritative and not re-verified**; the sink falls back to
     * hashing [bytes] only when null (the metadata-cache cheap path computes no hash, and
     * standalone callers — tests, other write paths — have no dedup upstream). The contract the
     * caller must uphold: bitwise-identical [bytes] flow to `isKnown` and to this call in the
     * same iteration (no in-place mutation between dedup and commit), and the value is exactly
     * what [io.github.tzhvh.scryernext.util.sha256Hex] yields — SHA-256, lowercase hex, 64 chars.
     */
    suspend fun commit(
        candidate: Candidate,
        text: String?,
        processed: Boolean,
        bytes: ByteArray,
        precomputedContentHash: String?,
    )

    /**
     * Drain any durability [commit] has deferred — the checkpoint-batched-flush hook (issue `01`,
     * the fd-exhaustion cliff fix: `INGESTION_ADHOC_REPRO_HARNESS` §15.5 #1). A sink that commits
     * synchronously (every commit already durable when it returns — [RoomWriteSink], test fakes)
     * needs no checkpoint: the default is a no-op, so only batching sinks override it.
     *
     * The contract (the load-bearing invariant, D13 at batch granularity): on return, every doc
     * previously handed to [commit] must be **flushed first, then** marked processed in the Room
     * row — never the reverse. A throw out of `checkpoint` means the tail was not drained (the
     * rows stay `processed = 0` and re-OCR next run — the bounded re-OCR contract, not a hole).
     *
     * The engine calls this on its terminal [Progress] states: before `Progress.Completed`
     * (required — otherwise the run would forfeit its tail batch's OCR work), and best-effort on
     * the `Progress.Error` path (a secondary checkpoint failure must not mask the primary error).
     * Correctness never *requires* it — an uncheckpointed batch is self-healing re-OCR — so no
     * caller should block cancellation on it.
     */
    suspend fun checkpoint() {}
}
