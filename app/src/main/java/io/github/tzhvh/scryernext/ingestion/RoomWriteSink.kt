/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

import io.github.tzhvh.scryernext.persistence.ScreenshotContentModel
import io.github.tzhvh.scryernext.repository.ScreenshotRepository

/**
 * Production implementation of [WriteSink] for the Room database/MediaStore era.
 *
 * For a given [Candidate] (where locator is the content URI under Room), it:
 * 1. Locates the matching screenshot row in the database.
 * 2. Updates the OCR text content in `screenshot_content`.
 * 3. Marks the screenshot as `processed = true` so it is cleared from the unprocessed backlog.
 */
class RoomWriteSink(
    private val repository: ScreenshotRepository
) : WriteSink {

    override suspend fun commit(candidate: Candidate, text: String?, processed: Boolean) {
        val locator = candidate.locator ?: return
        val screenshot = repository.getScreenshotList().find { it.uri == locator } ?: return
        if (processed) {
            repository.updateScreenshotContent(ScreenshotContentModel(screenshot.id, text))
            screenshot.processed = true
            repository.updateScreenshots(listOf(screenshot))
        }
    }
}
