/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.scryer.scan

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class BackgroundScanner(
        context: Context,
        params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        OcrTextHelper.scanAndSave()
        return Result.success()
    }
}
