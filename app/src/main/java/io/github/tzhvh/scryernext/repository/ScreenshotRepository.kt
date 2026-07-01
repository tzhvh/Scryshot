/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.repository

import android.content.Context
import kotlinx.coroutines.flow.Flow
import io.github.tzhvh.scryernext.persistence.CollectionModel
import io.github.tzhvh.scryernext.persistence.ScreenshotContentModel
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import io.github.tzhvh.scryernext.ingestion.Candidate

interface ScreenshotRepository {
    companion object Factory {
        fun createRepository(context: Context, onCreated: () -> Unit): ScreenshotRepository {
            return ScreenshotDatabaseRepository.create(context, onCreated)
        }
    }

    suspend fun addCollection(collection: CollectionModel)
    fun getCollections(): Flow<List<CollectionModel>>
    suspend fun getCollectionList(): List<CollectionModel>
    suspend fun getCollection(id: String): CollectionModel?
    /** collection_id to model */
    fun getCollectionCovers(): Flow<Map<String, ScreenshotModel>>
    suspend fun updateCollection(collection: CollectionModel)
    suspend fun updateCollectionId(collection: CollectionModel, id: String)
    suspend fun deleteCollection(collection: CollectionModel)

    suspend fun addScreenshot(screenshots: List<ScreenshotModel>)
    suspend fun updateScreenshots(screenshots: List<ScreenshotModel>)
    suspend fun getScreenshot(screenshotId: String): ScreenshotModel?
    fun getScreenshots(): Flow<List<ScreenshotModel>>
    suspend fun getScreenshotList(): List<ScreenshotModel>
    fun getScreenshots(collectionIds: List<String>): Flow<List<ScreenshotModel>>
    suspend fun getScreenshotList(collectionIds: List<String>): List<ScreenshotModel>
    suspend fun deleteScreenshot(screenshot: ScreenshotModel)
    fun searchScreenshots(queryText: String): Flow<List<ScreenshotModel>>
    suspend fun searchScreenshotList(queryText: String): List<ScreenshotModel>

    fun getScreenshotContent(): Flow<List<ScreenshotContentModel>>
    suspend fun updateScreenshotContent(screenshotContent: ScreenshotContentModel)
    suspend fun getContentText(screenshot: ScreenshotModel): String?

    /**
     * Has this candidate's content already been ingested? (ADR 0004 §3; Amendment 2 `[G1]`.)
     *
     * Identity resolution + dedup live here, on the repository — the engine never resolves identity.
     * The engine owns the **single READ** (it opens [Candidate.byteHandle] exactly once — honouring
     * Candidate's "at most once" contract and avoiding SAF's double-`open()` tax) and hands the
     * resulting [bytes] over. The repository then resolves known-status **without re-opening
     * anything**:
     *
     * 1. **Cheap path — metadata cache (no hash, no `open()`):** a hit on the filesystem triple
     *    `(locator, mtime, size)` returns the cached `indexed` flag directly. This is the
     *    steady-state fast path for unchanged files (zvec Phase 2 issue 01's
     *    `ContentMetadataCache`).
     * 2. **Miss path:** the repository hashes [bytes] (SHA-256) and checks the content_hash against
     *    the store — Room (via the cache's hash index / the processed-aware locator lookup) in the
     *    transition, zvec PK existence once issue 03 lands.
     *
     * `suspend` because the hash streams [bytes] and the lookup crosses a store; a plain
     * `fun: Boolean` would foreclose that. This signature is the contract that survives the
     * Room→zvec transition — the engine is already on the post-reorder READ→DEDUP loop, so swapping
     * the store (issue 03) needs no engine change.
     *
     * The dedup-skip branch that calls this must also call [markProcessed] so the row leaves the
     * producer's `processed = 0` queue — otherwise it is re-pulled every run.
     */
    suspend fun isKnown(candidate: Candidate, bytes: ByteArray): Boolean

    /**
     * Retire a candidate's row from the producer's work queue — the **dedup-skip seam** (the
     * `processed`-vs-`isKnown` distinction; ADR 0004 §3, ZVEC_PHASE2.md
     * § "The `processed` flag and the dedup-skip seam").
     *
     * `ScreenshotModel.processed` is the **producer's queue flag** (`MediaStoreProducer` queries
     * `WHERE processed = 0`); [isKnown] is the **content-hash dedup check**. They are keyed
     * differently. When [isKnown] returns `true`, the engine's dedup branch `continue`s **without**
     * calling the [io.github.tzhvh.scryernext.ingestion.WriteSink] — so if the sink is where
     * `processed` gets flipped, a dedup-skipped row stays `processed = 0` and the producer re-pulls
     * it every run, re-reads the bytes, re-hashes, and re-discovers it's a duplicate. This method
     * closes that loop: it resolves the row by [Candidate.locator] (`getScreenshotByUri`) and flips
     * `processed = true`, retiring it.
     *
     * Distinct from the sink's own `processed = true` write (which runs on the OCR-success /
     * permanent-failure paths): this is the no-OCR, content-already-known path.
     */
    suspend fun markProcessed(candidate: Candidate)

    suspend fun getUnprocessedScreenshotList(): List<ScreenshotModel>

    suspend fun getUnprocessedCount(): Int

    /** Issue 11/#4: lookup a screenshot by its `uri` locator (cheap indexed query). */
    suspend fun getScreenshotByUri(uri: String): ScreenshotModel?

    suspend fun setupDefaultContent(context: Context)
}
