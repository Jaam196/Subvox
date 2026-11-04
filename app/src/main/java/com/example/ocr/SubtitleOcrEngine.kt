package com.example.ocr

import android.graphics.Bitmap
import android.os.SystemClock
import com.example.domain.model.OcrBlockCandidate
import com.example.domain.model.OcrLineItem
import com.example.domain.model.OcrScriptMode
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

sealed class OcrResult {
    /**
     * @param meanConfidence confianza media de las líneas [0..1]; 0 si el reconocedor no la informa.
     */
    data class Success(
        val blocks: List<OcrBlockCandidate>,
        val meanConfidence: Float = 0f
    ) : OcrResult()

    data object ModelDownloading : OcrResult()
    data class Error(val message: String) : OcrResult()
}

/**
 * Motor OCR 100% local basado en ML Kit Text Recognition v2.
 *
 * Mejora v2 del modo AUTO: antes probaba hasta 4 reconocedores seguidos en cada frame sin texto.
 * Ahora el servicio solo llama al OCR cuando ya detectó píxeles de texto, se empieza por el
 * último script que funcionó y los scripts alternativos se prueban como mucho una vez cada
 * [FALLBACK_COOLDOWN_MS] cuando el principal no devuelve nada legible.
 */
class SubtitleOcrEngine {

    private val recognizers = mutableMapOf<OcrScriptMode, TextRecognizer>()
    private var lastSuccessfulAutoScript: OcrScriptMode = OcrScriptMode.LATIN
    private var lastFallbackAttemptMs: Long = 0L

    @Synchronized
    private fun getOrCreateRecognizer(script: OcrScriptMode): TextRecognizer {
        val targetScript = if (script == OcrScriptMode.AUTO) OcrScriptMode.LATIN else script
        val existing = recognizers[targetScript]
        if (existing != null) return existing

        val created = when (targetScript) {
            OcrScriptMode.LATIN, OcrScriptMode.AUTO ->
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            OcrScriptMode.JAPANESE ->
                TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            OcrScriptMode.KOREAN ->
                TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            OcrScriptMode.CHINESE ->
                TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        }
        recognizers[targetScript] = created
        return created
    }

    suspend fun recognizeBitmap(
        bitmap: Bitmap,
        scriptMode: OcrScriptMode = OcrScriptMode.LATIN
    ): OcrResult {
        if (scriptMode != OcrScriptMode.AUTO) {
            return runSingleRecognizer(bitmap, scriptMode)
        }

        val primaryScript = lastSuccessfulAutoScript
        val primaryResult = runSingleRecognizer(bitmap, primaryScript)
        if (primaryResult is OcrResult.Success && hasMeaningfulText(primaryResult.blocks)) {
            return primaryResult
        }
        if (primaryResult !is OcrResult.Success) {
            return primaryResult
        }

        // El script principal no leyó nada: probar alternativas, pero con enfriamiento
        val now = SystemClock.elapsedRealtime()
        if (now - lastFallbackAttemptMs < FALLBACK_COOLDOWN_MS) {
            return primaryResult
        }
        lastFallbackAttemptMs = now

        val fallbackOrder = listOf(
            OcrScriptMode.JAPANESE,
            OcrScriptMode.LATIN,
            OcrScriptMode.KOREAN,
            OcrScriptMode.CHINESE
        ).filter { it != primaryScript }

        for (candidateScript in fallbackOrder) {
            val res = runSingleRecognizer(bitmap, candidateScript)
            if (res is OcrResult.Success && hasMeaningfulText(res.blocks)) {
                lastSuccessfulAutoScript = candidateScript
                return res
            }
        }

        return primaryResult
    }

    private suspend fun runSingleRecognizer(
        bitmap: Bitmap,
        script: OcrScriptMode
    ): OcrResult {
        return try {
            val client = getOrCreateRecognizer(script)
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText = client.process(inputImage).await()

            var confidenceSum = 0f
            var confidenceCount = 0
            val candidates = visionText.textBlocks.mapNotNull { block ->
                val rect = block.boundingBox ?: return@mapNotNull null
                val lines = block.lines.map { line ->
                    if (line.confidence > 0f) {
                        confidenceSum += line.confidence
                        confidenceCount++
                    }
                    OcrLineItem(
                        text = line.text,
                        boundingBox = line.boundingBox,
                        confidence = line.confidence
                    )
                }
                OcrBlockCandidate(
                    text = block.text,
                    boundingBox = rect,
                    lines = lines
                )
            }
            val mean = if (confidenceCount > 0) confidenceSum / confidenceCount else 0f
            OcrResult.Success(candidates, mean)
        } catch (e: MlKitException) {
            if (e.errorCode == MlKitException.UNAVAILABLE) {
                OcrResult.ModelDownloading
            } else {
                OcrResult.Error(e.localizedMessage ?: "Error en motor OCR (${script.displayName})")
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            OcrResult.Error(t.localizedMessage ?: "Error inesperado en OCR")
        }
    }

    private fun hasMeaningfulText(blocks: List<OcrBlockCandidate>): Boolean {
        return blocks.any { block ->
            block.text.count { it.isLetterOrDigit() } >= 2
        }
    }

    @Synchronized
    fun close() {
        recognizers.values.forEach { recognizer ->
            runCatching { recognizer.close() }
        }
        recognizers.clear()
    }

    companion object {
        private const val FALLBACK_COOLDOWN_MS = 1500L
    }
}
