/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.tzhvh.scryernext.databinding.ActivityMainBinding
import io.github.tzhvh.scryernext.ingestion.Progress
import io.github.tzhvh.scryernext.preference.PreferenceWrapper
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
    }

    private lateinit var binding: ActivityMainBinding

    private val prefs: PreferenceWrapper by lazy {
        PreferenceWrapper(this)
    }

    var isFirstTimeLaunched: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        isFirstTimeLaunched = prefs.isFirstTimeLaunch()
        if (isFirstTimeLaunched) {
            prefs.setFirstTimeLaunched()
        }

        window?.let {
            it.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            it.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            it.decorView.systemUiVisibility = it.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            it.statusBarColor = Color.TRANSPARENT
        }



        if (BuildConfig.DEBUG) {
            binding.scanProgressBar.visibility = View.VISIBLE
            // Issue 17: source-swapped from ContentScanner.getProgressState() LiveData onto the
            // app-scope StateFlow<Progress>. Indexing -> bar; anything else -> hidden (the old
            // current==total->GONE fold is subsumed by "not Indexing").
            lifecycleScope.launch {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    ScryerApplication.getIngestionProgressStore().progress.collect { progress ->
                        val indexing = progress as? Progress.Indexing
                        if (indexing != null) {
                            updateDebugProgress(indexing)
                        } else {
                            binding.scanProgressBar.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }



    private fun updateDebugProgress(progress: Progress.Indexing) {
        binding.scanProgressBar.progress = if (progress.current == progress.total) {
            100
        } else {
            (100 * progress.current / progress.total.toFloat()).toInt()
        }
        binding.scanProgressBar.visibility = if (binding.scanProgressBar.progress == 100) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }
}

fun setSupportActionBar(activity: FragmentActivity?, toolbar: Toolbar) {
    (activity as AppCompatActivity).setSupportActionBar(toolbar)
}

fun getSupportActionBar(activity: FragmentActivity?): ActionBar {
    val actionBar = (activity as AppCompatActivity).supportActionBar
    return actionBar ?: throw RuntimeException("no action bar set")
}

