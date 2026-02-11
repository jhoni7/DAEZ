package com.DAEZ.DAEZKit.managers

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.DAEZ.DAEZKit.R
import com.google.mlkit.vision.text.Text
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    // Views para la barra flotante (solo captura)
    private var bottomBarView: View? = null
    private var captureButton: Button? = null
    private var downloadProgressBar: android.widget.ProgressBar? = null
    private var downloadStatusText: android.widget.TextView? = null

    // Views para el overlay de traducción
    private var translationView: FrameLayout? = null
    private var translationParams: WindowManager.LayoutParams? = null

    private val occupiedAreas = mutableListOf<Rect>()

    // Callbacks
    private var onCaptureClickListener: (() -> Unit)? = null
    private var onStopClickListener: (() -> Unit)? = null
    private var onLanguageChangeListener: ((String, String) -> Unit)? = null

    // Estado mejorado para prevenir parpadeo
    private var fontSize = 10f
    private var isCapturing = false
    private var isTranslationShowing = false
    private var translationDismissRunnable: Runnable? = null
    private var restoreRunnable: Runnable? = null

    // Estados para lectura continua
    private var isContinuousReading = false
    private var continuousReadingRunnable: Runnable? = null
    private var longPressRunnable: Runnable? = null
    private var buttonPressStartTime = 0L

    // botón flotante y arrastrable
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialX = 0
    private var initialY = 0
    private var isDragging = false

    // transparencia
    private var fadeOutRunnable: Runnable? = null
    private val fadeOutHandler = Handler(Looper.getMainLooper())
    private val FADE_OUT_DELAY = 3000L // 3 segundos de inactividad

    // Configuraciones mejoradas
    companion object {
        private const val MIN_DIALOG_WIDTH = 80
        private const val MIN_DIALOG_HEIGHT = 25
        private const val PROXIMITY_THRESHOLD = 60

        private const val LINE_HEIGHT_THRESHOLD = 1.5f
        private const val MAX_DIALOG_ASPECT_RATIO = 10f
        private const val MIN_WORDS_PER_DIALOG = 1
        private const val CAPTURE_RESTORE_DELAY = 300L
        private const val FORCE_RESTORE_TIMEOUT = 4000L
        private const val TRANSLATION_DISPLAY_TIME = 15000L // Aumentado a 15 segundos
        private const val TRANSLATION_MIN_DISPLAY_TIME = 3000L // Mínimo 3 segundos visible
        private const val DEBOUNCE_TIME = 500L // Tiempo para evitar múltiples capturas rápidas

        // Configuraciones para lectura continua
        private const val LONG_PRESS_DURATION = 3000L // 3 segundos para activar lectura continua
        private const val CONTINUOUS_READING_INTERVAL = 10000L // 10 segundos entre capturas automáticas
    }

    data class DialogBubble(
        val boundingBox: Rect,
        val textElements: List<Text.Element>,
        val lines: List<Text.Line>,
        val confidence: Float,
        val estimatedType: DialogType
    )

    enum class DialogType {
        SPEECH_BUBBLE,
        THOUGHT_BUBBLE,
        NARRATIVE_TEXT,
        SOUND_EFFECT
    }

    // Variables para control de debounce
    private var lastCaptureTime = 0L
    private var translationStartTime = 0L

    fun showBottomBar() {
        if (bottomBarView != null) return

        try {
            bottomBarView = LayoutInflater.from(context).inflate(R.layout.bottom_bar_layout, null)
            captureButton = bottomBarView?.findViewById(R.id.capture_button)
            downloadProgressBar = bottomBarView?.findViewById(R.id.download_progress)
            downloadStatusText = bottomBarView?.findViewById(R.id.download_status)

            setupBottomBarButtons()

            val bottomBarParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 100
            }

            windowManager.addView(bottomBarView, bottomBarParams)
            android.util.Log.d("OverlayManager", "Bottom bar shown successfully")

            // Iniciar el timer de fade out
            startFadeOutTimer()

        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Error showing bottom bar", e)
        }
    }

    private fun setupBottomBarButtons() {
        // Iniciar el timer cuando se muestra el botón
        startFadeOutTimer()

        captureButton?.setOnTouchListener { view, event ->
            val layoutParams = bottomBarView?.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Restaurar opacidad al tocar
                    cancelFadeOut()

                    handleButtonDown()
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY

                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isDragging = true
                        longPressRunnable?.let { handler.removeCallbacks(it) }
                    }

                    if (isDragging) {
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(bottomBarView, layoutParams)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isDragging) {
                        handleButtonUp()
                    } else {
                        constrainToScreen(layoutParams)
                    }
                    isDragging = false

                    // Reiniciar el timer de fade out después de soltar
                    startFadeOutTimer()
                    true
                }

                else -> false
            }
        }
    }

    private fun constrainToScreen(params: WindowManager.LayoutParams) {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val view = bottomBarView ?: return
        val buttonWidth = view.width
        val buttonHeight = view.height

        // Limitar coordenadas
        params.x = params.x.coerceIn(0, screenWidth - buttonWidth)
        params.y = params.y.coerceIn(0, screenHeight - buttonHeight)

        windowManager.updateViewLayout(view, params)
    }

    // manejo de transparencia
    private fun startFadeOutTimer() {
        // Cancelar cualquier fade anterior
        fadeOutRunnable?.let { fadeOutHandler.removeCallbacks(it) }

        // Restaurar opacidad completa
        bottomBarView?.alpha = 1f

        // Programar el fade out
        fadeOutRunnable = Runnable {
            bottomBarView?.animate()
                ?.alpha(0.3f) // 30% de opacidad
                ?.setDuration(500) // Duración de la animación
                ?.start()
        }

        fadeOutHandler.postDelayed(fadeOutRunnable!!, FADE_OUT_DELAY)
    }

    // ocultar boton
    fun hideBottomBar() {
        try {
            fadeOutRunnable?.let { fadeOutHandler.removeCallbacks(it) }
            if (bottomBarView != null) {
                windowManager.removeView(bottomBarView)
                bottomBarView = null
                captureButton = null
            }
        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Error hiding bottom bar", e)
        }
    }

    private fun cancelFadeOut() {
        fadeOutRunnable?.let { fadeOutHandler.removeCallbacks(it) }
        bottomBarView?.alpha = 1f
    }

    private fun handleButtonDown() {
        val currentTime = System.currentTimeMillis()
        buttonPressStartTime = currentTime

        // Cancelar cualquier long press anterior
        longPressRunnable?.let { handler.removeCallbacks(it) }

        // Programar detección de long press
        longPressRunnable = Runnable {
            if (buttonPressStartTime > 0) { // Asegurar que el botón sigue presionado
                activateContinuousReading()
            }
        }

        handler.postDelayed(longPressRunnable!!, LONG_PRESS_DURATION)
        android.util.Log.d("OverlayManager", "Button pressed down, long press scheduled")
    }

    private fun handleButtonUp() {
        val currentTime = System.currentTimeMillis()
        val pressDuration = currentTime - buttonPressStartTime

        // Cancelar long press si no se completó
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null

        if (buttonPressStartTime > 0) {
            if (pressDuration < LONG_PRESS_DURATION) {
                // Presión corta - captura única
                handleSingleCapture(currentTime)
            }
            // Si fue presión larga, ya se activó la lectura continua
        }

        buttonPressStartTime = 0L
        android.util.Log.d("OverlayManager", "Button released after ${pressDuration}ms")
    }

    private fun handleSingleCapture(currentTime: Long) {
        // Si está en lectura continua, detenerla
        if (isContinuousReading) {
            deactivateContinuousReading()
            return
        }

        // Prevenir capturas múltiples muy rápidas (debounce)
        if (currentTime - lastCaptureTime < DEBOUNCE_TIME) {
            android.util.Log.d("OverlayManager", "Capture ignored due to debounce")
            return
        }

        if (!isCapturing) {
            lastCaptureTime = currentTime
            startCaptureSequence()
        }
    }

    private fun activateContinuousReading() {
        if (isContinuousReading) return

        android.util.Log.d("OverlayManager", "Activating continuous reading mode")
        isContinuousReading = true

        // Cambiar apariencia del botón para indicar modo continuo
        updateButtonAppearance()

        // Iniciar primera captura inmediata
        if (!isCapturing) {
            lastCaptureTime = System.currentTimeMillis()
            startCaptureSequence()
        }

        // Programar capturas automáticas
        scheduleContinuousCapture()
    }

    private fun deactivateContinuousReading() {
        if (!isContinuousReading) return

        android.util.Log.d("OverlayManager", "Deactivating continuous reading mode")
        isContinuousReading = false

        // Cancelar capturas programadas
        continuousReadingRunnable?.let { handler.removeCallbacks(it) }
        continuousReadingRunnable = null

        // Restaurar apariencia del botón
        updateButtonAppearance()
    }

    private fun scheduleContinuousCapture() {
        if (!isContinuousReading) return

        continuousReadingRunnable?.let { handler.removeCallbacks(it) }

        continuousReadingRunnable = Runnable {
            if (isContinuousReading && !isCapturing) {
                android.util.Log.d("OverlayManager", "Executing automatic capture in continuous mode")
                lastCaptureTime = System.currentTimeMillis()
                startCaptureSequence()

                // Programar siguiente captura
                scheduleContinuousCapture()
            }
        }

        handler.postDelayed(continuousReadingRunnable!!, CONTINUOUS_READING_INTERVAL)
        android.util.Log.d("OverlayManager", "Next automatic capture scheduled in ${CONTINUOUS_READING_INTERVAL}ms")
    }

    private fun updateButtonAppearance() {
        captureButton?.let { button ->
            val context = button.context
            if (isContinuousReading) {
                // Cambiar apariencia para indicar modo continuo
                button.text = "CONTINUO"
                button.background = context.getDrawable(R.drawable.button_continuous_mode_background)
                button.setTextColor(android.graphics.Color.WHITE)
            } else {
                // Apariencia normal
                button.text = "CAPTURAR"
                button.background = context.getDrawable(R.drawable.button_capture_background)
                button.setTextColor(android.graphics.Color.WHITE)
            }
        }
    }

    fun setDownloadProgress(isDownloading: Boolean, language: String? = null) {
        handler.post {
            downloadProgressBar?.visibility = if (isDownloading) View.VISIBLE else View.GONE
            downloadStatusText?.visibility = if (isDownloading) View.VISIBLE else View.GONE
            
            if (isDownloading) {
                val langName = language?.let { getLanguageName(it) } ?: "Idioma"
                downloadStatusText?.text = "Descargando $langName..."
                bottomBarView?.alpha = 1f // Asegurar visibilidad completa
            }
        }
    }

    private fun getLanguageName(code: String): String {
        return when (code) {
            "es" -> "Español"
            "en" -> "Inglés"
            "fr" -> "Francés"
            "de" -> "Alemán"
            "it" -> "Italiano"
            "pt" -> "Portugués"
            "zh" -> "Chino"
            "ja" -> "Japonés"
            "ko" -> "Coreano"
            "ru" -> "Ruso"
            "ar" -> "Árabe"
            else -> code.uppercase()
        }
    }

    fun setAppReady(isReady: Boolean) {
        // No longer showing proactive notifications as per user request
    }

    fun showNotReadyToast() {
        handler.post {
            android.widget.Toast.makeText(
                context,
                "Traductor no listo. Espere por favor...",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun clearTranslations() {
        handler.post {
            translationView?.removeAllViews()
        }
    }


    fun isContinuousReadingActive(): Boolean = isContinuousReading

    fun toggleContinuousReading() {
        if (isContinuousReading) {
            deactivateContinuousReading()
        } else {
            activateContinuousReading()
        }
    }

    private fun startCaptureSequence() {
        if (isCapturing) return

        android.util.Log.d("OverlayManager", "Starting capture sequence (continuous: $isContinuousReading)")
        isCapturing = true

        // Cancelar cualquier restore pendiente
        cancelPendingOperations()

        // Ocultar traducción actual si existe, pero mantener el tiempo mínimo
        hideCurrentTranslationIfReady()

        hideAllOverlaysTemporarily()

        // Programar timeout de seguridad
        scheduleForceRestore()

        handler.postDelayed({
            try {
                onCaptureClickListener?.invoke()
            } catch (e: Exception) {
                android.util.Log.e("OverlayManager", "Error in capture callback", e)
                forceRestoreOverlays()
            }
        }, 200)
    }

    private fun cancelPendingOperations() {
        // Cancelar todas las operaciones pendientes (excepto continuous reading)
        restoreRunnable?.let { handler.removeCallbacks(it) }
        translationDismissRunnable?.let { handler.removeCallbacks(it) }
        restoreRunnable = null
        translationDismissRunnable = null
    }

    private fun hideCurrentTranslationIfReady() {
        if (isTranslationShowing) {
            val currentTime = System.currentTimeMillis()
            val timeShowing = currentTime - translationStartTime

            // Solo ocultar si ha estado visible el tiempo mínimo
            if (timeShowing >= TRANSLATION_MIN_DISPLAY_TIME) {
                hideTranslationOverlay()
                android.util.Log.d("OverlayManager", "Previous translation hidden after ${timeShowing}ms")
            } else {
                android.util.Log.d("OverlayManager", "Translation still showing (${timeShowing}ms < ${TRANSLATION_MIN_DISPLAY_TIME}ms)")
            }
        }
    }

    private fun scheduleForceRestore() {
        restoreRunnable = Runnable {
            if (isCapturing) {
                android.util.Log.w("OverlayManager", "Force restoring overlays after timeout")
                forceRestoreOverlays()
            }
        }
        handler.postDelayed(restoreRunnable!!, FORCE_RESTORE_TIMEOUT)
    }

    private fun hideAllOverlaysTemporarily() {
        android.util.Log.d("OverlayManager", "Hiding overlays temporarily")
        bottomBarView?.visibility = View.GONE
    }

    fun restoreOverlaysAfterCapture() {
        android.util.Log.d("OverlayManager", "Restoring overlays after capture")

        // Cancelar timeout de seguridad
        restoreRunnable?.let { handler.removeCallbacks(it) }
        restoreRunnable = null

        isCapturing = false

        handler.postDelayed({
            try {
                bottomBarView?.visibility = View.VISIBLE
                android.util.Log.d("OverlayManager", "Bottom bar restored")
            } catch (e: Exception) {
                android.util.Log.e("OverlayManager", "Error restoring bottom bar", e)
            }
        }, CAPTURE_RESTORE_DELAY)
    }

    fun forceRestoreOverlays() {
        android.util.Log.d("OverlayManager", "Force restoring overlays")

        cancelPendingOperations()

        isCapturing = false

        handler.post {
            try {
                bottomBarView?.visibility = View.VISIBLE
                android.util.Log.d("OverlayManager", "Bottom bar force restored")
            } catch (e: Exception) {
                android.util.Log.e("OverlayManager", "Error force restoring bottom bar", e)
            }
        }
    }

    /**
     * Método principal mejorado con estabilidad anti-parpadeo
     */
    fun showTranslationOverlay(visionText: Text, translatedTexts: List<String>) {
        android.util.Log.d("OverlayManager", "showTranslationOverlay called with ${visionText.textBlocks.size} blocks and ${translatedTexts.size} translations")

        // Cancelar dismissal anterior para evitar parpadeo
        translationDismissRunnable?.let { handler.removeCallbacks(it) }

        // Limpiar overlay anterior solo si no está siendo mostrado actualmente
        if (!isTranslationShowing) {
            cleanupTranslationView()
        }

        if (visionText.textBlocks.isEmpty() || translatedTexts.isEmpty()) {
            android.util.Log.w("OverlayManager", "No text blocks or translations found")
            restoreOverlaysAfterCapture()
            return
        }

        try {
            // Si ya hay una traducción mostrándose, actualizarla en lugar de recrear
            val shouldCreateNew = translationView == null

            if (shouldCreateNew) {
                translationView = FrameLayout(context)
                createTranslationParams()
            } else {
                // Limpiar contenido anterior
                translationView?.removeAllViews()
                occupiedAreas.clear()
            }

            // Detectar y procesar globos de diálogo
            val dialogBubbles = detectDialogBubbles(visionText)
            android.util.Log.d("OverlayManager", "Detected ${dialogBubbles.size} dialog bubbles")

            if (dialogBubbles.isEmpty()) {
                android.util.Log.w("OverlayManager", "No dialog bubbles detected")
                restoreOverlaysAfterCapture()
                return
            }

            // Crear TextViews optimizados
            createOptimizedTranslationViews(dialogBubbles, translatedTexts)

            if (shouldCreateNew) {
                windowManager.addView(translationView, translationParams)
            }

            // Marcar como mostrando y registrar tiempo
            isTranslationShowing = true
            translationStartTime = System.currentTimeMillis()

            restoreOverlaysAfterCapture()
            scheduleTranslationDismiss()

        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Error showing translation overlay", e)
            restoreOverlaysAfterCapture()
        }
    }

    private fun createTranslationParams() {
        translationParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
    }

    private fun scheduleTranslationDismiss() {
        // Cancelar dismissal anterior
        translationDismissRunnable?.let { handler.removeCallbacks(it) }

        translationDismissRunnable = Runnable {
            android.util.Log.d("OverlayManager", "Auto-hiding translation overlay")
            hideTranslationOverlay()
        }

        handler.postDelayed(translationDismissRunnable!!, TRANSLATION_DISPLAY_TIME)
    }

    // Método sobrecargado mejorado
    fun showTranslationOverlay(visionText: Text, translatedText: String) {
        android.util.Log.d("OverlayManager", "showTranslationOverlay (single text) called")

        // Cancelar dismissal anterior
        translationDismissRunnable?.let { handler.removeCallbacks(it) }

        if (!isTranslationShowing) {
            cleanupTranslationView()
            occupiedAreas.clear()
        }

        if (visionText.textBlocks.isEmpty() || translatedText.trim().isEmpty()) {
            android.util.Log.w("OverlayManager", "No text blocks or empty translation")
            restoreOverlaysAfterCapture()
            return
        }

        try {
            val shouldCreateNew = translationView == null

            if (shouldCreateNew) {
                translationView = FrameLayout(context)
                createTranslationParams()
            } else {
                translationView?.removeAllViews()
                occupiedAreas.clear()
            }

            distributeTranslationByBlocks(visionText, translatedText.trim())

            if (shouldCreateNew) {
                windowManager.addView(translationView, translationParams)
            }

            isTranslationShowing = true
            translationStartTime = System.currentTimeMillis()

            restoreOverlaysAfterCapture()
            scheduleTranslationDismiss()

        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Error showing single translation overlay", e)
            restoreOverlaysAfterCapture()
        }
    }

    private fun cleanupTranslationView() {
        try {
            translationView?.let { view ->
                try {
                    windowManager.removeView(view)
                } catch (e: Exception) {
                    android.util.Log.w("OverlayManager", "Translation view already removed", e)
                }
            }
            translationView = null
            translationParams = null
            occupiedAreas.clear()
        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Error cleaning up translation view", e)
        }
    }

    /**
     * Detecta globos de diálogo con mejor tolerancia a errores
     */
    fun detectDialogBubbles(visionText: Text): List<DialogBubble> {
        return try {
            val dialogBubbles = mutableListOf<DialogBubble>()
            val allElements = mutableListOf<Text.Element>()

            // Recopilar todos los elementos con sus bounding boxes
            for (textBlock in visionText.textBlocks) {
                for (line in textBlock.lines) {
                    for (element in line.elements) {
                        if (element.boundingBox != null && element.text.isNotBlank()) {
                            allElements.add(element)
                        }
                    }
                }
            }

            if (allElements.isEmpty()) {
                return createFallbackDialog(visionText)
            }

            // Agrupar elementos por proximidad espacial mejorada
            val elementGroups = clusterElementsByProximity(allElements)

            // Crear diálogos a partir de los grupos
            for (group in elementGroups) {
                try {
                    val lines = visionText.textBlocks.flatMap { block -> block.lines }
                        .filter { line -> line.elements.any { element -> group.contains(element) } }

                    val dialog = createDialogFromElements(group, lines)
                    if (dialog != null) {
                        dialogBubbles.add(dialog)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("OverlayManager", "Error creating dialog from group", e)
                    continue
                }
            }

            // Filtrar y fusionar diálogos muy cercanos
            val validDialogs = filterValidDialogs(dialogBubbles)
            val mergedDialogs = mergeOverlappingDialogs(validDialogs)

            android.util.Log.d("OverlayManager", "Detected ${allElements.size} elements -> ${elementGroups.size} groups -> ${mergedDialogs.size} dialogs")

            if (mergedDialogs.isEmpty()) {
                return createFallbackDialog(visionText)
            }

            mergedDialogs
        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Error detecting dialog bubbles", e)
            createFallbackDialog(visionText)
        }
    }

    private fun createFallbackDialog(visionText: Text): List<DialogBubble> {
        return try {
            if (visionText.textBlocks.isEmpty()) return emptyList()

            val allElements = mutableListOf<Text.Element>()
            val allLines = mutableListOf<Text.Line>()
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE
            var totalConfidence = 0f
            var elementCount = 0

            for (textBlock in visionText.textBlocks) {
                for (line in textBlock.lines) {
                    allLines.add(line)
                    for (element in line.elements) {
                        allElements.add(element)
                        element.boundingBox?.let { box ->
                            minX = min(minX, box.left)
                            minY = min(minY, box.top)
                            maxX = max(maxX, box.right)
                            maxY = max(maxY, box.bottom)
                            totalConfidence += element.confidence ?: 0f
                            elementCount++
                        }
                    }
                }
            }

            if (elementCount == 0 || minX == Int.MAX_VALUE) return emptyList()

            val fallbackDialog = DialogBubble(
                boundingBox = Rect(minX, minY, maxX, maxY),
                textElements = allElements,
                lines = allLines,
                confidence = totalConfidence / elementCount,
                estimatedType = DialogType.SPEECH_BUBBLE
            )

            android.util.Log.d("OverlayManager", "Created fallback dialog with ${allElements.size} elements")
            listOf(fallbackDialog)
        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Error creating fallback dialog", e)
            emptyList()
        }
    }

    /**
     * Agrupa elementos por proximidad espacial usando clustering mejorado
     */
    private fun clusterElementsByProximity(elements: List<Text.Element>): List<List<Text.Element>> {
        if (elements.isEmpty()) return emptyList()
        if (elements.size == 1) return listOf(elements)

        return try {
            val clusters = mutableListOf<MutableList<Text.Element>>()
            val processed = BooleanArray(elements.size)

            for (i in elements.indices) {
                if (processed[i]) continue

                val cluster = mutableListOf<Text.Element>()
                val queue = mutableListOf(i)

                while (queue.isNotEmpty()) {
                    val currentIdx = queue.removeAt(0)
                    if (processed[currentIdx]) continue

                    processed[currentIdx] = true
                    cluster.add(elements[currentIdx])

                    // Buscar elementos cercanos
                    for (j in elements.indices) {
                        if (!processed[j] && shouldGroupElements(elements[currentIdx], elements[j])) {
                            queue.add(j)
                        }
                    }
                }

                if (cluster.isNotEmpty()) {
                    clusters.add(cluster)
                }
            }

            // Ordenar clusters por posición (arriba-izquierda primero)
            clusters.sortBy { cluster ->
                val firstBox = cluster.firstOrNull()?.boundingBox
                if (firstBox != null) {
                    firstBox.top * 10000 + firstBox.left
                } else {
                    Int.MAX_VALUE
                }
            }

            android.util.Log.d("OverlayManager", "Clustered ${elements.size} elements into ${clusters.size} groups")
            clusters
        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Error clustering elements", e)
            listOf(elements)
        }
    }

    /**
     * Determina si dos elementos deben agruparse basándose en proximidad y alineación
     */
    private fun shouldGroupElements(element1: Text.Element, element2: Text.Element): Boolean {
        return try {
            val box1 = element1.boundingBox ?: return false
            val box2 = element2.boundingBox ?: return false

            // Calcular distancias
            val horizontalDistance = when {
                box1.right < box2.left -> box2.left - box1.right
                box2.right < box1.left -> box1.left - box2.right
                else -> 0 // Se superponen horizontalmente
            }

            val verticalDistance = when {
                box1.bottom < box2.top -> box2.top - box1.bottom
                box2.bottom < box1.top -> box1.top - box2.bottom
                else -> 0 // Se superponen verticalmente
            }

            // Altura promedio para establecer umbrales
            val avgHeight = (box1.height() + box2.height()) / 2f
            val maxHorizontalGap = avgHeight * 2.0f // Permitir gaps horizontales más grandes
            val maxVerticalGap = avgHeight * 1.5f // Gap vertical más restrictivo

            // Verificar alineación vertical (están en la misma "línea")
            val verticalOverlap = !(box1.bottom < box2.top || box2.bottom < box1.top)
            val roughlyAligned = abs(box1.centerY() - box2.centerY()) < avgHeight * 0.8f

            // Decisión de agrupamiento
            when {
                // Misma línea horizontal
                verticalOverlap || roughlyAligned -> horizontalDistance <= maxHorizontalGap
                // Líneas diferentes pero muy cercanas verticalmente
                verticalDistance <= maxVerticalGap -> {
                    // Deben estar razonablemente alineados horizontalmente también
                    val horizontalOverlap = !(box1.right < box2.left || box2.right < box1.left)
                    val horizontalAlignment = abs(box1.left - box2.left) < avgHeight * 3f
                    horizontalOverlap || horizontalAlignment
                }
                else -> false
            }
        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Error checking element grouping", e)
            false
        }
    }

    private fun createDialogFromElements(
        elements: List<Text.Element>,
        lines: List<Text.Line>
    ): DialogBubble? {
        return try {
            if (elements.isEmpty()) return null

            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE
            var totalConfidence = 0f

            for (element in elements) {
                element.boundingBox?.let { box ->
                    minX = min(minX, box.left)
                    minY = min(minY, box.top)
                    maxX = max(maxX, box.right)
                    maxY = max(maxY, box.bottom)
                    totalConfidence += element.confidence ?: 0f
                }
            }

            if (minX == Int.MAX_VALUE) return null

            val combinedBox = Rect(minX, minY, maxX, maxY)
            val avgConfidence = totalConfidence / elements.size
            val dialogType = determineDialogType(elements, combinedBox)

            DialogBubble(
                boundingBox = combinedBox,
                textElements = elements,
                lines = lines,
                confidence = avgConfidence,
                estimatedType = dialogType
            )
        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Error creating dialog from elements", e)
            null
        }
    }

    private fun determineDialogType(elements: List<Text.Element>, boundingBox: Rect): DialogType {
        return try {
            val combinedText = elements.joinToString(" ") { it.text }
            val aspectRatio = if (boundingBox.height() > 0) {
                boundingBox.width().toFloat() / boundingBox.height().toFloat()
            } else 1f

            when {
                combinedText.length <= 10 && combinedText.any { it in "!*#@" } -> DialogType.SOUND_EFFECT
                aspectRatio > MAX_DIALOG_ASPECT_RATIO && elements.size > 8 -> DialogType.NARRATIVE_TEXT
                combinedText.contains(Regex("(think|thought|wonder|maybe|perhaps)", RegexOption.IGNORE_CASE)) -> DialogType.THOUGHT_BUBBLE
                else -> DialogType.SPEECH_BUBBLE
            }
        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Error determining dialog type", e)
            DialogType.SPEECH_BUBBLE
        }
    }

    private fun filterValidDialogs(dialogs: List<DialogBubble>): List<DialogBubble> {
        return try {
            dialogs.filter { dialog ->
                val box = dialog.boundingBox
                val text = dialog.textElements.joinToString(" ") { it.text }.trim()

                box.width() >= MIN_DIALOG_WIDTH &&
                        box.height() >= MIN_DIALOG_HEIGHT &&
                        text.isNotBlank() &&
                        text.length > 0 &&
                        dialog.confidence > 0.1f // Reducido para mayor tolerancia
            }
        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Error filtering dialogs", e)
            dialogs
        }
    }

    private fun mergeDialogs(dialogs: List<DialogBubble>): DialogBubble? {
        return try {
            if (dialogs.isEmpty()) return null

            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE

            val allElements = mutableListOf<Text.Element>()
            val allLines = mutableListOf<Text.Line>()
            var totalConfidence = 0f

            for (dialog in dialogs) {
                val box = dialog.boundingBox
                minX = min(minX, box.left)
                minY = min(minY, box.top)
                maxX = max(maxX, box.right)
                maxY = max(maxY, box.bottom)

                allElements.addAll(dialog.textElements)
                allLines.addAll(dialog.lines)
                totalConfidence += dialog.confidence
            }

            DialogBubble(
                boundingBox = Rect(minX, minY, maxX, maxY),
                textElements = allElements.distinct(),
                lines = allLines.distinct(),
                confidence = totalConfidence / dialogs.size,
                estimatedType = dialogs.first().estimatedType
            )
        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Error merging dialogs", e)
            null
        }
    }

    /**
     * Fusiona diálogos que se superponen o están muy cercanos
     */
    private fun mergeOverlappingDialogs(dialogs: List<DialogBubble>): List<DialogBubble> {
        return try {
            if (dialogs.size <= 1) return dialogs

            val merged = mutableListOf<DialogBubble>()
            val processed = BooleanArray(dialogs.size)

            for (i in dialogs.indices) {
                if (processed[i]) continue

                val toMerge = mutableListOf(dialogs[i])
                processed[i] = true

                // Buscar diálogos que se superponen o están muy cercanos
                for (j in i + 1 until dialogs.size) {
                    if (!processed[j] && shouldMergeDialogs(dialogs[i], dialogs[j])) {
                        toMerge.add(dialogs[j])
                        processed[j] = true
                    }
                }

                if (toMerge.size > 1) {
                    val mergedDialog = mergeDialogs(toMerge)
                    if (mergedDialog != null) {
                        merged.add(mergedDialog)
                    }
                } else {
                    merged.add(dialogs[i])
                }
            }

            android.util.Log.d("OverlayManager", "Merged ${dialogs.size} dialogs into ${merged.size}")
            merged
        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Error merging dialogs", e)
            dialogs
        }
    }

    /**
     * Determina si dos diálogos deben fusionarse
     */
    private fun shouldMergeDialogs(dialog1: DialogBubble, dialog2: DialogBubble): Boolean {
        return try {
            val box1 = dialog1.boundingBox
            val box2 = dialog2.boundingBox

            // Verificar superposición directa
            if (Rect.intersects(box1, box2)) {
                return true
            }

            // Calcular distancia entre centros
            val centerDistance = sqrt(
                (box2.centerX() - box1.centerX()).toDouble().pow(2) +
                        (box2.centerY() - box1.centerY()).toDouble().pow(2)
            )

            // Umbral basado en el tamaño de los diálogos
            val avgSize = (max(box1.width(), box1.height()) + max(box2.width(), box2.height())) / 2f
            val mergeThreshold = avgSize * 0.8f // Más restrictivo para evitar fusiones innecesarias

            centerDistance <= mergeThreshold
        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Error checking dialog merge", e)
            false
        }
    }

    private fun createOptimizedTranslationViews(
        dialogBubbles: List<DialogBubble>,
        translatedTexts: List<String>
    ) {
        try {
            for (i in dialogBubbles.indices) {
                val dialog = dialogBubbles[i]
                val translation = translatedTexts.getOrNull(i)?.trim()

                if (!translation.isNullOrEmpty()) {
                    createDialogTranslationView(dialog, translation)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Error creating translation views", e)
        }
    }

    /**
     * Encuentra la posición óptima para el texto traducido evitando superposiciones
     */
    private fun findOptimalPosition(originalBox: Rect, textView: TextView): android.graphics.Point {
        return try {
            // Medir el TextView de manera más precisa
            val widthSpec = View.MeasureSpec.makeMeasureSpec(
                minOf(originalBox.width() + 80, windowManager.defaultDisplay.width - 40),
                View.MeasureSpec.AT_MOST
            )
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

            textView.measure(widthSpec, heightSpec)

            val width = textView.measuredWidth
            val height = textView.measuredHeight

            val screenWidth = windowManager.defaultDisplay.width
            val screenHeight = windowManager.defaultDisplay.height

            // Margen de seguridad
            val margin = 20

            // Estrategia de posicionamiento: priorizar posiciones que no se superpongan
            val candidates = generatePositionCandidates(originalBox, width, height, margin, screenWidth, screenHeight)

            // Evaluar cada candidato y seleccionar el mejor
            var bestCandidate: android.graphics.Point? = null
            var bestScore = Float.MAX_VALUE

            for (candidate in candidates) {
                val candidateRect = Rect(candidate.x, candidate.y, candidate.x + width, candidate.y + height)

                // Calcular score (menor es mejor)
                var score = 0f

                // Penalizar superposiciones con otras traducciones
                var hasCollision = false
                for (occupied in occupiedAreas) {
                    if (Rect.intersects(candidateRect, occupied)) {
                        hasCollision = true
                        score += 1000f // Penalización alta por colisión

                        // Calcular área de superposición
                        val intersection = Rect(candidateRect)
                        if (intersection.intersect(occupied)) {
                            val overlapArea = intersection.width() * intersection.height()
                            score += overlapArea.toFloat()
                        }
                    }
                }

                // Penalizar superposición con texto original
                if (Rect.intersects(candidateRect, originalBox)) {
                    val intersection = Rect(candidateRect)
                    if (intersection.intersect(originalBox)) {
                        val overlapArea = intersection.width() * intersection.height()
                        score += overlapArea.toFloat() * 0.5f // Menor penalización que con otras traducciones
                    }
                }

                // Penalizar si está fuera de la pantalla
                if (candidate.x < 0 || candidate.y < 0 ||
                    candidate.x + width > screenWidth ||
                    candidate.y + height > screenHeight) {
                    score += 500f
                }

                // Preferir posiciones debajo del texto original
                if (candidate.y > originalBox.bottom) {
                    score -= 50f
                }

                // Preferir posiciones con buena alineación horizontal
                val horizontalAlignment = abs(candidate.x - originalBox.left)
                score += horizontalAlignment * 0.1f

                if (score < bestScore) {
                    bestScore = score
                    bestCandidate = candidate
                }
            }

            val finalPosition = bestCandidate ?: candidates.first()

            // Ajustar para asegurar que está dentro de la pantalla
            val adjustedX = maxOf(margin, minOf(finalPosition.x, screenWidth - width - margin))
            val adjustedY = maxOf(margin, minOf(finalPosition.y, screenHeight - height - 80))

            val finalRect = Rect(adjustedX, adjustedY, adjustedX + width, adjustedY + height)
            occupiedAreas.add(finalRect)

            android.util.Log.d("OverlayManager", "Found position at ($adjustedX, $adjustedY) with score $bestScore")
            android.graphics.Point(adjustedX, adjustedY)

        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Error finding optimal position", e)
            val fallbackX = maxOf(10, minOf(originalBox.left, windowManager.defaultDisplay.width - 100))
            val fallbackY = maxOf(10, minOf(originalBox.bottom + 20, windowManager.defaultDisplay.height - 100))
            android.graphics.Point(fallbackX, fallbackY)
        }
    }

    /**
     * Genera candidatos de posición en orden de prioridad
     */
    private fun generatePositionCandidates(
        originalBox: Rect,
        width: Int,
        height: Int,
        margin: Int,
        screenWidth: Int,
        screenHeight: Int
    ): List<android.graphics.Point> {
        val candidates = mutableListOf<android.graphics.Point>()
        val spacing = 15

        // Prioridad 1: Debajo, alineado a la izquierda
        candidates.add(android.graphics.Point(
            originalBox.left,
            originalBox.bottom + spacing
        ))

        // Prioridad 2: Debajo, centrado
        candidates.add(android.graphics.Point(
            originalBox.centerX() - width / 2,
            originalBox.bottom + spacing
        ))

        // Prioridad 3: Arriba, alineado a la izquierda
        candidates.add(android.graphics.Point(
            originalBox.left,
            originalBox.top - height - spacing
        ))

        // Prioridad 4: Arriba, centrado
        candidates.add(android.graphics.Point(
            originalBox.centerX() - width / 2,
            originalBox.top - height - spacing
        ))

        // Prioridad 5: A la derecha
        candidates.add(android.graphics.Point(
            originalBox.right + spacing,
            originalBox.top
        ))

        // Prioridad 6: A la izquierda
        candidates.add(android.graphics.Point(
            originalBox.left - width - spacing,
            originalBox.top
        ))

        // Prioridad 7: Debajo a la derecha
        candidates.add(android.graphics.Point(
            originalBox.right - width,
            originalBox.bottom + spacing
        ))

        // Prioridad 8: Arriba a la derecha
        candidates.add(android.graphics.Point(
            originalBox.right - width,
            originalBox.top - height - spacing
        ))

        // Prioridad 9: Posiciones adicionales con más separación
        val largeSpacing = spacing * 3
        candidates.add(android.graphics.Point(
            originalBox.left,
            originalBox.bottom + largeSpacing
        ))
        candidates.add(android.graphics.Point(
            originalBox.left,
            originalBox.top - height - largeSpacing
        ))

        return candidates
    }

    private fun createDialogTranslationView(dialog: DialogBubble, translation: String) {
        try {
            val originalBox = dialog.boundingBox

            val textView = TextView(context).apply {
                text = translation
                setTextColor(android.graphics.Color.WHITE)

                when (dialog.estimatedType) {
                    DialogType.SPEECH_BUBBLE -> {
                        textSize = fontSize
                    }
                    DialogType.THOUGHT_BUBBLE -> {
                        textSize = fontSize * 0.9f
                    }
                    DialogType.NARRATIVE_TEXT -> {
                        textSize = fontSize * 0.85f
                    }
                    DialogType.SOUND_EFFECT -> {
                        textSize = fontSize * 1.1f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                }

                setPadding(30, 20, 30, 20)

                // Sombra más pronunciada para mejor legibilidad
                setShadowLayer(10f, 0f, 0f, android.graphics.Color.BLACK)

                // Configurar líneas según tamaño del diálogo
                maxLines = calculateMaxLines(dialog)
                ellipsize = android.text.TextUtils.TruncateAt.END

                // Mejor legibilidad
                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                setLineSpacing(6f, 1.2f)

                // Bordes redondeados mediante drawable
                background = createRoundedBackground(dialog.estimatedType)
            }

            // Posicionar el TextView de manera óptima
            val position = findOptimalPosition(originalBox, textView)

            // Ancho máximo basado en el texto original con padding adicional
            val maxWidth = minOf(
                originalBox.width() + 120,
                windowManager.defaultDisplay.width - 60
            )

            val layoutParams = FrameLayout.LayoutParams(
                maxWidth,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = position.x
                topMargin = position.y
            }

            translationView?.addView(textView, layoutParams)

            android.util.Log.d("OverlayManager",
                "Added translation at (${position.x}, ${position.y}): ${translation.take(30)}...")

        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Error creating dialog translation view", e)
        }
    }

    /**
     * Crea un drawable con bordes redondeados para mejor apariencia
     */
    private fun createRoundedBackground(dialogType: DialogType): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 32f // Bordes más redondeados para estilo moderno

            // Color oscuro uniforme mejorado (85% opacidad)
            setColor("#D9121212".toColorInt())

            // Borde sutil para mejor definición
            setStroke(3, "#40FFFFFF".toColorInt())
        }
    }

    private fun calculateMaxLines(dialog: DialogBubble): Int {
        return try {
            val height = dialog.boundingBox.height()
            val estimatedLineHeight = fontSize * 1.6f
            val maxLines = maxOf(2, minOf(6, (height / estimatedLineHeight).toInt()))

            // Ajustar según el tipo de diálogo
            when (dialog.estimatedType) {
                DialogType.SOUND_EFFECT -> minOf(maxLines, 2)
                DialogType.NARRATIVE_TEXT -> maxOf(maxLines, 3)
                else -> maxLines
            }
        } catch (e: Exception) {
            3 // Default seguro
        }
    }

    private fun distributeTranslationByBlocks(visionText: Text, translatedText: String) {
        try {
            val textBlocks = visionText.textBlocks
            if (textBlocks.isEmpty()) return

            val originalTexts = textBlocks.map { it.text.trim() }.filter { it.isNotEmpty() }
            if (originalTexts.isEmpty()) return

            val totalOriginalLength = originalTexts.sumOf { it.length }
            if (totalOriginalLength == 0) return

            val translatedParts = mutableListOf<String>()
            var translatedIndex = 0

            for (i in originalTexts.indices) {
                val originalText = originalTexts[i]

                if (i == originalTexts.lastIndex) {
                    // Último bloque: tomar todo lo que queda
                    val remaining = translatedText.substring(translatedIndex).trim()
                    if (remaining.isNotEmpty()) {
                        translatedParts.add(remaining)
                    }
                } else {
                    val proportion = originalText.length.toDouble() / totalOriginalLength
                    val estimatedLength = (translatedText.length * proportion).toInt()

                    val endIndex = findAppropriateBreakPoint(
                        translatedText,
                        translatedIndex,
                        translatedIndex + estimatedLength
                    )

                    val part = translatedText.substring(translatedIndex, endIndex).trim()
                    if (part.isNotEmpty()) {
                        translatedParts.add(part)
                    }
                    translatedIndex = endIndex
                }
            }

            // Crear TextViews para cada parte
            val validBlocks = textBlocks.filter { it.boundingBox != null }
            for (i in validBlocks.indices) {
                val textBlock = validBlocks[i]
                val boundingBox = textBlock.boundingBox!!

                if (i < translatedParts.size) {
                    val translatedPart = translatedParts[i]
                    if (translatedPart.isNotEmpty()) {
                        createTranslationTextView(translatedPart, boundingBox.left, boundingBox.top, boundingBox)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Error distributing translation by blocks", e)
        }
    }

    private fun findAppropriateBreakPoint(text: String, startIndex: Int, estimatedEndIndex: Int): Int {
        return try {
            if (estimatedEndIndex >= text.length) return text.length
            if (estimatedEndIndex <= startIndex) return minOf(startIndex + 1, text.length)

            val searchRange = minOf(30, (text.length - startIndex) / 3)

            // Buscar hacia adelante primero (puntuación fuerte)
            for (i in estimatedEndIndex until minOf(estimatedEndIndex + searchRange, text.length)) {
                if (text[i] in ".!?。！？") {
                    return minOf(i + 1, text.length)
                }
            }

            // Buscar puntuación más débil hacia adelante
            for (i in estimatedEndIndex until minOf(estimatedEndIndex + searchRange, text.length)) {
                if (text[i] in ",;:，；：") {
                    return minOf(i + 1, text.length)
                }
            }

            // Buscar espacios hacia adelante
            for (i in estimatedEndIndex until minOf(estimatedEndIndex + searchRange, text.length)) {
                if (text[i] in " \n\t") {
                    return minOf(i + 1, text.length)
                }
            }

            // Buscar hacia atrás con puntuación fuerte
            for (i in estimatedEndIndex downTo maxOf(startIndex + 1, estimatedEndIndex - searchRange)) {
                if (text[i] in ".!?。！？") {
                    return minOf(i + 1, text.length)
                }
            }

            // Buscar hacia atrás con puntuación débil
            for (i in estimatedEndIndex downTo maxOf(startIndex + 1, estimatedEndIndex - searchRange)) {
                if (text[i] in ",;: \n\t，；：") {
                    return minOf(i + 1, text.length)
                }
            }

            // Si no encuentra nada, usar la estimación original
            minOf(estimatedEndIndex, text.length)
        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Error finding break point", e)
            minOf(estimatedEndIndex, text.length)
        }
    }

    private fun createTranslationTextView(text: String, x: Int, y: Int, originalBounds: Rect? = null) {
        try {
            if (text.trim().isEmpty()) return

            val textView = TextView(context).apply {
                this.text = text.trim()
                setTextColor(android.graphics.Color.WHITE)
                textSize = fontSize
                setPadding(30, 20, 30, 20)
                setShadowLayer(10f, 0f, 0f, android.graphics.Color.BLACK)
                maxLines = 4
                ellipsize = android.text.TextUtils.TruncateAt.END
                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                setLineSpacing(6f, 1.2f)

                // Usar drawable redondeado
                background = createRoundedBackground(DialogType.SPEECH_BUBBLE)
            }

            // Medir con límites más realistas
            val maxWidth = originalBounds?.width()?.plus(40) ?: (windowManager.defaultDisplay.width / 2)
            val constrainedWidth = minOf(maxWidth, windowManager.defaultDisplay.width - 40)

            val widthSpec = View.MeasureSpec.makeMeasureSpec(constrainedWidth, View.MeasureSpec.AT_MOST)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

            textView.measure(widthSpec, heightSpec)

            val width = textView.measuredWidth
            val height = textView.measuredHeight

            // Encontrar posición sin colisiones
            var newX = x
            var newY = y
            val screenWidth = windowManager.defaultDisplay.width
            val screenHeight = windowManager.defaultDisplay.height

            // Asegurar que esté dentro de los límites de pantalla
            newX = maxOf(10, minOf(newX, screenWidth - width - 10))
            newY = maxOf(10, minOf(newY, screenHeight - height - 100))

            // Verificar colisiones y ajustar si es necesario
            var attempts = 0
            val maxAttempts = 8
            val offset = 25

            while (attempts < maxAttempts) {
                val testRect = Rect(newX, newY, newX + width, newY + height)
                val hasCollision = occupiedAreas.any { Rect.intersects(it, testRect) }

                if (!hasCollision) {
                    break
                }

                // Mover la posición para evitar colisión
                when (attempts % 4) {
                    0 -> newY += offset  // Mover abajo
                    1 -> newY -= offset  // Mover arriba
                    2 -> newX += offset  // Mover derecha
                    3 -> newX -= offset  // Mover izquierda
                }

                // Asegurar que sigue dentro de los límites
                newX = maxOf(10, minOf(newX, screenWidth - width - 10))
                newY = maxOf(10, minOf(newY, screenHeight - height - 100))

                attempts++
            }

            val finalRect = Rect(newX, newY, newX + width, newY + height)
            occupiedAreas.add(finalRect)

            val layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = newX
                topMargin = newY
            }

            translationView?.addView(textView, layoutParams)
            android.util.Log.d("OverlayManager", "Added text view at ($newX, $newY) after $attempts attempts")

        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Error creating translation text view", e)
        }
    }

    fun hideTranslationOverlay() {
        try {
            android.util.Log.d("OverlayManager", "Hiding translation overlay")

            // Cancelar dismissal programado
            translationDismissRunnable?.let { handler.removeCallbacks(it) }
            translationDismissRunnable = null

            // Marcar como no mostrando
            isTranslationShowing = false

            // Limpiar áreas ocupadas
            occupiedAreas.clear()

            // Remover vista si existe
            translationView?.let { view ->
                try {
                    windowManager.removeView(view)
                    android.util.Log.d("OverlayManager", "Translation view removed from window manager")
                } catch (e: Exception) {
                    android.util.Log.w("OverlayManager", "Translation view already removed or not attached", e)
                }
            }

            translationView = null
            translationParams = null

        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Error hiding translation overlay", e)
        }
    }

    // Métodos de manejo de errores mejorados
    fun handleProcessingError(error: Throwable?) {
        android.util.Log.e("OverlayManager", "Processing error occurred", error)

        // Cancelar todas las operaciones pendientes
        cancelPendingOperations()

        // Limpiar estados
        isTranslationShowing = false
        hideTranslationOverlay()

        // Forzar restauración inmediata
        forceRestoreOverlays()
    }

    fun handleNoTextDetected() {
        android.util.Log.d("OverlayManager", "No text detected, restoring overlays")

        // Cancelar operaciones pendientes
        cancelPendingOperations()

        // Restaurar overlays inmediatamente
        restoreOverlaysAfterCapture()
    }

    // Estado y getters mejorados
    fun isCurrentlyCapturing(): Boolean = isCapturing

    fun isTranslationCurrentlyShowing(): Boolean = isTranslationShowing

    // Callbacks y setters
    fun setOnCaptureClickListener(listener: () -> Unit) {
        onCaptureClickListener = listener
    }

    fun setOnStopClickListener(listener: () -> Unit) {
        onStopClickListener = listener
    }

    fun setOnLanguageChangeListener(listener: (String, String) -> Unit) {
        onLanguageChangeListener = listener
    }

    fun getFontSize(): Float = fontSize

    fun setFontSize(size: Float) {
        fontSize = size.coerceIn(10f, 28f) // Rango más amplio
    }

    /**
     * Método público para verificar y restaurar estado si es necesario
     */
    fun ensureOverlaysVisible() {
        try {
            if (isCapturing) {
                val captureTime = System.currentTimeMillis() - lastCaptureTime
                if (captureTime > FORCE_RESTORE_TIMEOUT) {
                    android.util.Log.w("OverlayManager", "Overlays stuck in capturing state for ${captureTime}ms, forcing restore")
                    forceRestoreOverlays()
                }
            }

            bottomBarView?.let { view ->
                if (view.visibility != View.VISIBLE && !isCapturing) {
                    android.util.Log.d("OverlayManager", "Bottom bar not visible but not capturing, making it visible")
                    view.visibility = View.VISIBLE
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Error ensuring overlays visible", e)
        }
    }

    /**
     * Método de limpieza mejorado con mejor manejo de recursos
     */
    fun cleanup() {
        android.util.Log.d("OverlayManager", "Starting comprehensive cleanup")

        try {
            // Desactivar lectura continua
            deactivateContinuousReading()

            // Cancelar todos los callbacks pendientes
            handler.removeCallbacksAndMessages(null)
            cancelPendingOperations()

            // Cancelar long press
            longPressRunnable?.let { handler.removeCallbacks(it) }
            longPressRunnable = null

            // Cancelar fade out
            fadeOutRunnable?.let { fadeOutHandler.removeCallbacks(it) }
            fadeOutRunnable = null

            // Ocultar overlay de traducción
            hideTranslationOverlay()

            // Remover vistas del window manager con mejor manejo de errores
            bottomBarView?.let { view ->
                try {
                    if (view.parent != null) {
                        windowManager.removeView(view)
                        android.util.Log.d("OverlayManager", "Bottom bar view removed successfully")
                    }
                } catch (e: IllegalArgumentException) {
                    android.util.Log.w("OverlayManager", "Bottom bar view was not attached to window manager", e)
                } catch (e: Exception) {
                    android.util.Log.e("OverlayManager", "Error removing bottom bar view", e)
                }
            }

            translationView?.let { view ->
                try {
                    if (view.parent != null) {
                        windowManager.removeView(view)
                        android.util.Log.d("OverlayManager", "Translation view removed successfully")
                    }
                } catch (e: IllegalArgumentException) {
                    android.util.Log.w("OverlayManager", "Translation view was not attached to window manager", e)
                } catch (e: Exception) {
                    android.util.Log.e("OverlayManager", "Error removing translation view", e)
                }
            }

        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Error during cleanup", e)
        } finally {
            // Limpiar todas las referencias y estado
            bottomBarView = null
            captureButton = null
            translationView = null
            translationParams = null
            restoreRunnable = null
            translationDismissRunnable = null
            continuousReadingRunnable = null
            longPressRunnable = null
            fadeOutRunnable = null

            // Resetear estado
            isCapturing = false
            isTranslationShowing = false
            isContinuousReading = false
            lastCaptureTime = 0L
            translationStartTime = 0L
            buttonPressStartTime = 0L

            // Limpiar listas
            occupiedAreas.clear()

            // Limpiar callbacks
            onCaptureClickListener = null
            onStopClickListener = null
            onLanguageChangeListener = null

            android.util.Log.d("OverlayManager", "Cleanup completed successfully")
        }
    }
}