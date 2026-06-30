/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

/**
 * Result of attempting OCR on one [Candidate]. ADR 0004 §7.2's three-class
 * failure taxonomy is encoded here, and [IngestionEngine] branches on it to
 * decide the per-candidate write (issue `07`).
 *
 * | [OcrOutcome]              | Engine write            | Counter |
 * |---------------------------|-------------------------|---------|
 * | [Success]                 | text, `processed=true`  | indexed |
 * | [PermanentContentFailure] | empty/null, `processed=true` | indexed (processed-but-empty) |
 * | [TransientFailure]        | **nothing**             | failed  |
 *
 * A thrown write sink (Insert/store failure) is distinct from any [OcrOutcome] —
 * the engine surfaces it as [Progress.Error] and stops.
 *
 * See: [ADR 0004 §7.2](../../../../../docs/adr/0004-ingestion-engine-and-trigger-architecture-v2.md)
 */
sealed interface OcrOutcome {
    /** OCR succeeded; [text] is the recognized content (possibly empty). */
    data class Success(val text: String) : OcrOutcome

    /**
     * Permanent-content failure (corrupt bitmap, truly illegible image,
     * unsupported format). The engine writes a row with **empty/null text +
     * `processed = true`** — "processed-but-empty," counted as *indexed* (toward
     * completion) so it leaves the unindexed set and does not re-poison the
     * backlog via Phase 2.5's discovery worker (ADR 0004 §7.2).
     */
    data object PermanentContentFailure : OcrOutcome

    /**
     * Transient failure (ML Kit model still downloading, binder timeout,
     * transient I/O). Re-attemptable: the engine **writes nothing** and counts
     * it `failed`; the next run re-derives it from the unindexed set.
     */
    class TransientFailure(val cause: Throwable) : OcrOutcome
}

/**
 * The seam that isolates [IngestionEngine] from ML Kit + `ContentResolver`
 * — neither can run on the JVM, and this project has no Robolectric
 * (Phase 0 issues `03`/`04`). The engine is JVM-unit-testable because its
 * OCR backend is a parameter, not a hardcoded call.
 *
 * The real backend (a port of the former `OcrTextHelper.extractText(Bitmap)` +
 * `decodeFromUri`, deleted in Phase 3 issue `16`) is issue `08`; tests pass a fake that returns canned
 * [OcrOutcome]s.
 *
 * **Read ownership / stage boundary (issue `07`):** the **engine** owns the
 * read — it calls [Candidate.byteHandle] exactly once (honouring Candidate's
 * "at most once" contract) and hands the resulting bytes to [attempt]. [attempt]
 * owns **decode + OCR** over those bytes; it never opens the byte-handle itself.
 * This keeps the per-stage `Stopwatch` honest (`readMs` = engine, `ocrMs` =
 * stage) and avoids double-I/O. Should issue `08`'s adapter want to expose
 * `decodeMs` separately, it can fold its own internal timing into the returned
 * outcome's context — but the seam here stays "bytes in, outcome out."
 *
 * [attempt] never calls the repository (dedup is the engine's concern, not the
 * stage's).
 *
 * See: [ADR 0004 §2, §7.3](../../../../../docs/adr/0004-ingestion-engine-and-trigger-architecture-v2.md)
 */
fun interface OcrStage {
    /**
     * Decode + OCR the already-read [bytes]. Never calls the repository, never
     * re-opens [Candidate.byteHandle] (the engine owns the read — see class KDoc).
     */
    suspend fun attempt(candidate: Candidate, bytes: ByteArray): OcrOutcome
}
