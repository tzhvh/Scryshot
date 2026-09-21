/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

import kotlinx.coroutines.flow.Flow

/**
 * A source of ingest [Candidate]s (ADR 0004 §1, §2). The engine consumes the emitted flow and is
 * source-agnostic — it never sees how candidates were sourced.
 *
 * Designed against both producers' needs: MediaStore (the Room-era implementation) and SAF (the design
 * constraint — `takePersistableUriPermission`, the 128-URI persisted-permission limit, no change
 * notifications). The SAF-shaped test fake (issue 04) is what validates this shape holds against SAF before
 * SAF's persistence layer exists.
 */
interface Producer {
    fun candidates(): Flow<Candidate>
}
