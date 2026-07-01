/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy.Companion.REPLACE
import androidx.room.Query

/**
 * The dedup fast-path DAO for [ContentMetadataCache] (zvec Phase 2, issue 01).
 *
 * Matches the codebase DAO convention: synchronous (no `suspend`), no `Flow` — the repository
 * layer (issue 02) wraps these in `withContext(Dispatchers.IO)` and exposes `suspend`/`Flow` to
 * the engine. See `ScreenshotDao` for the established pattern.
 */
@Dao
interface ContentMetadataCacheDao {

    /**
     * The cheap, no-`open()` dedup check. Returns the cached row keyed on the filesystem triple
     * `(locator, mtime, size)`, or null on miss. A change to `mtime` or `size` naturally forces a
     * miss (the composite PK no longer matches), which is the desired "file changed → re-hash"
     * behaviour without an explicit invalidation call.
     */
    @Query("SELECT * FROM content_metadata_cache WHERE locator = :locator AND mtime = :mtime AND size = :size LIMIT 1")
    fun lookup(locator: String, mtime: Long, size: Long): ContentMetadataCache?

    /**
     * The post-hash secondary lookup. Used after hashing (a cheap-key miss) to short-circuit a
     * zvec PK-existence round-trip when the content is already known (e.g. a duplicate file under
     * a different uri warmed the cache earlier in the same run).
     */
    @Query("SELECT * FROM content_metadata_cache WHERE content_hash = :contentHash LIMIT 1")
    fun lookupByHash(contentHash: String): ContentMetadataCache?

    /**
     * Insert-or-replace on the `(locator, mtime, size)` composite PK. Re-inserting the same triple
     * is idempotent in effect (REPLACE collapses to the existing row). The `content_hash` secondary
     * index is updated as a side effect of the row write.
     */
    @Insert(onConflict = REPLACE)
    fun upsert(entry: ContentMetadataCache)

    /**
     * Flips `indexed = 1` for every row sharing [contentHash]. Called after a successful zvec write
     * (issue 03) so the next [lookup] for any (locator, mtime, size) resolving to this hash reports
     * `indexed = true` without re-querying zvec. Affects all rows for the hash because duplicate
     * files (same content, different locators) all map to it.
     *
     * Returns the number of rows updated so callers may assert the flag advanced.
     */
    @Query("UPDATE content_metadata_cache SET `indexed` = 1 WHERE content_hash = :contentHash")
    fun markIndexed(contentHash: String): Int
}
