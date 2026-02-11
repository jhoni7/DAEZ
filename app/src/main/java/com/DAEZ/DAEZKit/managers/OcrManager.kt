package com.DAEZ.DAEZKit.managers

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

class OcrManager {

    // Múltiples reconocedores para diferentes scripts
    private val latinTextRecognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val chineseTextRecognizer: TextRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val japaneseTextRecognizer: TextRecognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    private val koreanTextRecognizer: TextRecognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    private var onTextRecognizedListener: ((com.google.mlkit.vision.text.Text) -> Unit)? = null

    fun processImage(bitmap: Bitmap) {
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            Log.d("OCR", "Starting text recognition on image: ${bitmap.width}x${bitmap.height}")

            // Intentar con múltiples reconocedores simultáneamente
            processWithMultipleRecognizers(inputImage)

        } catch (e: Exception) {
            Log.e("OCR", "Error processing image for OCR", e)
        }
    }

    private fun processWithMultipleRecognizers(inputImage: InputImage) {
        val recognizers = listOf(
            Pair("Latin", latinTextRecognizer),
            Pair("Chinese", chineseTextRecognizer),
            Pair("Japanese", japaneseTextRecognizer),
            Pair("Korean", koreanTextRecognizer)
        )

        val results = mutableListOf<com.google.mlkit.vision.text.Text>()
        var completedRecognizers = 0

        recognizers.forEach { (name, recognizer) ->
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    completedRecognizers++

                    if (visionText.text.isNotEmpty()) {
                        val filteredText = filterValidText(visionText)
                        if (filteredText != null && filteredText.text.trim().isNotEmpty()) {
                            results.add(filteredText)
                            Log.d("OCR", "$name recognizer found text: ${filteredText.text.length} characters")
                        }
                    }

                    // Solo procesar resultados cuando TODOS los reconocedores hayan terminado
                    if (completedRecognizers == recognizers.size) {
                        processResults(results)
                    }
                }
                .addOnFailureListener { e ->
                    completedRecognizers++
                    Log.w("OCR", "$name recognizer failed", e)

                    if (completedRecognizers == recognizers.size) {
                        processResults(results)
                    }
                }
        }
    }

    private fun processResults(results: List<com.google.mlkit.vision.text.Text>) {
        if (results.isEmpty()) {
            Log.d("OCR", "No text detected by any recognizer")
            return
        }

        // Seleccionar el mejor resultado (el que más texto detectó)
        val bestResult = results.maxByOrNull { it.text.length }

        if (bestResult != null) {
            Log.d("OCR", "Best OCR result: ${bestResult.text.length} characters")
            Log.d("OCR", "Detected text: ${bestResult.text}")
            onTextRecognizedListener?.invoke(bestResult)
        }
    }

    private fun filterValidText(visionText: com.google.mlkit.vision.text.Text): com.google.mlkit.vision.text.Text? {
        val validBlocks = visionText.textBlocks.filter { block ->
            val text = block.text.trim()
            // Criterios mejorados para texto válido:
            // - Al menos 1 caracter para CJK (pueden ser logogramas)
            // - Contiene al menos una letra, dígito o caracter CJK
            text.isNotEmpty() && (
                    text.any { it.isLetter() } ||
                            text.any { it.isDigit() } ||
                            text.any { isCJKCharacter(it) }
                    )
        }

        return if (validBlocks.isEmpty()) {
            null
        } else {
            visionText
        }
    }

    private fun isCJKCharacter(char: Char): Boolean {
        val codePoint = char.code
        return (codePoint in 0x4E00..0x9FFF) ||  // CJK Unified Ideographs
                (codePoint in 0x3040..0x309F) ||  // Hiragana
                (codePoint in 0x30A0..0x30FF) ||  // Katakana
                (codePoint in 0xAC00..0xD7AF) ||  // Hangul Syllables
                (codePoint in 0x1100..0x11FF) ||  // Hangul Jamo
                (codePoint in 0x3130..0x318F)     // Hangul Compatibility Jamo
    }

    fun setOnTextRecognizedListener(listener: (com.google.mlkit.vision.text.Text) -> Unit) {
        onTextRecognizedListener = listener
    }

    fun cleanup() {
        try {
            latinTextRecognizer.close()
            chineseTextRecognizer.close()
            japaneseTextRecognizer.close()
            koreanTextRecognizer.close()
            Log.d("OCR", "All text recognizers closed")
        } catch (e: Exception) {
            Log.e("OCR", "Error closing text recognizers", e)
        }
    }
}