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
     * TODO(Phase 1): "known" must mean `processed = true`, not merely "a row exists" — otherwise permanent-content
     * failures (corrupt/illegible, written with empty text + processed=true per ADR 0004 §7.2) re-poison the
     * backlog via the discovery worker. Until the `processed` flag lands, this returns true iff an indexed record
     * exists for the candidate's identity/locator — the same semantics as today's `getContentText == null` check.
     */
    suspend fun isKnown(candidate: Candidate): Boolean

    suspend fun setupDefaultContent(context: Context)
}
