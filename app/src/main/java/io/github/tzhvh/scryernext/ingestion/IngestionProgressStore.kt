/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * App-scope single source of truth for ingestion state — **two things at once**
 * (ADR 0004 §2, §7.5; issue `10.5`):
 *
 * 1. **The shared progress/notification surface.** A single [StateFlow]<[Progress]>
 *    that every trigger publishes to and every consumer (the UI observers, the
 *    foreground notification, the in-app banner) reads. This is the structural
 *    replacement for `ContentScanner.getProgressState()`, which Phase 3 (issue `16`)
 *    migrated the consumers off of and then deleted. The engine is a pure cold
 *    `Flow<Progress>` (ADR 0004 §2 — it knows nothing of scopes); the collecting
 *    trigger (Phase 2's on-open coroutine / bulk `CoroutineWorker`) feeds the
 *    engine's emissions into [publish].
 * 2. **The §7.5 re-entrancy guard.** [tryEnter] is a single atomic
 *    `Idle → Indexing` transition; the atomicity *is* the lock that prevents a
 *    double-tap or an on-open/bulk collision from running two engines over one
 *    producer. A read-check-then-write would be the same race §7.5 was written
 *    to prevent (`index_service.dart` scar), just relocated — so the lock is the
 *    atomic CAS, not a polite suggestion.
 *
 * ### The guard is in-memory only and self-healing — load-bearing
 *
 * **Do NOT persist the guard state to disk.** This resolves the trap where
 * ADR 0004's §5 (dedup-as-resumption) and §7.5 (re-entrancy guard) almost
 * contradict:
 * - **dedup (§5)** is about *work correctness* — "did this file get processed?"
 *   — and lives in the DB (`processed` flag), surviving process death,
 *   idempotent by construction. We never persist *which file we were on*.
 * - **the guard (§7.5)** is about *run coordination* — "is a session active
 *   right now?" — and that state is what dies with the process.
 *
 * Persisting the `Indexing` guard state would turn it into a **poison pill**: if
 * the process died without a clean transition (force-stop, or
 * `WorkManager.cancelUniqueWork` while the app process is already gone), the
 * persisted `Indexing` never clears and every future [tryEnter] returns false
 * forever — the ingestion system wedges. So:
 * - The guard is in-memory. It coordinates *within a live process*; a dead
 *   process can't double-tap anything. On fresh boot it starts `Idle` — safe.
 * - **Cross-process "is a session pending?" is *derived*, not stored.** The
 *   banner/notification derives it from the **union** of `store.progress.value
 *   is Indexing` (active now, in this process — the on-open case, which has no
 *   WorkManager job) AND `WorkManager.getWorkInfoForUniqueWork("ingestion")`
 *   state `RUNNING`/`ENQUEUED` (a bulk job pending across process death — issue
 *   `14b`). `WorkInfo` is OS-owned and transitions on real job lifecycle; the
 *   in-memory store resets on every boot. Neither can wedge.
 *
 * See [issue 10.5](file:///.scratch/ingestion/issues/10.5-ingestion-progress-store.md)
 * and [ADR 0004 §2, §5, §7.5, §7.6](file:///docs/adr/0004-ingestion-engine-and-trigger-architecture-v2.md).
 *
 * @param logger tagged-logcat sink for status-change events (§7.6). Production
 *   wiring supplies an `android.util.Log`-backed implementation; tests supply a
 *   capturing one or [IngestionLogger.Noop]. The store logs **status changes
 *   only** (enter / complete / fail), never per-candidate ticks.
 */
class IngestionProgressStore(
    private val logger: IngestionLogger = IngestionLogger.Noop
) {
    /**
     * Which kind of trigger owns the current run. Drives notification behaviour
     * (on-open: no notification; bulk: persistent progress) and makes the guard's
     * refusal log name the occupant, so a stuck run is diagnosable. Not YAGNI:
     * without it the store cannot distinguish "bulk refused because on-open is
     * active" from "bulk refused because bulk is active" — exactly the
     * diagnosability §7.6 asks for.
     */
    enum class TriggerKind { ON_OPEN, BULK }

    /** Held only to drive the `onTerminal` clear hook (issue `14a` cosmetic cleanup). */
    private var onTerminal: (() -> Unit)? = null

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    /** The live ingestion state. Initial value is [Progress.Idle]; resets to it on terminal. */
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val _backlog = MutableStateFlow(0)
    /** Discovery worker's (issue `13`) unindexed count; drives the in-app banner (ADR 0004 §4). */
    val backlog: StateFlow<Int> = _backlog.asStateFlow()

    /**
     * The §7.5 lock. `null` == no run active (Idle); non-null == a run of that
     * [TriggerKind] is active. An [AtomicReference] + `compareAndSet` makes
     * [tryEnter] a real atomic CAS rather than a read-then-write race.
     */
    private val activeRun = AtomicReference<TriggerKind?>(null)

    /**
     * Atomically enter an indexing session (ADR 0004 §7.5 guard).
     *
     * Single CAS: `null → [kind]`. Returns `true` and transitions [progress] to
     * an early [Progress.Indexing] (total = 0) on success; returns `false` and
     * logs the occupying kind on refusal. **There is no `forceEnter` / preempt
     * API** — preemption recreates the §7.5 race and adds a cancel-then-enter
     * failure mode. Abort (issue `14a`) is the only escape hatch; the run it
     * tears down must be genuinely cancellable (issue `11`'s precondition).
     */
    fun tryEnter(kind: TriggerKind): Boolean {
        val granted = activeRun.compareAndSet(null, kind)
        if (granted) {
            _progress.value = Progress.Indexing(current = 0, total = 0, failedCount = 0)
            logger.log("tryEnter($kind) → granted")
        } else {
            logger.log("tryEnter($kind) → refused (${activeRun.get()} active)")
        }
        return granted
    }

    /**
     * Publish mid-run progress — the collecting trigger feeds the engine's
     * `Flow<Progress>` `Indexing` emissions in here.
     *
     * Typed [Progress.Indexing] (not [Progress]) so the "Indexing-only" contract
     * is enforced at compile time, not as a silent runtime drop. A trigger
     * collecting the engine's cold `Flow<Progress>` must therefore **route**
     * terminal states through [complete] / [fail] — which also release the guard
     * and fire the [onTerminalClear] hook — rather than passing them here.
     * Accepting bare [Progress] and silently dropping non-`Indexing` values would
     * let a naive `collect { store.publish(it) }` leak the guard forever on a
     * `Completed`/`Error` (the terminal would never release it). `Paused`
     * (issue `14`) gets its own `pause()` method when that work lands.
     */
    fun publish(progress: Progress.Indexing) {
        _progress.value = progress
    }

    /**
     * Publish the discovery worker's (issue `13`) unindexed count to [backlog],
     * so the in-app banner (Phase 3) reads a single shared count rather than
     * each consumer re-counting.
     */
    fun publishBacklog(count: Int) {
        _backlog.value = count
    }

    /**
     * Register a hook to run on the next terminal transition ([complete] /
     * [fail]). Issue `14a` uses this to clear the session-scoped cosmetic Prefs
     * `(sessionStartTotal, doneCount)` so a stale session's numbers don't bleed
     * into the next. Cosmetic-only; never a persisted guard-state write.
     *
     * Once-consumed: the hook is cleared after firing, so it runs for the next
     * terminal transition only (matching the "next" in the method name). A new
     * run that wants cleanup re-registers (issue `14a` does this at session
     * start, symmetric with where it writes the cosmetic Prefs).
     */
    fun onTerminalClear(action: () -> Unit) {
        onTerminal = action
    }

    /**
     * Terminal success. Transitions [progress] to [Progress.Completed], releases
     * the guard (`active → null`), and runs the [onTerminalClear] hook (which
     * is then cleared — once-consumed; see [onTerminalClear]).
     */
    fun complete(result: Progress.Completed) {
        _progress.value = result
        val was = activeRun.getAndSet(null)
        val hook = onTerminal
        onTerminal = null
        hook?.invoke()
        logger.log("complete(${result.indexed}/${result.total}, failed=${result.failed}) [was $was]")
    }

    /**
     * Terminal failure. Transitions [progress] to [Progress.Error], releases the
     * guard, and runs the [onTerminalClear] hook (then cleared — see [onTerminalClear]).
     */
    fun fail(error: Progress.Error) {
        _progress.value = error
        val was = activeRun.getAndSet(null)
        val hook = onTerminal
        onTerminal = null
        hook?.invoke()
        logger.log("fail(${error.throwable.javaClass.simpleName}: ${error.throwable.message}) [was $was]")
    }

    /**
     * Terminal cancellation (issue `14a` abort; on-open cancel-on-backgrounding).
     *
     * Releases the guard and transitions to [Progress.Aborted] — **not**
     * [Progress.Error]: cancellation is an intentional stop, so the UI must not
     * read "indexing failed." Mirrors [fail]'s guard release + onTerminal hook,
     * but emits a distinct terminal state so consumers can distinguish "stopped"
     * from "failed." Used by [OnOpenTrigger]'s `CancellationException` handler.
     */
    fun abort() {
        val was = activeRun.getAndSet(null)
        if (was == null) return   // nothing to abort; no run active
        val hook = onTerminal
        onTerminal = null
        _progress.value = Progress.Aborted
        hook?.invoke()
        logger.log("abort [was $was]")
    }

    /** Convenience: is a run currently active in *this* process? (Cross-process is WorkInfo.) */
    val isActive: Boolean get() = activeRun.get() != null
}
