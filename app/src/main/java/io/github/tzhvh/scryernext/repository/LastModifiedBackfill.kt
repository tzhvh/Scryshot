/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.repository

import io.github.tzhvh.scryernext.ZvecEventRecorder
import io.github.tzhvh.scryernext.ingestion.ZvecContentStore
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import io.github.tzhvh.scryernext.zvec.ZvecValue

/**
 * Phase 2.1 step 4 — the one-shot `last_modified` backfill (design: `ZVEC_PHASE2.1.md`
 * §Schema / decision 2.1-D2, idempotency 2.1-D11).
 *
 * The [io.github.tzhvh.scryernext.ingestion.ZvecContentStore] self-heals the column in via
 * runtime DDL on open, but every doc written before the column existed reads it as **null** —
 * and the R8-pinned contract (null rows are EXCLUDED by scalar filters, three-valued logic)
 * makes a lazy fill impossible: a date-range filter shipped over null rows would silently hide
 * them. So this pass runs exactly once per install, at app start: for every Room row carrying a
 * `content_hash`, fetch the zvec doc and re-upsert it with the row's capture time.
 *
 * Ordering inside the pass is the crash-safety story:
 *  1. every row is idempotently re-upserted on its content_hash PK (R8 contract #9 — an in-place
 *     overwrite, never a duplicate), preserving the fetched OCR text and locator;
 *  2. the store is flushed (durable before the marker);
 *  3. ONLY then is the done-marker written. A crash anywhere in 1–2 leaves the marker unset, and
 *     the next launch re-runs the whole idempotent pass.
 *
 * The date-range chip's render gate (step 6) reads [BackfillMarker.isDone] — the UI affordance
 * exists only once this class says the corpus has no null rows.
 */
class LastModifiedBackfill(
    private val store: ZvecContentStore,
    private val rowSource: suspend () -> List<ScreenshotModel>,
    private val marker: BackfillMarker,
) {

    /** The backfill-completion flag. Production: SharedPreferences-backed; JVM tests: in-memory. */
    interface BackfillMarker {
        fun isDone(): Boolean
        fun markDone()
    }

    /**
     * Run the pass if it has not completed yet. Returns the number of docs re-upserted (0 on a
     * done marker — the steady state, with zero store calls).
     */
    suspend fun runIfNeeded(): Int {
        if (marker.isDone()) return 0

        var backfilled = 0
        for (row in rowSource()) {
            val hash = row.contentHash ?: continue // never indexed into zvec — nothing to fill
            val doc = store.fetch(hash) ?: continue // stale: the zvec doc is gone (all dupes deleted)
            val content = (doc.fields[ZvecContentStore.FIELD_CONTENT] as? ZvecValue.Str)?.value ?: ""
            val locator = (doc.fields[ZvecContentStore.FIELD_LOCATOR] as? ZvecValue.Str)?.value ?: row.uri
            store.upsert(
                contentHash = hash,
                locator = locator,
                content = content,
                collectionId = row.collectionId,
                lastModified = row.lastModified,
            )
            backfilled++
        }
        store.flush()
        marker.markDone()
        ZvecEventRecorder.record { "last_modified backfill complete: $backfilled docs re-upserted" }
        return backfilled
    }
}
