/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

/**
 * The logging seam for the ingestion layer (ADR 0004 §7.6).
 *
 * Exists so [IngestionProgressStore] (and other ingestion classes) can log
 * status-change events *without* depending on `android.util.Log` — which
 * keeps them pure-Kotlin and JVM-unit-testable (the project runs ingestion
 * unit tests on the JVM without Robolectric; see `IngestionEngineTest`).
 *
 * The production implementation is wired in `ScryerApplication` to tagged
 * logcat (`android.util.Log.d("IngestionProgressStore", message)`); tests
 * supply a capturing implementation (or [Noop]) to assert on or silence the
 * output. A `fun interface` (rather than a bare `(String) -> Unit` lambda
 * type) mirrors the [WriteSink] precedent — a named, discoverable seam with
 * room for KDoc.
 *
 * Per §7.6 the store logs **status changes only** (enter/abort/fail/complete),
 * not per-candidate ticks — WorkInfo owns lifecycle state, logcat owns the
 * transition firehose.
 */
fun interface IngestionLogger {
    /** Log a single status-change message. The tag is the implementation's concern. */
    fun log(message: String)

    companion object {
        /** A logger that discards everything — the default for tests that don't assert on logging. */
        val Noop: IngestionLogger = IngestionLogger { /* discard */ }
    }
}
