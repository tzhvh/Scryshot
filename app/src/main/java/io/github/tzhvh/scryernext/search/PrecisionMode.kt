/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.search

import io.github.tzhvh.scryernext.ingestion.ZvecContentStore

/**
 * Phase 2.1 (decision 2.1-D13) — the Exact ↔ Balanced ↔ Fuzzy precision dial state.
 *
 * The dial is **per-request, not per-index**: both FTS fields exist in the collection since the
 * v2 schema, so switching needs no schema change, no re-ingest — only a different search call.
 * [toQuerySpec] is that mapping, kept pure (data in, data out) so it is JVM-testable and the
 * engine call site ([io.github.tzhvh.scryernext.ingestion.ZvecContentStore.search]) just executes
 * the spec it receives.
 *
 * Positions and their measured trade-offs (bronze set, issue 02 B3 — `[MEASURED]`):
 *  - **Exact** — stemmed single-leg on `content`: whole words, stem-matched, accent-folded.
 *    general Recall@10 0.861 / @50 0.984; fragments ~0.20.
 *  - **Balanced** (the default = the B4 ship decision) — two-leg weighted fusion 1.0/0.3.
 *    Partial-token Recall@10 0.200 → 0.817; general @10 0.871 within the pre-registered
 *    allowance. The accepted cost: general Recall@50 0.901 vs Exact's 0.984.
 *  - **Fuzzy** — the same fusion with the ngram leg shifted up (0.7). Measured identical to 0.3
 *    on this corpus — the content leg dominates — so the shifted weight is an uncalibrated
 *    default; calibration is explicitly Phase 4 work (the dial makes it reversible per-search).
 *
 * There is no Custom position yet: it exists only when the fusion weight becomes a manual
 * control (the advanced ranking-params row), which does not render in 2.1 — scope-ruling, no
 * dead control.
 */
sealed interface PrecisionMode {

    /** The engine call this position maps to: leg fields +, for fusions, the per-leg weights. */
    data class QuerySpec(
        val legFields: List<String>,
        val weights: Map<String, Float>?,
    )

    data object Exact : PrecisionMode
    data object Balanced : PrecisionMode
    data object Fuzzy : PrecisionMode

    fun toQuerySpec(): QuerySpec = when (this) {
        Exact -> QuerySpec(
            legFields = listOf(ZvecContentStore.FIELD_CONTENT),
            weights = null,
        )
        Balanced -> QuerySpec(
            legFields = listOf(ZvecContentStore.FIELD_CONTENT, ZvecContentStore.FIELD_CONTENT_NGRAM),
            weights = mapOf(
                ZvecContentStore.FIELD_CONTENT to 1.0f,
                ZvecContentStore.FIELD_CONTENT_NGRAM to 0.3f,
            ),
        )
        Fuzzy -> QuerySpec(
            legFields = listOf(ZvecContentStore.FIELD_CONTENT, ZvecContentStore.FIELD_CONTENT_NGRAM),
            weights = mapOf(
                ZvecContentStore.FIELD_CONTENT to 1.0f,
                ZvecContentStore.FIELD_CONTENT_NGRAM to 0.7f,
            ),
        )
    }

    companion object {
        /** The dial's out-of-box position: the B3-measured, B4-shipped Balanced fusion. */
        val Default: PrecisionMode = Balanced
    }
}
