/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

import android.content.ContentResolver
import android.net.Uri
import io.github.tzhvh.scryernext.repository.ScreenshotRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The production [Producer] implementation for the Room/MediaStore era (ADR 0004 §1).
 *
 * ### Load-Bearing Design Decision: Model B
 * This implementation queries the database repository for unprocessed rows (where `processed = false`).
 * It does **NOT** scan MediaStore and compute a live-vs-DB set-difference. This is a deliberate,
 * scrutinized design decision:
 * 1. The gallery dependency: [io.github.tzhvh.scryernext.filemonitor.ScreenshotFetcher] owns MediaStore discovery
 *    and inserts screenshot rows immediately so that the gallery shows images before OCR completes. MediaStore
 *    discovery is not the OCR producer's job.
 * 2. Race condition: If the producer scanned MediaStore and computed a diff, it might yield candidates whose
 *    screenshot rows have not been inserted into the DB by [io.github.tzhvh.scryernext.filemonitor.ScreenshotFetcher] yet.
 *    The [WriteSink] then has no row to update and flip `processed = true` on, causing orphans or crashes.
 * 3. Structural guarantee: Reading `processed = false` rows from the DB guarantees that a screenshot row
 *    always exists before the engine attempts to run OCR on it.
 *
 * Consequently, this producer never calls `computeSyncPlan` (which is not in the OCR runtime path under Room).
 *
 * See: [.scratch/ingestion/issues/10-mediastoreproducer-unindexed-enumeration.md](file:///.scratch/ingestion/issues/10-mediastoreproducer-unindexed-enumeration.md)
 * See: [ADR 0004 §1, §2, §7.1](file:///docs/adr/0004-ingestion-engine-and-trigger-architecture-v2.md)
 */
class MediaStoreProducer(
    private val repository: ScreenshotRepository,
    private val contentResolver: ContentResolver
) : Producer {

    override fun candidates(): Flow<Candidate> = flow {
        // Query unprocessed screenshot rows (processed = false) from the DB.
        // Reading unprocessed rows acts as the OCR engine work queue.
        repository.getUnprocessedScreenshotList().forEach { row ->
            emit(Candidate(
                locator = row.uri,
                byteHandle = {
                    try {
                        contentResolver.openInputStream(Uri.parse(row.uri))
                            ?: ByteArrayInputStream(ByteArray(0))
                    } catch (e: Exception) {
                        // Per-candidate failure handling: return an empty stream.
                        // The engine/OcrStage (MlKitOcrStage)'s decode-null rule maps empty bytes
                        // to PermanentContentFailure, ensuring loop hygiene (no crashes, no re-scan).
                        ByteArrayInputStream(ByteArray(0))
                    }
                },
                identity = null // Room: locator (uri) is the identity, repository.isKnown handles locator lookup when null.
            ))
        }
    }
}
