package com.cap.screentranslator

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1000
        private const val REQUEST_SYSTEM_ALERT_WINDOW = 1001
        private const val REQUEST_PERMISSIONS = 1002
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var mainService: Intent

    // UI Components
    private lateinit var btnSettings: Button
    private lateinit var btnStartService: Button
    private lateinit var btnStopService: Button
    private lateinit var settingsContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var sourceLanguageSpinner: Spinner
    private lateinit var targetLanguageSpinner: Spinner
    private lateinit var fontSizeSeekBar: SeekBar
    private lateinit var fontSizeLabel: TextView

    // State
    private var isServiceRunning = false
    private var isSettingsVisible = false
    private var sourceLanguage = "auto"
    private var targetLanguage = "es"
    private var fontSize = 16f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mainService = Intent(this, MainService::class.java)

        initViews()
        setupEventListeners()
        setupLanguageSpinners()
        setupFontSizeControl()
        updateUI()
    }

    private fun initViews() {
        btnSettings = findViewById(R.id.btn_settings)
        btnStartService = findViewById(R.id.btn_start_service)
        btnStopService = findViewById(R.id.btn_stop_service)
        settingsContainer = findViewById(R.id.settings_container)
        statusText = findViewById(R.id.status_text)
        sourceLanguageSpinner = findViewById(R.id.source_language_spinner)
        targetLanguageSpinner = findViewById(R.id.target_language_spinner)
        fontSizeSeekBar = findViewById(R.id.font_size_seekbar)
        fontSizeLabel = findViewById(R.id.font_size_label)
    }

    private fun setupEventListeners() {
        btnSettings.setOnClickListener {
            toggleSettings()
        }

        btnStartService.setOnClickListener {
            if (!isServiceRunning) {
                requestPermissions()
            }
        }

        btnStopService.setOnClickListener {
            if (isServiceRunning) {
                stopService()
            }
        }
    }

    private fun setupLanguageSpinners() {
        val languages = arrayOf(
            "Auto", "Español", "English", "Français", "Deutsch",
            "Italiano", "Português", "中文", "日本語", "한국어", "Русский", "العربية"
        )
        val languageCodes = arrayOf(
            "auto", "es", "en", "fr", "de",
            "it", "pt", "zh", "ja", "ko", "ru", "ar"
        )

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        sourceLanguageSpinner.adapter = adapter
        targetLanguageSpinner.adapter = adapter

        // Set default selections
        sourceLanguageSpinner.setSelection(0) // Auto
        targetLanguageSpinner.setSelection(1) // Español

        sourceLanguageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                sourceLanguage = languageCodes[position]
                updateServiceLanguages()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        targetLanguageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                targetLanguage = languageCodes[position]
                updateServiceLanguages()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupFontSizeControl() {
        fontSizeSeekBar.apply {
            min = 6
            max = 32
            progress = fontSize.toInt()

            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        fontSize = progress.toFloat()
                        updateFontSizeLabel()
                        updateServiceFontSize()
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        updateFontSizeLabel()
    }

    private fun updateFontSizeLabel() {
        fontSizeLabel.text = "Tamaño de fuente: ${fontSize.toInt()}sp"
    }

    private fun toggleSettings() {
        isSettingsVisible = !isSettingsVisible
        settingsContainer.visibility = if (isSettingsVisible) View.VISIBLE else View.GONE
        btnSettings.text = if (isSettingsVisible) "Ocultar Configuración" else "Configuración"
    }

    private fun updateServiceLanguages() {
        if (isServiceRunning) {
            // Enviar broadcast o usar otro mecanismo para actualizar el servicio
            val updateIntent = Intent("UPDATE_TRANSLATION_SETTINGS")
            updateIntent.putExtra("sourceLanguage", sourceLanguage)
            updateIntent.putExtra("targetLanguage", targetLanguage)
            sendBroadcast(updateIntent)
        }
    }

    private fun updateServiceFontSize() {
        if (isServiceRunning) {
            // Enviar broadcast para actualizar el tamaño de fuente
            val updateIntent = Intent("UPDATE_FONT_SIZE")
            updateIntent.putExtra("fontSize", fontSize)
            sendBroadcast(updateIntent)
        }
    }

    private fun requestPermissions() {
        // Verificar permiso de notificaciones para Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_PERMISSIONS)
                return
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            startActivityForResult(intent, REQUEST_SYSTEM_ALERT_WINDOW)
        } else {
            startMediaProjection()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    startActivityForResult(intent, REQUEST_SYSTEM_ALERT_WINDOW)
                } else {
                    startMediaProjection()
                }
            }
        }
    }

    private fun startMediaProjection() {
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_MEDIA_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    startTranslationService(resultCode, data)
                }
            }
            REQUEST_SYSTEM_ALERT_WINDOW -> {
                if (Settings.canDrawOverlays(this)) {
                    startMediaProjection()
                } else {
                    Toast.makeText(this, "Se requiere permiso de overlay para continuar", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startTranslationService(resultCode: Int, data: Intent) {
        // Iniciar MainService con los datos de MediaProjection y configuración inicial
        mainService.putExtra("resultCode", resultCode)
        mainService.putExtra("data", data)
        mainService.putExtra("sourceLanguage", sourceLanguage)
        mainService.putExtra("targetLanguage", targetLanguage)
        mainService.putExtra("fontSize", fontSize)

        startForegroundService(mainService)

        isServiceRunning = true
        updateUI()

        Toast.makeText(this, "Servicio de traducción iniciado. Usa el botón flotante para capturar.", Toast.LENGTH_LONG).show()
    }

    private fun stopService() {
        stopService(mainService)
        isServiceRunning = false
        updateUI()
        Toast.makeText(this, "Servicio de traducción detenido", Toast.LENGTH_SHORT).show()
    }

    private fun updateUI() {
        btnStartService.isEnabled = !isServiceRunning
        btnStopService.isEnabled = isServiceRunning

        if (isServiceRunning) {
            statusText.text = "Estado: Servicio activo - Botón flotante disponible"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            btnStartService.text = "Servicio Activo"
            btnStopService.text = "Detener Servicio"
        } else {
            statusText.text = "Estado: Servicio detenido"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            btnStartService.text = "Iniciar Servicio de Traducción"
            btnStopService.text = "Detener Servicio"
        }
    }

    override fun onResume() {
        super.onResume()
        // Verificar si el servicio sigue corriendo
        checkServiceStatus()
    }

    private fun checkServiceStatus() {
        // Aquí puedes implementar lógica para verificar si el servicio está corriendo
        // Por ejemplo, usando SharedPreferences o un broadcast receiver
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        // No detener automáticamente el servicio al cerrar la actividad
        // El servicio debe seguir corriendo en background
    }
}