package com.cap.screentranslator.managers

import android.content.Context
import android.graphics.PixelFormat
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.*
import android.os.Handler
import android.os.Looper
import com.cap.screentranslator.R
import com.google.mlkit.vision.text.Text

class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    // Views para la barra flotante (solo captura)
    private var bottomBarView: View? = null
    private var captureButton: Button? = null

    // Views para el overlay de traducción
    private var translationView: FrameLayout? = null

    private val occupiedAreas = mutableListOf<android.graphics.Rect>()


    // Callbacks
    private var onCaptureClickListener: (() -> Unit)? = null
    private var onStopClickListener: (() -> Unit)? = null
    private var onLanguageChangeListener: ((String, String) -> Unit)? = null

    // Estado
    private var fontSize = 16f
    private var isCapturing = false

    fun showBottomBar() {
        if (bottomBarView != null) return

        bottomBarView = LayoutInflater.from(context).inflate(R.layout.bottom_bar_layout, null)
        captureButton = bottomBarView?.findViewById(R.id.capture_button)

        setupBottomBarButtons()

        val bottomBarParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            y = 100 // Separación del borde inferior
        }

        windowManager.addView(bottomBarView, bottomBarParams)
    }

    private fun setupBottomBarButtons() {
        captureButton?.setOnClickListener {
            startCaptureSequence()
        }
    }

    /**
     * Secuencia de captura que oculta los overlays temporalmente
     */
    private fun startCaptureSequence() {
        if (isCapturing) return // Prevenir múltiples capturas simultáneas

        isCapturing = true

        // 1. Ocultar todos los overlays
        hideAllOverlaysTemporarily()

        // 2. Esperar un momento para que desaparezcan completamente
        handler.postDelayed({
            // 3. Ejecutar la captura
            onCaptureClickListener?.invoke()
        }, 200)
    }

    /**
     * Oculta temporalmente todos los overlays para la captura
     */
    private fun hideAllOverlaysTemporarily() {
        // Ocultar barra flotante
        bottomBarView?.visibility = View.GONE

        // Ocultar overlay de traducción anterior si existe
        translationView?.visibility = View.GONE
    }

    /**
     * Restaura la visibilidad de los overlays después de la captura
     */
    fun restoreOverlaysAfterCapture() {
        isCapturing = false

        // Restaurar barra flotante después de un pequeño delay
        handler.postDelayed({
            bottomBarView?.visibility = View.VISIBLE
        }, 300)
    }

    fun showTranslationOverlay(visionText: com.google.mlkit.vision.text.Text, translatedTexts: List<String>) {
        hideTranslationOverlay() // Limpia el overlay anterior

        // Si no hay texto detectado, no hacemos nada
        if (visionText.textBlocks.isEmpty()) {
            // Restaurar overlays si no hay traducción que mostrar
            restoreOverlaysAfterCapture()
            return
        }

        translationView = FrameLayout(context)

        // Crear TextViews para cada bloque con su traducción correspondiente
        createTranslationTextViews(visionText, translatedTexts)

        val translationParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(translationView, translationParams)

        // Restaurar overlays después de mostrar la traducción
        restoreOverlaysAfterCapture()

        // Auto-ocultar traducción después de 10 segundos
        handler.postDelayed({
            hideTranslationOverlay()
        }, 10000)
    }

    // Método sobrecargado para mantener compatibilidad hacia atrás
    fun showTranslationOverlay(visionText: com.google.mlkit.vision.text.Text, translatedText: String) {
        hideTranslationOverlay()
        occupiedAreas.clear()

        if (visionText.textBlocks.isEmpty()) {
            restoreOverlaysAfterCapture()
            return
        }

        translationView = FrameLayout(context)
        distributeTranslationByBlocks(visionText, translatedText)

        val translationParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(translationView, translationParams)
        restoreOverlaysAfterCapture()

        handler.postDelayed({
            hideTranslationOverlay()
        }, 10000)
    }

    private fun createTranslationTextViews(visionText: Text, translatedTexts: List<String>) {
        val textBlocks = visionText.textBlocks

        for (i in textBlocks.indices) {
            val block = textBlocks[i]
            val boundingBox = block.boundingBox
            val translation = translatedTexts.getOrNull(i)?.trim()

            if (boundingBox != null && !translation.isNullOrEmpty()) {
                createTranslationTextView(translation, boundingBox.left, boundingBox.top)
            }
        }
    }

    private fun distributeTranslationByBlocks(visionText: com.google.mlkit.vision.text.Text, translatedText: String) {
        val textBlocks = visionText.textBlocks
        val originalTexts = textBlocks.map { it.text.trim() }

        val totalOriginalLength = originalTexts.sumOf { it.length }

        if (totalOriginalLength == 0) return

        // Dividir la traducción proporcionalmente
        val translatedParts = mutableListOf<String>()
        var translatedIndex = 0

        for (i in originalTexts.indices) {
            val originalText = originalTexts[i]

            if (i == originalTexts.lastIndex) {
                translatedParts.add(translatedText.substring(translatedIndex).trim())
            } else {
                val proportion = originalText.length.toDouble() / totalOriginalLength
                val estimatedLength = (translatedText.length * proportion).toInt()

                val endIndex = findAppropriateBreakPoint(
                    translatedText,
                    translatedIndex,
                    translatedIndex + estimatedLength
                )

                translatedParts.add(translatedText.substring(translatedIndex, endIndex).trim())
                translatedIndex = endIndex
            }
        }

        // Crear TextViews para cada bloque
        for (i in textBlocks.indices) {
            val textBlock = textBlocks[i]
            val boundingBox = textBlock.boundingBox

            if (boundingBox != null && i < translatedParts.size) {
                val translatedPart = translatedParts[i]
                if (translatedPart.isNotEmpty()) {
                    createTranslationTextView(translatedPart, boundingBox.left, boundingBox.top)
                }
            }
        }
    }

    private fun findAppropriateBreakPoint(text: String, startIndex: Int, estimatedEndIndex: Int): Int {
        if (estimatedEndIndex >= text.length) return text.length
        if (estimatedEndIndex <= startIndex) return startIndex + 1

        val searchRange = minOf(20, (text.length - startIndex) / 4)

        // Buscar hacia adelante
        for (i in estimatedEndIndex until minOf(estimatedEndIndex + searchRange, text.length)) {
            if (text[i] in " .!?;,\n") {
                return i + 1
            }
        }

        // Buscar hacia atrás
        for (i in estimatedEndIndex downTo maxOf(startIndex + 1, estimatedEndIndex - searchRange)) {
            if (text[i] in " .!?;,\n") {
                return i + 1
            }
        }

        return estimatedEndIndex
    }

    private fun createTranslationTextView(text: String, x: Int, y: Int) {
        if (text.trim().isEmpty()) return

        val textView = TextView(context).apply {
            this.text = text.trim()
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#CC000000"))
            textSize = fontSize
            setPadding(12, 8, 12, 8)
            setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
            maxLines = 8
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        // Medir tamaño
        textView.measure(
            View.MeasureSpec.UNSPECIFIED,
            View.MeasureSpec.UNSPECIFIED
        )
        val width = textView.measuredWidth
        val height = textView.measuredHeight

        var newX = x
        var newY = y
        val maxAttempts = 10
        val offset = 20

        var attempts = 0
        var newRect: android.graphics.Rect

        do {
            newRect = android.graphics.Rect(newX, newY, newX + width, newY + height)
            val collides = occupiedAreas.any { it.intersect(newRect) }

            if (collides) {
                newY -= offset
                newX += offset
            } else {
                break
            }

            attempts++
        } while (attempts < maxAttempts)

        // Guardar el área ocupada
        occupiedAreas.add(newRect)

        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = newX
            topMargin = newY
        }

        translationView?.addView(textView, layoutParams)
    }

    fun hideTranslationOverlay() {
        occupiedAreas.clear()

        translationView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                // View already removed
            }
        }
        translationView = null
    }


    // Callback setter
    fun setOnCaptureClickListener(listener: () -> Unit) {
        onCaptureClickListener = listener
    }

    fun setOnStopClickListener(listener: () -> Unit) {
        onStopClickListener = listener
    }

    fun setOnLanguageChangeListener(listener: (String, String) -> Unit) {
        onLanguageChangeListener = listener
    }

    // Getters y setters para el tamaño de fuente
    fun getFontSize(): Float = fontSize

    fun setFontSize(size: Float) {
        fontSize = size
    }

    /**
     * Método público para restaurar overlays manualmente si es necesario
     */
    fun forceRestoreOverlays() {
        restoreOverlaysAfterCapture()
    }

    fun cleanup() {
        handler.removeCallbacksAndMessages(null) // Limpiar handlers pendientes

        try {
            bottomBarView?.let { windowManager.removeView(it) }
            translationView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            // Views already removed
        }

        bottomBarView = null
        translationView = null
        isCapturing = false
    }
}