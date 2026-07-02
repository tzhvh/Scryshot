/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Debug-only zvec FTS benchmark screen. Launches from the `action_zvec_benchmark` menu item
 * (`HomeFragment.onCreateOptionsMenu`, `BuildConfig.DEBUG`-gated — same pattern as `SvgViewerActivity`).
 *
 * Runs [ZvecBenchmarkRunner] against the production [io.github.tzhvh.scryernext.ingestion.ZvecContentStore]
 * (the real corpus at `filesDir/zvec/screenshots`) and streams the report live into the TextView as
 * each stage completes. The runner's math is JVM-unit-tested; this Activity is the thin on-device
 * shell that wires the runner to the UI thread.
 *
 * Measures FTS only — Phase 2's schema has no vector field. See the runner's class KDoc + the
 * on-screen footer for the honest framing.
 */
class ZvecBenchmarkActivity : AppCompatActivity() {

    private lateinit var reportView: TextView
    private lateinit var progressView: ProgressBar
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_zvec_benchmark)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        reportView = findViewById(R.id.benchmark_report)
        progressView = findViewById(R.id.benchmark_progress)
        runButton = findViewById(R.id.benchmark_run_button)

        runButton.setOnClickListener { runBenchmark() }

        // Auto-run once on open so the screen isn't blank until the user taps.
        runBenchmark()
    }

    private fun runBenchmark() {
        val store = ScryerApplication.getZvecContentStore()
        val runner = ZvecBenchmarkRunner(store)

        runButton.isEnabled = false
        progressView.visibility = View.VISIBLE
        reportView.text = "Measuring…"

        // lifecycleScope.launch keeps the run scoped to this Activity; cancellation on exit is free.
        // The runner self-relocates its native calls to Dispatchers.IO, so this collector runs on
        // Default; each onStage callback hops back to main via Dispatchers.Main (lifecycleScope's
        // default) to touch the TextView.
        lifecycleScope.launch {
            runner.run { partial -> runOnUiThread { reportView.text = partial } }
            runButton.isEnabled = true
            progressView.visibility = View.GONE
        }
    }
}
