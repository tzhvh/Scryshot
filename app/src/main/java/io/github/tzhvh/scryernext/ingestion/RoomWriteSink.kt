/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

import io.github.tzhvh.scryernext.persistence.ScreenshotContentModel
import io.github.tzhvh.scryernext.repository.ScreenshotRepository

/**
 * The Room-database/MediaStore-era [WriteSink]. **Superseded in production by [ZvecWriteSink] as of
 * zvec Phase 2 issue 03** (the engine's sink wiring now constructs `ZvecWriteSink`); retained in-tree
 * because the content tables survive until issue 04 drops them, and `IngestionWorker` still references
 * it for the WorkManager path. Issue 04 deletes this class with the content tables.
 *
 * ## The missing-row case
 *
 * Issue 10's Model B guarantees a screenshot row exists before the engine OCRs it (the producer
 * reads `processed = false` rows, so the row must be there). A missing row here is therefore an
 * **invariant violation**, not a normal path — it is logged loudly and the write is skipped, so
 * the failure is diagnosable rather than silently swallowed (which the prior `?: return` did).
 */
class RoomWriteSink(
    private val repository: ScreenshotRepository
) : WriteSink {

    override suspend fun commit(candidate: Candidate, text: String?, processed: Boolean, bytes: ByteArray) {
        val locator = candidate.locator ?: return
        val screenshot = repository.getScreenshotByUri(locator)
        if (screenshot == null) {
            // Model B invariant violation: the producer only yields rows that exist. A missing
            // row means the locator drifted from the DB (e.g. a SAF/Room key-space mismatch) or
            // the row was deleted mid-run. Log loudly; do not crash the run.
            android.util.Log.w(
                "RoomWriteSink",
                "commit: no screenshot row for locator=$locator (Model B invariant violated); skipping write."
            )
            return
        }
        if (processed) {
            repository.updateScreenshotContent(ScreenshotContentModel(screenshot.id, text))
            repository.updateScreenshots(listOf(screenshot.copy(processed = true)))
        }
    }
}
