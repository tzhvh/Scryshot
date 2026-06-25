/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

/**
 * Result of attempting OCR on one [Candidate]. ADR 0004 §7.2's three-class
 * failure taxonomy is encoded here so issue `07` can refine the engine's
 * per-branch handling without a breaking change to [OcrStage]'s signature.
 *
 * Issue `06` uses only the [Success] branch to drive writes; [PermanentContentFailure]
 * and [TransientFailure] are counted and the run continues (a non-crashing
 * placeholder). Issue `07` populates the real taxonomy (write-empty-on-permanent,
 * throw/re-attempt-on-transient).
 *
 * See: [ADR 0004 §7.2](../../../../../docs/adr/0004-ingestion-engine-and-trigger-architecture-v2.md)
 */
sealed interface OcrOutcome {
    /** OCR succeeded; [text] is the recognized content (possibly empty). */
    data class Success(val text: String) : OcrOutcome

    /**
     * Permanent-content failure (corrupt bitmap, truly illegible image,
     * unsupported format). Counted as *processed-but-empty* — the engine
     * increments `failed` and continues; issue `07` writes a row with empty
     * text + `processed = true` here.
     */
    data object PermanentContentFailure : OcrOutcome

    /**
     * Transient failure (ML Kit model still downloading, binder timeout,
     * transient I/O). Re-attemptable; the engine writes nothing. Issue `07`
     * decides throw-vs-continue semantics.
     */
    class TransientFailure(val cause: Throwable) : OcrOutcome
}

/**
 * The seam that isolates [IngestionEngine] from ML Kit + `ContentResolver`
 * — neither can run on the JVM, and this project has no Robolectric
 * (Phase 0 issues `03`/`04`). The engine is JVM-unit-testable because its
 * OCR backend is a parameter, not a hardcoded call.
 *
 * The real backend (a port of `OcrTextHelper.extractText(Bitmap)` +
 * `decodeFromUri`) is issue `08`; tests pass a fake that returns canned
 * [OcrOutcome]s. [attempt] reads [Candidate.byteHandle], decodes, and OCRs;
 * it never calls the repository (dedup is the engine's concern, not the stage's).
 *
 * See: [ADR 0004 §2](../../../../../docs/adr/0004-ingestion-engine-and-trigger-architecture-v2.md)
 */
fun interface OcrStage {
    /** Read [Candidate.byteHandle], decode, OCR. Never calls the repository. */
    suspend fun attempt(candidate: Candidate): OcrOutcome
}
