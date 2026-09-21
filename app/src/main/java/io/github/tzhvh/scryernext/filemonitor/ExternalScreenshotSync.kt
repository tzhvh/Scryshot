/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.filemonitor

import android.content.Context
import io.github.tzhvh.scryernext.persistence.CollectionModel
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import io.github.tzhvh.scryernext.repository.ScreenshotRepository
import java.util.UUID

/**
 * The foreign-screenshot gallery sync — the ONE writer of DB rows for
 * screenshots other apps saved (ADR 0008's re-homing table): discovers via
 * [ScreenshotFetcher]'s MediaStore query, inserts unseen rows as
 * uncategorized (which is also the ingestion work queue — MediaStoreProducer
 * ingests `processed = false` rows, so nothing indexes until this runs), and
 * drops rows whose backing content URI is no longer readable.
 *
 * Extracted from HomeFragment (where it lived as private merge helpers
 * behind the old PermissionFlow's finish event) so both consumers — Home's
 * per-resume backfill and the wizard's done-screen "Start indexing" — share
 * one implementation instead of two racing copies.
 *
 * Callers run this off the main thread; [sync] touches `contentResolver` and
 * the repository only.
 *
 * @return the rows this sync newly inserted.
 */
class ExternalScreenshotSync(
    private val context: Context,
    private val repository: ScreenshotRepository
) {
    suspend fun sync(): List<ScreenshotModel> {
        val dbList = repository.getScreenshotList()
        val externalList = ScreenshotFetcher().fetchScreenshots(context)
        return mergeExternal(externalList, dbList)
    }

    /**
     * @return screenshots from external that hadn't been recorded in the DB.
     */
    private suspend fun mergeExternal(
        externalList: List<ScreenshotModel>,
        dbList: List<ScreenshotModel>
    ): List<ScreenshotModel> {
        // A lookup table of DB rows keyed by uri, so we can quickly check whether each
        // screenshot from MediaStore had already been recorded before.
        val localModels = dbList.map { it.uri to it }.toMap().toMutableMap()

        val results = mutableListOf<ScreenshotModel>()
        externalList.forEach { externalModel ->
            val localModel = localModels[externalModel.uri]
            localModel?.let {
                localModels.remove(externalModel.uri)

            } ?: run {
                // No record found, make a new uncategorized item
                externalModel.id = UUID.randomUUID().toString()
                externalModel.collectionId = CollectionModel.UNCATEGORIZED

                results.add(externalModel)
            }
        }

        // Drop DB rows whose backing content URI is no longer readable (user deleted the
        // screenshot from MediaStore via another app). Issue 21: replaces the old
        // File(path).exists() gate, which was meaningless for content URIs.
        val resolver = context.contentResolver
        for (entry in localModels) {
            val model = entry.value
            val readable = try {
                resolver.openInputStream(android.net.Uri.parse(model.uri))?.use { true } ?: false
            } catch (e: Exception) {
                false
            }
            if (!readable) {
                repository.deleteScreenshot(model)
            }
        }

        repository.addScreenshot(results)
        return results
    }
}
