package io.github.tzhvh.scryernext.filemonitor

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.provider.MediaStore
import io.github.tzhvh.scryernext.persistence.CollectionModel
import io.github.tzhvh.scryernext.persistence.ScreenshotModel

/**
 * Issue 21: the read path no longer walks the filesystem (`File(dirPath).listFiles()`).
 * Instead it queries MediaStore.Images for screenshots in other apps' folders (not the app's
 * own Scryshot/ folder — those arrive via issue 20's write path), building
 * [ScreenshotModel]s with the content URI as identity and caching display_name + size so the
 * UI doesn't have to resolve them per-row.
 */
class ScreenshotFetcher {

    companion object {
        private val supportExt = listOf("jpg", "png")

        fun isExtSupported(fileName: String): Boolean {
            return supportExt.any { fileName.endsWith(it, true) }
        }

    }

    fun fetchScreenshots(context: Context): List<ScreenshotModel> {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.RELATIVE_PATH)
        // Foreign screenshots only — exclude the app's own Scryshot/ captures (those are
        // inserted directly into the DB by the capture pipeline). Match any image whose
        // filename or folder mentions "screenshot".
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE '%screenshot%' " +
                "AND (${MediaStore.Images.Media.RELATIVE_PATH} NOT LIKE '%Scryshot%')"
        val results = mutableListOf<ScreenshotModel>()

        try {
            context.contentResolver.query(uri,
                    columns,
                    selection,
                    null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC"
            ).use {
                val cursor = it ?: return@use
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeIdx = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(nameIdx) ?: continue
                    if (!isExtSupported(displayName)) {
                        continue
                    }
                    val id = cursor.getLong(idIdx)
                    val contentUri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                    val dateAdded = cursor.getLong(dateIdx) * 1000L

                    results.add(ScreenshotModel(
                            uri = contentUri.toString(),
                            displayName = displayName,
                            size = size,
                            lastModified = dateAdded,
                            collectionId = CollectionModel.UNCATEGORIZED
                    ))
                }
            }
        } catch (e: SecurityException) {
            // Reading foreign MediaStore rows may require READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE
            // (declared in the manifest via issue 23). If a row is from another app and the
            // permission isn't granted, the query throws — degrade to an empty list rather than crash.
            android.util.Log.w("ScreenshotFetcher", "Failed to read screenshots", e)
        }

        return results
    }
}
