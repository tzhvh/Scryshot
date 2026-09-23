/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ingestion Inspector — per-item event recorder, cloned from [io.github.tzhvh.scryernext.ZvecEventRecorder]
 * (ZVEC_INSPECTOR.md §6's pattern) with one structural difference: this is an **instantiable class,
 * not a process singleton**, so [IngestionEngine] can take it as a nullable constructor seam
 * (`events: IngestionEventRecorder? = null`) and stay free of static state — the same
 * delegate-don't-lookup rule the engine applies to [ScreenshotRepository]/[OcrStage]/[WriteSink].
 * There is exactly one app-scope instance (wired from `ScryerApplication.onCreate` into both engine
 * construction sites — on-open and bulk); the Ingestion Inspector reads it back through the same
 * accessor.
 *
 * What it captures: the per-item outcomes the engine's loop otherwise counts and drops
 * (`OcrOutcome` has no per-item survivor) — dedup-skips, successes, permanent-content
 * failures, transient failures, and write-sink throws — each with URI, cause, and stage
 * latencies, in a bounded ring buffer, so "this URI keeps transient-failing" is visible
 * after the run instead of only in the moment.
 *
 * ## Gating — zero release overhead
 *
 * The same centralized-gate design as [io.github.tzhvh.scryernext.ZvecEventRecorder]: the engine's
 * `record` call sites are NOT inline-conditional; the gate is the instance's [enabled] flag, wired
 * once from `ScryerApplication.onCreate` (`isDebuggable`). The load-bearing detail: [record] is
 * `inline` with a lazy message `() -> String`, so when the gate is off the call site compiles to a
 * `@Volatile` boolean read that branches to nothing — the string interpolation (the bulk of the
 * per-call cost) is never evaluated. In release builds the recorder is additionally passed with
 * `enabled = false`, so per-candidate cost is one predictable branch inside a loop whose real work
 * is a file open plus ML Kit OCR.
 *
 * Ingestion runs on background dispatchers only, so the `@Synchronized` ring operations never
 * contend with the main thread.
 */
class IngestionEventRecorder {
    data class Event(
        val timestamp: Long,
        val message: String
    ) {
        val formattedTime: String by lazy {
            SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
        }

        override fun toString(): String = "[$formattedTime] $message"
    }

    private companion object {
        const val MAX_SIZE = 100
    }

    private val events = java.util.ArrayDeque<Event>(MAX_SIZE)

    /**
     * Whether [record] captures events. Set once from `ScryerApplication.onCreate` via [init];
     * `@Volatile` + `@PublishedApi internal` so the inline [record] function can read it (a public
     * inline function cannot access a private member). The volatile read is safely published from
     * the application-init thread to the ingestion/IO threads. Default false → constructs (and
     * JVM tests that never call [init]) pay nothing.
     */
    @Volatile
    @PublishedApi
    internal var enabled: Boolean = false

    /**
     * Optional debug-only mirror — when set, every recorded event is ALSO written to it (wired
     * from `ScryerApplication.onCreate` to `Log.d("IngestionRecorder", …)` so per-item outcomes
     * are visible in logcat without opening the Ingestion Inspector). Null (default / JVM tests)
     * keeps this class free of `android.util.Log`. `@Volatile` for safe publication, same as
     * [enabled].
     */
    @Volatile
    private var mirror: ((String) -> Unit)? = null

    /**
     * Initialize the gate. Called once from `ScryerApplication.onCreate`; `enabled = isDebuggable`.
     * Safe to call from tests to opt the recorder in for assertions (a null [mirror] leaves the
     * ring buffer as the only sink — no android dependency).
     */
    fun init(enabled: Boolean, mirror: ((String) -> Unit)? = null) {
        this.enabled = enabled
        this.mirror = mirror
    }

    /**
     * The gate. `inline` so the message lambda is not evaluated when [enabled] is false — the
     * call site compiles to a volatile-read + branch in release builds. The synchronized
     * dequeue work lives in [recordInternal] (not inlined) so it is not duplicated at every site.
     * The engine calls this through a nullable seam (`events?.record { … }`) — the safe call
     * compounds the same branch-to-nothing property when no recorder is wired.
     */
    inline fun record(message: () -> String) {
        if (enabled) recordInternal(message())
    }

    @Synchronized
    fun recordInternal(message: String) {
        if (events.size >= MAX_SIZE) {
            events.pollFirst()
        }
        events.addLast(Event(System.currentTimeMillis(), message))
        // Inside the same monitor so logcat ordering matches the ring's order.
        mirror?.invoke(message)
    }

    @Synchronized
    fun getEvents(): List<Event> {
        return events.toList()
    }

    @Synchronized
    fun clear() {
        events.clear()
    }
}
