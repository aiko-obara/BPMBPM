package com.example.gemmabuddy

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log

class ScreenCaptureManager(
    private val mediaProjection: MediaProjection,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val screenDensity: Int
) {
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    fun start() {
        // Android 14+: createVirtualDisplay()前にコールバック登録が必須
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stop()
            }
        }, Handler(Looper.getMainLooper()))

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "GemmaBuddyCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
        Log.i(TAG, "スクリーンキャプチャ開始: ${screenWidth}x${screenHeight}")
    }

    fun captureScreen(): Bitmap? {
        val reader = imageReader ?: return null
        var image: Image? = null
        return try {
            image = reader.acquireLatestImage() ?: return null
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
        } catch (e: Exception) {
            Log.e(TAG, "キャプチャ失敗", e)
            null
        } finally {
            image?.close()
        }
    }

    fun stop() {
        virtualDisplay?.release()
        imageReader?.close()
        virtualDisplay = null
        imageReader = null
        Log.i(TAG, "スクリーンキャプチャ停止")
    }

    companion object {
        private const val TAG = "ScreenCaptureManager"
    }
}
