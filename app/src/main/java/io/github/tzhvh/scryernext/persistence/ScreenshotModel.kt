/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.persistence

import androidx.room.*
import java.util.*

/**
 * Issue 21: identity column renamed from `absolute_path` (a filesystem path) to `uri`
 * (a `content://` MediaStore URI). Two cached columns were added so list rendering doesn't
 * have to resolve them asynchronously via ContentResolver on the UI thread:
 *  - `display_name` — the human-readable filename (e.g. "Screenshot_123.jpg")
 *  - `size` — the byte length, for the info/delete-size dialogs (was File.length() at read time)
 * `last_modified` is retained (the model's own capture time, not the file's mtime).
 *
 * The v2→v3 migration is destructive (rows wiped): this is a personal fork with no users to
 * migrate, and zvec treats path/URI as an opaque locator string, so the identity change is
 * invisible downstream.
 *
 * `content_hash` (zvec Phase 2, issue 03) is the **read bridge** to zvec (decision D13): the gallery
 * row is keyed by UUID (`id`), but zvec's screenshot-content doc is keyed by SHA-256 content_hash, so
 * a content-text read (`getContentText`, issue 04) needs the hash on the row to fetch
 * (`zvec.fetch(screenshot.contentHash)`). Nullable — populated by `ZvecWriteSink` after the zvec
 * upsert (zvec-first ordering, D13); a null means "not yet indexed into zvec." This extends an
 * existing coupling (the row already carries `uri`, zvec's `locator`) rather than creating a new one.
 */
@Entity(tableName = "screenshot",
        indices = [
            Index("collection_id"),
            Index("uri", unique = true)
        ]
)
data class ScreenshotModel constructor (
        @PrimaryKey(autoGenerate = false) var id: String,
        @ColumnInfo(name = "uri") var uri: String,
        @ColumnInfo(name = "display_name") var displayName: String,
        @ColumnInfo(name = "size") var size: Long,
        @ColumnInfo(name = "last_modified") var lastModified: Long,
        @ColumnInfo(name = "collection_id") var collectionId: String,
        @ColumnInfo(name = "processed", defaultValue = "0") var processed: Boolean = false,
        @ColumnInfo(name = "content_hash") var contentHash: String? = null
) {
    @Ignore
    constructor(
            uri: String,
            displayName: String,
            size: Long,
            lastModified: Long,
            collectionId: String
    ) : this(UUID.randomUUID().toString(), uri, displayName, size, lastModified, collectionId)
}
