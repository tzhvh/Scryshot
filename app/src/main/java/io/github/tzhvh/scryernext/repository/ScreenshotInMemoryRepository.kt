/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.tzhvh.scryernext.persistence.CollectionModel
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import io.github.tzhvh.scryernext.ingestion.Candidate
import io.github.tzhvh.scryernext.search.PrecisionMode
import io.github.tzhvh.scryernext.search.RankPolicy

@Suppress("unused")
open class ScreenshotInMemoryRepository : ScreenshotRepository {

    private val collectionData = MutableStateFlow<List<CollectionModel>>(emptyList())
    private val collectionList = mutableListOf<CollectionModel>()
    private val screenshotData = MutableStateFlow<List<ScreenshotModel>>(emptyList())
    private val screenshotList = mutableListOf<ScreenshotModel>()

    override suspend fun addCollection(collection: CollectionModel) {
        collectionList.add(collection)
        collectionData.value = collectionList.toList()
    }

    override fun getCollections(): Flow<List<CollectionModel>> {
        return collectionData.asStateFlow()
    }

    override suspend fun getCollectionList(): List<CollectionModel> {
        return collectionList
    }

    override fun getCollectionCovers(): Flow<Map<String, ScreenshotModel>> {
        return MutableStateFlow(emptyMap())
    }

    override suspend fun addScreenshot(screenshots: List<ScreenshotModel>) {
        screenshotList.addAll(screenshots)
        screenshotData.value = screenshotList.toList()
    }

    override suspend fun updateScreenshots(screenshots: List<ScreenshotModel>) {
        screenshotData.value = screenshotList.toList()
    }

    override suspend fun getScreenshot(screenshotId: String): ScreenshotModel? {
        return screenshotList.find { it.id == screenshotId }
    }

    override fun getScreenshots(): Flow<List<ScreenshotModel>> {
        return screenshotData.asStateFlow()
    }

    override fun getScreenshots(collectionIds: List<String>): Flow<List<ScreenshotModel>> {
        return screenshotData.asStateFlow()
    }

    override suspend fun deleteScreenshot(screenshot: ScreenshotModel) {
        screenshotList.remove(screenshot)
        screenshotData.value = screenshotList.toList()
    }

    override suspend fun getScreenshotList(): List<ScreenshotModel> {
        return screenshotList
    }

    override suspend fun getScreenshotList(collectionIds: List<String>): List<ScreenshotModel> {
        return screenshotList
    }

    override suspend fun updateCollection(collection: CollectionModel) {
    }

    override suspend fun deleteCollection(collection: CollectionModel) {
    }

    override suspend fun updateCollectionId(collection: CollectionModel, id: String) {
    }

    override suspend fun getCollection(id: String): CollectionModel? {
        return null
    }

    override fun searchScreenshots(
        queryText: String,
        policy: RankPolicy,
        filter: String?,
        precision: PrecisionMode,
    ): Flow<SearchOutcome> {
        return kotlinx.coroutines.flow.flow { emit(SearchOutcome.Results(screenshotList)) }
    }

    override suspend fun searchScreenshotList(
        queryText: String,
        policy: RankPolicy,
        filter: String?,
        precision: PrecisionMode,
    ): SearchOutcome {
        return SearchOutcome.Results(screenshotList)
    }

    /**
     * zvec Phase 2, issue 04: content-text reads are served from zvec by [ZvecScreenshotRepository].
     * This in-memory reference repo keeps a no-op stub so the interface compiles in JVM contexts;
     * it never held real content. (The Room content tables died with the cutover.)
     */
    override suspend fun getContentText(screenshot: ScreenshotModel): String? = null

    override suspend fun isKnown(candidate: Candidate, bytes: ByteArray): DedupResult {
        // Phase 2 issue 02: the engine has already read `bytes` (READ→DEDUP reorder, ADR 0004 [G1]).
        // This in-memory repo is unused reference code; its contract is the post-reorder shape so the
        // interface compiles, but it keeps the Room-era locator/identity resolution (no SHA-256 gold-
        // plating — zvec's content_hash identity is issue 03's job, against the real DB repo + cache).
        // D1: returns DedupResult; this reference impl never resolves a hash (no resolvedContentHash).
        val key = candidate.identity ?: candidate.locator ?: return DedupResult.UNKNOWN
        val screenshot = screenshotList.find { it.uri == key } ?: return DedupResult.UNKNOWN
        return DedupResult(known = screenshot.processed)
    }

    override suspend fun markProcessed(candidate: Candidate) {
        // The dedup-skip seam: flip processed = true on the matched row so it leaves the producer's
        // `processed = 0` queue. Mirrors ScreenshotDatabaseRepository.markProcessed.
        val key = candidate.identity ?: candidate.locator ?: return
        screenshotList.find { it.uri == key }?.let { it.processed = true }
    }

    open override suspend fun markContentIndexed(screenshot: ScreenshotModel, contentHash: String) {
        // The zvec-success retirement: record the bridge hash + flip processed = true. Mirrors
        // ScreenshotDatabaseRepository.markContentIndexed.
        screenshotList.find { it.id == screenshot.id }?.let {
            it.contentHash = contentHash
            it.processed = true
        }
    }

    override suspend fun getUnprocessedScreenshotList(): List<ScreenshotModel> {
        return screenshotList.filter { !it.processed }
    }

    override suspend fun getUnprocessedCount(): Int {
        return screenshotList.count { !it.processed }
    }

    override suspend fun getScreenshotByUri(uri: String): ScreenshotModel? {
        return screenshotList.firstOrNull { it.uri == uri }
    }


    override suspend fun setupDefaultContent(context: android.content.Context) {}
}
