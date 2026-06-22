/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.scryer.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.mozilla.scryer.ScryerApplication
import org.mozilla.scryer.persistence.ScreenshotContentModel
import org.mozilla.scryer.persistence.ScreenshotModel
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OcrTextHelper {
    companion object {
        private const val TAG = "OcrTextHelper"

        private val textRecognizer: TextRecognizer by lazy {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }

        /** Cancellable scan **/
        suspend fun scan(
                updateListener: suspend (((model: ScreenshotModel?, index: Int, total: Int) -> Unit))
        ) = withContext(Dispatchers.IO) {
            val list = ScryerApplication.getScreenshotRepository()
                    .getScreenshotList()
                    .sortedByDescending { it.lastModified }

            val remains = list.filter {
                ScryerApplication.getScreenshotRepository().getContentText(it) == null
            }

            val indexedCount = list.size - remains.size
            updateListener.invoke(null, indexedCount, list.size)

            if (remains.isEmpty()) {
                return@withContext
            }

            remains.forEachIndexed { index, model ->
                if (!isActive) {
                    android.util.Log.d(TAG, "scan interrupted")
                    return@withContext
                }

                updateListener.invoke(model, indexedCount + index + 1, list.size)
                android.util.Log.d(TAG, "progress: ${index + 1}/${remains.size}")
            }
            android.util.Log.d(TAG, "scan finished")
        }

        suspend fun scanAndSave(updateListener: ((index: Int, total: Int) -> Unit)? = null) {
            scan { model, index, total ->
                model?.let {
                    writeContentTextToDb(model, extractText(model))
                }
                updateListener?.invoke(index, total)
            }
        }

        suspend fun extractText(screenshot: ScreenshotModel): String {
            return try {
                decodeFromUri(screenshot.uri)?.let {
                    extractText(it).text
                } ?: ""
            } catch (e: Throwable) {
                if (isModelUnavailableException(e)) {
                    throw e
                }
                ""
            }
        }

        suspend fun extractText(selectedImage: Bitmap): Text {
            return suspendCancellableCoroutine { cont ->
                val image = InputImage.fromBitmap(selectedImage, 0)
                textRecognizer.process(image)
                        .addOnSuccessListener { texts ->
                            cont.resume(texts)
                        }
                        .addOnFailureListener { exception ->
                            cont.resumeWithException(exception)
                        }
            }
        }

        suspend fun writeContentTextToDb(
                screenshot: ScreenshotModel,
                contentText: String
        ) = withContext(Dispatchers.IO) {
            val model = ScreenshotContentModel(screenshot.id, contentText)
            val fileExisted = isUriReadable(screenshot.uri)
            val recordExist = ScryerApplication.getScreenshotRepository()
                    .getScreenshotList()
                    .find { it.id == screenshot.id }
                    ?.let { true } ?: false
            if (fileExisted && recordExist) {
                ScryerApplication.getScreenshotRepository().updateScreenshotContent(model)
            }
        }

        private fun isModelUnavailableException(e: Throwable): Boolean {
            return (e as? MlKitException)?.errorCode == MlKitException.UNAVAILABLE
        }

        /** Issue 21: decode a screenshot bitmap from its content URI via ContentResolver. */
        private fun decodeFromUri(uriString: String): Bitmap? {
            return try {
                val resolver = ScryerApplication.getContentResolver()
                resolver.openInputStream(android.net.Uri.parse(uriString))?.use { input ->
                    BitmapFactory.decodeStream(input)
                }
            } catch (e: Exception) {
                null
            }
        }

        /** Issue 21: probe whether a content URI is still readable (replaces File.exists()). */
        private fun isUriReadable(uriString: String): Boolean {
            return try {
                val resolver = ScryerApplication.getContentResolver()
                resolver.openInputStream(android.net.Uri.parse(uriString))?.use { true } ?: false
            } catch (e: Exception) {
                false
            }
        }
    }
}
