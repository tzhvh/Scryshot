/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion.triggers

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.tzhvh.scryernext.ingestion.IngestionProgressStore
import io.github.tzhvh.scryernext.ingestion.Progress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * The user-facing control + cross-process liveness surface for an ingestion session —
 * issue `14`. Owns everything that is **Context-bound** (WorkManager, SharedPreferences)
 * so [IngestionProgressStore] stays a pure, JVM-unit-testable class with no Android dep.
 *
 * ### 14b — Cross-process "is a session pending?" is **derived, not stored**
 *
 * [isSessionPending] is the **union** of two sources, neither of which can wedge:
 * - **in-memory** — `store.progress is Indexing` → a session active *now, in this process*
 *   (the on-open case, which has no WM job).
 * - **WorkInfo** — `WorkManager.getWorkInfoForUniqueWork("ingestion")` state
 *   `RUNNING`/`ENQUEUED` → a bulk job pending *across process death* (ADR 0004 §7.6:
 *   WM persists WorkInfo across process death and reboot by construction).
 *
 * On process death + resurrection: the in-memory half resets to `Idle`, then goes
 * `Indexing` again when the resurrected worker re-enters the guard (transient cosmetic
 * gap during resurrection); the `WorkInfo` half was `RUNNING` the whole time. **No stuck
 * state** — `WorkInfo` is OS-owned and transitions on real job lifecycle.
 *
 * ### NEVER persist the guard's `Indexing` state to disk — the poison-pill trap
 *
 * Persisting the guard's `Indexing` flag to Prefs "for UI continuity" introduces a
 * **stuck-`Indexing`-forever** failure mode: if the process dies without a clean
 * transition (force-stop, or `cancelUniqueWork` while the app process is already gone),
 * the persisted `Indexing` never clears and every future `tryEnter()` returns false
 * forever — the ingestion system wedges. Cross-process liveness is *derived* from
 * `WorkInfo`, never persisted from the guard. See [IngestionProgressStore]'s "Self-healing"
 * KDoc and [ADR 0004 §7.6](file:///docs/adr/0004-ingestion-engine-and-trigger-architecture-v2.md).
 *
 * ### 14a — Cosmetic continuity (session-scoped, worst-case-cosmetic)
 *
 * Unlike guard state, the **cosmetic** numerics `(sessionStartTotal, doneCount)` are safe to
 * persist because they are bounded to a single session and worst-case-cosmetic: if the clear
 * on terminal is missed (crash mid-session), the worst case is a wrong-looking bar for one
 * session — never a wedged guard (ADR 0004 §5: *"Pause is presentation state, not worklist
 * state… if lost, the worst case is a cosmetic reset — no correctness impact"*). So they live
 * in a session-scoped Prefs file and are cleared on every terminal transition via the store's
 * [IngestionProgressStore.onTerminalClear] hook (registered at session start).
 *
 * @param context any context; this class derives its own `applicationContext`.
 * @param store   the app-scope progress store; read for the in-memory half of [isSessionPending].
 */
class IngestionSession(
    context: Context,
    private val store: IngestionProgressStore
) {
    private val appContext = context.applicationContext

    // ---------------------------------------------------------------------- 14b: pending

    /**
     * "Is an ingestion session pending?" — the banner/notification's source of truth
     * (issue `14b`). A [Flow] of the **union** of the in-memory and WorkInfo halves; the
     * combination logic is the pure [isPending] helper (extracted so it's JVM-unit-testable,
     * symmetric with issue 12/13's pure-helper convention).
     *
     * Phase 3's in-app banner reads this; it must not re-derive the union.
     */
    fun isSessionPending(): Flow<Boolean> =
        combine(store.progress, workInfoState()) { progress, state ->
            isPending(progress, state)
        }

    /**
     * The WM half of [isSessionPending] — the bulk job's lifecycle state, or null if never run.
     *
     * `getWorkInfosForUniqueWorkFlow` returns a `List<WorkInfo>` (unique work may be a chain);
     * the ingestion job is a single [OneTimeWorkRequest], so the list has at most one element and
     * its state is the representative state. `null` if the unique work was never enqueued.
     */
    private fun workInfoState(): Flow<WorkInfo.State?> =
        WorkManager.getInstance(appContext)
            .getWorkInfosForUniqueWorkFlow(IngestionWorker.UNIQUE_NAME)
            .map { list -> list.firstOrNull()?.state }

    // ---------------------------------------------------------------------- 14a: abort

    /**
     * User-facing start of the bulk job (issue `18`) — the symmetric pair of [abort].
     * The banner's "Index now" button calls this. Mirrors [abort]'s shape: the session owns the
     * WM enqueue surface so the UI (and [DiscoveryActionReceiver]) don't re-implement it and
     * never touch [WorkManager] directly. Delegates to [IngestionWorker.enqueue], whose
     * `ExistingWorkPolicy.KEEP` makes a double-tap a no-op (no second bulk job).
     */
    fun startBulk() {
        IngestionWorker.enqueue(appContext)
    }

    /**
     * User-facing abort (issue `14a`) — the *only* override (issue 10.5: no `forceEnter`/preempt).
     * The banner's Stop button calls this.
     *
     * Cancels the bulk worker via `cancelUniqueWork("ingestion")`; WM then cancels the worker
     * → cancels its collecting scope → propagates to the engine's suspend points (issue `11`'s
     * cancellability precondition) → the worker's `catch(CancellationException)` calls
     * `store.abort()`, transitioning the surface to [Progress.Aborted] and firing the
     * [onTerminalClear][IngestionProgressStore.onTerminalClear] hook (which clears the cosmetic
     * Prefs). For the on-open case (no WM job), backgrounding cancels its scope the same way.
     *
     * **No new receiver** — issue 12 already wired the notification's Stop to
     * `createCancelPendingIntent(id)` (the same cancel path). This helper exists so the
     * *banner* has a callable entry point without each consumer re-implementing the cancel.
     */
    fun abort() {
        WorkManager.getInstance(appContext).cancelUniqueWork(IngestionWorker.UNIQUE_NAME)
    }

    // ---------------------------------------------------------------------- 14a: cosmetic continuity

    /**
     * Persist the cosmetic session numerics for bar continuity (issue `14a`, ADR 0004 §5).
     * Called by the triggers at session start (after the guard is acquired). **Cosmetic only** —
     * these carry no work-correctness information and are cleared on terminal transition.
     */
    fun saveCosmetic(sessionStartTotal: Int, doneCount: Int) {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SESSION_START_TOTAL, sessionStartTotal)
            .putInt(KEY_DONE_COUNT, doneCount)
            .apply()
    }

    /**
     * Read the persisted cosmetic numerics for bar continuity, or null if none. On a missed
     * clear (crash mid-session) a stale value may be read — that is the bounded,
     * worst-case-cosmetic failure ADR 0004 §5 explicitly accepts (a wrong-looking bar for one
     * session, not a wedged guard).
     */
    fun loadCosmetic(): Progress.Paused? {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // A session that wrote cosmetics always wrote BOTH keys; absence of either means no
        // active session's cosmetics were persisted — treat as "no continuity data."
        if (!prefs.contains(KEY_SESSION_START_TOTAL) || !prefs.contains(KEY_DONE_COUNT)) return null
        return Progress.Paused(
            sessionStartTotal = prefs.getInt(KEY_SESSION_START_TOTAL, 0),
            doneCount = prefs.getInt(KEY_DONE_COUNT, 0)
        )
    }

    /**
     * Clear the cosmetic session numerics. Called via the store's
     * [onTerminalClear][IngestionProgressStore.onTerminalClear] hook on every terminal
     * transition (`complete`/`fail`/`abort`), so a finished session's numbers don't bleed
     * into the next. Session-scoped cleanup; never a guard-state operation.
     */
    fun clearCosmetic() {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SESSION_START_TOTAL)
            .remove(KEY_DONE_COUNT)
            .apply()
    }

    companion object {
        private const val PREFS = "ingestion_session"
        private const val KEY_SESSION_START_TOTAL = "session_start_total"
        private const val KEY_DONE_COUNT = "done_count"
    }
}

/**
 * The cross-process "is a session pending?" decision as a pure, device-free function
 * (issue 14 AC: "the `isSessionPending` combination logic … is unit-testable as pure
 * functions"). Extracted from [IngestionSession.isSessionPending] so the union rule —
 * in-memory `Indexing` OR WorkInfo `RUNNING`/`ENQUEUED` — has one named, documented,
 * tested home.
 *
 * - `progress is Indexing` covers the on-open case (in-process, no WM job).
 * - `state == RUNNING || state == ENQUEUED` covers the bulk case (cross-process, WM-owned).
 * - A terminal progress ([Progress.Completed]/[Progress.Error]/[Progress.Aborted]) with a
 *   terminal WorkInfo state (`SUCCEEDED`/`FAILED`/`CANCELLED`) is correctly *not* pending.
 */
internal fun isPending(progress: Progress, state: WorkInfo.State?): Boolean =
    progress is Progress.Indexing ||
        state == WorkInfo.State.RUNNING ||
        state == WorkInfo.State.ENQUEUED
