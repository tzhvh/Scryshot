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
import io.github.tzhvh.scryernext.search.RankPolicy
import java.security.MessageDigest

class ScreenshotDatabaseRepository(internal val database: ScreenshotDatabase) : ScreenshotRepository {

    companion object {
        /** SHA-256 hex lookup — shared with `ZvecWriteSink` + the façade's read path. */
        val HEX = "0123456789abcdef".toCharArray()

        fun create(context: Context, onCreated: () -> Unit): ScreenshotDatabaseRepository {
            val callback = object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    onCreated()
                }
            }
            return ScreenshotDatabaseRepository(
                    Room.databaseBuilder(context.applicationContext, ScreenshotDatabase::class.java,
                            "screenshot-db")
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
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

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `screenshot` ADD COLUMN `processed` INTEGER NOT NULL DEFAULT 0")
                database.execSQL(
                    "UPDATE `screenshot` SET `processed` = 1 WHERE `id` IN (SELECT `id` FROM `screenshot_content` WHERE `content_text` IS NOT NULL)"
                )
            }
        }

        /**
         * zvec Phase 2, issue 01: adds the [ContentMetadataCache] table — the dedup fast path for
         * content_hash identity. Purely additive (no existing column/table changes), starts empty,
         * and warms lazily as the engine ingests. There is no installed base to backfill, so no
         * `processed`-flip or content migration is needed here (hard cutover — see ZVEC_PHASE2.md).
         * DDL must mirror exactly what Room generates from the @Entity (composite PK + the
         * content_hash index + the `indexed` default) so the schema-export stays valid.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `content_metadata_cache` (" +
                    "`locator` TEXT NOT NULL, " +
                    "`mtime` INTEGER NOT NULL, " +
                    "`size` INTEGER NOT NULL, " +
                    "`content_hash` TEXT NOT NULL, " +
                    "`indexed` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`locator`, `mtime`, `size`))"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_content_metadata_cache_content_hash` " +
                    "ON `content_metadata_cache` (`content_hash`)"
                )
            }
        }

        /**
         * zvec Phase 2, issue 03: adds the nullable `content_hash` bridge column to `screenshot`
         * (decision D13 — the read bridge from a UUID-keyed gallery row to a content_hash-keyed zvec
         * doc). Purely additive (`ALTER TABLE … ADD COLUMN`), nullable so existing rows start "not
         * yet indexed into zvec" and are populated lazily by `ZvecWriteSink` as the engine ingests.
         * No backfill under hard cutover (no installed base to migrate); a plain `ADD COLUMN` mirrors
         * exactly what Room generates from the @Entity so the schema-export stays valid.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `screenshot` ADD COLUMN `content_hash` TEXT")
            }
        }

        /**
         * zvec Phase 2, issue 04: drops the Room content tables (`screenshot_content` + its FTS4
         * shadow `fts`). OCR content now lives in zvec, surfaced through `ZvecScreenshotRepository`'s
         * search + `getContentText` (FTS over zvec's `content` field). Hard cutover — no installed
         * base, so nothing to salvage; the tables are simply dropped. `ScreenshotModel` (the gallery
         * row) + `CollectionModel` + `ContentMetadataCache` all stay (D8 — Room keeps the listing /
         * enumeration / queue jobs zvec can't do).
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP TABLE IF EXISTS `screenshot_content`")
                database.execSQL("DROP TABLE IF EXISTS `fts`")
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

    /**
     * zvec Phase 2, issue 04: content search is now served from zvec by [ZvecScreenshotRepository],
     * which overrides this method. The Room FTS4 search JOIN died with the content tables (issue 04);
     * there is no Room-side search to delegate to. Reaching this base impl means the façade wasn't
     * wired — fail loudly rather than return a silent empty result (a "search works" symptom would
     * hide the wiring regression).
     */
    override fun searchScreenshots(queryText: String, policy: RankPolicy): Flow<List<ScreenshotModel>> =
        throw UnsupportedOperationException("searchScreenshots is served by ZvecScreenshotRepository (zvec); wire the façade.")

    override suspend fun searchScreenshotList(queryText: String, policy: RankPolicy): List<ScreenshotModel> =
        throw UnsupportedOperationException("searchScreenshotList is served by ZvecScreenshotRepository (zvec); wire the façade.")

    /**
     * zvec Phase 2, issue 04: content-text reads are served from zvec by [ZvecScreenshotRepository],
     * which overrides this method. The Room `screenshot_content` row died with the content tables.
     */
    override suspend fun getContentText(screenshot: ScreenshotModel): String? =
        throw UnsupportedOperationException("getContentText is served by ZvecScreenshotRepository (zvec); wire the façade.")

    override suspend fun isKnown(candidate: Candidate, bytes: ByteArray): DedupResult = withContext(Dispatchers.IO) {
        // ── Phase 2 issue 02 (READ→DEDUP reorder): the engine has already opened+read the file once
        //    and hands us `bytes`. Dedup runs AFTER the read (ADR 0004 Amendment 2 [G1]) so the same
        //    bytes flow to OCR with no re-open. Two resolution paths:
        //
        // 1. Cheap path — metadata cache hit on (locator, mtime, size)? → no hash, no open.
        //    Bridge mtime/size from the matching ScreenshotModel row (the producer has already
        //    inserted it — Model B). A hit returns the cached `indexed` flag directly.
        // 2. Miss → hash the bytes (SHA-256, the zvec-era identity) and check the cache's hash index
        //    (a duplicate file under a different uri warms it earlier in the same run). The cache's
        //    `indexed` flag is the zvec-PK-existence proxy: `ZvecWriteSink` sets it after the zvec
        //    upsert (issue 03), so a hit means "content already in zvec." A genuine miss (hash not in
        //    the cache at all) returns false — the engine proceeds to OCR + write.
        //
        //    A producer-pre-computed identity still wins on the cheap path's row lookup; under Room
        //    the locator *is* the identity (uri is the unique index).
        //
        //    D1: returns DedupResult so the dedup-skip branch can stamp the resolved content_hash onto
        //    the duplicate's Room row (D2). The hash is threaded only on the miss path (where it was
        //    computed); the cheap path returns null — a cheap-path duplicate already has its hash set
        //    by a prior full-pipeline write of the same uri.
        val screenshot = candidate.locator?.let { database.screenshotDao().getScreenshotByUri(it) }
        if (screenshot != null) {
            // The cache key is the filesystem triple; a change to mtime/size naturally forces a miss
            // (composite PK no longer matches) → fall through to the miss path → re-resolve.
            database.contentMetadataCacheDao()
                .lookup(screenshot.uri, screenshot.lastModified, screenshot.size)
                ?.let { return@withContext DedupResult(known = it.indexed) }
        }
        // Miss path: hash the bytes, consult the cache's hash index. The hash index is the
        // cross-locator dedup path (same content under a different uri) and the zvec-existence proxy.
        val hash = sha256(bytes)
        val known = database.contentMetadataCacheDao().lookupByHash(hash)?.indexed ?: false
        DedupResult(known = known, resolvedContentHash = if (known) hash else null)
    }

    override suspend fun markProcessed(candidate: Candidate) {
        // The dedup-skip seam: retire a known-content row from the producer's `processed = 0`
        // queue so it isn't re-pulled/re-read/re-hashed on every run. Resolved by locator (uri is
        // the unique index) and flipped processed = true. No-ops if there's no row to retire (a
        // null locator, or the row was deleted mid-run) — matching RoomWriteSink's missing-row policy.
        val locator = candidate.locator ?: return
        withContext(Dispatchers.IO) {
            val screenshot = database.screenshotDao().getScreenshotByUri(locator) ?: return@withContext
            database.screenshotDao().updateScreenshot(listOf(screenshot.copy(processed = true)))
        }
    }

    override suspend fun markContentIndexed(screenshot: ScreenshotModel, contentHash: String) {
        // The zvec-success retirement: record the bridge hash (D13) + flip processed = 1 in one
        // update. Called by ZvecWriteSink AFTER the zvec upsert (zvec-first ordering — see the
        // interface KDoc). Uses the row's stable `id` (not locator) since the caller already holds
        // the resolved ScreenshotModel.
        withContext(Dispatchers.IO) {
            database.screenshotDao().markContentIndexed(screenshot.id, contentHash)
        }
    }

    override suspend fun getUnprocessedScreenshotList(): List<ScreenshotModel> {
        return withContext(Dispatchers.IO) {
            database.screenshotDao().getUnprocessed()
        }
    }

    override suspend fun getUnprocessedCount(): Int {
        return withContext(Dispatchers.IO) {
            database.screenshotDao().getUnprocessedCount()
        }
    }

    override suspend fun getScreenshotByUri(uri: String): ScreenshotModel? {
        return withContext(Dispatchers.IO) {
            database.screenshotDao().getScreenshotByUri(uri)
        }
    }

    /**
     * zvec Phase 2, issue 04: the **batched** search-result → gallery-row bridge, exposed for
     * [ZvecScreenshotRepository]'s read-flip. zvec FTS returns content docs carrying `locator`
     * (=uri); resolving each with [getScreenshotByUri] would N+1. This resolves the whole result set
     * in one `WHERE uri IN (...)` query. Callers index the result by `uri` to preserve zvec rank
     * order; a locator absent here means the screenshot was deleted from Room between the zvec FTS
     * query and this lookup (the façade filters those nulls silently).
     */
    suspend fun getScreenshotsByUri(uris: List<String>): List<ScreenshotModel> {
        if (uris.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            database.screenshotDao().getScreenshotsByUri(uris)
        }
    }

    /**
     * zvec Phase 2, D3 — the dedup-correct bridge. Returns ALL Room rows sharing each content_hash,
     * so a duplicate whose uri isn't zvec's stored locator is still found. Callers group by hash to
     * expand each zvec hit into its surviving rows.
     */
    suspend fun getScreenshotsByContentHash(hashes: List<String>): List<ScreenshotModel> {
        if (hashes.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            database.screenshotDao().getScreenshotsByContentHash(hashes)
        }
    }

    /**
     * SHA-256 hex of [bytes] — the zvec-era content identity. Shared with `ZvecWriteSink` / the
     * façade's read path so every side computes the same PK. (Issue 04: the base repo's `isKnown`
     * miss-path hashes here and checks the cache's hash index; the sink hashes here and upserts to
     * zvec on the result.)
     */
    internal fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }
}
