/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.detailpage

import com.google.mlkit.vision.text.Text

/**
 * The outcome of DetailPage's on-demand OCR, surfaced by
 * [io.github.tzhvh.scryernext.ingestion.MlKitOcrStage.recognize].
 *
 * DetailPage's interactive OCR is structurally distinct from the ingestion engine's
 * bulk loop (issue 15, option b): its [DetailPageActivity.Result] taxonomy has a
 * [DetailPageActivity.Result.WeiredImageSize] success-with-warning variant, it gates its
 * write on `isRecognizing`, and — load-bearingly — it consumes the structured ML Kit
 * [Text] (blocks/elements/frames) to render the graphic overlay via
 * [GraphicOverlayHelper.convertToGraphicBlocks]. That last point is why this type
 * carries the [Text] object rather than a flattened `String`: the engine's
 * [io.github.tzhvh.scryernext.ingestion.OcrOutcome.Success] *is* a `String` (it persists
 * text only), and reusing it here would force growing `OcrOutcome` a `Success(Text)`
 * variant — exactly the "bloat `OcrOutcome` with a DetailPage-only variant" the
 * option-(b) decision forbids. So this is a parallel taxonomy that lives where its only
 * consumer lives (`detailpage/`), keeping the engine's contract clean.
 *
 * The three failure classes mirror ADR 0004 §7.2 so DetailPage's single-file write can
 * honour the same "permanent failure = processed-but-empty" discipline the engine
 * applies — otherwise an illegible screenshot stays `processed = false` forever and
 * re-poisons issue 13's discovery-worker backlog count.
 *
 * See: [issue 15](../../../../../.scratch/ingestion/issues/15-ocrtexthelper-ownership-detailpage-repoint.md),
 * [ADR 0004 §7.2](../../../../../docs/adr/0004-ingestion-engine-and-trigger-architecture-v2.md)
 */
sealed interface OcrTextResult {
    /** OCR succeeded; [text] is the structured ML Kit result (used by the graphic overlay). */
    data class Success(val text: Text) : OcrTextResult

    /**
     * Permanent-content failure (corrupt bitmap, truly illegible image, unsupported format).
     * DetailPage writes a `processed = true`-but-empty row so the file leaves the unindexed set
     * (ADR 0004 §7.2) — distinct from the legacy `OcrTextHelper.extractText(ScreenshotModel)`
     * swallow-to-`""` (deleted in Phase 3 issue `16`), which wrote nothing and left the row
     */
    data object PermanentContentFailure : OcrTextResult

    /**
     * Transient failure (ML Kit model still downloading, binder timeout, transient I/O).
     * Re-attemptable: DetailPage writes nothing and surfaces the connect prompt
     * ([DetailPageActivity.Result.Unavailable]).
     */
    class TransientFailure(val cause: Throwable) : OcrTextResult
}
