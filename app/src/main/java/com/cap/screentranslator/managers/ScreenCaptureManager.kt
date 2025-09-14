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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlin.math.roundToInt

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
                        // Aplicar el recorte de la barra de estado solo en Android 10-14
                        val processedBitmap = if (shouldCropStatusBar()) {
                            cropStatusBar(bitmap)
                        } else {
                            bitmap
                        }

                        Log.d("ScreenCapture", "Screen captured successfully: ${processedBitmap.width}x${processedBitmap.height}")
                        callback(processedBitmap)
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

    /**
     * Determina si se debe recortar la barra de estado basándose en la versión de Android
     */
    private fun shouldCropStatusBar(): Boolean {
        return Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        // Android 10 (API 29) hasta Android 14 (API 34)
    }

    /**
     * Detecta la altura de la barra de estado y recorta la imagen
     */
    private fun cropStatusBar(originalBitmap: Bitmap): Bitmap {
        return try {
            val statusBarHeight = getStatusBarHeight()

            Log.d("ScreenCapture", "Status bar height detected: ${statusBarHeight}px")

            // Si no se puede detectar la altura o es 0, devolver la imagen original
            if (statusBarHeight <= 0) {
                Log.w("ScreenCapture", "Invalid status bar height, returning original bitmap")
                return originalBitmap
            }

            // Verificar que la altura de recorte no sea mayor que la imagen
            val cropHeight = minOf(statusBarHeight, originalBitmap.height)
            val newHeight = originalBitmap.height - cropHeight

            if (newHeight <= 0) {
                Log.w("ScreenCapture", "Crop would result in empty bitmap, returning original")
                return originalBitmap
            }

            // Crear el bitmap recortado
            val croppedBitmap = Bitmap.createBitmap(
                originalBitmap,
                0, // x
                cropHeight, // y - empezar después de la barra de estado
                originalBitmap.width, // width
                newHeight // height
            )

            Log.d("ScreenCapture", "Bitmap cropped from ${originalBitmap.width}x${originalBitmap.height} to ${croppedBitmap.width}x${croppedBitmap.height}")

            // Liberar el bitmap original si no es el mismo
            if (originalBitmap != croppedBitmap && !originalBitmap.isRecycled) {
                originalBitmap.recycle()
            }

            croppedBitmap
        } catch (e: Exception) {
            Log.e("ScreenCapture", "Error cropping status bar", e)
            originalBitmap
        }
    }

    /**
     * Obtiene la altura de la barra de estado del sistema
     */
    private fun getStatusBarHeight(): Int {
        return try {
            // Método 1: Usar recursos del sistema
            val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId > 0) {
                val heightFromResource = context.resources.getDimensionPixelSize(resourceId)
                Log.d("ScreenCapture", "Status bar height from resource: ${heightFromResource}px")
                return heightFromResource
            }

            // Método 2: Cálculo basado en densidad (fallback)
            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            val density = displayMetrics.density

            // Altura típica de barra de estado: 24dp
            val fallbackHeight = (24 * density).roundToInt()
            Log.d("ScreenCapture", "Status bar height fallback calculation: ${fallbackHeight}px (density: $density)")

            fallbackHeight
        } catch (e: Exception) {
            Log.e("ScreenCapture", "Error getting status bar height", e)
            // Último recurso: valor fijo basado en densidades comunes
            50 // ~24dp en densidad media
        }
    }

    /**
     * Método alternativo para obtener la altura de la barra de estado usando WindowInsets
     * (Para usar en casos específicos donde se tenga acceso a una View)
     */
    fun getStatusBarHeightFromInsets(context: Context): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Método moderno para Android 11+
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val metrics = windowManager.currentWindowMetrics
                val insets = metrics.windowInsets
                insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
            } else {
                // Fallback para versiones anteriores
                getStatusBarHeight()
            }
        } catch (e: Exception) {
            Log.e("ScreenCapture", "Error getting status bar height from insets", e)
            getStatusBarHeight()
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