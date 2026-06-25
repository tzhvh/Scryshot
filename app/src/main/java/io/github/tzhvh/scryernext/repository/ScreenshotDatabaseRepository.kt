/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.repository

import android.content.Context
import androidx.lifecycle.asFlow
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import io.github.tzhvh.scryernext.R
import io.github.tzhvh.scryernext.persistence.*
import io.github.tzhvh.scryernext.ingestion.Candidate

class ScreenshotDatabaseRepository(private val database: ScreenshotDatabase) : ScreenshotRepository {

    companion object {
        fun create(context: Context, onCreated: () -> Unit): ScreenshotDatabaseRepository {
            val callback = object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    onCreated()
                }
            }
            return ScreenshotDatabaseRepository(
                    Room.databaseBuilder(context.applicationContext, ScreenshotDatabase::class.java,
                            "screenshot-db")
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                            .addCallback(callback)
                            .build()
            )
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                        "CREATE TABLE IF NOT EXISTS `screenshot_content` (`id` TEXT NOT NULL, `content_text` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`id`) REFERENCES `screenshot`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                database.execSQL(
                        "CREATE VIRTUAL TABLE IF NOT EXISTS `fts` USING FTS4(" +
                        "`content_text`, " +
                        "content=`screenshot_content`)"
                )
            }
        }

        /**
         * Issue 21: destructive v2→v3 migration. The screenshot identity column changes from
         * `absolute_path` (filesystem path) to `uri` (content:// MediaStore URI), and two cached
         * columns (`display_name`, `size`) are added so list rendering avoids ContentResolver
         * queries on the UI thread. Old rows held filesystem paths that are meaningless under
         * scoped storage, so the table is wiped and recreated rather than transformed. This is a
         * personal fork with no users to migrate; zvec treats path/URI as an opaque locator.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Drop old screenshot artifacts (table, indices, FTS content) and let Room
                // recreate the v3 schema from the @Entity definitions on first access.
                database.execSQL("DROP TABLE IF EXISTS `screenshot`")
                database.execSQL(
                        "CREATE TABLE IF NOT EXISTS `screenshot` (" +
                        "`id` TEXT NOT NULL, " +
                        "`uri` TEXT NOT NULL, " +
                        "`display_name` TEXT NOT NULL, " +
                        "`size` INTEGER NOT NULL, " +
                        "`last_modified` INTEGER NOT NULL, " +
                        "`collection_id` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_screenshot_uri` ON `screenshot` (`uri`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_screenshot_collection_id` ON `screenshot` (`collection_id`)")
            }
        }
    }

    private val collectionListData = database.collectionDao().getCollections()
    private val screenshotListData = database.screenshotDao().getScreenshots()

    override suspend fun addScreenshot(screenshots: List<ScreenshotModel>) {
        withContext(Dispatchers.IO) {
            database.screenshotDao().addScreenshot(screenshots)
        }
    }

    override suspend fun updateScreenshots(screenshots: List<ScreenshotModel>) {
        withContext(Dispatchers.IO) {
            database.screenshotDao().updateScreenshot(screenshots)
        }
    }

    override suspend fun getScreenshot(screenshotId: String): ScreenshotModel? {
        return withContext(Dispatchers.IO) {
            database.screenshotDao().getScreenshot(screenshotId)
        }
    }

    override fun getScreenshots(collectionIds: List<String>): Flow<List<ScreenshotModel>> {
        return database.screenshotDao().getScreenshots(collectionIds).asFlow()
    }

    override fun getScreenshots(): Flow<List<ScreenshotModel>> {
        return screenshotListData.asFlow()
    }

    override suspend fun deleteScreenshot(screenshot: ScreenshotModel) {
        withContext(Dispatchers.IO) {
            database.screenshotDao().deleteScreenshot(screenshot)
        }
    }

    override fun getCollections(): Flow<List<CollectionModel>> {
        return collectionListData.asFlow()
    }

    override suspend fun getCollectionList(): List<CollectionModel> {
        return withContext(Dispatchers.IO) {
            database.collectionDao().getCollectionList()
        }
    }

    override suspend fun addCollection(collection: CollectionModel) {
        withContext(Dispatchers.IO) {
            database.collectionDao().addCollection(collection)
        }
    }

    override suspend fun getCollection(id: String): CollectionModel? {
        return withContext(Dispatchers.IO) {
            database.collectionDao().getCollection(id)
        }
    }

    override fun getCollectionCovers(): Flow<Map<String, ScreenshotModel>> {
        return database.screenshotDao().getCollectionCovers().asFlow().map { models ->
            models.map { it.collectionId to it }.toMap()
        }
    }

    override suspend fun updateCollection(collection: CollectionModel) {
        withContext(Dispatchers.IO) {
            database.collectionDao().updateCollection(collection)
        }
    }

    override suspend fun setupDefaultContent(context: Context) {
        val none = CollectionModel(CollectionModel.CATEGORY_NONE,
                context.getString(R.string.home_action_unsorted), 0, 0)
        addCollection(none)

        val nameList = listOf(R.string.sorting_suggestion_1st,
                R.string.sorting_suggestion_2nd,
                R.string.sorting_suggestion_3rd,
                R.string.sorting_suggestion_4th,
                R.string.sorting_suggestion_5th)

        if (nameList.size < SuggestCollectionHelper.suggestCollections.size) {
            throw RuntimeException("Not enough name for all suggestion collection")
        }

        SuggestCollectionHelper.suggestCollections.forEachIndexed { index, collection ->
            collection.name = context.getString(nameList[index])
            addCollection(collection)
        }
    }

    override suspend fun getScreenshotList(): List<ScreenshotModel> {
        return withContext(Dispatchers.IO) {
            database.screenshotDao().getScreenshotList()
        }
    }

    override suspend fun getScreenshotList(collectionIds: List<String>): List<ScreenshotModel> {
        return withContext(Dispatchers.IO) {
            database.screenshotDao().getScreenshotList(collectionIds)
        }
    }

    override suspend fun deleteCollection(collection: CollectionModel) {
        withContext(Dispatchers.IO) {
            database.collectionDao().deleteCollection(collection)
        }
    }

    override suspend fun updateCollectionId(collection: CollectionModel, id: String) {
        withContext(Dispatchers.IO) {
            database.collectionDao().updateCollectionId(collection, id)
        }
    }

    override fun searchScreenshots(queryText: String): Flow<List<ScreenshotModel>> {
        return MatchStrategy().search(queryText, database)
    }

    override suspend fun searchScreenshotList(queryText: String): List<ScreenshotModel> {
        return MatchStrategy().searchList(queryText, database)
    }

    override fun getScreenshotContent(): Flow<List<ScreenshotContentModel>> {
        return database.screenshotDao().getScreenshotContent().asFlow()
    }

    override suspend fun updateScreenshotContent(screenshotContent: ScreenshotContentModel) {
        withContext(Dispatchers.IO) {
            database.screenshotDao().updateContentText(screenshotContent)
        }
    }

    override suspend fun getContentText(screenshot: ScreenshotModel): String? {
        return withContext(Dispatchers.IO) {
            database.screenshotDao().getContentText(screenshot.id)?.contentText
        }
    }

    override suspend fun isKnown(candidate: Candidate): Boolean {
        return withContext(Dispatchers.IO) {
            when {
                // A producer handed us a pre-computed identity. Under Room the locator *is* the identity (uri is
                // the unique index), so treat a non-null identity as the locator to look up.
                candidate.identity != null -> candidate.identity in dbKeysByLocator()
                // No pre-computed identity: fall back to the locator (URI) lookup.
                candidate.locator != null -> candidate.locator in dbKeysByLocator()
                // Neither identity nor locator: conservatively unknown (the engine will attempt it).
                else -> false
            }
        }
    }

    private fun dbKeysByLocator(): Set<String> {
        return database.screenshotDao().getIndexedUris().toSet()
    }

    private interface SearchStrategy {
        fun search(
                queryText: String,
                database: ScreenshotDatabase
        ): Flow<List<ScreenshotModel>>

        suspend fun searchList(
                queryText: String,
                database: ScreenshotDatabase
        ): List<ScreenshotModel>
    }

    private class MatchStrategy : SearchStrategy {
        override fun search(
                queryText: String,
                database: ScreenshotDatabase
        ): Flow<List<ScreenshotModel>> {
            return database.screenshotDao().searchScreenshots(processQuery(queryText)).asFlow()
        }

        override suspend fun searchList(
                queryText: String,
                database: ScreenshotDatabase
        ): List<ScreenshotModel> {
            return withContext(Dispatchers.IO) {
                database.screenshotDao().searchScreenshotList(processQuery(queryText))
            }
        }

        private fun processQuery(queryText: String): String {
            return queryText
                    .split("[ \"\\-*]".toRegex())
                    .joinToString(" ", "", "*")
        }
    }
}
