package org.mozilla.scryer.capture

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Context.WINDOW_SERVICE
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager

class ScreenCaptureManager(private val context: Context, private val screenCapturePermissionIntent: Intent, private val screenCaptureListener: ScreenCaptureListener) {
    companion object {
        const val SCREENSHOT_DIR = "ScreenshotGo"

        /** MediaStore subfolder the app writes captures into. */
        private val RELATIVE_PATH = "${Environment.DIRECTORY_PICTURES}/$SCREENSHOT_DIR"
    }

    private val projectionManager: MediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private val workerHandler: Handler
    private val uiHandler: Handler
    private var defaultDisplay: Display
    private var virtualDisplay: VirtualDisplay? = null
    private val metrics: DisplayMetrics = DisplayMetrics()
    private val density: Int
    private var width = 0
    private var height = 0

    init {
        val windowManager = context.getSystemService(WINDOW_SERVICE) as WindowManager
        defaultDisplay = windowManager.defaultDisplay
        defaultDisplay.getMetrics(metrics)
        density = metrics.densityDpi

        val handlerThread = HandlerThread("ScreenCaptureThread")
        handlerThread.start()
        val looper = handlerThread.looper
        workerHandler = Handler(looper)

        uiHandler = Handler()
    }

    fun captureScreen() {
        startProjection()
    }

    private fun startProjection() {
        uiHandler.post {
            try {
                mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, screenCapturePermissionIntent)
            } catch (exception: IllegalStateException) {
                // There is no hint from MediaProjectionManager to know if there is already a
                // MediaProjection instance running. So, just catch the exception and skip the capture.
                return@post
            }
            createVirtualDisplay()
            // register media projection stop callback
            mediaProjection?.registerCallback(MediaProjectionStopCallback(), workerHandler)
        }
    }

    private fun stopProjection() {
        workerHandler.post {
            mediaProjection?.stop()
        }
    }

    private fun createVirtualDisplay() {
        val size = Point()
        defaultDisplay.getRealSize(size)
        width = size.x
        height = size.y

        // start capture reader
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay("screen-capture",
                width, height, density, DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION, imageReader?.surface, null, workerHandler)
        imageReader?.setOnImageAvailableListener(ImageAvailableListener(), workerHandler)
    }

    private inner class ImageAvailableListener : ImageReader.OnImageAvailableListener {
        override fun onImageAvailable(reader: ImageReader) {
            imageReader?.setOnImageAvailableListener(null, null)

            val displayName = "Screenshot_" + System.currentTimeMillis() + ".jpg"
            var bitmap: Bitmap? = null
            var croppedBitmap: Bitmap? = null
            var capturedUri: Uri? = null

            try {
                val image = reader.acquireLatestImage() ?: return
                image.use {
                    val planes = it.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    // create bitmap
                    bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                    bitmap?.copyPixelsFromBuffer(buffer)

                    // trim the screenshot to the correct size.
                    croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                }

                // Write the cropped bitmap to MediaStore under Pictures/ScreenshotGo.
                // minSdk 29, so RELATIVE_PATH + IS_PENDING are always available — no
                // legacy File-branch fallback.
                capturedUri = insertScreenshot(croppedBitmap, displayName)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                croppedBitmap?.recycle()
                bitmap?.recycle()

                stopProjection()
            }

            // Empty string signals a failed capture (the capture-listener contract).
            uiHandler.post { screenCaptureListener.onScreenShotTaken(capturedUri?.toString() ?: "") }
        }

        /**
         * Insert the captured [bitmap] into MediaStore.Images under Pictures/ScreenshotGo
         * and return the content URI, which becomes the screenshot's stable identity.
         * The [IS_PENDING] flag is set while writing and cleared once the bytes land so
         * other apps can see the image.
         */
        private fun insertScreenshot(bitmap: Bitmap?, displayName: String): Uri? {
            bitmap ?: return null
            val resolver = context.contentResolver

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null

            try {
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Best-effort cleanup so we don't leave a dangling IS_PENDING row.
                resolver.delete(uri, null, null)
                return null
            }

            val updateValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, updateValues, null, null)
            return uri
        }
    }

    private inner class MediaProjectionStopCallback : MediaProjection.Callback() {
        override fun onStop() {
            workerHandler.post {
                virtualDisplay?.release()
                imageReader?.setOnImageAvailableListener(null, null)
                mediaProjection?.unregisterCallback(this@MediaProjectionStopCallback)
            }
        }
    }
}
