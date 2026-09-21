/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.repository

import io.github.tzhvh.scryernext.persistence.ScreenshotModel

/**
 * The result of one search submission (Phase 2.1 step 7, decision 2.1-D10): either the rows in
 * the requested policy's final order, or a recoverable engine query error.
 *
 * Before any syntax was UI-reachable, the engine could not reject a normal query, so a bare list
 * sufficed. Now that `-term` exclusions ride the match string, a malformed submission can reach
 * the boolean parser — and it must surface as a STATE (the UI's parse-error notice, query intact
 * in the field), never as a crash of the collector's Flow. The repository catches the engine's
 * [io.github.tzhvh.scryernext.zvec.ZvecException] on the search call and wraps it here.
 */
sealed interface SearchOutcome {
    /** Rows in final rank order; empty for a no-match or blank submission. */
    data class Results(val rows: List<ScreenshotModel>) : SearchOutcome

    /** The engine rejected the query. Recoverable: the UI shows a notice and keeps the input. */
    data class QueryError(val cause: Exception) : SearchOutcome
}
