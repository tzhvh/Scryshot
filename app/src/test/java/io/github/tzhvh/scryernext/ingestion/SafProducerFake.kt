/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayInputStream

/**
 * A fake [Producer] shaped after SAF's real constraints (docs/SAF.md), used to validate the Producer /
 * Candidate contract holds against the awkward producer *before* SAF's persistence layer exists.
 *
 * Encodes, as fake behaviour, the three SAF facts the real producer must handle:
 *  - `takePersistableUriPermission` — each emitted candidate's locator is a persisted document URI; the fake
 *    records that a permission was "taken" so a test can assert the producer took it before emitting.
 *  - the 128-URI persisted-permission limit — the fake refuses to take more than 128 and surfaces the
 *    overflow (e.g. throws / caps), so a test can assert the contract doesn't silently lose candidates.
 *  - no change notifications — the fake emits a finite, enumerated flow (no "live" re-emission), modelling
 *    that SAF has no MediaStore-style observer to ride on; discovery must be poll-driven.
 */
class SafProducerFake(
    private val documentUris: List<String>,         // the persisted tree's children
    private val byteFor: (String) -> ByteArray,     // fake bytes behind each URI
    val takenPermissions: MutableList<String> = mutableListOf()
) : Producer {

    init {
        require(documentUris.size <= MAX_PERSISTED_URIS) { "SAF 128-URI limit exceeded" }
    }

    override fun candidates(): Flow<Candidate> = flow {
        documentUris.forEach { uri ->
            takenPermissions += uri   // models takePersistableUriPermission happening before emit
            emit(Candidate(
                locator = uri,
                byteHandle = { ByteArrayInputStream(byteFor(uri)) },
                identity = null        // SAF would pre-hash (roadmap Phase 3b I/O-pool); left null here
            ))
        }
    }

    companion object { 
        const val MAX_PERSISTED_URIS = 128 
    }
}
