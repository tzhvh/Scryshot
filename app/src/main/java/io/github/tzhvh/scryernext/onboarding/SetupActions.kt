/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.onboarding

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import io.github.tzhvh.scryernext.ScryerApplication
import io.github.tzhvh.scryernext.ScryerService

/**
 * The action a setup row's button performs, carried in `View.tag` as a typed
 * value. Both setup surfaces (wizard, hub) dispatch their buttons on this
 * instead of string tags.
 */
internal enum class SetupRowAction {
    /** The primary grant/enable path. */
    NONE,

    /** The OS has stopped offering the runtime dialog — deep-link Settings. */
    OPEN_SETTINGS
}

/**
 * Permission-critical actions shared by the wizard and the setup hub.
 * Extracted after review flagged byte-identical copies in both activities —
 * these are exactly the paths that must not drift (a grant that persists
 * `floatingEnable` but never mounts the button, on one surface only, is a
 * silent bug).
 */
internal object SetupActions {

    /**
     * An overlay grant mirrors Settings' own toggle path: persist
     * `floatingEnable` through the repository (the Settings switch observes
     * it) and start the service action that actually mounts the button
     * (`ScryerService.initFloatingButton` gates on both).
     */
    fun enableFloatingButton(context: Context) {
        ScryerApplication.getSettingsRepository().floatingEnable = true
        val intent = Intent(context, ScryerService::class.java)
        intent.action = ScryerService.ACTION_ENABLE_CAPTURE_BUTTON
        context.startService(intent)
    }

    /** The app-details page — the only recovery left for a permanent denial. */
    fun openAppDetails(activity: Activity) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.fromParts("package", activity.packageName, null)
        activity.startActivity(intent)
    }
}
