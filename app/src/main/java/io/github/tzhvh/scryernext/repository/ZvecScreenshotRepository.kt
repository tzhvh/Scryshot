/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.repository

import android.util.Log
import io.github.tzhvh.scryernext.ingestion.ZvecContentStore
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
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
) : ScreenshotRepository by delegate {

    /**
     * The metadata-cache DAO on the underlying Room DB — exposed so the WorkManager path
     * ([io.github.tzhvh.scryernext.ingestion.triggers.IngestionWorker]) can build a `ZvecWriteSink`
     * with the same cache-DAO provider as `ScryerApplication.onCreate`, without casting the
     * repository to the concrete [delegate] type (the façade wraps the Room repo; the cache lives on
     * the Room side, so this delegates straight through).
     */
    fun metadataCacheDao(): io.github.tzhvh.scryernext.persistence.ContentMetadataCacheDao =
        delegate.database.contentMetadataCacheDao()

    /**
     * FTS search routed to zvec, bridged back to gallery rows. Returns a cold [Flow] so the existing
     * `Flow<List<ScreenshotModel>>` search UI ([FullTextSearchFragment]) is unchanged — every search
     * re-runs the zvec query + Room lookup. The zvec result is finite (zvec returns a ranked set, not
     * a stream), so the flow emits exactly once per query and completes.
     *
     * Rank order is preserved by indexing the Room rows by `uri` and walking the zvec result order
     * (NOT the Room result order). A locator absent from Room (the screenshot was deleted between the
     * zvec FTS query and the lookup — a delete-during-search race) is filtered **silently**: a stale
     * zvec doc must not surface in search. Logged at debug so the staleness is observable.
     */
    override fun searchScreenshots(queryText: String): Flow<List<ScreenshotModel>> = flow {
        emit(searchZvecAndBridge(queryText))
    }.flowOn(Dispatchers.IO)

    override suspend fun searchScreenshotList(queryText: String): List<ScreenshotModel> =
        withContext(Dispatchers.IO) { searchZvecAndBridge(queryText) }

    /**
     * The shared zvec-FTS-then-batched-Room-lookup body for both the Flow and the List entry points.
     * See [searchScreenshots] for the bridge + null policy.
     */
    private suspend fun searchZvecAndBridge(queryText: String): List<ScreenshotModel> {
        val trimmed = queryText.trim()
        if (trimmed.isEmpty()) return emptyList()

        val docs = store.search(processQuery(trimmed))
        if (docs.isEmpty()) return emptyList()

        // The locator (uri) is the gallery-row bridge. Collect them preserving zvec rank order.
        val rankedLocators = ArrayList<String>(docs.size)
        for (doc in docs) {
            val locator = (doc.fields[ZvecContentStore.FIELD_LOCATOR] as? ZvecValue.Str)?.value
            if (locator != null) rankedLocators.add(locator)
        }
        if (rankedLocators.isEmpty()) return emptyList()

        // One batched Room query (WHERE uri IN (...)) — NOT N+1 per-result lookups. Index by uri so
        // the walk-back preserves zvec rank, not the arbitrary Room result order.
        val rows = delegate.getScreenshotsByUri(rankedLocators)
        val byUri = HashMap<String, ScreenshotModel>(rows.size).apply {
            for (row in rows) put(row.uri, row)
        }
        val result = ArrayList<ScreenshotModel>(rankedLocators.size)
        for (locator in rankedLocators) {
            val row = byUri[locator]
            if (row != null) {
                result.add(row)
            } else if (DEBUG_STALENESS) {
                // A stale zvec doc: the screenshot was deleted from Room after indexing. Filtered
                // silently (debug-logged) — a normal delete-during-search race, not an error.
                Log.d(TAG, "search: stale zvec locator not in Room (deleted); filtered: $locator")
            }
        }
        return result
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
     * The query pre-shape from the Room era (`processQuery`), preserved verbatim so the UI's existing
     * search semantics carry over. Splits on whitespace / quotes / dashes / asterisks and rejoins as
     * space-separated terms with a trailing `*` wildcard each (prefix-match per term). A live-engine
     * probe (2026-07-01) confirmed zvec's FTS match-string accepts this shape.
     */
    private fun processQuery(queryText: String): String =
        queryText.split("[ \"\\-*]".toRegex())
            .filter { it.isNotEmpty() }
            .joinToString(" ", "", "*")

    private companion object {
        const val TAG = "ZvecScreenshotRepository"
        const val DEBUG_STALENESS = true
    }
}
