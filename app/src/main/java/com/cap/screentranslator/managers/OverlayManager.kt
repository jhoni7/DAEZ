package com.cap.screentranslator.managers

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.cap.screentranslator.R
import com.google.mlkit.vision.text.Text
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

    // Views para el overlay de traducción
    private var translationView: FrameLayout? = null

    private val occupiedAreas = mutableListOf<Rect>()

    // Callbacks
    private var onCaptureClickListener: (() -> Unit)? = null
    private var onStopClickListener: (() -> Unit)? = null
    private var onLanguageChangeListener: ((String, String) -> Unit)? = null

    // Estado
    private var fontSize = 10f
    private var isCapturing = false

    // Configuraciones para detección de globos de diálogo
    companion object {
        private const val MIN_DIALOG_WIDTH = 100
        private const val MIN_DIALOG_HEIGHT = 30
        private const val PROXIMITY_THRESHOLD = 50
        private const val LINE_HEIGHT_THRESHOLD = 1.5f
        private const val MAX_DIALOG_ASPECT_RATIO = 8f
        private const val MIN_WORDS_PER_DIALOG = 2
    }

    data class DialogBubble(
        val boundingBox: Rect,
        val textElements: List<Text.Element>,
        val lines: List<Text.Line>,
        val confidence: Float,
        val estimatedType: DialogType
    )

    enum class DialogType {
        SPEECH_BUBBLE,    // Globo de diálogo normal
        THOUGHT_BUBBLE,   // Globo de pensamiento
        NARRATIVE_TEXT,   // Texto narrativo
        SOUND_EFFECT      // Efectos de sonido
    }

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
            y = 100
        }

        windowManager.addView(bottomBarView, bottomBarParams)
    }

    private fun setupBottomBarButtons() {
        captureButton?.setOnClickListener {
            startCaptureSequence()
        }
    }

    private fun startCaptureSequence() {
        if (isCapturing) return
        isCapturing = true
        hideAllOverlaysTemporarily()
        handler.postDelayed({
            onCaptureClickListener?.invoke()
        }, 200)
    }

    private fun hideAllOverlaysTemporarily() {
        bottomBarView?.visibility = View.GONE
        translationView?.visibility = View.GONE
    }

    fun restoreOverlaysAfterCapture() {
        isCapturing = false
        handler.postDelayed({
            bottomBarView?.visibility = View.VISIBLE
        }, 300)
    }

    /**
     * Método principal mejorado para mostrar traducciones de globos de diálogo
     */
    fun showTranslationOverlay(visionText: Text, translatedTexts: List<String>) {
        hideTranslationOverlay()

        if (visionText.textBlocks.isEmpty()) {
            restoreOverlaysAfterCapture()
            return
        }

        translationView = FrameLayout(context)

        // Detectar y procesar globos de diálogo
        val dialogBubbles = detectDialogBubbles(visionText)

        // Crear TextViews optimizados para cada globo
        createOptimizedTranslationViews(dialogBubbles, translatedTexts)

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
        }, 12000) // Aumentado a 12 segundos para diálogos más complejos
    }

    /**
     * Detecta globos de diálogo analizando la estructura del texto
     */
    private fun detectDialogBubbles(visionText: Text): List<DialogBubble> {
        val dialogBubbles = mutableListOf<DialogBubble>()
        val processedElements = mutableSetOf<Text.Element>()

        for (textBlock in visionText.textBlocks) {
            val blockBubbles = analyzeTextBlockForDialogs(textBlock, processedElements)
            dialogBubbles.addAll(blockBubbles)
        }

        // Filtrar y fusionar globos cercanos
        return mergeNearbyDialogs(filterValidDialogs(dialogBubbles))
    }

    /**
     * Analiza un bloque de texto en busca de globos de diálogo
     */
    private fun analyzeTextBlockForDialogs(
        textBlock: Text.TextBlock,
        processedElements: MutableSet<Text.Element>
    ): List<DialogBubble> {
        val dialogs = mutableListOf<DialogBubble>()
        val blockElements = mutableListOf<Text.Element>()
        val blockLines = mutableListOf<Text.Line>()

        // Recopilar elementos y líneas del bloque
        for (line in textBlock.lines) {
            blockLines.add(line)
            for (element in line.elements) {
                if (!processedElements.contains(element)) {
                    blockElements.add(element)
                    processedElements.add(element)
                }
            }
        }

        if (blockElements.isEmpty()) return dialogs

        // Agrupar elementos por proximidad y características
        val elementGroups = groupElementsByProximity(blockElements)

        for (group in elementGroups) {
            val dialog = createDialogFromElements(group, blockLines.filter { line ->
                line.elements.any { element -> group.contains(element) }
            })

            if (dialog != null) {
                dialogs.add(dialog)
            }
        }

        return dialogs
    }

    /**
     * Agrupa elementos de texto por proximidad espacial
     */
    private fun groupElementsByProximity(elements: List<Text.Element>): List<List<Text.Element>> {
        if (elements.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<Text.Element>>()
        val processed = mutableSetOf<Text.Element>()

        for (element in elements) {
            if (processed.contains(element)) continue

            val group = mutableListOf<Text.Element>()
            val queue = mutableListOf(element)

            while (queue.isNotEmpty()) {
                val current = queue.removeAt(0)
                if (processed.contains(current)) continue

                processed.add(current)
                group.add(current)

                // Buscar elementos cercanos
                for (other in elements) {
                    if (!processed.contains(other) && areElementsClose(current, other)) {
                        queue.add(other)
                    }
                }
            }

            if (group.size >= MIN_WORDS_PER_DIALOG) {
                groups.add(group)
            }
        }

        return groups
    }

    /**
     * Determina si dos elementos están lo suficientemente cerca para pertenecer al mismo globo
     */
    private fun areElementsClose(element1: Text.Element, element2: Text.Element): Boolean {
        val box1 = element1.boundingBox ?: return false
        val box2 = element2.boundingBox ?: return false

        val centerX1 = box1.centerX()
        val centerY1 = box1.centerY()
        val centerX2 = box2.centerX()
        val centerY2 = box2.centerY()

        val distance = sqrt((centerX2 - centerX1).toDouble().pow(2) + (centerY2 - centerY1).toDouble().pow(2))

        // La distancia máxima depende del tamaño de los elementos
        val maxDistance = max(box1.height(), box2.height()) * 2.5

        return distance <= maxOf(PROXIMITY_THRESHOLD.toDouble(), maxDistance)
    }

    /**
     * Crea un DialogBubble a partir de elementos agrupados
     */
    private fun createDialogFromElements(
        elements: List<Text.Element>,
        lines: List<Text.Line>
    ): DialogBubble? {
        if (elements.isEmpty()) return null

        // Calcular bounding box combinado
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

        // Determinar tipo de diálogo
        val dialogType = determineDialogType(elements, combinedBox)

        return DialogBubble(
            boundingBox = combinedBox,
            textElements = elements,
            lines = lines,
            confidence = avgConfidence,
            estimatedType = dialogType
        )
    }

    /**
     * Determina el tipo de globo de diálogo basado en características del texto
     */
    private fun determineDialogType(elements: List<Text.Element>, boundingBox: Rect): DialogType {
        val combinedText = elements.joinToString(" ") { it.text }
        val aspectRatio = boundingBox.width().toFloat() / boundingBox.height().toFloat()

        return when {
            // Efectos de sonido (texto corto, mayúsculas, caracteres especiales)
            combinedText.length <= 10 && combinedText.any { it in "!*#@" } -> DialogType.SOUND_EFFECT

            // Texto narrativo (aspecto muy ancho, muchas palabras)
            aspectRatio > MAX_DIALOG_ASPECT_RATIO && elements.size > 8 -> DialogType.NARRATIVE_TEXT

            // Globo de pensamiento (texto introspectivo, palabras clave)
            combinedText.contains(Regex("(think|thought|wonder|maybe|perhaps)", RegexOption.IGNORE_CASE)) -> DialogType.THOUGHT_BUBBLE

            // Por defecto: globo de diálogo
            else -> DialogType.SPEECH_BUBBLE
        }
    }

    /**
     * Filtra globos válidos basado en tamaño y contenido
     */
    private fun filterValidDialogs(dialogs: List<DialogBubble>): List<DialogBubble> {
        return dialogs.filter { dialog ->
            val box = dialog.boundingBox
            val text = dialog.textElements.joinToString(" ") { it.text }.trim()

            box.width() >= MIN_DIALOG_WIDTH &&
                    box.height() >= MIN_DIALOG_HEIGHT &&
                    text.isNotBlank() &&
                    text.length > 1 &&
                    dialog.confidence > 0.3f
        }
    }

    /**
     * Fusiona globos de diálogo que están muy cerca uno del otro
     */
    private fun mergeNearbyDialogs(dialogs: List<DialogBubble>): List<DialogBubble> {
        if (dialogs.size <= 1) return dialogs

        val merged = mutableListOf<DialogBubble>()
        val processed = mutableSetOf<DialogBubble>()

        for (dialog in dialogs) {
            if (processed.contains(dialog)) continue

            val closeDialogs = mutableListOf(dialog)
            processed.add(dialog)

            // Buscar otros globos cercanos
            for (other in dialogs) {
                if (!processed.contains(other) && areDialogsClose(dialog, other)) {
                    closeDialogs.add(other)
                    processed.add(other)
                }
            }

            // Crear globo fusionado si es necesario
            if (closeDialogs.size > 1) {
                val mergedDialog = mergeDialogs(closeDialogs)
                if (mergedDialog != null) {
                    merged.add(mergedDialog)
                }
            } else {
                merged.add(dialog)
            }
        }

        return merged
    }

    /**
     * Determina si dos globos están lo suficientemente cerca para fusionarse
     */
    private fun areDialogsClose(dialog1: DialogBubble, dialog2: DialogBubble): Boolean {
        val box1 = dialog1.boundingBox
        val box2 = dialog2.boundingBox

        val distance = sqrt(
            (box2.centerX() - box1.centerX()).toDouble().pow(2) +
                    (box2.centerY() - box1.centerY()).toDouble().pow(2)
        )

        val threshold = max(box1.height(), box2.height()) * 1.5
        return distance <= threshold
    }

    /**
     * Fusiona múltiples globos en uno solo
     */
    private fun mergeDialogs(dialogs: List<DialogBubble>): DialogBubble? {
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

        return DialogBubble(
            boundingBox = Rect(minX, minY, maxX, maxY),
            textElements = allElements.distinct(),
            lines = allLines.distinct(),
            confidence = totalConfidence / dialogs.size,
            estimatedType = dialogs.first().estimatedType
        )
    }

    /**
     * Crea TextViews optimizados para cada globo de diálogo
     */
    private fun createOptimizedTranslationViews(
        dialogBubbles: List<DialogBubble>,
        translatedTexts: List<String>
    ) {
        occupiedAreas.clear()

        for (i in dialogBubbles.indices) {
            val dialog = dialogBubbles[i]
            val translation = translatedTexts.getOrNull(i)?.trim()

            if (!translation.isNullOrEmpty()) {
                createDialogTranslationView(dialog, translation)
            }
        }
    }

    /**
     * Crea un TextView especializado para un globo de diálogo específico
     */
    private fun createDialogTranslationView(dialog: DialogBubble, translation: String) {
        val originalBox = dialog.boundingBox

        val textView = TextView(context).apply {
            text = translation
            setTextColor(android.graphics.Color.WHITE)

            // Personalizar estilo según el tipo de globo
            when (dialog.estimatedType) {
                DialogType.SPEECH_BUBBLE -> {
                    setBackgroundColor(android.graphics.Color.parseColor("#CC1976D2")) // Azul para diálogo
                    textSize = fontSize
                }
                DialogType.THOUGHT_BUBBLE -> {
                    setBackgroundColor(android.graphics.Color.parseColor("#CC9C27B0")) // Púrpura para pensamientos
                    textSize = fontSize * 0.9f
                }
                DialogType.NARRATIVE_TEXT -> {
                    setBackgroundColor(android.graphics.Color.parseColor("#CC424242")) // Gris para narrativa
                    textSize = fontSize * 0.85f
                }
                DialogType.SOUND_EFFECT -> {
                    setBackgroundColor(android.graphics.Color.parseColor("#CCF57C00")) // Naranja para efectos
                    textSize = fontSize * 1.1f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
            }

            setPadding(16, 12, 16, 12)
            setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK)
            maxLines = calculateMaxLines(dialog)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        // Posicionar el TextView
        val position = findOptimalPosition(originalBox, textView)

        val layoutParams = FrameLayout.LayoutParams(
            minOf(originalBox.width() + 40, windowManager.defaultDisplay.width - 40),
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = position.x
            topMargin = position.y
        }

        translationView?.addView(textView, layoutParams)
    }

    /**
     * Calcula el número máximo de líneas basado en el tamaño del globo original
     */
    private fun calculateMaxLines(dialog: DialogBubble): Int {
        val height = dialog.boundingBox.height()
        val estimatedLineHeight = fontSize * 1.5f
        return maxOf(2, minOf(8, (height / estimatedLineHeight).toInt()))
    }

    /**
     * Encuentra la posición óptima para mostrar la traducción
     */
    private fun findOptimalPosition(originalBox: Rect, textView: TextView): android.graphics.Point {
        textView.measure(
            View.MeasureSpec.UNSPECIFIED,
            View.MeasureSpec.UNSPECIFIED
        )

        val width = textView.measuredWidth
        val height = textView.measuredHeight

        // Intentar diferentes posiciones
        val candidates = listOf(
            android.graphics.Point(originalBox.left, originalBox.bottom + 10), // Debajo
            android.graphics.Point(originalBox.left, originalBox.top - height - 10), // Encima
            android.graphics.Point(originalBox.right + 10, originalBox.top), // Derecha
            android.graphics.Point(originalBox.left - width - 10, originalBox.top), // Izquierda
            android.graphics.Point(originalBox.left, originalBox.top) // Superpuesto (último recurso)
        )

        for (candidate in candidates) {
            val candidateRect = Rect(candidate.x, candidate.y, candidate.x + width, candidate.y + height)

            // Verificar que esté dentro de la pantalla
            if (candidateRect.left >= 0 && candidateRect.top >= 0 &&
                candidateRect.right <= windowManager.defaultDisplay.width &&
                candidateRect.bottom <= windowManager.defaultDisplay.height) {

                // Verificar que no colisione con otras traducciones
                if (!occupiedAreas.any { it.intersect(candidateRect) }) {
                    occupiedAreas.add(candidateRect)
                    return candidate
                }
            }
        }

        // Si no se encuentra una posición ideal, usar la primera válida
        val fallback = candidates.first()
        occupiedAreas.add(Rect(fallback.x, fallback.y, fallback.x + width, fallback.y + height))
        return fallback
    }

    // Método sobrecargado para mantener compatibilidad
    fun showTranslationOverlay(visionText: Text, translatedText: String) {
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

    private fun distributeTranslationByBlocks(visionText: Text, translatedText: String) {
        val textBlocks = visionText.textBlocks
        val originalTexts = textBlocks.map { it.text.trim() }

        val totalOriginalLength = originalTexts.sumOf { it.length }
        if (totalOriginalLength == 0) return

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

        for (i in estimatedEndIndex until minOf(estimatedEndIndex + searchRange, text.length)) {
            if (text[i] in " .!?;,\n") {
                return i + 1
            }
        }

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
        var newRect: Rect

        do {
            newRect = Rect(newX, newY, newX + width, newY + height)
            val collides = occupiedAreas.any { it.intersect(newRect) }

            if (collides) {
                newY -= offset
                newX += offset
            } else {
                break
            }

            attempts++
        } while (attempts < maxAttempts)

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
        fontSize = size
    }

    fun forceRestoreOverlays() {
        restoreOverlaysAfterCapture()
    }

    fun cleanup() {
        handler.removeCallbacksAndMessages(null)

        try {
            bottomBarView?.let { windowManager.removeView(it) }
            translationView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            // Views already removed
        }

        bottomBarView = null
        translationView = null
        isCapturing = false
        occupiedAreas.clear()
    }
}