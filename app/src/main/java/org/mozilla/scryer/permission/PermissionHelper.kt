/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.scryer.permission

import android.app.Activity
import android.content.Context
import android.os.Build
import org.mozilla.scryer.overlay.OverlayPermission

class PermissionHelper {
    companion object {
        fun hasOverlayPermission(context: Context): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || OverlayPermission.hasPermission(context)
        }

        fun requestOverlayPermission(activity: Activity?, requestCode: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return
            }
            activity?.let {
                val intent = OverlayPermission.createPermissionIntent(it)
                it.startActivityForResult(intent, requestCode)
            }
        }
    }
}
