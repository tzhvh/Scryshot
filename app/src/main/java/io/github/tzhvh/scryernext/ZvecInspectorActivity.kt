/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import io.github.tzhvh.scryernext.repository.processFtsQuery
import io.github.tzhvh.scryernext.zvec.ZvecValue
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only zvec Runtime Inspector screen (ZVEC_INSPECTOR.md).
 * Surfaces the state, drift, query results, and log event history of the production zvec store.
 */
class ZvecInspectorActivity : AppCompatActivity() {

    private lateinit var stateText: TextView
    private lateinit var driftText: TextView
    private lateinit var queryInput: EditText
    private lateinit var searchButton: Button
    private lateinit var queryResultsText: TextView
    private lateinit var eventLogText: TextView
    private lateinit var refreshButton: Button
    private lateinit var clearLogsButton: Button

    private lateinit var runner: ZvecInspectorRunner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_zvec_inspector)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        stateText = findViewById(R.id.state_snapshot_text)
        driftText = findViewById(R.id.drift_meter_text)
        queryInput = findViewById(R.id.query_input)
        searchButton = findViewById(R.id.query_search_button)
        queryResultsText = findViewById(R.id.query_results_text)
        eventLogText = findViewById(R.id.event_log_text)
        refreshButton = findViewById(R.id.inspector_refresh_button)
        clearLogsButton = findViewById(R.id.inspector_clear_logs_button)

        val store = ScryerApplication.getZvecContentStore()
        val dao = ScryerApplication.getScreenshotDao()
        // O3: pass the production query shaper so the inspector matches what users see.
        runner = ZvecInspectorRunner(store, dao, queryPreShaper = ::processFtsQuery)

        refreshButton.setOnClickListener { refreshReport() }
        clearLogsButton.setOnClickListener {
            ZvecEventRecorder.clear()
            ZvecEventRecorder.record { "Logs cleared by user" }
            refreshReport()
        }

        searchButton.setOnClickListener { runSearchQuery() }

        // Initial load
        refreshReport()
    }

    private fun refreshReport() {
        lifecycleScope.launch {
            val report = runner.getReport()
            renderReport(report)
        }
    }

    private fun renderReport(report: ZvecInspectorRunner.Report) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        fun formatTime(ts: Long) = if (ts == 0L) "never" else dateFormat.format(Date(ts))

        // State Snapshot layout
        val stateBuilder = StringBuilder().apply {
            append("  Open: ").append(if (report.state.isOpen) "YES (Handle live)" else "NO (Handle closed)").append("\n")
            // When stats() failed, surface the failure explicitly rather than showing a misleading
            // "Doc Count: 0" that would read as "empty corpus." See ZvecContentStore.lastStatsError.
            if (report.state.statsError != null) {
                append("  Doc Count: ⚠️ stats read FAILED\n")
                append("    reason: ").append(report.state.statsError).append("\n")
                append("    (see Event Log — drift verdict below is UNRELIABLE)\n")
            } else {
                append("  Doc Count: ").append(report.state.docCount).append("\n")
            }
            append("  Last Open Outcome: ").append(report.state.lastOpenOutcome).append("\n")
            append("  LOCK Recoveries: ").append(report.state.lockRecoveriesCount).append("\n")
            append("  Last Flush: ").append(formatTime(report.state.lastFlushTimestamp)).append("\n")
            append("  Last Close: ").append(formatTime(report.state.lastCloseTimestamp)).append("\n")
            if (report.state.indexCompleteness.isNotEmpty()) {
                append("  Indexes:\n")
                report.state.indexCompleteness.forEach { (name, completeness) ->
                    append("    - ").append(name).append(": ").append("%.1f%%".format(completeness * 100)).append("\n")
                }
            } else {
                append("  Indexes: none (Phase 2 FTS only)\n")
            }
        }
        stateText.text = stateBuilder.toString()

        // Drift Meter layout
        val driftBuilder = StringBuilder().apply {
            append("  zvec docCount: ").append(report.drift.zvecDocCount).append("\n")
            append("  Room content_hash not null: ").append(report.drift.roomIndexedCount).append("\n")
            // D4: the distinct-hash count is the one that maps 1:1 to zvec docs.
            append("  Room DISTINCT content_hash: ").append(report.drift.roomDistinctIndexedCount).append("\n")
            append("  Room processed = 1: ").append(report.drift.roomProcessedCount).append("\n")
            append("  Room processed = 0 (Backlog): ").append(report.drift.roomUnprocessedCount).append("\n")
            append("  ────────────────────────────\n")
            append("  Orphans (zvec > Room distinct): ").append(report.drift.orphansCount).append("\n")
            append("  Holes (Room distinct > zvec): ").append(report.drift.holesCount).append("\n")
            if (report.drift.unbridgedProcessedCount > 0) {
                // D4: processed-but-no-hash — the legacy dedup-skip-without-hash gap (D2 fixes new
                // rows; this surfaces legacy ones). NOT a hole; a bridging gap. Benign under duplicates.
                append("  Unbridged (processed, no hash): ").append(report.drift.unbridgedProcessedCount).append("\n")
            }
            append("\n")
            append("  Phase 2 Invariant check:\n")
            when {
                // B2: a failed stats read makes the counts meaningless — never render a verdict.
                !report.drift.statsReadable -> {
                    append("    ⚠️ UNAVAILABLE: stats() read failed; cannot verify consistency.\n")
                    append("       See Event Log for the failure reason.\n")
                }
                report.drift.orphansCount == 0L && report.drift.holesCount == 0L -> {
                    append("    ✅ PASS: Stores are consistent.")
                    if (report.drift.unbridgedProcessedCount > 0) {
                        append(" (${report.drift.unbridgedProcessedCount} unbridged row(s) — see above)")
                    }
                }
                report.drift.orphansCount > 0L && report.drift.holesCount > 0L -> {
                    append("    ⚠️ BOTH: orphans (no auto-delete) AND holes (self-heal next run).\n")
                }
                // O2: orphans do NOT self-heal (no PK-enumeration primitive). Only holes do.
                report.drift.orphansCount > 0L -> {
                    append("    ⚠️ ORPHANS: ${report.drift.orphansCount} zvec doc(s) with no Room row.\n")
                    append("       Does NOT self-heal — no PK enumeration. Investigate manually.\n")
                    append("       (Killed benchmark run? Synth docs overwrite on next run.)\n")
                }
                else -> {
                    append("    ⚠️ HOLES: ${report.drift.holesCount} Room row(s) with no zvec doc.\n")
                    append("       Self-heals on next ingest (zvec-first write ordering re-indexes).\n")
                }
            }
        }
        driftText.text = driftBuilder.toString()

        // Events Log layout
        if (report.events.isEmpty()) {
            eventLogText.text = "  No events recorded."
        } else {
            val eventsBuilder = StringBuilder()
            report.events.reversed().forEach { event ->
                eventsBuilder.append("  ").append(event.toString()).append("\n")
            }
            eventLogText.text = eventsBuilder.toString()
        }
    }

    private fun runSearchQuery() {
        val qText = queryInput.text.toString()
        if (qText.trim().isEmpty()) {
            queryResultsText.text = "Enter a search term."
            return
        }

        searchButton.isEnabled = false
        queryResultsText.text = "Searching..."

        lifecycleScope.launch {
            try {
                val results = runner.inspectQuery(qText)
                if (results.isEmpty()) {
                    queryResultsText.text = "No raw matches in zvec."
                } else {
                    val sb = StringBuilder()
                    sb.append("Found ").append(results.size).append(" raw docs:\n\n")
                    results.forEachIndexed { i, doc ->
                        val contentVal = (doc.fields["content"] as? ZvecValue.Str)?.value ?: ""
                        val locatorVal = (doc.fields["locator"] as? ZvecValue.Str)?.value ?: ""
                        val snippet = if (contentVal.length > 80) contentVal.take(80) + "..." else contentVal
                        sb.append("  [%d] PK (hash): %s\n".format(i + 1, doc.pk.take(8) + "..."))
                        sb.append("      Locator: %s\n".format(locatorVal))
                        sb.append("      Score: %s\n".format(doc.score?.toString() ?: "N/A"))
                        sb.append("      Content: \"%s\"\n\n".format(snippet))
                    }
                    queryResultsText.text = sb.toString()
                }
            } catch (e: Exception) {
                queryResultsText.text = "Error: ${e.message}"
            } finally {
                searchButton.isEnabled = true
            }
        }
    }
}
