/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

import android.util.Log
import io.github.tzhvh.scryernext.persistence.ContentMetadataCache
import io.github.tzhvh.scryernext.persistence.ContentMetadataCacheDao
import io.github.tzhvh.scryernext.repository.ScreenshotRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * zvec Phase 2, issue 03 — the write-cutover [WriteSink]. Upserts OCR content to zvec on the
 * content_hash PK (instead of Room's `ScreenshotContentModel`), flushes for durability, then records
 * the hash on the Room row and warms the metadata cache. After this issue zvec holds OCR content;
 * reads still come from Room until issue 04 flips them.
 *
 * ## The write order is load-bearing — zvec-first, then Room (decision D13)
 *
 * 1. `zvecContentStore.upsert(contentHash, …)` — content goes to zvec.
 * 2. `zvecContentStore.flush()` — the honest durability contract under hard cutover (no fallback
 *    store); a per-file flush is cheap on the small Phase-2 corpus. If profiling later shows flush
 *    dominating write latency, downgrade to "flush on run completion" (the engine's
 *    `Progress.Completed`) — but start correct, not fast.
 * 3. `repository.markContentIndexed(screenshot, contentHash)` — record the bridge hash + flip
 *    `processed = 1` so the producer's `processed = 0` queue retires the row.
 * 4. `metadataCache.upsert(…)` + `metadataCache.markIndexed(contentHash)` — warm the cache so the
 *    next run's `isKnown` cheap-path hits (issue 01's DAO surface).
 *
 * A crash between step 2 and step 3 leaves a zvec doc whose `ScreenshotModel` row is still
 * `processed = 0`. That's self-healing: the producer re-pulls the row, the engine re-reads, `isKnown`
 * returns `true` (hash already in zvec), the dedup-skip seam ([ScreenshotRepository.markProcessed])
 * retires the row, and R8 makes the would-be re-upsert a no-op. **Never reverse the order** —
 * Room-first would leave a row marked `processed = 1` pointing at a nonexistent zvec doc: a silent
 * hole `getContentText` can't fill.
 *
 * ## No re-open of the file
 *
 * The hash is computed from [bytes] — the SAME bytes the engine already read once (issue 02's
 * READ→DEDUP reorder). The sink never re-opens `Candidate.byteHandle`; single-open is the whole
 * point of the reorder.
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

    override suspend fun commit(candidate: Candidate, text: String?, processed: Boolean, bytes: ByteArray) {
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
            return
        }

        val contentHash = sha256(bytes)

        // ── zvec-first (D13) ──────────────────────────────────────────────────────────────
        // Step 1 + 2: upsert content to zvec, then flush for durability. A crash after this block
        // but before the Room update (step 3) leaves a zvec doc with an un-`processed` row — the
        // self-healing case documented on the class.
        zvecContentStore.upsert(
            contentHash = contentHash,
            locator = locator,
            content = text ?: "",
            collectionId = screenshot.collectionId,
        )
        zvecContentStore.flush()

        // ── then Room ──────────────────────────────────────────────────────────────────────
        // Step 3: record the bridge hash + retire the row from the producer's queue.
        repository.markContentIndexed(screenshot, contentHash)

        // Step 4: warm the metadata cache so the next run's isKnown cheap-path hits (issue 01).
        withContext(Dispatchers.IO) {
            val dao = metadataCacheDaoProvider()
            dao.upsert(
                ContentMetadataCache(
                    locator = screenshot.uri,
                    mtime = screenshot.lastModified,
                    size = screenshot.size,
                    contentHash = contentHash,
                    indexed = false,
                )
            )
            dao.markIndexed(contentHash)
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        // Hex-encode; the zvec PK is the content_hash string.
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    private companion object {
        const val TAG = "ZvecWriteSink"
        val HEX = "0123456789abcdef".toCharArray()
    }
}
