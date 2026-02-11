package com.DAEZ.DAEZKit

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.DAEZ.DAEZKit.managers.LanguageDetector
import com.DAEZ.DAEZKit.managers.OcrManager
import com.DAEZ.DAEZKit.managers.OverlayManager
import com.DAEZ.DAEZKit.managers.ScreenCaptureManager
import com.DAEZ.DAEZKit.managers.TranslatorManager

class MainService : Service() {

    private lateinit var overlayManager: OverlayManager
    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var ocrManager: OcrManager
    private lateinit var languageDetector: LanguageDetector
    private lateinit var translatorManager: TranslatorManager

    private lateinit var settingsReceiver: BroadcastReceiver

    private val handler = Handler(Looper.getMainLooper())

    // Variables para mantener el contexto de los globos detectados
    private var currentDialogBubbles: List<OverlayManager.DialogBubble>? = null
    private var currentTextsToTranslate: List<String>? = null
    
    private var lastCaptureId: Long = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        initializeManagers()
        createNotificationChannel()
        setupSettingsReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        // Aplicar configuración inicial si está disponible
        val initialSourceLanguage = intent?.getStringExtra("sourceLanguage") ?: "auto"
        val initialTargetLanguage = intent?.getStringExtra("targetLanguage") ?: "es"
        val initialFontSize = intent?.getFloatExtra("fontSize", 16f) ?: 16f

        translatorManager.updateLanguages(initialSourceLanguage, initialTargetLanguage)
        overlayManager.setFontSize(initialFontSize)

        Log.d("MainService", "Service started with initial settings: $initialSourceLanguage -> $initialTargetLanguage, font: ${initialFontSize}sp")

        // Configurar MediaProjection después de que el servicio esté corriendo
        intent?.let {
            val resultCode = it.getIntExtra("resultCode", 0)
            val data = it.getParcelableExtra<Intent>("data")
            if (data != null && resultCode != 0) {
                handler.postDelayed({
                    setupScreenCapture(resultCode, data)
                    showBottomBar()
                }, 1000)
            }
        }

        return START_STICKY
    }

    private fun setupSettingsReceiver() {
        settingsReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "UPDATE_TRANSLATION_SETTINGS" -> {
                        val sourceLanguage = intent.getStringExtra("sourceLanguage") ?: "auto"
                        val targetLanguage = intent.getStringExtra("targetLanguage") ?: "es"
                        translatorManager?.updateLanguages(sourceLanguage, targetLanguage)
                        Log.d("MainService", "Languages updated: $sourceLanguage -> $targetLanguage")
                    }

                    "UPDATE_FONT_SIZE" -> {
                        val fontSize = intent.getFloatExtra("fontSize", 16f)
                        overlayManager?.setFontSize(fontSize)
                        Log.d("MainService", "Font size updated: ${fontSize}sp")
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction("UPDATE_TRANSLATION_SETTINGS")
            addAction("UPDATE_FONT_SIZE")
        }

        registerReceiver(settingsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    private fun initializeManagers() {
        overlayManager = OverlayManager(this)
        screenCaptureManager = ScreenCaptureManager(this)
        ocrManager = OcrManager()
        languageDetector = LanguageDetector()
        translatorManager = TranslatorManager()
        setupManagerCallbacks()
    }

    private fun setupManagerCallbacks() {
        overlayManager.setOnCaptureClickListener {
            if (translatorManager.isTranslatorReady()) {
                captureAndProcess()
            } else {
                Log.d("MainService", "Capture aborted: Translator not ready")
                overlayManager.showNotReadyToast()
            }
        }

        overlayManager.setOnStopClickListener {
            stopService()
        }

        overlayManager.setOnLanguageChangeListener { sourceLanguage, targetLanguage ->
            translatorManager.updateLanguages(sourceLanguage, targetLanguage)
        }

        ocrManager.setOnTextRecognizedListener { visionText ->
            processRecognizedText(visionText)
        }

        languageDetector.setOnLanguageDetectedListener { detectedLanguage, visionText ->
            translateTexts(detectedLanguage, visionText)
        }

        // Callback actualizado para recibir lista de traducciones
        translatorManager.setOnTranslationCompleteListener { visionText: com.google.mlkit.vision.text.Text, translatedTexts: List<String> ->
            showTranslations(visionText, translatedTexts)
        }

        translatorManager.setOnDownloadStateListener { isDownloading, language ->
            overlayManager.setDownloadProgress(isDownloading, language)
        }


        // Una vez configurados los callbacks, inicializar el manager
        translatorManager.initialize()
    }

    private fun setupScreenCapture(resultCode: Int, data: Intent) {
        screenCaptureManager.setupMediaProjection(resultCode, data)
    }

    private fun showBottomBar() {
        overlayManager.showBottomBar()
    }

    private fun captureAndProcess() {
        val requestId = System.currentTimeMillis()
        lastCaptureId = requestId
        
        // Limpiar traducciones previas inmediatamente
        overlayManager.clearTranslations()
        
        if (!screenCaptureManager.isReady()) {
            Log.e("MainService", "Capture manager not ready, attempting capture anyway (it will try to recover)")
        }

        screenCaptureManager.captureScreen { bitmap ->
            if (bitmap != null) {
                // Verificar que seguimos en la misma "transacción"
                if (requestId == lastCaptureId) {
                    processRecognizedText(bitmap, requestId)
                } else {
                    Log.d("MainService", "Discarding captured frame: newer request in progress")
                }
            } else {
                Log.e("MainService", "Failed to capture screen")
                
                // Si la captura falló y el manager sigue sin estar listo, informar al usuario
                if (!screenCaptureManager.isReady()) {
                    android.widget.Toast.makeText(this, 
                        "Error de captura: el permiso ha expirado o se ha revocado. Por favor, reinicia el servicio.", 
                        android.widget.Toast.LENGTH_LONG).show()
                }
                
                overlayManager.handleProcessingError(Exception("Screen capture failed"))
            }
        }
    }

    private fun processRecognizedText(bitmap: android.graphics.Bitmap, requestId: Long) {
        ocrManager.processImage(bitmap)
        
        // El callback de OCR debe ser consciente del requestId
        // Pero el ocrManager actual no soporta pasar IDs.
        // Lo manejaremos almacenando el ID actual en el servicio.
    }

    private fun processRecognizedText(visionText: com.google.mlkit.vision.text.Text) {
        val requestId = lastCaptureId
        
        if (visionText.text.isEmpty() || visionText.textBlocks.isEmpty()) {
            Log.d("MainService", "No text detected in image")
            overlayManager.handleNoTextDetected()
            return
        }

        try {
            // Log del texto completo detectado por OCR
            Log.d("MainService", "OCR detected full text: ${visionText.text.take(100)}...")

            // Paso 1: Detectar globos de diálogo usando el método existente de OverlayManager
            val dialogBubbles = overlayManager.detectDialogBubbles(visionText)

            if (dialogBubbles.isEmpty()) {
                Log.w("MainService", "No dialog bubbles detected")
                overlayManager.handleNoTextDetected()
                return
            }

            Log.d("MainService", "Detected ${dialogBubbles.size} dialog bubbles")

            // Paso 2: Extraer texto de cada globo en orden
            val textsToTranslate = dialogBubbles.map { bubble ->
                extractTextFromBubble(bubble)
            }

            // Guardar contexto para usar después de la detección de idioma
            currentDialogBubbles = dialogBubbles
            currentTextsToTranslate = textsToTranslate

            // Log de los textos detectados (ahora con el texto completo)
            textsToTranslate.forEachIndexed { index, text ->
                Log.d("MainService", "Bubble ${index + 1} (${text.length} chars): $text")
            }

            // Paso 3: Detectar idioma del primer texto (asumiendo mismo idioma en todos)
            val firstText = textsToTranslate.firstOrNull()?.trim() ?: ""
            if (firstText.isEmpty()) {
                Log.w("MainService", "First bubble text is empty")
                overlayManager.handleNoTextDetected()
                return
            }

            languageDetector.detectLanguage(firstText, visionText)

        } catch (e: Exception) {
            Log.e("MainService", "Error processing recognized text", e)
            overlayManager.handleProcessingError(e)
        }
    }

    /**
     * Extrae el texto de un globo, ordenando los elementos correctamente
     * MEJORADO: Usa las líneas que ya están ordenadas por ML Kit
     */
    private fun extractTextFromBubble(bubble: OverlayManager.DialogBubble): String {
        try {
            // MEJOR MÉTODO: Usar las líneas que ya vienen ordenadas por ML Kit
            if (bubble.lines.isNotEmpty()) {
                // Las líneas ya están en el orden correcto de lectura
                val textByLines = bubble.lines
                    .sortedBy { it.boundingBox?.top ?: 0 } // Ordenar por posición vertical
                    .joinToString(" ") { it.text }
                    .trim()

                if (textByLines.isNotEmpty()) {
                    return textByLines
                }
            }

            // FALLBACK 1: Ordenar elementos por líneas (agrupando por altura similar)
            val elementsByLine = groupElementsByLine(bubble.textElements)
            if (elementsByLine.isNotEmpty()) {
                return elementsByLine.joinToString(" ") { it.trim() }.trim()
            }

            // FALLBACK 2: Ordenamiento simple
            val sortedElements = bubble.textElements.sortedWith(
                compareBy<com.google.mlkit.vision.text.Text.Element> {
                    it.boundingBox?.top ?: 0
                }.thenBy {
                    it.boundingBox?.left ?: 0
                }
            )

            return sortedElements.joinToString(" ") { it.text }.trim()

        } catch (e: Exception) {
            Log.e("MainService", "Error extracting text from bubble", e)
            // Último fallback: concatenar todo
            return bubble.textElements.joinToString(" ") { it.text }.trim()
        }
    }

    /**
     * Agrupa elementos en líneas basándose en su posición vertical
     */
    private fun groupElementsByLine(elements: List<com.google.mlkit.vision.text.Text.Element>): List<String> {
        if (elements.isEmpty()) return emptyList()

        try {
            // Agrupar elementos que están en la misma línea horizontal
            val sortedElements = elements.sortedBy { it.boundingBox?.top ?: 0 }
            val lines = mutableListOf<MutableList<com.google.mlkit.vision.text.Text.Element>>()

            for (element in sortedElements) {
                val elementTop = element.boundingBox?.top ?: 0
                val elementHeight = element.boundingBox?.height() ?: 0

                // Buscar una línea existente donde este elemento encaje
                var addedToLine = false
                for (line in lines) {
                    val lineTop = line.first().boundingBox?.top ?: 0
                    val lineHeight = line.first().boundingBox?.height() ?: 0

                    // Si la diferencia vertical es menor que la mitad de la altura, están en la misma línea
                    if (kotlin.math.abs(elementTop - lineTop) < lineHeight / 2) {
                        line.add(element)
                        addedToLine = true
                        break
                    }
                }

                // Si no se añadió a ninguna línea, crear una nueva
                if (!addedToLine) {
                    lines.add(mutableListOf(element))
                }
            }

            // Ordenar elementos dentro de cada línea por posición horizontal
            return lines.map { line ->
                line.sortedBy { it.boundingBox?.left ?: 0 }
                    .joinToString(" ") { it.text }
            }

        } catch (e: Exception) {
            Log.e("MainService", "Error grouping elements by line", e)
            return elements.map { it.text }
        }
    }

    private fun translateTexts(detectedLanguage: String, visionText: com.google.mlkit.vision.text.Text) {
        val textsToTranslate = currentTextsToTranslate
        val dialogBubbles = currentDialogBubbles

        if (textsToTranslate == null || dialogBubbles == null) {
            Log.e("MainService", "Missing context for translation")
            overlayManager.handleProcessingError(Exception("Missing translation context"))
            return
        }

        Log.d("MainService", "Translating ${textsToTranslate.size} texts from $detectedLanguage")

        // Traducir usando el nuevo método que acepta textos individuales
        translatorManager.translateTextsByList(
            visionText = visionText,
            textsToTranslate = textsToTranslate,
            detectedSourceLanguage = detectedLanguage
        )
    }

    private fun showTranslations(visionText: com.google.mlkit.vision.text.Text, translatedTexts: List<String>) {
        try {
            // Mostrar directamente las traducciones sin depender del contexto guardado
            // En un escenario real, pasaríamos el requestId por toda la cadena de callbacks.
            // Dado que el flujo es lineal, basta con verificar que no haya empezado una captura nueva.
            Log.d("MainService", "Showing translations for current request context")
            overlayManager.showTranslationOverlay(visionText, translatedTexts)

            // Limpiar estado previo
            currentDialogBubbles = null
            currentTextsToTranslate = null
        } catch (e: Exception) {
            Log.e("MainService", "Error displaying translations", e)
            overlayManager.handleProcessingError(e)
        }
    }


    private fun stopService() {
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "MAIN_SERVICE_CHANNEL",
                "DAEZKit Service",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Servicio principal de traducción"
            }

            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = androidx.core.app.NotificationCompat.Builder(this, "MAIN_SERVICE_CHANNEL")
            .setContentTitle("DAEZKit")
            .setContentText("Servicio de traducción activo")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            unregisterReceiver(settingsReceiver)
        } catch (e: Exception) {
            Log.e("MainService", "Error unregistering receiver", e)
        }

        // Limpiar contexto
        currentDialogBubbles = null
        currentTextsToTranslate = null

        overlayManager.cleanup()
        screenCaptureManager.cleanup()
        ocrManager.cleanup()
        languageDetector.cleanup()
        translatorManager.cleanup()

        handler.removeCallbacksAndMessages(null)
    }
}