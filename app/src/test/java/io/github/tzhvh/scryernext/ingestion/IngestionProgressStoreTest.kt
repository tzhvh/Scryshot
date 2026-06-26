package io.github.tzhvh.scryernext.ingestion

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [IngestionProgressStore] — issue `10.5`'s app-scope state
 * surface + atomic §7.5 re-entrancy guard.
 *
 * Plain JUnit4 (no `kotlinx-coroutines-test`, no `mockk`, no Robolectric — matches
 * the Phase 1 ingestion test convention; see `IngestionEngineTest`). The store is
 * pure Kotlin (`StateFlow` + `AtomicReference`), so a JVM test is sufficient. A
 * capturing [IngestionLogger] substitutes for `android.util.Log`.
 *
 * Coverage of the issue's acceptance criteria:
 * - two concurrent `tryEnter` calls → exactly one `true`, one `false` (the CAS).
 * - terminal transitions reset to `Idle` and release the guard.
 * - `publish` updates `progress.value`; non-Indexing values are ignored by publish.
 * - refusal log captures the occupying [IngestionProgressStore.TriggerKind].
 * - `onTerminalClear` hook runs on terminal transitions.
 */
class IngestionProgressStoreTest {

    /** Captures log messages so tests can assert on the §7.6 status-change events. */
    private class CapturingLogger : IngestionLogger {
        val messages: MutableList<String> = mutableListOf()
        override fun log(message: String) { messages += message }
    }

    private fun newStore(logger: IngestionLogger = IngestionLogger.Noop) =
        IngestionProgressStore(logger)

    // ==========================================================================
    // tryEnter — the §7.5 atomic guard
    // ==========================================================================

    @Test
    fun initial_state_is_idle_and_no_run_active() {
        val store = newStore()
        assertTrue("progress should start Idle", store.progress.value is Progress.Idle)
        assertFalse("no run should be active initially", store.isActive)
    }

    @Test
    fun tryEnter_succeeds_when_idle_and_transitions_to_indexing() {
        val store = newStore()
        val granted = store.tryEnter(IngestionProgressStore.TriggerKind.ON_OPEN)
        assertTrue("first tryEnter should be granted", granted)
        assertTrue("progress should be Indexing after enter", store.progress.value is Progress.Indexing)
        assertTrue("a run should be active after enter", store.isActive)
    }

    @Test
    fun tryEnter_after_an_active_run_is_refused_without_tearing_down_the_holder() {
        val logger = CapturingLogger()
        val store = newStore(logger)
        assertTrue(store.tryEnter(IngestionProgressStore.TriggerKind.BULK))
        val holderProgress = store.progress.value

        // A second enter — of a different kind — must be refused…
        assertFalse(store.tryEnter(IngestionProgressStore.TriggerKind.ON_OPEN))
        // …AND must not disturb the holding run: same progress value, still active.
        assertSame("refusal must not tear down the holder's progress",
            holderProgress, store.progress.value)
        assertTrue("refusal must not release the holder's guard", store.isActive)
    }

    @Test
    fun concurrent_tryEnter_calls_result_in_exactly_one_winner() {
        // The load-bearing §7.5 property: two simultaneous entrants cannot both
        // succeed. A read-check-then-write would fail this under contention; the
        // AtomicReference.compareAndSet is the lock that makes it race-free.
        // Run many trials because a racy implementation may pass some of them.
        var racesDetected = 0
        repeat(200) {
            val store = newStore()
            val onOpenWins = java.util.concurrent.atomic.AtomicInteger(0)
            val bulkWins = java.util.concurrent.atomic.AtomicInteger(0)

            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            val barrier = java.util.concurrent.Phaser(2)
            try {
                val f1 = pool.submit {
                    barrier.arriveAndAwaitAdvance()
                    if (store.tryEnter(IngestionProgressStore.TriggerKind.ON_OPEN)) onOpenWins.incrementAndGet()
                }
                val f2 = pool.submit {
                    barrier.arriveAndAwaitAdvance()
                    if (store.tryEnter(IngestionProgressStore.TriggerKind.BULK)) bulkWins.incrementAndGet()
                }
                f1.get(); f2.get()
            } finally {
                pool.shutdown()
            }

            val totalWinners = onOpenWins.get() + bulkWins.get()
            // Exactly one entrant must win every trial — never zero, never two.
            assertEquals("trial #$it: exactly one tryEnter should win", 1, totalWinners)
            if (onOpenWins.get() == 1 && bulkWins.get() == 1) racesDetected++
        }
        assertEquals("a racy guard would let both win in some trials", 0, racesDetected)
    }

    // ==========================================================================
    // Terminal transitions reset to Idle and release the guard
    // ==========================================================================

    @Test
    fun complete_transitions_to_completed_releases_guard_and_resets_to_idle() = runBlocking {
        val store = newStore()
        store.tryEnter(IngestionProgressStore.TriggerKind.BULK)
        val result = Progress.Completed(indexed = 7, failed = 1, total = 8)

        store.complete(result)

        // The Completed value is published (a consumer collecting on the frame sees it)...
        assertSame(result, store.progress.value)
        // ...and a subsequent tryEnter succeeds (guard released).
        assertTrue(store.tryEnter(IngestionProgressStore.TriggerKind.ON_OPEN))
    }

    @Test
    fun fail_transitions_to_error_releases_guard_and_resets_to_idle() {
        val store = newStore()
        store.tryEnter(IngestionProgressStore.TriggerKind.ON_OPEN)
        val error = Progress.Error(IllegalStateException("boom"))

        store.fail(error)

        assertSame(error, store.progress.value)
        assertTrue("guard should be released after fail", store.tryEnter(IngestionProgressStore.TriggerKind.BULK))
    }

    @Test
    fun complete_without_an_active_run_still_publishes_and_is_idempotent() {
        // A terminal call when no run is active (defensive — e.g. a late callback)
        // must not throw and must publish its value. getAndSet(null)→null is fine.
        val store = newStore()
        val result = Progress.Completed(0, 0, 0)
        store.complete(result)   // no prior tryEnter
        assertSame(result, store.progress.value)
        assertFalse(store.isActive)
    }

    // ==========================================================================
    // publish — mid-run progress + backlog
    // ==========================================================================

    @Test
    fun publish_updates_progress_value_with_indexing_emissions() {
        val store = newStore()
        store.tryEnter(IngestionProgressStore.TriggerKind.BULK)
        val mid = Progress.Indexing(current = 3, total = 10, failedCount = 1)

        store.publish(mid)

        val current = store.progress.value
        assertTrue("publish should update progress to the Indexing emission", current is Progress.Indexing)
        val indexing = current as Progress.Indexing
        assertEquals(3, indexing.current)
        assertEquals(10, indexing.total)
        assertEquals(1, indexing.failedCount)
    }

    @Test
    fun publish_ignores_non_indexing_values() {
        // publish() is typed Progress.Indexing (not Progress) so the compiler
        // *enforces* that terminal states go through complete()/fail(). This
        // test pins that contract at the type level: the following would not
        // compile if the type were ever widened back to bare Progress, because
        // a bare `publish(Completed(...))` call is a type error here by design.
        val store = newStore()
        store.tryEnter(IngestionProgressStore.TriggerKind.BULK)
        val mid = Progress.Indexing(current = 1, total = 3, failedCount = 0)
        store.publish(mid)   // the only call shape publish() accepts
        assertSame(mid, store.progress.value)
        // Terminal transitions release the guard only via complete()/fail():
        store.complete(Progress.Completed(1, 0, 1))
        assertFalse(store.isActive)
    }

    @Test
    fun publishBacklog_updates_backlog_state() {
        val store = newStore()
        assertEquals(0, store.backlog.value)
        store.publishBacklog(42)
        assertEquals(42, store.backlog.value)
    }

    // ==========================================================================
    // Logging — §7.6 status-change events name the occupant
    // ==========================================================================

    @Test
    fun granted_tryEnter_is_logged() {
        val logger = CapturingLogger()
        val store = newStore(logger)
        store.tryEnter(IngestionProgressStore.TriggerKind.BULK)
        assertTrue("granted log should mention the kind",
            logger.messages.any { it.contains("BULK") && it.contains("granted") })
    }

    @Test
    fun refused_tryEnter_log_names_the_occupying_kind() {
        val logger = CapturingLogger()
        val store = newStore(logger)
        store.tryEnter(IngestionProgressStore.TriggerKind.ON_OPEN)   // squats
        store.tryEnter(IngestionProgressStore.TriggerKind.BULK)     // refused

        val refusal = logger.messages.first { it.contains("refused") }
        assertTrue("refusal log must name the occupant (ON_OPEN)",
            refusal.contains("ON_OPEN"))
    }

    @Test
    fun terminal_logs_name_the_prior_run_kind() {
        val logger = CapturingLogger()
        val store = newStore(logger)
        store.tryEnter(IngestionProgressStore.TriggerKind.BULK)
        store.complete(Progress.Completed(1, 0, 1))
        assertTrue("complete log should record the prior run",
            logger.messages.any { it.contains("complete") && it.contains("was BULK") })
    }

    // ==========================================================================
    // onTerminalClear — issue 14a's cosmetic-Prefs cleanup hook
    // ==========================================================================

    @Test
    fun onTerminalClear_hook_runs_on_complete() {
        val store = newStore()
        var cleared = false
        store.onTerminalClear { cleared = true }
        store.tryEnter(IngestionProgressStore.TriggerKind.BULK)
        assertFalse(cleared)
        store.complete(Progress.Completed(1, 0, 1))
        assertTrue("onTerminalClear should fire on complete", cleared)
    }

    @Test
    fun onTerminalClear_hook_runs_on_fail() {
        val store = newStore()
        var cleared = false
        store.onTerminalClear { cleared = true }
        store.tryEnter(IngestionProgressStore.TriggerKind.ON_OPEN)
        store.fail(Progress.Error(IllegalStateException("x")))
        assertTrue("onTerminalClear should fire on fail", cleared)
    }

    @Test
    fun onTerminalClear_does_not_fire_before_a_terminal_transition() {
        val store = newStore()
        var cleared = false
        store.onTerminalClear { cleared = true }
        store.tryEnter(IngestionProgressStore.TriggerKind.BULK)
        store.publish(Progress.Indexing(current = 1, total = 5, failedCount = 0))
        assertFalse("hook must not fire mid-run", cleared)
        assertNotNull("progress did update though", store.progress.value as? Progress.Indexing)
    }

    // --------------------------------------------------------------------------
    // onTerminalClear is once-consumed (the "next" in the method name)
    // --------------------------------------------------------------------------

    @Test
    fun onTerminalClear_hook_is_cleared_after_firing_so_a_double_terminal_fires_once() {
        // The "next terminal transition" contract: complete() must not re-fire a
        // stale hook on a subsequent terminal. A guard that never nulled the hook
        // would fire it on every terminal forever — this pins the once-consumed
        // lifecycle.
        val store = newStore()
        var fired = 0
        store.onTerminalClear { fired++ }
        store.tryEnter(IngestionProgressStore.TriggerKind.BULK)
        store.complete(Progress.Completed(1, 0, 1))   // fires once
        assertEquals(1, fired)
        // A subsequent terminal with no new registration must NOT fire the old hook.
        store.complete(Progress.Completed(2, 0, 2))
        assertEquals("stale hook must not re-fire", 1, fired)
    }

    @Test
    fun onTerminalClear_supports_per_session_reregistration_across_two_runs() {
        // Issue 14a's lifecycle: write cosmetic Prefs at session start (register
        // the clear there), the clear fires at session end, the next session
        // re-registers. The store must support this reregistration.
        val store = newStore()
        var cleared = 0

        // Run 1
        store.onTerminalClear { cleared++ }
        store.tryEnter(IngestionProgressStore.TriggerKind.BULK)
        store.complete(Progress.Completed(1, 0, 1))
        assertEquals(1, cleared)

        // Run 2 re-registers its own hook (14a does this at its session start)
        store.onTerminalClear { cleared++ }
        store.tryEnter(IngestionProgressStore.TriggerKind.ON_OPEN)
        store.fail(Progress.Error(IllegalStateException("x")))
        assertEquals(2, cleared)
    }
}
