/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.scryer.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.scryer.persistence.CollectionModel
import org.mozilla.scryer.persistence.ScreenshotContentModel
import org.mozilla.scryer.persistence.ScreenshotModel

@Suppress("unused")
class ScreenshotInMemoryRepository : ScreenshotRepository {

    private val collectionData = MutableStateFlow<List<CollectionModel>>(emptyList())
    private val collectionList = mutableListOf<CollectionModel>()
    private val screenshotData = MutableStateFlow<List<ScreenshotModel>>(emptyList())
    private val screenshotList = mutableListOf<ScreenshotModel>()
    private val screenshotContentData = MutableStateFlow<List<ScreenshotContentModel>>(emptyList())

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

    override fun searchScreenshots(queryText: String): Flow<List<ScreenshotModel>> {
        return screenshotData.asStateFlow()
    }

    override suspend fun searchScreenshotList(queryText: String): List<ScreenshotModel> {
        return screenshotList
    }

    override suspend fun updateScreenshotContent(screenshotContent: ScreenshotContentModel) {}

    override suspend fun getContentText(screenshot: ScreenshotModel): String? {
        return ""
    }

    override fun getScreenshotContent(): Flow<List<ScreenshotContentModel>> {
        return screenshotContentData.asStateFlow()
    }

    override suspend fun setupDefaultContent(context: android.content.Context) {}
}
