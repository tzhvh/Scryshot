/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.persistence

import androidx.lifecycle.LiveData
import androidx.room.*
import androidx.room.OnConflictStrategy.Companion.REPLACE

@Dao
interface ScreenshotDao {

    @Query("SELECT * FROM screenshot WHERE id = :screenshotId")
    fun getScreenshot(screenshotId: String): ScreenshotModel

    @Query("SELECT * FROM screenshot")
    fun getScreenshots(): LiveData<List<ScreenshotModel>>
    @Query("SELECT * FROM screenshot")
    fun getScreenshotList(): List<ScreenshotModel>

    @Query("SELECT * FROM screenshot WHERE collection_id IN(:collectionIds)")
    fun getScreenshots(collectionIds: List<String>): LiveData<List<ScreenshotModel>>
    @Query("SELECT * FROM screenshot WHERE collection_id IN(:collectionIds)")
    fun getScreenshotList(collectionIds: List<String>): List<ScreenshotModel>

    @Insert(onConflict = OnConflictStrategy.Companion.IGNORE)
    fun addScreenshot(screenshot: List<ScreenshotModel>)

    @Update(onConflict = REPLACE)
    fun updateScreenshot(screenshots: List<ScreenshotModel>)

    @Delete
    fun deleteScreenshot(screenshot: ScreenshotModel)

    @Query("SELECT screenshot.* FROM (SELECT id, max(last_modified) AS max_date FROM screenshot GROUP BY collection_id) AS latest INNER JOIN screenshot ON latest.id = screenshot.id AND screenshot.last_modified = latest.max_date")
    fun getCollectionCovers(): LiveData<List<ScreenshotModel>>

    /**
     * zvec Phase 2, issue 03: record the [contentHash] bridge column (D13) **and** retire the row
     * (`processed = 1`) in one update. Called by `ZvecWriteSink` *after* the zvec upsert (zvec-first
     * ordering — a crash between the two leaves the row un-`processed`, so the producer re-pulls it
     * and `isKnown` self-heals on the next run; R8 makes the re-upsert a no-op). The hash is the
     * bridge `getContentText` (issue 04) fetches from zvec by.
     */
    @Query("UPDATE screenshot SET content_hash = :contentHash, processed = 1 WHERE id = :id")
    fun markContentIndexed(id: String, contentHash: String)

    @Query("SELECT * FROM screenshot WHERE processed = 0")
    fun getUnprocessed(): List<ScreenshotModel>

    @Query("SELECT COUNT(*) FROM screenshot WHERE processed = 0")
    fun getUnprocessedCount(): Int

    /** Issue 11/#4: lookup by `uri` (the indexed unique column) without materializing every row. */
    @Query("SELECT * FROM screenshot WHERE uri = :uri LIMIT 1")
    fun getScreenshotByUri(uri: String): ScreenshotModel?

    /**
     * zvec Phase 2, issue 04: the **batched** search-result → gallery-row bridge. zvec FTS search
     * returns content docs carrying `locator` (=uri); the UI needs the [ScreenshotModel] gallery row.
     * Resolving each result with [getScreenshotByUri] would N+1 (40 results = 40 Room queries); this
     * resolves the whole result set in one `WHERE uri IN (...)` query.
     *
     * Callers preserve zvec rank order by indexing the returned rows by `uri` (not by the Room result
     * order) when mapping back. A row deleted from Room between the zvec FTS query and this lookup is
     * simply absent here — the façade filters those nulls silently (a stale zvec doc must not surface).
     */
    @Query("SELECT * FROM screenshot WHERE uri IN (:uris)")
    fun getScreenshotsByUri(uris: List<String>): List<ScreenshotModel>
}
