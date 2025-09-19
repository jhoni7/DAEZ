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

    // Estado para manejo de errores
    private var isSetupComplete = false
    private var captureInProgress = false

    fun setupMediaProjection(resultCode: Int, data: Intent) {
        try {
            Log.d("ScreenCapture", "Starting MediaProjection setup")

            // Limpiar cualquier configuración previa
            cleanup()

            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.d("ScreenCapture", "MediaProjection stopped")
                    isSetupComplete = false
                    cleanup()
                }

                override fun onCapturedContentResize(width: Int, height: Int) {
                    super.onCapturedContentResize(width, height)
                    Log.d("ScreenCapture", "Content resized: ${width}x${height}")
                }

                override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                    super.onCapturedContentVisibilityChanged(isVisible)
                    Log.d("ScreenCapture", "Content visibility changed: $isVisible")
                }
            }, handler)

            createImageReader()
            createVirtualDisplay()

            isSetupComplete = true
            Log.d("ScreenCapture", "MediaProjection setup complete")

        } catch (e: SecurityException) {
            Log.e("ScreenCapture", "Security error setting up MediaProjection", e)
            isSetupComplete = false
        } catch (e: Exception) {
            Log.e("ScreenCapture", "Error setting up MediaProjection", e)
            isSetupComplete = false
        }
    }

    private fun createImageReader() {
        try {
            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(displayMetrics)

            // Cerrar ImageReader anterior si existe
            imageReader?.close()

            imageReader = ImageReader.newInstance(
                displayMetrics.widthPixels,
                displayMetrics.heightPixels,
                PixelFormat.RGBA_8888,
                2 // Mantener 2 imágenes en buffer
            )

            // Configurar listener para manejo de imágenes disponibles
            imageReader?.setOnImageAvailableListener({ reader ->
                // Solo procesar si hay una captura en progreso
                if (captureInProgress) {
                    Log.d("ScreenCapture", "Image available from ImageReader")
                }
            }, handler)

            Log.d("ScreenCapture", "ImageReader created: ${displayMetrics.widthPixels}x${displayMetrics.heightPixels}")

        } catch (e: Exception) {
            Log.e("ScreenCapture", "Error creating ImageReader", e)
            throw e
        }
    }

    private fun createVirtualDisplay() {
        try {
            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(displayMetrics)

            // Liberar VirtualDisplay anterior si existe
            virtualDisplay?.release()

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
                        isSetupComplete = false
                    }
                },
                handler
            )

            Log.d("ScreenCapture", "VirtualDisplay created")

        } catch (e: Exception) {
            Log.e("ScreenCapture", "Error creating VirtualDisplay", e)
            throw e
        }
    }

    fun captureScreen(callback: (Bitmap?) -> Unit) {
        if (!isReady()) {
            Log.e("ScreenCapture", "ScreenCapture not ready")
            callback(null)
            return
        }

        if (captureInProgress) {
            Log.w("ScreenCapture", "Capture already in progress, ignoring request")
            callback(null)
            return
        }

        captureInProgress = true

        try {
            Log.d("ScreenCapture", "Starting screen capture")

            // Pequeña pausa para asegurar que el buffer esté listo
            handler.postDelayed({
                performActualCapture(callback)
            }, 100)

        } catch (e: Exception) {
            Log.e("ScreenCapture", "Error initiating screen capture", e)
            captureInProgress = false
            callback(null)
        }
    }

    private fun performActualCapture(callback: (Bitmap?) -> Unit) {
        try {
            imageReader?.let { reader ->
                // Intentar obtener la imagen más reciente
                var image: Image? = null
                var attempts = 0
                val maxAttempts = 3

                while (image == null && attempts < maxAttempts) {
                    try {
                        image = reader.acquireLatestImage()
                        if (image == null) {
                            Log.d("ScreenCapture", "No image available, attempt ${attempts + 1}")
                            Thread.sleep(50) // Breve pausa antes de reintentar
                            attempts++
                        }
                    } catch (e: Exception) {
                        Log.w("ScreenCapture", "Error acquiring image on attempt ${attempts + 1}", e)
                        attempts++
                        Thread.sleep(50)
                    }
                }

                if (image != null) {
                    try {
                        val bitmap = imageToBitmap(image)

                        if (bitmap != null && !bitmap.isRecycled) {
                            // Aplicar el recorte de la barra de estado solo en Android 10-14
                            val processedBitmap = if (shouldCropStatusBar()) {
                                cropStatusBar(bitmap)
                            } else {
                                bitmap
                            }

                            Log.d("ScreenCapture", "Screen captured successfully: ${processedBitmap.width}x${processedBitmap.height}")
                            captureInProgress = false
                            callback(processedBitmap)
                        } else {
                            Log.e("ScreenCapture", "Failed to convert image to bitmap or bitmap is recycled")
                            captureInProgress = false
                            callback(null)
                        }
                    } finally {
                        // Siempre cerrar la imagen para liberar memoria
                        try {
                            image.close()
                        } catch (e: Exception) {
                            Log.w("ScreenCapture", "Error closing image", e)
                        }
                    }
                } else {
                    Log.e("ScreenCapture", "No image available after $maxAttempts attempts")
                    captureInProgress = false
                    callback(null)
                }
            } ?: run {
                Log.e("ScreenCapture", "ImageReader is null")
                captureInProgress = false
                callback(null)
            }
        } catch (e: Exception) {
            Log.e("ScreenCapture", "Error performing actual capture", e)
            captureInProgress = false
            callback(null)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            if (planes.isEmpty()) {
                Log.e("ScreenCapture", "Image has no planes")
                return null
            }

            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            Log.d("ScreenCapture", "Image properties - Width: ${image.width}, Height: ${image.height}, PixelStride: $pixelStride, RowStride: $rowStride, RowPadding: $rowPadding")

            if (rowPadding < 0) {
                Log.e("ScreenCapture", "Invalid row padding: $rowPadding")
                return null
            }

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )

            // Verificar que el buffer tenga suficientes datos
            val expectedBytes = bitmap.byteCount
            val availableBytes = buffer.remaining()

            if (availableBytes < expectedBytes) {
                Log.e("ScreenCapture", "Buffer too small: expected $expectedBytes, available $availableBytes")
                bitmap.recycle()
                return null
            }

            bitmap.copyPixelsFromBuffer(buffer)

            // Crear bitmap del tamaño correcto sin padding
            val finalBitmap = if (rowPadding > 0) {
                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                bitmap.recycle() // Liberar bitmap temporal
                croppedBitmap
            } else {
                bitmap
            }

            Log.d("ScreenCapture", "Bitmap created successfully: ${finalBitmap.width}x${finalBitmap.height}")
            finalBitmap

        } catch (e: OutOfMemoryError) {
            Log.e("ScreenCapture", "Out of memory converting image to bitmap", e)
            null
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
            if (originalBitmap.isRecycled) {
                Log.e("ScreenCapture", "Cannot crop recycled bitmap")
                return originalBitmap
            }

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
        } catch (e: OutOfMemoryError) {
            Log.e("ScreenCapture", "Out of memory cropping status bar", e)
            originalBitmap
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
        val ready = isSetupComplete &&
                mediaProjection != null &&
                imageReader != null &&
                virtualDisplay != null &&
                !captureInProgress

        Log.d("ScreenCapture", "isReady: $ready (setup: $isSetupComplete, projection: ${mediaProjection != null}, reader: ${imageReader != null}, display: ${virtualDisplay != null}, capturing: $captureInProgress)")
        return ready
    }

    /**
     * Verifica si hay una captura en progreso
     */
    fun isCaptureInProgress(): Boolean = captureInProgress

    /**
     * Cancela una captura en progreso (para casos de timeout)
     */
    fun cancelCapture() {
        if (captureInProgress) {
            Log.w("ScreenCapture", "Cancelling capture in progress")
            captureInProgress = false
        }
    }

    /**
     * Reinicia el ScreenCaptureManager en caso de error
     */
    fun reset() {
        Log.d("ScreenCapture", "Resetting ScreenCaptureManager")
        captureInProgress = false
        isSetupComplete = false

        // Limpiar recursos actuales
        cleanup()

        Log.d("ScreenCapture", "ScreenCaptureManager reset complete")
    }

    fun cleanup() {
        try {
            Log.d("ScreenCapture", "Starting cleanup")

            captureInProgress = false
            isSetupComplete = false

            // Cancelar callbacks pendientes
            handler.removeCallbacksAndMessages(null)

            // Liberar VirtualDisplay
            virtualDisplay?.let {
                try {
                    it.release()
                    Log.d("ScreenCapture", "VirtualDisplay released")
                } catch (e: Exception) {
                    Log.w("ScreenCapture", "Error releasing VirtualDisplay", e)
                }
            }
            virtualDisplay = null

            // Cerrar ImageReader
            imageReader?.let {
                try {
                    it.close()
                    Log.d("ScreenCapture", "ImageReader closed")
                } catch (e: Exception) {
                    Log.w("ScreenCapture", "Error closing ImageReader", e)
                }
            }
            imageReader = null

            // Detener MediaProjection
            mediaProjection?.let {
                try {
                    it.stop()
                    Log.d("ScreenCapture", "MediaProjection stopped")
                } catch (e: Exception) {
                    Log.w("ScreenCapture", "Error stopping MediaProjection", e)
                }
            }
            mediaProjection = null

            Log.d("ScreenCapture", "Cleanup completed")
        } catch (e: Exception) {
            Log.e("ScreenCapture", "Error during cleanup", e)
        }
    }
}