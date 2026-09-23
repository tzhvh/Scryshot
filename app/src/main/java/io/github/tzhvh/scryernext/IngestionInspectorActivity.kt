/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.tzhvh.scryernext.ingestion.IngestionEventRecorder
import io.github.tzhvh.scryernext.ingestion.IngestionProgressStore
import io.github.tzhvh.scryernext.ingestion.MlKitOcrStage
import io.github.tzhvh.scryernext.ingestion.Progress
import io.github.tzhvh.scryernext.ingestion.StageTimings
import io.github.tzhvh.scryernext.ingestion.triggers.IngestionSession
import io.github.tzhvh.scryernext.ui.ConfirmationDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only Ingestion Inspector screen — the [ZvecInspectorActivity] shell applied to the OCR
 * ingestion pipeline (ingestion/IngestionEngine, READ→DEDUP→OCR→WRITE). Thin view only: all data
 * assembly lives in the JVM-tested [IngestionInspectorRunner], all actions delegate to the
 * existing app-scope surfaces ([IngestionSession.startBulk]/[IngestionSession.abort], the DAO's
 * `resetProcessedForReingest`), and every panel reads state that already exists — nothing here
 * touches the production data path.
 *
 * Live updating: the store's [Progress] flow is collected while STARTED and re-renders the live
 * panel per emission (count-only reads, no Room). The other panels refresh on demand (Refresh
 * button / after each action), matching the zvec inspector's manual-refresh contract.
 */
class IngestionInspectorActivity : AppCompatActivity() {

    private lateinit var liveStatusText: TextView
    private lateinit var liveStateText: TextView
    private lateinit var queueDriftText: TextView
    private lateinit var stageTimingsText: TextView
    private lateinit var historyText: TextView
    private lateinit var eventLogText: TextView
    private lateinit var mlkitText: TextView
    private lateinit var zvecHandoffText: TextView

    private lateinit var runner: IngestionInspectorRunner
    private lateinit var session: IngestionSession

    /** Latest fully-rendered report as one string, for Copy report. */
    private var reportText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ingestion_inspector)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        liveStatusText = findViewById(R.id.live_status_text)
        liveStateText = findViewById(R.id.live_state_text)
        queueDriftText = findViewById(R.id.queue_drift_text)
        stageTimingsText = findViewById(R.id.stage_timings_text)
        historyText = findViewById(R.id.history_text)
        eventLogText = findViewById(R.id.event_log_text)
        mlkitText = findViewById(R.id.mlkit_text)
        zvecHandoffText = findViewById(R.id.zvec_handoff_text)

        val store = ScryerApplication.getIngestionProgressStore()
        session = ScryerApplication.getIngestionSession()
        runner = IngestionInspectorRunner(
            store = store,
            screenshotDao = ScryerApplication.getScreenshotDao(),
            metadataCacheDao = ScryerApplication.getMetadataCacheDao(),
            contentStore = ScryerApplication.getZvecContentStore(),
            events = ScryerApplication.getIngestionEventRecorder(),
            sessionPending = { session.isSessionPending().first() },
            mlKitState = {
                val createdAt = MlKitOcrStage.recognizerCreatedAtMs
                IngestionInspectorRunner.MlKitState(
                    recognizerCreated = createdAt != 0L,
                    createdAtMs = createdAt,
                )
            },
        )

        findViewById<Button>(R.id.inspector_refresh_button).setOnClickListener { refreshReport() }

        findViewById<Button>(R.id.inspector_run_bulk_button).setOnClickListener {
            session.startBulk()
            toast("Bulk run enqueued")
            refreshReport()
        }

        findViewById<Button>(R.id.inspector_abort_button).setOnClickListener {
            session.abort()
            toast("Abort requested")
            refreshReport()
        }

        findViewById<Button>(R.id.inspector_reingest_button).setOnClickListener { confirmReingestAll() }

        findViewById<Button>(R.id.inspector_copy_button).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ingestion inspector report", reportText))
            toast("Report copied")
        }

        findViewById<Button>(R.id.inspector_clear_logs_button).setOnClickListener {
            ScryerApplication.getIngestionEventRecorder().clear()
            ScryerApplication.getIngestionEventRecorder().record { "Event log cleared by user" }
            refreshReport()
        }

        // Live panel tracks the store per emission (count-only, no Room); the data panels stay
        // manual-refresh — a per-candidate full report would hammer Room for a debug screen.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                store.progress.collect { progress ->
                    renderLive(
                        progress = progress,
                        backlog = store.backlog.value,
                        isActive = store.isActive,
                        activeKind = store.activeKind,
                        sessionPending = lastKnownSessionPending,
                    )
                }
            }
        }

        refreshReport()
    }

    /** The cross-process half only changes on real WM transitions; refreshed with each full report. */
    private var lastKnownSessionPending: Boolean = false

    private fun refreshReport() {
        lifecycleScope.launch {
            val report = runner.getReport()
            lastKnownSessionPending = report.live.sessionPending
            renderReport(report)
        }
    }

    // ------------------------------------------------------------------ rendering

    private fun renderReport(report: IngestionInspectorRunner.Report) {
        // Each renderer sets its TextView and returns the same text, so the copyable
        // report and the screen never diverge.
        val sections = linkedMapOf(
            "Live State" to renderLive(
                progress = report.live.progress,
                backlog = report.live.backlog,
                isActive = report.live.isActive,
                activeKind = report.live.activeKind,
                sessionPending = report.live.sessionPending,
            ),
            "Queue & Drift" to renderQueueDrift(report.drift),
            "Stage Timings (EMA)" to renderStageTimings(report.stageTimings),
            "Run History (last 10)" to renderHistory(report.history),
            "Per-item Event Feed" to renderEvents(report.events),
            "ML Kit" to renderMlKit(report.mlKit),
            "zvec Handoff" to renderHandoff(report.handoff),
        )
        reportText = buildString {
            append("INGESTION INSPECTOR REPORT\n")
            sections.forEach { (title, body) ->
                append("\n== ").append(title).append(" ==\n").append(body)
            }
        }
    }

    private fun renderLive(
        progress: Progress,
        backlog: Int,
        isActive: Boolean,
        activeKind: IngestionProgressStore.TriggerKind?,
        sessionPending: Boolean,
    ): String {
        val (line, color) = when (progress) {
            is Progress.Indexing -> "● INDEXING ${progress.current}/${progress.total}" to Color.rgb(2, 119, 189)
            is Progress.Completed -> "✓ COMPLETED ${progress.indexed}/${progress.total} (failed=${progress.failed})" to Color.rgb(56, 142, 60)
            is Progress.Error -> "✗ ERROR ${progress.throwable.javaClass.simpleName}: ${progress.throwable.message}" to Color.rgb(198, 40, 40)
            is Progress.Aborted -> "■ ABORTED" to Color.rgb(230, 126, 0)
            is Progress.Paused -> "‖ PAUSED ${progress.doneCount}/${progress.sessionStartTotal}" to Color.rgb(230, 126, 0)
            Progress.Idle -> "○ IDLE" to Color.GRAY
        }
        liveStatusText.text = line
        liveStatusText.setTextColor(color)

        val detail = buildString {
            append("  Backlog (discovery): ").append(backlog).append("\n")
            append("  Guard: ").append(if (isActive) "HELD by ${activeKind ?: "??"}" else "free").append("\n")
            append("  Session pending (incl. WM): ").append(if (sessionPending) "YES" else "no").append("\n")
        }
        liveStateText.text = detail
        return "$line\n$detail"
    }

    private fun renderQueueDrift(drift: IngestionInspectorRunner.QueueDrift): String =
        buildString {
            append("  Room unprocessed (queue): ").append(drift.roomUnprocessedCount).append("\n")
            append("  Room processed: ").append(drift.roomProcessedCount).append("\n")
            append("  Room content_hash not null: ").append(drift.roomIndexedCount).append("\n")
            append("  Room DISTINCT content_hash: ").append(drift.roomDistinctIndexedCount).append("\n")
            if (drift.unbridgedProcessedCount > 0) {
                append("  Unbridged (processed, no hash): ").append(drift.unbridgedProcessedCount).append("\n")
            }
            append("  Dedup cache rows: ").append(drift.cacheRowCount).append("\n")
            if (!drift.statsReadable) {
                append("  zvec docCount: ⚠️ stats read FAILED — see zvec inspector\n")
            } else {
                append("  zvec docCount: ").append(drift.zvecDocCount).append("\n")
            }
            append("\n  Invariant check:\n")
            when {
                !drift.statsReadable -> {
                    append("    ⚠️ UNAVAILABLE: stats() read failed; cannot verify consistency.\n")
                    append("       Open the zvec inspector for the failure reason.\n")
                }
                drift.orphansCount == 0L && drift.holesCount == 0L -> {
                    append("    ✅ PASS: zvec ↔ Room (distinct) consistent.")
                    if (drift.unbridgedProcessedCount > 0) {
                        append(" (${drift.unbridgedProcessedCount} unbridged row(s))")
                    }
                    append("\n")
                }
                else -> {
                    append("    ⚠️ RAW DRIFT: orphans=${drift.orphansCount} holes=${drift.holesCount}\n")
                    append("       Raw gap only — the zvec inspector's verdict is authoritative\n")
                    append("       (it subtracts benchmark synth docs; holes self-heal next run).\n")
                }
            }
        }.also { queueDriftText.text = it }

    private fun renderStageTimings(timings: StageTimings?): String =
        (if (timings == null) {
            "  No samples yet — run an ingestion first."
        } else {
            buildString {
                append("  read:   ").append("%8.1f ms".format(timings.readMs)).append("\n")
                append("  decode: ").append("%8.1f ms".format(timings.decodeMs))
                    .append("  (always 0 — folded into ocr until the stage splits it)\n")
                append("  ocr:    ").append("%8.1f ms".format(timings.ocrMs)).append("\n")
                append("  write:  ").append("%8.1f ms".format(timings.writeMs)).append("\n")
            }
        }).also { stageTimingsText.text = it }

    private fun renderHistory(history: List<IngestionProgressStore.RunRecord>): String =
        (if (history.isEmpty()) {
            "  No finished runs retained yet (last 10, process lifetime)."
        } else {
            buildString {
                history.asReversed().forEach { run ->
                    append("  [").append(run.kind.name).append("] ")
                    append(formatTime(run.startedAtMs)).append(" → ").append(formatTime(run.endedAtMs))
                    append(" (").append(formatDuration(run.endedAtMs - run.startedAtMs)).append(") ")
                    when (val terminal = run.terminal) {
                        is Progress.Completed ->
                            append("Completed ").append(terminal.indexed).append("/").append(terminal.total)
                                .append(" failed=").append(terminal.failed)
                        is Progress.Error ->
                            append("ERROR ").append(terminal.throwable.javaClass.simpleName)
                        Progress.Aborted -> append("Aborted")
                        else -> append(terminal::class.java.simpleName)
                    }
                    append("\n")
                }
            }
        }).also { historyText.text = it }

    private fun renderEvents(events: List<IngestionEventRecorder.Event>): String =
        (if (events.isEmpty()) {
            "  No events recorded."
        } else {
            buildString {
                events.reversed().forEach { event ->
                    append("  ").append(event.toString()).append("\n")
                }
            }
        }).also { eventLogText.text = it }

    private fun renderMlKit(mlKit: IngestionInspectorRunner.MlKitState): String =
        (if (!mlKit.recognizerCreated) {
            "  Recognizer: NOT CREATED yet (created lazily on first stage construction).\n" +
                "  Model: bundled (ADR 0001 — no download panel)."
        } else {
            buildString {
                append("  Recognizer: ALIVE (process singleton)\n")
                append("  Created: ").append(formatTime(mlKit.createdAtMs))
                append(" (").append(formatDuration(System.currentTimeMillis() - mlKit.createdAtMs))
                append(" ago)\n")
                append("  Model: bundled (ADR 0001 — no download panel).")
            }
        }).also { mlkitText.text = it }

    private fun renderHandoff(handoff: IngestionInspectorRunner.ZvecHandoff): String =
        buildString {
            append("  Last open outcome: ").append(handoff.lastOpenOutcome).append("\n")
            append("  LOCK recoveries: ").append(handoff.lockRecoveriesCount).append("\n")
            append("  Schema wipes: ").append(handoff.schemaWipesCount).append("\n")
            append("  Last flush: ").append(formatTime(handoff.lastFlushTimestamp)).append("\n")
            append("  (order is load-bearing: zvec-first → flush → Room → cache)\n")
            append("  Full detail: overflow menu → zvec inspector")
        }.also { zvecHandoffText.text = it }

    // ------------------------------------------------------------------ actions

    private fun confirmReingestAll() {
        val dialog = ConfirmationDialog.build(
            context = this,
            title = "Re-ingest all?",
            positiveButtonText = "Re-ingest",
            positiveButtonListener = { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val reset = ScryerApplication.getScreenshotDao().resetProcessedForReingest()
                    ScryerApplication.getIngestionEventRecorder()
                        .record { "re-ingest-all: reset $reset row(s) to processed=0" }
                    launch(Dispatchers.Main) {
                        toast("$reset row(s) queued for re-ingest")
                        refreshReport()
                    }
                }
            },
            negativeButtonText = "Cancel",
            negativeButtonListener = { _, _ -> },
        )
        dialog.viewHolder.message?.text =
            "Marks every row processed = 0 so the next run re-reads and re-OCRs the whole library " +
                "(zvec content is upserted, not duplicated). Cost: a full pipeline pass."
        dialog.asAlertDialog().show()
    }

    // ------------------------------------------------------------------ formatting

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun formatTime(ts: Long): String =
        if (ts == 0L) "never" else timeFormat.format(Date(ts))

    private fun formatDuration(ms: Long): String = when {
        ms < 0 -> "?"
        ms < 10_000 -> "%.1fs".format(ms / 1000.0)
        ms < 60_000 -> "%.0fs".format(ms / 1000.0)
        else -> "%dm%02ds".format(ms / 60_000, (ms % 60_000) / 1000)
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
