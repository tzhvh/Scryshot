/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.persistence

/**
 * In-memory [ContentMetadataCacheDao] for instrumented tests that need the cache warmed by
 * [io.github.tzhvh.scryernext.ingestion.ZvecWriteSink] without standing up a full Room DB. Mirrors
 * the DAO's contract: composite-PK lookup, hash-index lookup, upsert (replace), markIndexed.
 */
class ContentMetadataCacheDaoFake : ContentMetadataCacheDao {
    private val rows = mutableListOf<ContentMetadataCache>()

    override fun lookup(locator: String, mtime: Long, size: Long): ContentMetadataCache? =
        rows.find { it.locator == locator && it.mtime == mtime && it.size == size }

    override fun lookupByHash(contentHash: String): ContentMetadataCache? =
        rows.find { it.contentHash == contentHash }

    override fun upsert(entry: ContentMetadataCache) {
        rows.removeAll { it.locator == entry.locator && it.mtime == entry.mtime && it.size == entry.size }
        rows += entry
    }

    override fun markIndexed(contentHash: String): Int {
        var n = 0
        for (i in rows.indices) {
            if (rows[i].contentHash == contentHash) {
                rows[i] = rows[i].copy(indexed = true)
                n++
            }
        }
        return n
    }
}
