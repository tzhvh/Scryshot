/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext

import android.annotation.TargetApi
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import io.github.tzhvh.scryernext.capture.RequestCaptureActivity
import io.github.tzhvh.scryernext.capture.ScreenCaptureListener
import io.github.tzhvh.scryernext.capture.ScreenCaptureManager
import io.github.tzhvh.scryernext.filemonitor.FileMonitor
import io.github.tzhvh.scryernext.filemonitor.MediaProviderDelegate
import io.github.tzhvh.scryernext.overlay.CaptureButtonController
import io.github.tzhvh.scryernext.permission.PermissionHelper
import io.github.tzhvh.scryernext.persistence.CollectionModel
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import io.github.tzhvh.scryernext.preference.PreferenceWrapper
import io.github.tzhvh.scryernext.promote.Promoter
import io.github.tzhvh.scryernext.sortingpanel.SortingPanelActivity
import io.github.tzhvh.scryernext.ui.ScryerToast
import io.github.tzhvh.scryernext.util.launchIO
import java.util.concurrent.TimeUnit


class ScryerService : Service(), CaptureButtonController.ClickListener, ScreenCaptureListener {
    companion object {
        // TODO: temp id
        private const val ID_FOREGROUND = 9487
        private const val ID_SCREENSHOT_DETECTED = 9488

        const val ACTION_CAPTURE_SCREEN = "action_capture"

        /** Disable service and no succession dialog will be shown */
        const val ACTION_DISABLE_SERVICE = "action_disable_service"
        /** Disable service while allowing to show a prompt-enable dialog in the future  */
        const val ACTION_DISABLE_SERVICE_SOFTLY = "action_disable_service_softly"

        /** Action indicating user has explicitly enabled the service */
        const val ACTION_ENABLE_SERVICE = "action_enable_service"

        const val ACTION_ENABLE_CAPTURE_BUTTON = "action_enable_capture_button"
        const val ACTION_DISABLE_CAPTURE_BUTTON = "action_disable_capture_button"

        private const val DELAY_CAPTURE_NOTIFICATION = 1000L
        private const val DELAY_CAPTURE_FAB = 0L

        // Broadcast sent from ScryerService
        const val EVENT_TAKE_SCREENSHOT = "io.github.tzhvh.scryernext.take_screenshot"
    }

    private var isRunning: Boolean = false
    private var captureButtonController: CaptureButtonController? = null

    private var screenCapturePermissionIntent: Intent? = null
    private var screenCaptureManager: ScreenCaptureManager? = null
    private lateinit var requestCaptureFilter: IntentFilter
    private lateinit var requestCaptureReceiver: BroadcastReceiver

    private val toast: ScryerToast by lazy {
        ScryerToast(this)
    }

    private val fileMonitor: FileMonitor by lazy {
        //FileMonitor(FileObserverDelegate(Handler(Looper.getMainLooper())))
        FileMonitor(MediaProviderDelegate(this, Handler(Looper.getMainLooper())))
    }

    private val bringTaskToFrontIntent: Intent by lazy {
        Intent(Intent.ACTION_MAIN).apply {
            setClass(this@ScryerService, MainActivity::class.java)
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private val bringMainActivityToFrontIntent: Intent by lazy {
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (null == intent) {
            stopSelf()
            return Service.START_NOT_STICKY
        }

        if (isRunning) {
            return dispatchOnStartCommandAction(intent)

        } else {
            val serviceEnabled = ScryerApplication.getSettingsRepository().serviceEnabled
            if (serviceEnabled) {
                isRunning = true
                val startedByUser = (intent.action == ACTION_ENABLE_SERVICE)
                startScryerService(startedByUser)
                return START_STICKY
            }
        }

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        if (isRunning) {
            isRunning = false
            destroyFloatingButton()
            fileMonitor.stopMonitor()
        }
        super.onDestroy()
    }

    private fun dispatchOnStartCommandAction(intent: Intent): Int {
        when (intent.action) {
            ACTION_DISABLE_SERVICE -> {
                disableScryerService(true)
                return START_NOT_STICKY
            }

            ACTION_DISABLE_SERVICE_SOFTLY -> {
                PreferenceWrapper(this).setShouldPromptEnableService(true)
                disableScryerService(false)
                return START_NOT_STICKY
            }

            ACTION_CAPTURE_SCREEN -> {
                postTakeScreenshot(DELAY_CAPTURE_NOTIFICATION)
            }

            ACTION_ENABLE_CAPTURE_BUTTON -> initFloatingButton()
            ACTION_DISABLE_CAPTURE_BUTTON -> destroyFloatingButton()
        }
        return START_STICKY
    }

    private fun startScryerService(startedExplicitly: Boolean) {
        if (startedExplicitly) {
            toast.show(getString(R.string.snackbar_enable), Toast.LENGTH_SHORT)
        }
        // Issue 22: pass the foreground service type explicitly so startForeground is legal
        // on Android 14+ (API 34+). ServiceCompat handles the version check internally and is
        // a no-op type on older APIs. The manifest declares foregroundServiceType="mediaProjection"
        // to match. Note: the MediaProjection session itself is obtained lazily on the first
        // user capture tap (ScreenCaptureManager.startProjection), well after this call, so the
        // Android-14 "projection must start after foreground" ordering rule is already satisfied.
        val notification = getForegroundNotification()
                ?: throw IllegalStateException("Unable to build foreground notification")
        ServiceCompat.startForeground(
                this@ScryerService,
                getForegroundNotificationId(),
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        initFileMonitors()
        initFloatingButton()
    }

    private fun disableScryerService(showToast: Boolean) {
        if (showToast) {
            toast.show(getString(R.string.snackbar_disable), Toast.LENGTH_SHORT)
        }
        ScryerApplication.getSettingsRepository().serviceEnabled = false
        stopSelf()
    }

    private fun initFloatingButton() {
        val enabled = ScryerApplication.getSettingsRepository().floatingEnable
        if (!enabled || !PermissionHelper.hasOverlayPermission(this)) {
            return
        }

        captureButtonController?: run {
            captureButtonController = CaptureButtonController(applicationContext)
            captureButtonController?.setOnClickListener(this)
            captureButtonController?.init()
        }
    }



    private fun destroyFloatingButton() {
        captureButtonController?.destroy()
        captureButtonController = null
    }

    private fun initFileMonitors() {
        fileMonitor.startMonitor(object : FileMonitor.ChangeListener {
            override fun onChangeFinish(uri: String) {
                postNotification(getScreenshotDetectedNotification())
                val (displayName, size) = resolveScreenshotMetadata(uri)
                val model = ScreenshotModel(
                        uri = uri,
                        displayName = displayName,
                        size = size,
                        lastModified = System.currentTimeMillis(),
                        collectionId = CollectionModel.UNCATEGORIZED)
                launchIO {
                    ScryerApplication.getScreenshotRepository().addScreenshot(listOf(model))
                }
            }
        })
    }

    /**
     * Issue 21: resolve the display name + byte size for a foreign screenshot from its
     * content URI, so they can be cached on the ScreenshotModel at insert time rather than
     * queried per-row during list rendering.
     */
    private fun resolveScreenshotMetadata(uri: String): Pair<String, Long> {
        var displayName = ""
        var size = 0L
        try {
            contentResolver.query(
                    android.net.Uri.parse(uri),
                    arrayOf(
                            android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                            android.provider.MediaStore.Images.Media.SIZE),
                    null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(android.provider.MediaStore.Images.Media.SIZE)
                    if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: ""
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
        } catch (e: SecurityException) {
            // Foreign image — may not be readable without READ_MEDIA_IMAGES; degrade gracefully.
            displayName = ""
        }
        return displayName to size
    }

    override fun onScreenshotButtonClicked() {
        postTakeScreenshot(DELAY_CAPTURE_FAB)
    }

    override fun onScreenshotButtonDismissed() {
        destroyFloatingButton()
        ScryerApplication.getSettingsRepository().floatingEnable = false
    }

    private fun postTakeScreenshot(delayed: Long) {
        handler.postDelayed({
            captureButtonController?.hide()
            takeScreenshot()
        }, delayed)
    }

    private fun takeScreenshot() {
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(EVENT_TAKE_SCREENSHOT))

        if (screenCapturePermissionIntent != null) {
            screenCaptureManager?.captureScreen()
        } else {
            requestCaptureFilter = IntentFilter(RequestCaptureActivity.getResultBroadcastAction(applicationContext))
            requestCaptureReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(applicationContext).unregisterReceiver(requestCaptureReceiver)

                    val resultCode = intent.getIntExtra(RequestCaptureActivity.RESULT_EXTRA_CODE, Activity.RESULT_CANCELED)
                    if (resultCode != Activity.RESULT_OK) {
                        onScreenShotTaken("")
                        return
                    }

                    screenCapturePermissionIntent = intent.getParcelableExtra(RequestCaptureActivity.RESULT_EXTRA_DATA)
                    screenCapturePermissionIntent?.let {
                        screenCaptureManager = ScreenCaptureManager(applicationContext, it, this@ScryerService)

                        if (intent.getBooleanExtra(RequestCaptureActivity.RESULT_EXTRA_PROMPT_SHOWN, true)) {
                            // Delay capture until after the permission dialog is gone.
                            handler.postDelayed({ screenCaptureManager?.captureScreen() }, 500)
                        } else {
                            screenCaptureManager?.captureScreen()
                        }
                    }
                }
            }

            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(applicationContext).registerReceiver(requestCaptureReceiver, requestCaptureFilter)
            val intent = Intent(applicationContext, RequestCaptureActivity::class.java)
            intent.flags = intent.flags or Intent.FLAG_ACTIVITY_NEW_TASK
            applicationContext.startActivity(intent)
        }
    }

    override fun onScreenShotTaken(uri: String) {
        captureButtonController?.show()
        if (!TextUtils.isEmpty(uri)) {
            startSortingPanelActivity(uri)
            // MediaScannerConnection.scanFile was removed in issue 20: the screenshot is
            // now inserted straight into MediaStore (ScreenCaptureManager.insertScreenshot),
            // which already indexes the row, so a separate scanFile call is a no-op.
            Promoter.onScreenshotTaken(this)
        }
    }

    private fun startSortingPanelActivity(uri: String) {
        val intent = SortingPanelActivity.sortNewScreenshot(this, uri, ScryerApplication.getSettingsRepository().addToCollectionEnable)
        intent.flags = intent.flags or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    private fun getForegroundNotificationId(): Int {
        return ID_FOREGROUND
    }

    private fun getForegroundNotification(): Notification? {
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createForegroundChannel()
        } else {
            ""
        }

        val tapIntent = Intent(ACTION_CAPTURE_SCREEN)
        tapIntent.setClass(this, ScryerService::class.java)
        val tapPendingIntent = PendingIntent.getService(this, 0, tapIntent, 0)

        val openAppPendingIntent = PendingIntent.getActivity(this, 0,
                bringTaskToFrontIntent, 0)
        val openAppAction = NotificationCompat.Action(0, getString(R.string.notification_action_open),
                openAppPendingIntent)

        val stopIntent = Intent(ACTION_DISABLE_SERVICE_SOFTLY)
        stopIntent.setClass(this, ScryerService::class.java)
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, 0)
        val stopAction = NotificationCompat.Action(0, getString(R.string.notification_action_stop),
                stopPendingIntent)

        val style = NotificationCompat.BigTextStyle()
        style.bigText(getString(R.string.notification_action_capture))
        return NotificationCompat.Builder(this, channelId)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setColor(ContextCompat.getColor(this, R.color.foreground_notification))
                .setContentTitle(getString(R.string.app_full_name))
                .setContentText(getString(R.string.notification_action_capture))
                .setContentIntent(tapPendingIntent)
                .setStyle(style)
                .addAction(openAppAction)
                .addAction(stopAction)
                .build()
    }

    private fun getScreenshotDetectedNotification(): Notification {
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createMessageChannel()
        } else {
            ""
        }

        val intent = SortingPanelActivity.sortCollection(this, CollectionModel.UNCATEGORIZED)
        intent.flags = intent.flags or Intent.FLAG_ACTIVITY_NEW_TASK
        val tapPendingIntent = PendingIntent.getActivity(this, 0, intent, 0)

        return NotificationCompat.Builder(this, channelId)
                .setCategory(Notification.CATEGORY_PROMO)
                .setSmallIcon(R.drawable.ic_stat_notify_sort)
                .setColor(ContextCompat.getColor(this, R.color.foreground_notification))
                .setContentTitle(getString(R.string.notification_action_collect_title))
                .setContentText(getString(R.string.notification_action_collect))
                .setContentIntent(tapPendingIntent)
                .setAutoCancel(true)
                .build()
    }

    private fun postNotification(notification: Notification) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(ID_SCREENSHOT_DETECTED, notification)
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun createForegroundChannel(): String {
        val channelId = "foreground_channel"
        val channelName = "ScreenshotPlus Service"
        val channel = NotificationChannel(channelId, channelName,
                NotificationManager.IMPORTANCE_NONE)

        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
        return channelId
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun createMessageChannel(): String {
        val channelId = "message_channel"
        val channelName = "ScreenshotPlus Message"
        val channel = NotificationChannel(channelId, channelName,
                NotificationManager.IMPORTANCE_HIGH)

        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
        return channelId
    }
}
