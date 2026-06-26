/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext

import android.app.Application
import android.util.Log

import io.github.tzhvh.scryernext.ingestion.IngestionLogger
import io.github.tzhvh.scryernext.ingestion.IngestionProgressStore
import io.github.tzhvh.scryernext.repository.ScreenshotRepository
import io.github.tzhvh.scryernext.scan.ContentScanner
import io.github.tzhvh.scryernext.scan.ForegroundAndBackgroundCharging
import io.github.tzhvh.scryernext.setting.PreferenceSettingsRepository
import io.github.tzhvh.scryernext.setting.SettingsRepository
import io.github.tzhvh.scryernext.util.launchIO

class ScryerApplication : Application() {
    companion object {
        private val instance: ScryerApplication by lazy {
            ApplicationHolder.instance
        }

        fun getScreenshotRepository(): ScreenshotRepository {
            return instance.screenshotRepository
        }

        fun getSettingsRepository(): SettingsRepository {
            return instance.settingsRepository
        }

        fun getContentScanner(): ContentScanner {
            return instance.contentScanner
        }

        /** Issue 10.5: app-scope ingestion state + §7.5 re-entrancy guard. */
        fun getIngestionProgressStore(): IngestionProgressStore {
            return instance.ingestionProgressStore
        }

        /** Issue 21: app-wide ContentResolver for decode/size queries against content URIs. */
        fun getContentResolver(): android.content.ContentResolver {
            return instance.contentResolver
        }
    }

    private object ApplicationHolder {
        lateinit var instance: ScryerApplication
    }

    lateinit var screenshotRepository: ScreenshotRepository
    lateinit var settingsRepository: SettingsRepository

    private val contentScanner = ContentScanner()

    /**
     * Issue 10.5: app-scope ingestion progress surface + atomic §7.5 guard.
     * Wired with a tagged-logcat [IngestionLogger] (ADR 0004 §7.6); the store
     * itself is pure Kotlin (no `android.util.Log` dependency) so it remains
     * JVM-testable. Mirrors [contentScanner]'s ownership/access pattern.
     */
    private val ingestionProgressStore = IngestionProgressStore(
        logger = IngestionLogger { msg -> Log.d("IngestionProgressStore", msg) }
    )

    override fun onCreate() {
        super.onCreate()
        ApplicationHolder.instance = this


        screenshotRepository = ScreenshotRepository.createRepository(this) {
            launchIO {
                screenshotRepository.setupDefaultContent(this@ScryerApplication)
            }
        }
        settingsRepository = PreferenceSettingsRepository.getInstance(this)

        contentScanner.onCreate(ForegroundAndBackgroundCharging())
    }
}
