package com.cap.screentranslator.managers

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

class ScreenCaptureManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val handler = Handler(Looper.getMainLooper())

    fun setupMediaProjection(resultCode: Int, data: Intent) {
        try {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.d("ScreenCapture", "MediaProjection stopped")
                    cleanup()
                }
            }, handler)

            createImageReader()
            createVirtualDisplay()

            Log.d("ScreenCapture", "MediaProjection setup complete")

        } catch (e: Exception) {
            Log.e("ScreenCapture", "Error setting up MediaProjection", e)
        }
    }

    private fun createImageReader() {
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)

        imageReader = ImageReader.newInstance(
            displayMetrics.widthPixels,
            displayMetrics.heightPixels,
            PixelFormat.RGBA_8888,
            2
        )

        Log.d("ScreenCapture", "ImageReader created: ${displayMetrics.widthPixels}x${displayMetrics.heightPixels}")
    }

    private fun createVirtualDisplay() {
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenTranslatorCapture",
            displayMetrics.widthPixels,
            displayMetrics.heightPixels,
            displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            object : VirtualDisplay.Callback() {
                override fun onPaused() {
                    super.onPaused()
                    Log.d("ScreenCapture", "VirtualDisplay paused")
                }

                override fun onResumed() {
                    super.onResumed()
                    Log.d("ScreenCapture", "VirtualDisplay resumed")
                }

                override fun onStopped() {
                    super.onStopped()
                    Log.d("ScreenCapture", "VirtualDisplay stopped")
                }
            },
            handler
        )

        Log.d("ScreenCapture", "VirtualDisplay created")
    }

    fun captureScreen(callback: (Bitmap?) -> Unit) {
        try {
            imageReader?.let { reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val bitmap = imageToBitmap(image)
                    image.close()

                    if (bitmap != null) {
                        Log.d("ScreenCapture", "Screen captured successfully: ${bitmap.width}x${bitmap.height}")
                        callback(bitmap)
                    } else {
                        Log.e("ScreenCapture", "Failed to convert image to bitmap")
                        callback(null)
                    }
                } else {
                    Log.e("ScreenCapture", "No image available")
                    callback(null)
                }
            } ?: run {
                Log.e("ScreenCapture", "ImageReader is null")
                callback(null)
            }
        } catch (e: Exception) {
            Log.e("ScreenCapture", "Error capturing screen", e)
            callback(null)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Create a properly sized bitmap without padding
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        } catch (e: Exception) {
            Log.e("ScreenCapture", "Error converting image to bitmap", e)
            null
        }
    }

    fun isReady(): Boolean {
        return mediaProjection != null && imageReader != null && virtualDisplay != null
    }

    fun cleanup() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null

            imageReader?.close()
            imageReader = null

            mediaProjection?.stop()
            mediaProjection = null

            Log.d("ScreenCapture", "Cleanup completed")
        } catch (e: Exception) {
            Log.e("ScreenCapture", "Error during cleanup", e)
        }
    }
}