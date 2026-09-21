/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.repository

import android.util.Log
import io.github.tzhvh.scryernext.ZvecEventRecorder
import io.github.tzhvh.scryernext.ingestion.ZvecContentStore
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import io.github.tzhvh.scryernext.search.DefaultRankStage
import io.github.tzhvh.scryernext.search.RankPolicy
import io.github.tzhvh.scryernext.search.RankStage
import io.github.tzhvh.scryernext.zvec.ZvecValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * zvec Phase 2, issue 04 — the read-flip façade over two stores (D8 + D14).
 *
 * Implements [ScreenshotRepository] as a **façade**: the **gallery + collections** methods delegate
 * to the Room-backed [delegate] ([ScreenshotDatabaseRepository]) unchanged — "enumerate all
 * screenshots / collections sorted & filtered" is SQLite's strength and zvec's documented gap (no
 * PK-enumeration primitive). The **content** methods route to zvec via [store]:
 *
 *  - [searchScreenshots] / [searchScreenshotList] → `store.search(q)` (FTS over zvec's `content`),
 *    then the **batched** gallery-row bridge (`getScreenshotsByUri`, `WHERE uri IN (...)` — not N+1),
 *    preserving zvec rank order and filtering deleted-during-search nulls silently.
 *  - [getContentText] → `store.fetch(screenshot.contentHash)` (the D13 bridge column; one hop, no
 *    cache dependency on the read path).
 *
 * The other three content-side methods' fates (D14) landed in the interface: `getScreenshotContent`
 * was deleted (no production caller), `updateScreenshotContent` was re-contracted to
 * [ScreenshotRepository.markContentIndexed] (content goes to zvec via the sink; Room just records the
 * hash + flips `processed`), and `getContentText` is kept (impl → zvec, here).
 *
 * The UI never knows content moved: every `Flow<List<ScreenshotModel>>` keeps its shape. The
 * `ScreenshotViewModel` swaps one constructor line (`createRepository` → this) — see `ScryerApplication`.
 *
 * ## Why the locator (uri) is the search-result → gallery-row bridge
 *
 * zvec search returns docs keyed by `content_hash` carrying `locator` (=uri). The gallery row is
 * keyed by UUID (`id`). For MediaStore (Phase 2) uri is stable, so resolving the gallery row by uri
 * is sound. (For SAF in Phase 3b, locator volatility is the known problem the metadata cache
 * addresses — out of scope here.)
 */
class ZvecScreenshotRepository(
    private val delegate: ScreenshotDatabaseRepository,
    private val store: ZvecContentStore,
    private val rankStage: RankStage = DefaultRankStage,
) : ScreenshotRepository by delegate {

    /**
     * A thin delegate accessor for the metadata-cache DAO on the underlying Room DB.
     *
     * **Deprecated:** this method was added so the WorkManager wiring path
     * ([io.github.tzhvh.scryernext.ingestion.triggers.IngestionWorker]) could build a `ZvecWriteSink`
     * without casting the app-scope repository down to the concrete [delegate] type. That path now
     * reads the DAO from [io.github.tzhvh.scryernext.ScryerApplication.getMetadataCacheDao] instead
     * (an app-scope field, sibling to the existing `ScreenshotDao` accessor) — making the wiring
     * cast-free AND symmetric with `ScryerApplication.onCreate`'s local-scope construction. This
     * method therefore has **no production callers**. Prefer the app-scope getter; this is retained
     * only to avoid a hard removal in case an untracked caller exists.
     */
    @Deprecated(
        "Use ScryerApplication.getMetadataCacheDao() instead — the WorkManager wiring path " +
            "(the original reason for this accessor) now reads the DAO from the app scope.",
        ReplaceWith("ScryerApplication.getMetadataCacheDao()"),
    )
    fun metadataCacheDao(): io.github.tzhvh.scryernext.persistence.ContentMetadataCacheDao =
        delegate.database.contentMetadataCacheDao()

    /**
     * FTS search routed to zvec, bridged back to gallery rows. Returns a cold [Flow] so the existing
     * `Flow<List<ScreenshotModel>>` search UI ([FullTextSearchFragment]) is unchanged — every search
     * re-runs the zvec query + Room lookup. The zvec result is finite (zvec returns a ranked set, not
     * a stream), so the flow emits exactly once per query and completes.
     *
     * Rank order leaving this method is the [policy]'s order, applied by [rankStage] over the
     * bridged rows + the engine score map (Phase 2.1 step 1) — callers render the list as returned;
     * the old downstream recency re-sort is gone. Changing the policy re-runs the search.
     */
    override fun searchScreenshots(queryText: String, policy: RankPolicy): Flow<List<ScreenshotModel>> = flow {
        emit(searchZvecAndBridge(queryText, policy))
    }.flowOn(Dispatchers.IO)

    override suspend fun searchScreenshotList(queryText: String, policy: RankPolicy): List<ScreenshotModel> =
        withContext(Dispatchers.IO) { searchZvecAndBridge(queryText, policy) }

    /**
     * The shared zvec-FTS-then-batched-Room-lookup body for both the Flow and the List entry points.
     *
     * D3: resolves by **content_hash** (the zvec doc PK), not by locator. zvec dedups identical
     * content to one doc, so N duplicate screenshots share one zvec doc whose PK is their shared
     * hash. Bridging by locator (the old path) lost every duplicate except the one zvec's locator
     * pointed at; bridging by hash returns ALL surviving Room rows for each matched hash, so
     * deleting one duplicate doesn't make the others unsearchable. Rank order is preserved by
     * walking the zvec result order and expanding each hash's rows in place.
     *
     * Phase 2.1 step 1 (2.1-D1/D7): the engine score also survives the bridge — `doc.score` (the
     * fused BM25, higher = stronger per the v0.7.0 pinned ordering) is collected per PK and passed
     * with the rows to [rankStage], which owns the final order per [policy]. The score never rides
     * on [ScreenshotModel]; it lives in this parallel map and dies here.
     */
    private suspend fun searchZvecAndBridge(queryText: String, policy: RankPolicy): List<ScreenshotModel> {
        val trimmed = queryText.trim()
        if (trimmed.isEmpty()) return emptyList()

        val docs = store.search(processQuery(trimmed))
        if (docs.isEmpty()) return emptyList()

        // The content_hash (zvec doc PK) is the gallery-row bridge. Collect PKs preserving zvec
        // rank order; de-dup the PK list (a multi-term match can return the same doc twice). The
        // first sighting of a PK carries its score.
        val rankedHashes = ArrayList<String>(docs.size)
        val scores = HashMap<String, Float>(docs.size)
        val seen = HashSet<String>()
        for (doc in docs) {
            if (doc.pk.isNotEmpty() && seen.add(doc.pk)) {
                rankedHashes.add(doc.pk)
                scores[doc.pk] = doc.score ?: 0f
            }
        }
        if (rankedHashes.isEmpty()) return emptyList()

        // One batched Room query (WHERE content_hash IN (...)) — NOT N+1. Group by hash so each
        // zvec hit expands into ALL its surviving Room rows (the dedup-correct behavior).
        val rows = delegate.getScreenshotsByContentHash(rankedHashes)
        val byHash = HashMap<String, MutableList<ScreenshotModel>>().apply {
            for (row in rows) {
                val h = row.contentHash
                if (h != null) getOrPut(h) { ArrayList() }.add(row)
            }
        }
        val result = ArrayList<ScreenshotModel>(rows.size)
        for (hash in rankedHashes) {
            val hashRows = byHash[hash]
            if (hashRows.isNullOrEmpty()) {
                // Stale: zvec matched a hash with no surviving Room row (all duplicates deleted
                // since the zvec doc was written). Filter silently; debug-log the hash.
                if (DEBUG_STALENESS) {
                    Log.d(TAG, "search: stale zvec hash not in Room (deleted); filtered: $hash")
                }
                ZvecEventRecorder.record { "Staleness drop: hash ${hash.take(8)}... filtered (no Room row)" }
            } else {
                result.addAll(hashRows)
            }
        }
        return rankStage.apply(result, scores, policy)
    }

    /**
     * `getContentText` → zvec fetch by the D13 bridge column. One hop, no cache dependency on the
     * read path. Null for a row whose [ScreenshotModel.contentHash] is null (not yet indexed into
     * zvec) or whose zvec doc is absent. The old Room `screenshot_content` row died with the content
     * tables; this is its zvec replacement.
     */
    override suspend fun getContentText(screenshot: ScreenshotModel): String? {
        val hash = screenshot.contentHash ?: return null
        val doc = store.fetch(hash) ?: return null
        return (doc.fields[ZvecContentStore.FIELD_CONTENT] as? ZvecValue.Str)?.value
    }

    /**
     * The query pre-shape applied to user input before hitting zvec FTS. Delegates to the top-level
     * [processFtsQuery] (single source of truth) so the debug ZvecInspectorRunner can apply the
     * identical transform without reaching the concrete repository class.
     */
    internal fun processQuery(queryText: String): String = processFtsQuery(queryText)

    private companion object {
        const val TAG = "ZvecScreenshotRepository"
        const val DEBUG_STALENESS = true
    }
}

/**
 * The query pre-shape from the Room era, preserved verbatim so the UI's existing search semantics
 * carry over. Splits on whitespace / quotes / dashes / asterisks and rejoins as space-separated
 * terms with a trailing `*` wildcard each (prefix-match per term). A live-engine probe (2026-07-01)
 * confirmed zvec's FTS match-string accepts this shape.
 *
 * Top-level (not a member of [ZvecScreenshotRepository]) so the debug ZvecInspectorRunner can apply
 * the identical transform — the inspector's Query Inspector must see what production sees, not a
 * divergent raw view that would misdiagnose a prefix-match result as a recall failure (O3).
 */
internal fun processFtsQuery(queryText: String): String =
    queryText.split("[ \"\\-*]".toRegex())
        .filter { it.isNotEmpty() }
        .joinToString(" ", "", "*")
