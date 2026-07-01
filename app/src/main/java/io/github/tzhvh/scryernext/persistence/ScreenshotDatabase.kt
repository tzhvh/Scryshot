/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.persistence

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * zvec Phase 2, issue 04: the content tables (`ScreenshotContentModel` + `FtsEntity`) are dropped —
 * OCR content now lives in zvec, surfaced through `ZvecScreenshotRepository`'s search + getContentText
 * (FTS over zvec's `content` field). `ScreenshotModel` (the gallery row) + `CollectionModel`
 * (collections, D4) + `ContentMetadataCache` (the dedup fast path) all stay — Room keeps the
 * listing/enumeration/queue jobs zvec can't do (D8). The `content_hash` bridge column on
 * `ScreenshotModel` (issue 03) is the row→zvec-doc link `getContentText` reads by.
 */
@Database(
        entities = [
            CollectionModel::class,
            ScreenshotModel::class,
            ContentMetadataCache::class
        ],
        version = 7
)
abstract class ScreenshotDatabase: RoomDatabase() {
    abstract fun screenshotDao(): ScreenshotDao
    abstract fun collectionDao(): CollectionDao
    abstract fun contentMetadataCacheDao(): ContentMetadataCacheDao
}
