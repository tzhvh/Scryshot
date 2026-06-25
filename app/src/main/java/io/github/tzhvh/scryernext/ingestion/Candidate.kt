/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

import java.io.InputStream

/**
 * The unit of work the [IngestionEngine] processes: a file identified as
 * potentially-needing-ingest, *before* identity/dedup resolution. Distinct from
 * an indexed Screenshot (see CONTEXT.md Glossary).
 *
 * Carries:
 * - a [locator] — "last known place this content was found" (a URI, path, or
 *   document id). Volatile and non-unique; safe for lookup but **never** for
 *   identity. Nullable: some producers have no locator at candidate time.
 * - a [byteHandle] — a *provider* of the bytes, not an already-open stream.
 *   Called (or not) at most once by whoever needs the bytes; the engine and
 *   repository never re-open a consumed handle.
 * - an optional pre-computed [identity] — ADR 0004 §3 refines CONTEXT.md's
 *   "identity is never on the Candidate" to permit a producer that has already
 *   hashed (e.g. the SAF I/O-pool) to pass it through, avoiding re-derivation.
 *   The engine **never** resolves identity itself; it delegates to
 *   `repository.isKnown(candidate)`, which honours [identity] when present and
 *   otherwise falls back to its own identity model.
 *
 * See: [ADR 0004 §3](../../../../../docs/adr/0004-ingestion-engine-and-trigger-architecture-v2.md)
 */
data class Candidate(
    val locator: String?,                          // volatile; nullable under some producers
    val byteHandle: suspend () -> InputStream,     // a provider, NOT a consumed stream
    val identity: String? = null                   // optional pre-computed; engine never resolves identity
)
