/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

import android.graphics.BitmapFactory
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Production implementation of [OcrStage] that ports the decoding and ML Kit OCR
 * mechanism from OcrTextHelper.
 */
class MlKitOcrStage(
    private val textRecognizer: TextRecognizer = defaultTextRecognizer
) : OcrStage {

    override suspend fun attempt(candidate: Candidate, bytes: ByteArray): OcrOutcome {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return OcrOutcome.PermanentContentFailure

            val text = suspendCancellableCoroutine<String> { cont ->
                val image = InputImage.fromBitmap(bitmap, 0)
                val task = textRecognizer.process(image)
                // Cancellation: the coroutine yields to cancellation IMMEDIATELY (so the
                // collecting scope's cancel propagates and the run stops), BUT the native
                // ML Kit `Task` cannot itself be aborted — `TextRecognizer.process` has no
                // `CancellationToken` overload and `Task` exposes no public `cancel()`.
                // The in-flight recognition therefore runs to completion in the background
                // (a battery/CPU cost on abort — bounded by one candidate's latency). This
                // hook's job is the residual safety: guard the resume against firing into an
                // already-cancelled continuation (which would otherwise throw
                // IllegalStateException "Already resumed").
                cont.invokeOnCancellation { /* Task not cancellable; see comment above */ }
                task.addOnSuccessListener { visionText ->
                    if (cont.isActive) cont.resume(visionText.text)
                }
                task.addOnFailureListener { exception ->
                    if (cont.isActive) cont.resumeWithException(exception)
                }
            }

            OcrOutcome.Success(text)
        } catch (e: MlKitException) {
            if (e.errorCode == MlKitException.UNAVAILABLE) {
                OcrOutcome.TransientFailure(e)
            } else {
                OcrOutcome.PermanentContentFailure
            }
        } catch (e: IOException) {
            OcrOutcome.TransientFailure(e)
        } catch (e: Throwable) {
            OcrOutcome.PermanentContentFailure
        }
    }

    companion object {
        private val defaultTextRecognizer: TextRecognizer by lazy {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }
}
