package com.cap.screentranslator

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.cap.screentranslator.managers.LanguageDetector
import com.cap.screentranslator.managers.OcrManager
import com.cap.screentranslator.managers.OverlayManager
import com.cap.screentranslator.managers.ScreenCaptureManager
import com.cap.screentranslator.managers.TranslatorManager

class MainService : Service() {

    private lateinit var overlayManager: OverlayManager
    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var ocrManager: OcrManager
    private lateinit var languageDetector: LanguageDetector
    private lateinit var translatorManager: TranslatorManager

    private lateinit var settingsReceiver: BroadcastReceiver

    private val handler = Handler(Looper.getMainLooper())

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
            captureAndProcess()
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

        languageDetector.setOnLanguageDetectedListener { detectedLanguage, originalText ->
            translateText(detectedLanguage, originalText)
        }

        translatorManager.setOnTranslationCompleteListener { visionText: com.google.mlkit.vision.text.Text, translatedText: String ->
            showTranslation(visionText, translatedText)
        }
    }

    private fun setupScreenCapture(resultCode: Int, data: Intent) {
        screenCaptureManager.setupMediaProjection(resultCode, data)
    }

    private fun showBottomBar() {
        overlayManager.showBottomBar()
    }

    private fun captureAndProcess() {
        screenCaptureManager.captureScreen { bitmap ->
            if (bitmap != null) {
                ocrManager.processImage(bitmap)
            }
        }
    }

    private fun processRecognizedText(visionText: com.google.mlkit.vision.text.Text) {
        if (visionText.text.isNotEmpty()) {
            languageDetector.detectLanguage(visionText.text, visionText)
        } else {
            Log.d("MainService", "No text detected")
        }
    }

    private fun translateText(detectedLanguage: String, visionText: com.google.mlkit.vision.text.Text) {
        translatorManager.translateText(visionText, detectedLanguage)
    }

    private fun showTranslation(visionText: com.google.mlkit.vision.text.Text, translatedText: String) {
        overlayManager.showTranslationOverlay(visionText, translatedText)
    }

    private fun stopService() {
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "MAIN_SERVICE_CHANNEL",
                "Screen Translator Service",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Servicio principal de traducción"
            }

            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = androidx.core.app.NotificationCompat.Builder(this, "MAIN_SERVICE_CHANNEL")
            .setContentTitle("Screen Translator")
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

        overlayManager.cleanup()
        screenCaptureManager.cleanup()
        ocrManager.cleanup()
        languageDetector.cleanup()
        translatorManager.cleanup()

        handler.removeCallbacksAndMessages(null)
    }
}
