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
     * Has this candidate's content already been ingested? (ADR 0004 §3.)
     *
     * Identity resolution lives here, on the repository — the engine never resolves identity itself.
     * - If [Candidate.identity] is non-null (a producer pre-computed it, e.g. the SAF I/O-pool pre-hashed),
     *   reuse it directly — no re-derivation, no double-I/O.
     * - If [Candidate.identity] is null, the repository falls back to its own identity model: locator/URI lookup
     *   under Room; content_hash (computed from [Candidate.byteHandle] if needed) under zvec.
     *
     * `suspend` because the zvec-era content-hash computation streams bytes and can throw / be cancelled; a plain
     * `fun: Boolean` would foreclose that. This signature is the contract that survives the Room→zvec transition.
     *
     * "known" means `processed = true` (an indexed record), and the implementation
     * (`ScreenshotDatabaseRepository.isKnown` + `dbKeysByLocator`) honours the `processed`-aware
     * query.
     */
    suspend fun isKnown(candidate: Candidate): Boolean

    suspend fun getUnprocessedScreenshotList(): List<ScreenshotModel>

    suspend fun getUnprocessedCount(): Int

    /** Issue 11/#4: lookup a screenshot by its `uri` locator (cheap indexed query). */
    suspend fun getScreenshotByUri(uri: String): ScreenshotModel?

    suspend fun setupDefaultContent(context: Context)
}
