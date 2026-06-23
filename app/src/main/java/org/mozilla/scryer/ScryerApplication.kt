/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.scryer

import android.app.Application

import org.mozilla.scryer.repository.ScreenshotRepository
import org.mozilla.scryer.scan.ContentScanner
import org.mozilla.scryer.scan.ForegroundAndBackgroundCharging
import org.mozilla.scryer.setting.PreferenceSettingsRepository
import org.mozilla.scryer.setting.SettingsRepository
import org.mozilla.scryer.util.launchIO

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
