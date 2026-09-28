/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion.harness

import io.github.tzhvh.scryernext.ingestion.Candidate
import io.github.tzhvh.scryernext.ingestion.OcrOutcome
import io.github.tzhvh.scryernext.ingestion.OcrStage

/**
 * The A1 arm's instant-fake [OcrStage] (`INGESTION_ADHOC_REPRO_HARNESS.md` §4):
 * canned [OcrOutcome.Success] with per-doc realistic OCR-shaped text, zero
 * decode, zero ML Kit — A1 adds *only* the real zvec/Room write path on top of
 * A0's engine bookkeeping, so a red here names the zvec-size accumulator
 * (H1/H4) without OCR in the loop at all.
 *
 * Text is derived from the candidate's harness locator index (never from call
 * order) so the stage stays correct even if dedup ever skips candidates.
 */
class FakeOcrStage(
    private val textFor: (Int) -> String = { HarnessCorpus.ocrTextFor(it) },
) : OcrStage {

    /** Total attempts — the engine calls this once per non-dedup candidate. */
    var attempts: Int = 0
        private set

    override suspend fun attempt(candidate: Candidate, bytes: ByteArray): OcrOutcome {
        attempts++
        return OcrOutcome.Success(textFor(HarnessCorpus.indexFor(candidate.locator)))
    }
}
