/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.search

import io.github.tzhvh.scryernext.ingestion.ZvecContentStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2.1 (2.1-D13) — JVM tests for the [PrecisionMode] dial state and its query-call mapping.
 *
 * The dial is per-request, not per-index (both FTS fields live in the collection; the dial only
 * selects the search call), so the whole surface is a pure mapping:
 *  - Exact → the single-legged call on the stemmed `content` field only;
 *  - Balanced (default, the B4/B3 measured ship decision) → the two-leg weighted fusion 1.0/0.3;
 *  - Fuzzy → the same fusion with the ngram leg shifted up (0.7 — uncalibrated; the B3 grid
 *    measured 0.7 indistinguishable from 0.3 on the bronze corpus, content leg dominates;
 *    calibration is explicitly Phase 4 work).
 */
class PrecisionModeTest {

    @Test
    fun balanced_isTheDefaultShipPosition() {
        // The B4 ship decision — the dial's out-of-box position is the measured rule's pick.
        assertTrue(PrecisionMode.Default is PrecisionMode.Balanced)
    }

    @Test
    fun exact_selectsSingleLeg_onTheStemmedField() {
        val spec = PrecisionMode.Exact.toQuerySpec()

        assertNull("Exact runs no fusion — the ngram leg is absent", spec.weights)
        assertEquals(listOf(ZvecContentStore.FIELD_CONTENT), spec.legFields)
    }

    @Test
    fun balanced_selectsWeightedFusion_1_0_to_0_3() {
        val spec = PrecisionMode.Balanced.toQuerySpec()

        assertEquals(
            mapOf(
                ZvecContentStore.FIELD_CONTENT to 1.0f,
                ZvecContentStore.FIELD_CONTENT_NGRAM to 0.3f,
            ),
            spec.weights,
        )
        assertEquals(listOf(ZvecContentStore.FIELD_CONTENT, ZvecContentStore.FIELD_CONTENT_NGRAM), spec.legFields)
    }

    @Test
    fun fuzzy_shiftsWeightTowardTheNgramLeg_sameLegs() {
        val spec = PrecisionMode.Fuzzy.toQuerySpec()

        val weights = spec.weights!!
        assertTrue(
            "fuzzy's ngram weight must exceed balanced's 0.3",
            weights[ZvecContentStore.FIELD_CONTENT_NGRAM]!! > 0.3f,
        )
        assertEquals(
            "the content leg keeps its 1.0 anchor",
            1.0f,
            weights[ZvecContentStore.FIELD_CONTENT]!!,
        )
    }
}
