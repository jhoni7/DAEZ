package com.cap.screentranslator.managers

import com.google.mlkit.vision.text.Text
import android.util.Log
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier

class LanguageDetector {

    private val languageIdentifier: LanguageIdentifier = LanguageIdentification.getClient()

    private var onLanguageDetectedListener: ((String, Text) -> Unit)? = null

    fun detectLanguage(text: String, visionText: Text) {
        Log.d("LanguageDetector", "Detecting language for text: ${text.take(50)}...")

        // Primer intento con detección sincrónica mejorada
        val syncDetection = detectLanguageSync(text)
        Log.d("LanguageDetector", "Sync detection result: $syncDetection")

        // Si la detección sincrónica es confiable (no inglés por defecto), úsala
        if (syncDetection != "en" || !containsLatinCharacters(text)) {
            Log.d("LanguageDetector", "Using sync detection: $syncDetection")
            onLanguageDetectedListener?.invoke(syncDetection, visionText)
            return
        }

        // Si no, usar ML Kit
        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { languageCode ->
                val detectedLanguage = if (languageCode == "und") {
                    Log.d("LanguageDetector", "Language undetected by ML Kit, using sync result: $syncDetection")
                    syncDetection
                } else {
                    Log.d("LanguageDetector", "ML Kit detected language: $languageCode")
                    languageCode
                }

                onLanguageDetectedListener?.invoke(detectedLanguage, visionText)
            }
            .addOnFailureListener { exception ->
                Log.e("LanguageDetector", "Language detection failed", exception)
                Log.d("LanguageDetector", "Falling back to sync detection: $syncDetection")
                onLanguageDetectedListener?.invoke(syncDetection, visionText)
            }
    }

    fun detectLanguageSync(text: String): String {
        // Análisis más robusto de caracteres
        val cleanText = text.trim()

        if (cleanText.isEmpty()) return "en"

        // Contar diferentes tipos de caracteres
        var cjkCount = 0
        var latinCount = 0
        var cyrillicCount = 0
        var arabicCount = 0

        // Contadores específicos para CJK
        var chineseCount = 0
        var hiraganaCount = 0
        var katakanaCount = 0
        var hangulCount = 0

        cleanText.forEach { char ->
            val codePoint = char.code
            when {
                // Chino (incluye caracteres tradicionales y simplificados)
                codePoint in 0x4E00..0x9FFF -> {
                    cjkCount++
                    chineseCount++
                }
                // Hiragana (japonés)
                codePoint in 0x3040..0x309F -> {
                    cjkCount++
                    hiraganaCount++
                }
                // Katakana (japonés)
                codePoint in 0x30A0..0x30FF -> {
                    cjkCount++
                    katakanaCount++
                }
                // Hangul (coreano)
                codePoint in 0xAC00..0xD7AF ||
                        codePoint in 0x1100..0x11FF ||
                        codePoint in 0x3130..0x318F -> {
                    cjkCount++
                    hangulCount++
                }
                // Alfabeto latino
                char in 'A'..'Z' || char in 'a'..'z' -> latinCount++
                // Cirílico
                codePoint in 0x0400..0x04FF -> cyrillicCount++
                // Árabe
                codePoint in 0x0600..0x06FF -> arabicCount++
            }
        }

        val totalChars = cleanText.length.toDouble()

        // Si más del 30% son caracteres CJK, determinar el idioma específico
        if (cjkCount / totalChars > 0.3) {
            return when {
                hangulCount > 0 -> "ko" // Coreano
                hiraganaCount > 0 || katakanaCount > 0 -> "ja" // Japonés
                chineseCount > 0 -> "zh" // Chino
                else -> "zh" // Por defecto chino si hay caracteres CJK no identificados
            }
        }

        // Si más del 30% son caracteres cirílicos
        if (cyrillicCount / totalChars > 0.3) {
            return "ru"
        }

        // Si más del 30% son caracteres árabes
        if (arabicCount / totalChars > 0.3) {
            return "ar"
        }

        // Para idiomas latinos, usar patrones de caracteres especiales
        return when {
            text.matches(Regex(".*[àáâãäåæçèéêëìíîïðñòóôõöøùúûüýþÿ].*", RegexOption.IGNORE_CASE)) -> "es"
            text.matches(Regex(".*[àâæçéèêëïîôœùûüÿ].*", RegexOption.IGNORE_CASE)) -> "fr"
            text.matches(Regex(".*[äöüß].*", RegexOption.IGNORE_CASE)) -> "de"
            text.matches(Regex(".*[àáâãçéêíóôõú].*", RegexOption.IGNORE_CASE)) -> "pt"
            text.matches(Regex(".*[àáéèíìóòú].*", RegexOption.IGNORE_CASE)) -> "it"
            else -> "en"
        }
    }

    private fun containsLatinCharacters(text: String): Boolean {
        return text.any { char ->
            char in 'A'..'Z' || char in 'a'..'z'
        }
    }

    fun setOnLanguageDetectedListener(listener: (String, Text) -> Unit) {
        onLanguageDetectedListener = listener
    }

    fun cleanup() {
        try {
            languageIdentifier.close()
            Log.d("LanguageDetector", "Language identifier closed")
        } catch (e: Exception) {
            Log.e("LanguageDetector", "Error closing language identifier", e)
        }
    }
}