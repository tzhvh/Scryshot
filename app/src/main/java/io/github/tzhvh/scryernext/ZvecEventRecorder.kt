/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0 with a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * zvec Runtime Inspector (ZVEC_INSPECTOR.md §6) — Event recorder.
 *
 * Process-singleton ring buffer for debug-only events (e.g. LOCK-recovery, staleness drop,
 * trim-close failure, write-path invariant violation).
 *
 * ## Gating — zero release overhead (correction of the analyser doc's original claim)
 *
 * The screen is gated behind `BuildConfig.DEBUG` (menu visibility). The *event sources* in the
 * production write/read paths ([io.github.tzhvh.scryernext.ingestion.ZvecContentStore.flush],
 * [io.github.tzhvh.scryernext.ingestion.ZvecWriteSink.commit], the staleness drop in
 * [io.github.tzhvh.scryernext.repository.ZvecScreenshotRepository]) are NOT inline-conditional
 * at each call site — instead, the gate is centralized here via an [enabled] flag, wired from
 * [io.github.tzhvh.scryernext.ScryerApplication.onCreate] (`isDebuggable`).
 *
 * The load-bearing detail: [record] is `inline` with a lazy message `() -> String`. Kotlin inlines
 * the call, so the `if (enabled)` check executes at the call site and the message lambda is **not
 * evaluated** when disabled. This skips the string interpolation (the bulk of the per-call cost),
 * not just the dequeue work — which is the subtlety a plain `if (enabled) record(msg)` inside a
 * non-inline function would get wrong (the arg is evaluated before the call). Same pattern as
 * `android.util.Log.d(tag) { msg }`.
 *
 * In release builds the call sites compile to a single `@Volatile` boolean read that branches to
 * nothing. No allocation, no lock acquisition, no string construction.
 */
object ZvecEventRecorder {
    data class Event(
        val timestamp: Long,
        val message: String
    ) {
        val formattedTime: String by lazy {
            SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
        }

        override fun toString(): String = "[$formattedTime] $message"
    }

    private const val MAX_SIZE = 100
    private val events = java.util.ArrayDeque<Event>(MAX_SIZE)

    /**
     * Whether [record] captures events. Set once from [ScryerApplication.onCreate] via
     * [init]; `@Volatile` + `@PublishedApi internal` so the inline [record] function can read it
     * from any module without exposing the setter publicly (the public-API inline function cannot
     * access a private member). The volatile read is safely published from the application-init
     * thread to the ingestion/IO threads. Default false → release builds with no [init] call pay
     * nothing.
     */
    @Volatile
    @PublishedApi
    internal var enabled: Boolean = false

    /**
     * Optional debug-only mirror — when set, every recorded event is ALSO written to it.
     * Wired from [ScryerApplication.onCreate] (debuggable builds) to `Log.d("ZvecRecorder", …)`
     * so open-path decisions (wipe, ladder rungs, invalidation, cancellation) are visible in
     * logcat without opening the in-memory Inspector (issue 06, track A2). Null (default /
     * JVM tests) keeps this object free of `android.util.Log` — the ring buffer stays the
     * only sink, so unit tests are unaffected. `@Volatile` for the same safe-publication
     * reason as [enabled].
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
