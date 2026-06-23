/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package io.github.tzhvh.scryernext.persistence

import androidx.room.*
import java.util.*

@Entity(tableName = "screenshot_content",
        foreignKeys = [(ForeignKey(
                entity = ScreenshotModel::class,
                parentColumns = ["id"],
                childColumns = ["id"],
                onDelete = ForeignKey.CASCADE))])
data class ScreenshotContentModel constructor (
        @PrimaryKey(autoGenerate = false) var id: String,
        @ColumnInfo(name = "content_text") var contentText: String? = null
)
