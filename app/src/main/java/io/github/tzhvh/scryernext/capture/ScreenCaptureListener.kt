package io.github.tzhvh.scryernext.capture

interface ScreenCaptureListener {
    /**
     * Called when a screenshot capture completes. [uri] is the `content://` MediaStore URI
     * of the saved image (or an empty string if the capture failed before write).
     */
    fun onScreenShotTaken(uri: String)
}
