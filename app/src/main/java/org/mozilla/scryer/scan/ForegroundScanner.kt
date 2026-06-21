/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.scryer.scan

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.google.mlkit.common.MlKitException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.mozilla.scryer.ScryerApplication
import org.mozilla.scryer.persistence.ScreenshotModel
import kotlin.coroutines.CoroutineContext

class ForegroundScanner : CoroutineScope {

    private val screenshotLiveData =
            ScryerApplication.getScreenshotRepository().getScreenshots()
    private val screenshotObserver = Observer<List<ScreenshotModel>> {
        scheduleForegroundScan()
    }

    private val progressState = MutableLiveData<ContentScanner.ProgressState>().apply {
        value = ContentScanner.ProgressState.Progress(0, 0)
    }

    private val parentJob = Job()
    private var scanJob: Job? = null
    private var isScanning = false
    // Conflated channel preserves the "drop intermediate triggers, run at most
    // one outstanding scan" semantics of the old `actor` builder (removed in
    // coroutines 1.0). A launch consumer drains it; closing/cancelling stops both.
    private var scanChannel: Channel<Unit>? = null

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + parentJob

    fun onCreate() {}

    fun onStart() {
        prepareScan()
        screenshotLiveData.observeForever(screenshotObserver)
    }

    fun onStop() {
        screenshotLiveData.removeObserver(screenshotObserver)
        cancelScan()
    }

    fun onDestroy() {
        parentJob.cancel()
    }

    fun getProgressState(): LiveData<ContentScanner.ProgressState> {
        return progressState
    }

    fun isScanning(): Boolean {
        return isScanning
    }

    private fun prepareScan() {
        val channel = Channel<Unit>(Channel.CONFLATED)
        scanChannel = channel
        scanJob = launch(Dispatchers.Main) {
            for (msg in channel) {
                scan()
            }
        }
    }

    private suspend fun scan() {
        try {
            isScanning = true
            updateProgressData(current = 0, total = 0)
            OcrTextHelper.scanAndSave { current, total ->
                updateProgressData(current = current, total = total)
            }
            isScanning = false
        } catch (e: MlKitException) {
            updateProgressData(isUnavailable = true)
        }
    }


    private fun updateProgressData(
            isUnavailable: Boolean = false,
            current: Int = 0,
            total: Int = 0
    ) = launch(Dispatchers.Main) {
        val state = when {
            isUnavailable -> ContentScanner.ProgressState.Unavailable
            isScanning -> ContentScanner.ProgressState.Progress(current, total)
            else -> ContentScanner.ProgressState.Progress(current, total)
        }
        progressState.value = state
    }

    private fun cancelScan() {
        isScanning = false
        scanChannel?.close()
        scanJob?.cancel()
    }

    private fun scheduleForegroundScan() {
        scanChannel?.trySend(Unit)
    }
}
