/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.scryer.permission

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.mozilla.scryer.overlay.OverlayPermission

class PermissionHelper {
    companion object {
        fun hasOverlayPermission(context: Context): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || OverlayPermission.hasPermission(context)
        }

        /**
         * Returns an Intent to open the system overlay-permission settings page, or null
         * pre-M where overlay is always granted. Callers launch this via
         * [ActivityResultContracts.StartActivityForResult] and re-evaluate the permission
         * on return.
         */
        fun getOverlayPermissionIntent(context: Context): Intent? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return null
            }
            return OverlayPermission.createPermissionIntent(context)
        }

        /** Issue 23: POST_NOTIFICATIONS gate (Android 13+). Below API 33 it doesn't exist. */
        fun hasPostNotificationsPermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return true
            }
            return ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }

        /**
         * READ_MEDIA_IMAGES gate (Android 13+) / READ_EXTERNAL_STORAGE (API 29–32).
         * With minSdk 29, never falls below. Returns true when the runtime permission
         * needed to query foreign MediaStore rows is already granted.
         */
        fun hasReadMediaPermission(context: Context): Boolean {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                android.Manifest.permission.READ_MEDIA_IMAGES
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }
            return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

        /**
         * The manifest-declared permission string to request at runtime, based on API level.
         */
        fun getReadMediaPermissionString(): String {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                android.Manifest.permission.READ_MEDIA_IMAGES
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }
        }
    }
}
