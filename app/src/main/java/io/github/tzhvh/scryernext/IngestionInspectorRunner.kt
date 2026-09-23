/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext

import io.github.tzhvh.scryernext.ingestion.IngestionEventRecorder
import io.github.tzhvh.scryernext.ingestion.IngestionProgressStore
import io.github.tzhvh.scryernext.ingestion.Progress
import io.github.tzhvh.scryernext.ingestion.StageTimings
import io.github.tzhvh.scryernext.ingestion.ZvecContentStore
import io.github.tzhvh.scryernext.persistence.ContentMetadataCacheDao
import io.github.tzhvh.scryernext.persistence.ScreenshotDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ingestion Inspector's read-only report builder — the [ZvecInspectorRunner] recipe applied to the
 * OCR ingestion pipeline: a pure class fed by constructor-injected dependencies, so the whole
 * panel set is JVM-unit-testable (no Android, no WorkManager, no ML Kit on the test classpath).
 *
 * Everything it reads already exists — this class owns no state and mutates nothing:
 * - live state: the app-scope [IngestionProgressStore] ([IngestionProgressStore.progress],
 *   [IngestionProgressStore.backlog], [IngestionProgressStore.isActive]/[IngestionProgressStore.activeKind]);
 * - queue & drift: the same four Room counts the zvec inspector's drift meter batches
 *   (O5) plus the dedup-cache size and the zvec `docCount()`;
 * - stage timings: the [StageTimings] EMA snapshot that already rides every [Progress] emission;
 * - per-item events / run history: [IngestionEventRecorder.getEvents] and
 *   [IngestionProgressStore.runHistory] — the two debug-only retention rings;
 * - the zvec handoff line: [ZvecContentStore]'s public post-open diagnostics, the ones the zvec
 *   inspector renders in full (the link-out covers the depth).
 *
 * The two Android-bound inputs arrive as lambdas so tests inject constants:
 * [sessionPending] (production: `session.isSessionPending().first()` — WorkManager-bound) and
 * [mlKitState] (production: [MlKitOcrStage] companion's recognizer timestamp).
 */
class IngestionInspectorRunner(
    private val store: IngestionProgressStore,
    private val screenshotDao: ScreenshotDao,
    private val metadataCacheDao: ContentMetadataCacheDao,
    private val contentStore: ZvecContentStore,
    private val events: IngestionEventRecorder,
    private val sessionPending: suspend () -> Boolean = { false },
    private val mlKitState: () -> MlKitState = { MlKitState() },
) {

    /** Panel 1 — who is running, how far, and is a session pending cross-process (union rule). */
    data class LiveState(
        val progress: Progress,
        val backlog: Int,
        val isActive: Boolean,
        val activeKind: IngestionProgressStore.TriggerKind?,
        val sessionPending: Boolean,
    )

    /**
     * Panel 2 — Room↔cache↔zvec queue counts with the drift math. `unbridged` mirrors the zvec
     * inspector's D4 calculation (`processed − distinct`, processed-but-no-hash rows);
     * `orphans`/`holes` are the RAW docCount↔distinctIndexed gaps — the zvec inspector remains
     * the authoritative verdict (it subtracts benchmark synth docs, O1); this panel renders the
     * quick check and points there.
     */
    data class QueueDrift(
        val roomUnprocessedCount: Int,
        val roomProcessedCount: Int,
        val roomIndexedCount: Int,
        val roomDistinctIndexedCount: Int,
        val unbridgedProcessedCount: Int,
        val cacheRowCount: Int,
        val zvecDocCount: Long,
        val orphansCount: Long,
        val holesCount: Long,
        /** False when `stats()` failed (docCount defaulted to 0 → the verdict is UNAVAILABLE). */
        val statsReadable: Boolean,
    )

    /** Panel 6 — ML Kit singleton liveness (ADR 0001 pins the bundled model; no download panel). */
    data class MlKitState(
        /** False when the singleton has never been needed (no ingestion run / DetailPage OCR yet). */
        val recognizerCreated: Boolean = false,
        /** Wall-clock creation timestamp; 0 when [recognizerCreated] is false. */
        val createdAtMs: Long = 0L,
    )

    /** Panel 7 — the zvec-first→flush→Room→cache handoff's health, one line each. */
    data class ZvecHandoff(
        val lastOpenOutcome: String,
        val lockRecoveriesCount: Int,
        val schemaWipesCount: Int,
        val lastFlushTimestamp: Long,
    )

    data class Report(
        val live: LiveState,
        val drift: QueueDrift,
        val stageTimings: StageTimings?,
        val history: List<IngestionProgressStore.RunRecord>,
        val events: List<IngestionEventRecorder.Event>,
        val mlKit: MlKitState,
        val handoff: ZvecHandoff,
    )

    suspend fun getReport(): Report = withContext(Dispatchers.Default) {
        val progress = store.progress.value

        // O5-style: one dispatcher hop for the five Room counts instead of sequential hops.
        val (unprocessed, processed, indexed, distinctIndexed, cacheRows) = withContext(Dispatchers.IO) {
            Penta(
                screenshotDao.getUnprocessedCount(),
                screenshotDao.getProcessedCount(),
                screenshotDao.getIndexedCount(),
                screenshotDao.getDistinctIndexedCount(),
                metadataCacheDao.getCount(),
            )
        }

        val stats = contentStore.getCollectionStats()
        val docCount = stats?.docCount ?: 0L
        val distinctIndexedCount = distinctIndexed
        val unbridged = (processed - distinctIndexedCount).coerceAtLeast(0)
        val orphans = (docCount - distinctIndexedCount).coerceAtLeast(0L)
        val holes = (distinctIndexedCount - docCount).coerceAtLeast(0L)

        val drift = QueueDrift(
            roomUnprocessedCount = unprocessed,
            roomProcessedCount = processed,
            roomIndexedCount = indexed,
            roomDistinctIndexedCount = distinctIndexedCount,
            unbridgedProcessedCount = unbridged,
            cacheRowCount = cacheRows,
            zvecDocCount = docCount,
            orphansCount = orphans,
            holesCount = holes,
            statsReadable = contentStore.lastStatsError == null && stats != null,
        )

        val live = LiveState(
            progress = progress,
            backlog = store.backlog.value,
            isActive = store.isActive,
            activeKind = store.activeKind,
            sessionPending = runCatching { sessionPending() }.getOrDefault(false),
        )

        Report(
            live = live,
            drift = drift,
            stageTimings = when (progress) {
                is Progress.Indexing -> progress.stageTimings
                is Progress.Completed -> progress.stageTimings
                else -> null
            },
            history = store.runHistory(),
            events = events.getEvents(),
            mlKit = mlKitState(),
            handoff = ZvecHandoff(
                lastOpenOutcome = contentStore.lastOpenOutcome,
                lockRecoveriesCount = contentStore.lockRecoveriesCount,
                schemaWipesCount = contentStore.schemaWipesCount,
                lastFlushTimestamp = contentStore.lastFlushTimestamp,
            ),
        )
    }

    /** Kotlin stdlib ships Pair/Triple but not five; private for the batched five-count read. */
    private data class Penta<A, B, C, D, E>(
        val first: A, val second: B, val third: C, val fourth: D, val fifth: E,
    )
}
