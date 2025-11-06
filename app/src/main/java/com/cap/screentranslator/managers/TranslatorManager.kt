package com.cap.screentranslator.managers

import android.util.Log
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.text.Text

class TranslatorManager {

    private var currentTranslator: Translator? = null
    private var sourceLanguage = "auto"
    private var targetLanguage = "es"

    private var onTranslationCompleteListener: ((com.google.mlkit.vision.text.Text, List<String>) -> Unit)? = null

    init {
        setupTranslator()
    }

    fun updateLanguages(sourceLanguage: String, targetLanguage: String) {
        this.sourceLanguage = sourceLanguage
        this.targetLanguage = targetLanguage
        setupTranslator()
    }

    private fun setupTranslator() {
        // Close previous translator
        currentTranslator?.close()
        currentTranslator = null

        // Only create translator if both languages are specified and different
        if (sourceLanguage != "auto" && targetLanguage != "auto" && sourceLanguage != targetLanguage) {
            try {
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLanguage)
                    .setTargetLanguage(targetLanguage)
                    .build()

                currentTranslator = Translation.getClient(options)

                // Pre-download model if needed
                currentTranslator?.downloadModelIfNeeded()
                    ?.addOnSuccessListener {
                        Log.d("Translator", "Model downloaded successfully for $sourceLanguage -> $targetLanguage")
                    }
                    ?.addOnFailureListener { exception ->
                        Log.e("Translator", "Failed to download model", exception)
                    }
            } catch (e: Exception) {
                Log.e("Translator", "Error setting up translator", e)
            }
        }
    }

    /**
     * NUEVO: Traduce una lista de textos individuales
     * Este método mantiene el orden y la correspondencia 1:1 entre entrada y salida
     */
    fun translateTextsByList(
        visionText: com.google.mlkit.vision.text.Text,
        textsToTranslate: List<String>,
        detectedSourceLanguage: String
    ) {
        if (textsToTranslate.isEmpty()) {
            Log.w("Translator", "Empty texts list provided")
            onTranslationCompleteListener?.invoke(visionText, emptyList())
            return
        }

        // If target language is same as detected source, no translation needed
        if (detectedSourceLanguage == targetLanguage) {
            Log.d("Translator", "Source and target languages are the same ($detectedSourceLanguage), no translation needed")
            onTranslationCompleteListener?.invoke(visionText, textsToTranslate)
            return
        }

        // Create appropriate translator for detected language
        val translatorToUse = if (sourceLanguage == "auto") {
            createTranslatorForLanguages(detectedSourceLanguage, targetLanguage)
        } else {
            currentTranslator
        }

        if (translatorToUse != null) {
            Log.d("Translator", "Translating ${textsToTranslate.size} individual texts from $detectedSourceLanguage to $targetLanguage")
            translateTextsSequentially(textsToTranslate, translatorToUse, visionText, detectedSourceLanguage)
        } else {
            Log.w("Translator", "No translator available, returning original texts")
            onTranslationCompleteListener?.invoke(visionText, textsToTranslate)
        }
    }

    /**
     * Traduce una lista de textos de forma secuencial, manteniendo el orden
     */
    private fun translateTextsSequentially(
        texts: List<String>,
        translator: Translator,
        visionText: com.google.mlkit.vision.text.Text,
        detectedSourceLanguage: String
    ) {
        val translatedTexts = MutableList(texts.size) { "" }
        var completedTranslations = 0

        val checkIfComplete = {
            if (completedTranslations == texts.size) {
                Log.d("Translator", "All ${texts.size} texts translated successfully")

                // Log de resultados para debug
                translatedTexts.forEachIndexed { index, translation ->
                    Log.d("Translator", "Text ${index + 1}/${texts.size}: '${texts[index].take(30)}...' -> '${translation.take(30)}...'")
                }

                onTranslationCompleteListener?.invoke(visionText, translatedTexts)
                cleanupTemporaryTranslator(translator, detectedSourceLanguage)
            }
        }

        texts.forEachIndexed { index, text ->
            val trimmedText = text.trim()

            if (trimmedText.isEmpty()) {
                Log.d("Translator", "Text ${index + 1}/${texts.size} is empty, skipping")
                translatedTexts[index] = ""
                completedTranslations++
                checkIfComplete()
                return@forEachIndexed
            }

            Log.d("Translator", "Translating text ${index + 1}/${texts.size}: ${trimmedText.take(50)}...")

            translator.translate(trimmedText)
                .addOnSuccessListener { translated ->
                    translatedTexts[index] = translated
                    completedTranslations++
                    Log.d("Translator", "Text ${index + 1} translated successfully: ${translated.take(50)}...")
                    checkIfComplete()
                }
                .addOnFailureListener { exception ->
                    Log.e("Translator", "Failed to translate text ${index + 1}", exception)
                    // En caso de error, usar el texto original
                    translatedTexts[index] = trimmedText
                    completedTranslations++
                    checkIfComplete()
                }
        }
    }

    /**
     * MÉTODO ORIGINAL: Traduce por bloques de texto de visionText
     * Mantenido para compatibilidad hacia atrás
     */
    fun translateTextByBlocks(visionText: com.google.mlkit.vision.text.Text, detectedSourceLanguage: String) {
        val textBlocks = visionText.textBlocks

        if (textBlocks.isEmpty()) {
            onTranslationCompleteListener?.invoke(visionText, emptyList())
            return
        }

        // If target language is same as detected source, no translation needed
        if (detectedSourceLanguage == targetLanguage) {
            Log.d("Translator", "Source and target languages are the same, no translation needed")
            val originalTexts = textBlocks.map { it.text }
            onTranslationCompleteListener?.invoke(visionText, originalTexts)
            return
        }

        // Create appropriate translator for detected language
        val translatorToUse = if (sourceLanguage == "auto") {
            createTranslatorForLanguages(detectedSourceLanguage, targetLanguage)
        } else {
            currentTranslator
        }

        if (translatorToUse != null) {
            Log.d("Translator", "Translating ${textBlocks.size} blocks from $detectedSourceLanguage to $targetLanguage")
            translateBlocksSequentially(textBlocks, translatorToUse, visionText, detectedSourceLanguage)
        } else {
            Log.w("Translator", "No translator available, returning original text")
            val originalTexts = textBlocks.map { it.text }
            onTranslationCompleteListener?.invoke(visionText, originalTexts)
        }
    }

    private fun translateBlocksSequentially(
        textBlocks: List<Text.TextBlock>,
        translator: Translator,
        visionText: com.google.mlkit.vision.text.Text,
        detectedSourceLanguage: String
    ) {
        val translatedTexts = MutableList(textBlocks.size) { "" }
        var completedTranslations = 0

        val checkIfComplete = {
            if (completedTranslations == textBlocks.size) {
                onTranslationCompleteListener?.invoke(visionText, translatedTexts)
                cleanupTemporaryTranslator(translator, detectedSourceLanguage)
            }
        }

        textBlocks.forEachIndexed { index, block ->
            val originalText = block.text.trim()

            if (originalText.isEmpty()) {
                translatedTexts[index] = ""
                completedTranslations++
                checkIfComplete()
                return@forEachIndexed
            }

            translator.translate(originalText)
                .addOnSuccessListener { translated ->
                    translatedTexts[index] = translated
                    completedTranslations++
                    checkIfComplete()
                }
                .addOnFailureListener {
                    translatedTexts[index] = originalText
                    completedTranslations++
                    checkIfComplete()
                }
        }
    }

    private fun cleanupTemporaryTranslator(translator: Translator, detectedSourceLanguage: String) {
        if (sourceLanguage == "auto" && translator != currentTranslator) {
            translator.close()
            Log.d("Translator", "Temporary translator for $detectedSourceLanguage closed")
        }
    }

    private fun createTranslatorForLanguages(sourceLanguage: String, targetLanguage: String): Translator? {
        return try {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(targetLanguage)
                .build()

            val translator = Translation.getClient(options)
            Log.d("Translator", "Created temporary translator for $sourceLanguage -> $targetLanguage")
            translator
        } catch (e: Exception) {
            Log.e("Translator", "Error creating temporary translator", e)
            null
        }
    }

    // Mantener el método original para compatibilidad hacia atrás
    fun translateText(visionText: com.google.mlkit.vision.text.Text, detectedSourceLanguage: String) {
        translateTextByBlocks(visionText, detectedSourceLanguage)
    }

    internal fun setOnTranslationCompleteListener(listener: (com.google.mlkit.vision.text.Text, List<String>) -> Unit) {
        onTranslationCompleteListener = listener
    }

    // MANTENER el método sobrecargado para compatibilidad hacia atrás:
    fun setOnTranslationCompleteListener(listener: (com.google.mlkit.vision.text.Text, String) -> Unit) {
        onTranslationCompleteListener = { visionText, translatedTexts ->
            // Combinar todas las traducciones en un solo string
            val combinedTranslation = translatedTexts.joinToString(" ")
            listener(visionText, combinedTranslation)
        }
    }

    fun cleanup() {
        try {
            currentTranslator?.close()
            currentTranslator = null
            Log.d("Translator", "Translator closed")
        } catch (e: Exception) {
            Log.e("Translator", "Error closing translator", e)
        }
    }
}