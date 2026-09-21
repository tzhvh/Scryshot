/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.ingestion.triggers

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.tzhvh.scryernext.ScryerApplication
import io.github.tzhvh.scryernext.ingestion.IngestionProgressStore
import io.github.tzhvh.scryernext.ingestion.Progress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Device smoke for **issue 14** — [IngestionSession]: abort, cosmetic continuity, and the
 * cross-process `isSessionPending` union.
 *
 * Covers the three AC families:
 *
 * - **14a abort:** `abort()` → `cancelUniqueWork("ingestion")` → the WorkInfo goes `CANCELLED`.
 * - **14a cosmetic continuity:** `saveCosmetic`/`loadCosmetic` round-trip, and the store's
 *   `onTerminalClear` hook (registered by [IngestionWorker]) clears them on a terminal
 *   transition — so a finished session's numerics don't bleed into the next.
 * - **14b isSessionPending union:** the `Flow<Boolean>` reads true when WorkInfo is
 *   `RUNNING`/`ENQUEUED` OR the in-memory store is `Indexing`; false once both halves are
 *   terminal. This is the cross-process liveness surface the banner reads (derived, never
 *   persisted — the poison-pill trap).
 */
@RunWith(AndroidJUnit4::class)
class IngestionSessionDeviceSmokeTest {

    private val TAG = "IngestionSessionSmoke"
    private lateinit var context: android.content.Context
    private lateinit var session: IngestionSession
    private lateinit var store: IngestionProgressStore
    private lateinit var wm: WorkManager

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        session = ScryerApplication.getIngestionSession()
        store = ScryerApplication.getIngestionProgressStore()
        val config = Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(IngestionWorker.UNIQUE_NAME)
        session.clearCosmetic()
        if (store.progress.value is Progress.Indexing) store.abort()
    }

    // ----------------------------------------------------- 14a: abort

    @Test
    fun abort_cancelsTheBulkUniqueWork() {
        // Hold the guard as ON_OPEN before enqueueing so the bulk worker blocks in its
        // bounded-wait loop (tryEnter(BULK) refuses while ON_OPEN holds) — this keeps the
        // WorkInfo RUNNING, giving a stable window to exercise abort. Without this the worker
        // OCRs a single real image in <1s and finishes SUCCEEDED before abort can land.
        assertTrue(
            "pre-hold the guard so the bulk worker blocks",
            store.tryEnter(IngestionProgressStore.TriggerKind.ON_OPEN)
        )
        try {
            IngestionWorker.enqueue(context)
            // Give WM a moment to register + start the request (RUNNING, blocked on tryEnter).
            Thread.sleep(1000)
            val before = wm.getWorkInfosForUniqueWork(IngestionWorker.UNIQUE_NAME).get()?.firstOrNull()
            assertNotNull("WorkInfo should exist after enqueue", before)
            Log.i(TAG, "WorkInfo state before abort = ${before?.state}")

            // Abort (the banner's Stop path).
            session.abort()

            // The WorkInfo must reach CANCELLED (terminal).
            val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30)
            var state: WorkInfo.State? = before?.state
            while (System.currentTimeMillis() < deadline) {
                val info = wm.getWorkInfosForUniqueWork(IngestionWorker.UNIQUE_NAME).get()?.firstOrNull()
                state = info?.state
                if (state == WorkInfo.State.CANCELLED) break
                Thread.sleep(300)
            }
            Log.i(TAG, "WorkInfo state after abort = $state")
            assertEquals(
                "abort() should drive the bulk unique work to CANCELLED",
                WorkInfo.State.CANCELLED, state
            )
        } finally {
            // Release the pre-hold guard if abort's cascade didn't already.
            if (store.progress.value is Progress.Indexing) store.abort()
        }
    }

    // ----------------------------------------------------- 14a: cosmetic continuity

    @Test
    fun cosmeticPrefs_roundTripAndClear() {
        // Initially empty.
        assertNull("no cosmetics before a session", session.loadCosmetic())

        // Save + read back.
        session.saveCosmetic(sessionStartTotal = 842, doneCount = 45)
        val loaded = session.loadCosmetic()
        assertNotNull("cosmetics should round-trip", loaded)
        assertEquals(842, loaded!!.sessionStartTotal)
        assertEquals(45, loaded.doneCount)

        // Clear (the onTerminalClear hook fires this on complete/fail/abort).
        session.clearCosmetic()
        assertNull("cosmetics cleared after terminal transition", session.loadCosmetic())
        Log.i(TAG, "PASS: cosmetic save/load/clear")
    }

    @Test
    fun onTerminalClear_hookFiresOnComplete_andClearsCosmetics() {
        // Simulate the worker's session-start wiring: register the clear hook + save cosmetics.
        store.onTerminalClear { session.clearCosmetic() }
        session.saveCosmetic(sessionStartTotal = 100, doneCount = 5)
        assertNotNull("cosmetics present at session start", session.loadCosmetic())

        // The guard must be held for complete()/fail() to transition (they release it).
        assertTrue("tryEnter should succeed on idle store", store.tryEnter(IngestionProgressStore.TriggerKind.BULK))

        // complete() is a terminal transition → fires onTerminalClear → clears cosmetics.
        store.complete(Progress.Completed(indexed = 5, failed = 0, total = 100))

        assertNull(
            "cosmetics should be cleared by the onTerminalClear hook on complete()",
            session.loadCosmetic()
        )
        // And the guard is released (not wedged Indexing).
        assertFalse(
            "guard released after complete (not Indexing)",
            store.progress.value is Progress.Indexing
        )
        Log.i(TAG, "PASS: onTerminalClear fires on complete() and clears cosmetics")
    }

    // ----------------------------------------------------- 14b: isSessionPending union

    @Test
    fun isSessionPending_reflectsWorkInfoRunningOrEnqueued() {
        // No work, idle store → not pending.
        val idlePending = runBlocking { session.isSessionPending().first() }
        assertFalse(
            "not pending when store idle and no WorkInfo",
            idlePending
        )

        // Pre-hold the guard so the enqueued bulk worker blocks in its bounded-wait (RUNNING,
        // can't acquire tryEnter(BULK)) — otherwise a single real image OCRs in <1s and the
        // WorkInfo is terminal before this assertion. (The in-memory ON_OPEN hold also makes
        // isSessionPending true via the store half; the assertion is that the *union* is true.)
        assertTrue(store.tryEnter(IngestionProgressStore.TriggerKind.ON_OPEN))
        try {
            IngestionWorker.enqueue(context)
            Thread.sleep(1000)
            val activePending = runBlocking { session.isSessionPending().first() }
            assertTrue(
                "pending when bulk WorkInfo is ENQUEUED/RUNNING (got $activePending)",
                activePending
            )

            // Release the in-memory hold AND cancel the WM job → both halves terminal → false.
            store.complete(Progress.Completed(0, 0, 0))
            session.abort()
            // wait for the WM half to reach CANCELLED.
            val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30)
            while (System.currentTimeMillis() < deadline) {
                val s = wm.getWorkInfosForUniqueWork(IngestionWorker.UNIQUE_NAME).get()?.firstOrNull()?.state
                if (s == WorkInfo.State.CANCELLED) break
                Thread.sleep(300)
            }
            val afterCancel = runBlocking { session.isSessionPending().first() }
            assertFalse(
                "not pending after WorkInfo terminal + store idle",
                afterCancel
            )
            Log.i(TAG, "PASS: isSessionPending union reflects WorkInfo state")
        } finally {
            if (store.progress.value is Progress.Indexing) store.abort()
        }
    }

    @Test
    fun isSessionPending_reflectsInMemoryIndexing() {
        // The on-open case: no WM job, but the in-memory store is Indexing → pending true.
        assertTrue(store.tryEnter(IngestionProgressStore.TriggerKind.ON_OPEN))
        val pending = runBlocking { session.isSessionPending().first() }
        assertTrue(
            "pending when in-memory store is Indexing (on-open case, no WM job)",
            pending
        )
        store.complete(Progress.Completed(0, 0, 0))
        val after = runBlocking { session.isSessionPending().first() }
        assertFalse("not pending after in-memory terminal", after)
        Log.i(TAG, "PASS: isSessionPending reflects in-memory Indexing (on-open case)")
    }
}
