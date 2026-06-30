/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

import android.graphics.BitmapFactory
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.tzhvh.scryernext.detailpage.OcrTextResult
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Production implementation of [OcrStage] that ports the decoding and ML Kit OCR
 * mechanism from the former `OcrTextHelper` (deleted in Phase 3 issue `16`).
 *
 * Two entry points share one decode+OCR core ([recognizeInternal]):
 *
 * - [attempt] — the engine path ([OcrStage]). Returns [OcrOutcome]; flattens the ML Kit
 *   [Text] to `.text` because the engine persists text only. Behavior-identical to the
 *   pre-issue-15 implementation (same decode-null rule, same catch chain).
 * - [recognize] — the DetailPage path (issue 15, option b). Returns [OcrTextResult],
 *   which carries the structured [Text] DetailPage needs for its graphic overlay
 *   ([io.github.tzhvh.scryernext.detailpage.GraphicOverlayHelper.convertToGraphicBlocks]).
 *
 * DetailPage owns the read (it opens the `ContentResolver` stream and hands the stage
 * `bytes`, mirroring how [IngestionEngine] calls [Candidate.byteHandle]); the stage owns
 * decode+OCR over those bytes — the same stage boundary the engine honours (see the
 * [OcrStage] KDoc).
 */
class MlKitOcrStage(
    private val textRecognizer: TextRecognizer = defaultTextRecognizer
) : OcrStage {

    override suspend fun attempt(candidate: Candidate, bytes: ByteArray): OcrOutcome {
        return try {
            OcrOutcome.Success(recognizeInternal(bytes).text)
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

    /**
     * DetailPage's on-demand OCR entry point (issue 15). Same decode+OCR core as [attempt],
     * but returns the structured ML Kit [Text] wrapped in [OcrTextResult] so the graphic
     * overlay can consume block/element/frame geometry. The failure taxonomy is isomorphic
     * to [OcrOutcome]'s (ADR 0004 §7.2) so DetailPage's single-file write can apply the
     * same permanent-failure = processed-but-empty discipline.
     */
    suspend fun recognize(bytes: ByteArray): OcrTextResult {
        return try {
            OcrTextResult.Success(recognizeInternal(bytes))
        } catch (e: MlKitException) {
            if (e.errorCode == MlKitException.UNAVAILABLE) {
                OcrTextResult.TransientFailure(e)
            } else {
                OcrTextResult.PermanentContentFailure
            }
        } catch (e: IOException) {
            OcrTextResult.TransientFailure(e)
        } catch (e: Throwable) {
            OcrTextResult.PermanentContentFailure
        }
    }

    /**
     * Decode + OCR the already-read [bytes]. Throws on any failure; both [attempt] and
     * [recognize] map the throwable to their respective failure outcomes.
     *
     * A `null` decode (corrupt/unsupported bytes) is the archetypal permanent-content
     * failure — signalled by [PermanentContentFailureDecode] so callers' catch-all arms
     * classify it correctly rather than mis-handling it as transient.
     */
    private suspend fun recognizeInternal(bytes: ByteArray): Text {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw PermanentContentFailureDecode

        return suspendCancellableCoroutine { cont ->
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
                if (cont.isActive) cont.resume(visionText)
            }
            task.addOnFailureListener { exception ->
                if (cont.isActive) cont.resumeWithException(exception)
            }
        }
    }

    /**
     * Sentinel for "the bytes could not be decoded into a bitmap" (corrupt/unsupported
     * image) — a permanent-content failure, not a transient I/O blip. Caught by the
     * catch-all in [attempt]/[recognize] and mapped to `PermanentContentFailure`.
     */
    private object PermanentContentFailureDecode : Throwable() {
        @Suppress("ACCIDENTAL_OVERRIDE")
        override fun fillInStackTrace(): Throwable = this
    }

    companion object {
        private val defaultTextRecognizer: TextRecognizer by lazy {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }
}
