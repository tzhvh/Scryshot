/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.scryer.filemonitor

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.scryer.capture.ScreenCaptureManager
import org.mozilla.scryer.util.launchIO

/**
 * Issue 21: the read path no longer touches the deprecated `MediaStore.Images.Media.DATA`
 * column (the absolute filesystem path, restricted under scoped storage). Instead it selects
 * `_ID` and builds the content URI via `ContentUris.withAppendedId`, using `DISPLAY_NAME` and
 * `RELATIVE_PATH` (API 29+) for the screenshot/folder heuristics that previously parsed the
 * raw path. The emitted identity passed to the listener is now the `content://` URI string.
 */
class MediaProviderDelegate(private val context: Context, private val handler: Handler?) : FileMonitorDelegate {

    private var observer: ContentObserver? = null

    override fun startMonitor(listener: FileMonitor.ChangeListener) {
        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                uri ?: return

                if (!uri.toString().contains(MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString())) {
                    return
                }

                context.contentResolver.query(uri,
                        arrayOf(
                                MediaStore.Images.Media._ID,
                                MediaStore.Images.Media.DISPLAY_NAME,
                                MediaStore.Images.Media.DATE_ADDED,
                                MediaStore.Images.Media.RELATIVE_PATH),
                        null,
                        null,
                        MediaStore.Images.Media.DATE_ADDED + " DESC"
                ).use {
                    val cursor = it ?: return@use
                    if (cursor.moveToFirst()) {
                        notifyChangeAsync(cursor, listener)
                    }
                }
            }

        }.apply {
            context.contentResolver.registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    true,
                    this)
        }
    }

    private fun notifyChangeAsync(cursor: Cursor, listener: FileMonitor.ChangeListener) {
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
        val displayName = cursor.getStringOrEmpty(MediaStore.Images.Media.DISPLAY_NAME)
        val relativePath = cursor.getStringOrEmpty(MediaStore.Images.Media.RELATIVE_PATH)
        val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
        val currentTime = System.currentTimeMillis() / 1000

        // Build the canonical content URI — this is the screenshot's identity from here on.
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val uriString = uri.toString()

        launchIO {
            val isExtSupported = ScreenshotFetcher.isExtSupported(displayName)
            val isPotentialScreenshot = displayName.contains("screenshot", true)
            // Fab captures land in Pictures/ScreenshotGo/ and are owned by the app's own write
            // path (issue 20); the filemonitor exists to detect *foreign* screenshots, so skip
            // anything inside the app's own folder.
            val isCapturedByFab = relativePath.contains("${ScreenCaptureManager.SCREENSHOT_DIR}", true)
            val isNewlyCaptured = Math.abs(currentTime - dateAdded) <= 10

            if (isExtSupported && isPotentialScreenshot && isNewlyCaptured && !isCapturedByFab) {
                withContext(Dispatchers.Main) {
                    listener.onChangeStart(uriString)
                    listener.onChangeFinish(uriString)
                }
            }
        }
    }

    private fun Cursor.getStringOrEmpty(column: String): String {
        val index = getColumnIndex(column)
        return if (index < 0) "" else try {
            getString(index)?.trim() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    override fun stopMonitor() {
        observer?.let {
            context.contentResolver.unregisterContentObserver(it)
        }
    }
}
