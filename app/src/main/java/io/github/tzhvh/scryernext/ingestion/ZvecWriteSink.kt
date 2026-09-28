/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

import android.util.Log
import io.github.tzhvh.scryernext.ZvecEventRecorder
import io.github.tzhvh.scryernext.persistence.ContentMetadataCache
import io.github.tzhvh.scryernext.persistence.ContentMetadataCacheDao
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import io.github.tzhvh.scryernext.repository.ScreenshotRepository
import io.github.tzhvh.scryernext.util.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * zvec Phase 2, issue 03 — the write-cutover [WriteSink]. Upserts OCR content to zvec on the
 * content_hash PK (instead of Room's `ScreenshotContentModel`), records the hash on the Room row,
 * and warms the metadata cache. After this issue zvec holds OCR content; reads still come from
 * Room until issue 04 flips them.
 *
 * ## Checkpoint-batched durability (issue `01` — the fd-exhaustion cliff fix)
 *
 * [commit] upserts to zvec immediately but defers the flush and the Room/cache side to a pending
 * batch; every [CHECKPOINT_BATCH_SIZE] commits the sink performs **one** `flush()` and only then
 * drains the batch's Room marks + cache warms, and [checkpoint] (the engine's terminal-state hook)
 * drains the remainder. This replaces the per-file flush this KDoc originally called "the honest
 * durability contract … start correct, not fast" — a framing the 2026-09-28 incident exposed as a
 * category error: correctness is carried by the **write order + the `isKnown` dedup seam**, not by
 * the flush; the per-file flush only avoided re-OCR work, at ~150× the amortized durability cost
 * (measured ~0.45–0.75 s/doc per-file vs ~4.2 ms/doc batched on the field device,
 * `INGESTION_ADHOC_REPRO_HARNESS.md` §15.4) while each flush sealed a new `scalar.NNN.ipc` +
 * RocksDB SSTs that the engine keeps open — ~5.5 fds/file into Android's 32,768-fd cap, the wall
 * that killed ingestion at ~6.5k docs (§15.1). Batching attacks the file-creation count directly:
 * an 18k run goes from ~18k flushes (~100k fds) to ~180.
 *
 * ## The write order is load-bearing — upsert, flush, THEN Room (decision D13, at batch granularity)
 *
 * 1. `zvecContentStore.upsert(contentHash, …)` — content goes to zvec, per doc, immediately (the
 *    upsert granularity is unchanged; only the flush cadence changed — R8's idempotent upsert
 *    keeps re-ingest dedup semantics simple, and `ZvecContentStore.upsertAll` stays tooling-only).
 * 2. `zvecContentStore.flush()` — once per [CHECKPOINT_BATCH_SIZE] docs + once at the terminal
 *    [checkpoint], never per doc.
 * 3. `repository.markContentIndexed(screenshot, contentHash)` — record the bridge hash + flip
 *    `processed = 1` so the producer's `processed = 0` queue retires the row.
 * 4. `metadataCache.upsert(…)` + `metadataCache.markIndexed(contentHash)` — warm the cache so the
 *    next run's `isKnown` cheap-path hits (issue 01's DAO surface).
 *
 * **A Room row must never be marked `processed = 1` before its zvec doc is flushed** — the D13
 * silent-hole constraint, restated at batch granularity. Room-first marking of an unflushed
 * (possibly lost) zvec doc is a silent hole `getContentText` can never fill; here the marks for a
 * whole batch sit behind the batch's single flush, and the two crash windows stay self-healing:
 *
 * - Crash **before** the checkpoint → zvec may lose the batch's unflushed docs, but their Room
 *   rows are still `processed = 0` → the next run re-reads, re-OCRs, re-upserts. Bounded re-OCR
 *   contract: at most ~N ≈ [CHECKPOINT_BATCH_SIZE] docs ≈ 1–2 min — cheap enough that
 *   WorkManager's 10-minute dataSync foreground kills do not translate into meaningful lost work.
 * - Crash **after** the flush, before the marks drain → zvec has the docs, Room rows still
 *   `processed = 0` → the next run's `isKnown` returns true and the dedup-skip seam
 *   ([ScreenshotRepository.markContentIndexed] on the skip path) retires the rows. Zero work lost.
 *
 * A throw mid-drain (step 3/4 of a flushed batch) leaves the failed mark and the tail pending —
 * every step is idempotent (Room UPDATE, cache upsert), so the engine's error-path checkpoint may
 * safely re-drain them, and a crash there degrades to the second window above. Small runs keep the
 * old shape: an on-open-sized run (≤ 12 files) never fills a batch, so it performs exactly one
 * flush — at its terminal checkpoint.
 *
 * ## Latency semantics — the engine's `writeMs` reads amortized (issue `01`)
 *
 * [commit] latency is deliberately bimodal: upsert-only for the N−1 deferred docs, one large
 * flush-bearing sample on every Nth (the engine times `commit` verbatim; the per-doc timing is
 * not faked). The [IngestionEngine] write EMA (ADR 0004 §7.3, the Phase 5 adaptive threshold's
 * input) therefore converges on the **amortized** durability cost — Σ over a batch ≈ one flush +
 * N upserts — which is the honest per-doc number; the terminal [checkpoint]'s flush lands outside
 * any per-candidate sample, so a small run's writeMs slightly under-counts its single flush.
 * Batch state is confined to the engine's single sequential collector — no synchronization.
 *
 * ## No re-open of the file
 *
 * The content identity comes from [bytes] — the SAME bytes the engine already read once (issue 02's
 * READ→DEDUP reorder). Prefer the `precomputedContentHash` the engine threads from `isKnown`'s miss
 * path (one digest per file — roadmap V2 §0.4); hash [bytes] only as the fallback. The sink never
 * re-opens [Candidate.byteHandle]; single-open is the whole point of the reorder.
 *
 * ## The missing-row case
 *
 * Issue 10's Model B guarantees a screenshot row exists before the engine OCRs it (the producer
 * reads `processed = false` rows). A missing row is therefore an invariant violation — logged loudly
 * and the write skipped, mirroring [RoomWriteSink]'s policy (no silent swallow, no run crash).
 *
 * @param metadataCacheDaoProvider a lazy provider for the cache DAO. Injected as a provider (not the
 *   DAO directly) so the sink stays JVM-testable without a Room DB; production wires
 *   `{ database.contentMetadataCacheDao() }`.
 */
class ZvecWriteSink(
    private val repository: ScreenshotRepository,
    private val zvecContentStore: ZvecContentStore,
    private val metadataCacheDaoProvider: () -> ContentMetadataCacheDao,
) : WriteSink {

    /**
     * One deferred Room mark + cache warm, held from its [commit] until the flush that covers it.
     * The [PendingMark.screenshot] row is the one [commit] resolved (sequential engine loop — it
     * cannot go stale within a batch) and carries everything the cache warm needs (uri, mtime,
     * size) plus the `markContentIndexed` payload.
     */
    private class PendingMark(val screenshot: ScreenshotModel, val contentHash: String)

    /**
     * The deferred batch: docs upserted to zvec whose Room mark + cache warm have not yet been
     * covered by a flush. Size is the batch counter — [commit] checkpoints when it reaches
     * [CHECKPOINT_BATCH_SIZE]. Confined to the engine's single sequential collector (see class
     * KDoc); a drain removes each mark only after it lands.
     */
    private val pending = ArrayDeque<PendingMark>()

    /**
     * True when every doc currently in [pending] is already covered by a flush — set by
     * [checkpoint]'s flush, cleared by the next [commit] (a doc that arrived after that flush is
     * not covered). Keeps an error-path re-drain from re-flushing an already-flushed batch:
     * flush **once** per batch, drain as many times as the failures demand (each flush seals
     * segment/SST files — the fd budget this whole class exists to protect).
     */
    private var pendingFlushed = false

    override suspend fun commit(
        candidate: Candidate,
        text: String?,
        processed: Boolean,
        bytes: ByteArray,
        precomputedContentHash: String?,
    ) {
        // The WriteSink contract: TransientFailure never reaches the sink. Defensive guard — a
        // processed=false call writes nothing (the row stays unprocessed so the next run re-attempts).
        if (!processed) return

        val locator = candidate.locator ?: return
        val screenshot = repository.getScreenshotByUri(locator)
        if (screenshot == null) {
            // Model B invariant violation — see the class KDoc. Log loudly; do not crash the run.
            Log.w(
                TAG,
                "commit: no screenshot row for locator=$locator (Model B invariant violated); skipping write."
            )
            ZvecEventRecorder.record { "Write-path invariant violation: no Room row for $locator; skipping write" }
            return
        }

        // §0.4: prefer the digest `isKnown`'s miss path already computed over these same bytes;
        // fall back to hashing only when none was threaded (cheap-path flow, standalone callers).
        val contentHash = precomputedContentHash ?: sha256Hex(bytes)

        // ── zvec-first (D13) ──────────────────────────────────────────────────────────────
        // Step 1: upsert content to zvec — per doc, immediately. Durability is deferred to the
        // batch flush; the doc is invisible to the pending Room mark until that flush lands.
        zvecContentStore.upsert(
            contentHash = contentHash,
            locator = locator,
            content = text ?: "",
            collectionId = screenshot.collectionId,
            // Phase 2.1 step 4 (2.1-D2): the row's capture time rides into zvec so date-range
            // filters push down. Every ingestion write populates it; the one-shot backfill
            // covers docs written before the column existed.
            lastModified = screenshot.lastModified,
        )

        // Steps 3 + 4 deferred: the Room mark and cache warm sit in [pending] BEHIND the flush
        // that must cover them (the load-bearing invariant — see class KDoc). This commit's
        // latency sample is therefore upsert-only for every doc but the batch boundary's, and
        // the doc it adds is (re)opening an unflushed batch.
        pending.addLast(PendingMark(screenshot, contentHash))
        pendingFlushed = false
        ZvecEventRecorder.record { "Committed doc with hash: ${contentHash.take(8)}... (locator=$locator)" }

        if (pending.size >= CHECKPOINT_BATCH_SIZE) checkpoint()
    }

    /**
     * Flush once per batch (a re-drain after a mid-drain failure skips the already-done flush),
     * then drain the pending Room marks + cache warms — the ONLY place a mark can run, and only
     * after the flush covering it (issue `01`'s acceptance-critical ordering).
     * [IngestionEngine] invokes this at its terminal `Progress` states; internally, [commit]
     * invokes it on every [CHECKPOINT_BATCH_SIZE]th doc. A no-op when nothing is pending (the
     * just-drained terminal call, or an empty run) so the terminal checkpoint of a fully-batched
     * run adds no second flush.
     */
    override suspend fun checkpoint() {
        if (pending.isEmpty()) return
        // Step 2: the batch's single flush — skipped only on a re-drain of an already-flushed
        // batch ([pendingFlushed]; the error-path checkpoint's retry case). A throw here leaves
        // every pending mark un-run — the rows stay processed = 0 and re-OCR next run
        // (bounded-re-OCR contract), never a hole.
        if (!pendingFlushed) {
            zvecContentStore.flush()
            pendingFlushed = true
        }

        // ── then Room (steps 3 + 4) ───────────────────────────────────────────────────────
        // Drain head-first, removing a mark only AFTER it lands: any throw mid-drain leaves the
        // failed mark (and the tail) pending — every step is idempotent (Room UPDATE, cache
        // upsert), so the engine's error-path checkpoint can re-drain them, and a crash there
        // degrades to crash window 2 (flushed-but-unmarked → dedup-skip retires, zero work lost).
        val dao = metadataCacheDaoProvider()
        var drained = 0
        while (pending.isNotEmpty()) {
            val mark = pending.first()
            // Step 3: record the bridge hash + retire the row from the producer's queue.
            repository.markContentIndexed(mark.screenshot, mark.contentHash)

            // Step 4: warm the metadata cache so the next run's isKnown cheap-path hits (issue 01).
            withContext(Dispatchers.IO) {
                dao.upsert(
                    ContentMetadataCache(
                        locator = mark.screenshot.uri,
                        mtime = mark.screenshot.lastModified,
                        size = mark.screenshot.size,
                        contentHash = mark.contentHash,
                        indexed = false,
                    )
                )
                dao.markIndexed(mark.contentHash)
            }
            pending.removeFirst()
            drained += 1
        }
        ZvecEventRecorder.record { "Checkpoint: flushed + marked $drained pending doc(s)" }
    }

    companion object {
        private const val TAG = "ZvecWriteSink"

        /**
         * Docs per durability checkpoint — the batch size N of the issue-01 policy. Justification:
         * crash-time re-OCR is bounded at ~N × 0.7 s ≈ 1–2 min on the field device, while a 100-doc
         * flush costs ~0.4 s (~4 ms/doc amortized, harness §15.4) and caps per-run segment-file
         * creation at ~total/N — the fd-cliff lever. A static named constant, not computed: there is
         * no live signal worth tuning it against, and a dynamic N would make the bounded-re-OCR
         * contract unstateable. `internal` so the JVM batch-boundary tests pin the exact seam.
         */
        internal const val CHECKPOINT_BATCH_SIZE = 100
    }
}
