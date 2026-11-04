package com.example.capture

import com.example.domain.model.NormalizedRect
import com.example.domain.model.OcrBlockCandidate
import kotlin.math.abs

/**
 * Heurística especializada para detectar automáticamente la zona de subtítulos (ROI)
 * en reproductores de anime (Chrome, Firefox, Samsung Internet, VLC, YouTube),
 * descartando relojes, barras de progreso, marcas de agua, letreros superiores y botones del reproductor.
 */
object RoiDetector {

    private val TIMESTAMP_REGEX = Regex("""^\s*\d{1,2}:\d{2}(:\d{2})?(\s*/\s*\d{1,2}:\d{2}(:\d{2})?)?\s*$""")
    private val URL_OR_UI_NOISE_REGEX = Regex(
        """(?i)(https?://|www\.|\.com|\.net|\.org|1080p|720p|480p|360p|saltar\s+intro|skip\s+intro|siguiente\s+episodio|next\s+episode|anuncio|publicidad|cargando\.\.\.)"""
    )

    data class RoiDetectionResult(
        val detectedRoi: NormalizedRect?,
        val bestSubtitleText: String
    )

    /**
     * Evalúa los bloques de texto detectados en una captura de pantalla completa y
     * selecciona el candidato que cumple la geometría y semántica de un subtítulo.
     */
    fun detectSubtitleRoiFromFullFrame(
        blocks: List<OcrBlockCandidate>,
        frameWidth: Int,
        frameHeight: Int
    ): RoiDetectionResult {
        if (blocks.isEmpty() || frameWidth <= 0 || frameHeight <= 0) {
            return RoiDetectionResult(null, "")
        }

        val scoredCandidates = mutableListOf<Pair<OcrBlockCandidate, Float>>()

        for (block in blocks) {
            val cleaned = block.text.trim()
            if (cleaned.length < 2) continue
            if (isUiNoiseOrPlayerChrome(cleaned)) continue

            val box = block.boundingBox
            val normCenterX = box.exactCenterX() / frameWidth.toFloat()
            val normCenterY = box.exactCenterY() / frameHeight.toFloat()
            val normHeight = box.height().toFloat() / frameHeight.toFloat()

            // 1. Los subtítulos de anime aparecen casi siempre en el tercio inferior (0.56 .. 0.96)
            if (normCenterY !in 0.56f..0.96f) continue

            // 2. Filtrar textos diminutos (barra de estado/reloj) o gigantes (logos/títulos)
            if (normHeight !in 0.014f..0.18f) continue

            // 3. Los subtítulos suelen estar centrados horizontalmente (|centerX - 0.5| < 0.30)
            val horizontalCenterDeviation = abs(normCenterX - 0.5f)
            if (horizontalCenterDeviation > 0.30f) continue

            var score = 0f

            val verticalDistanceToIdeal = abs(normCenterY - 0.82f)
            score += (1.0f - verticalDistanceToIdeal * 2.5f).coerceIn(0f, 1f) * 40f
            score += (1.0f - horizontalCenterDeviation * 3.0f).coerceIn(0f, 1f) * 35f

            val letterCount = cleaned.count { it.isLetter() }
            if (letterCount >= 3) score += 15f
            if (cleaned.any { it in "áéíóúüñÁÉÍÓÚÜÑ¿?¡!" }) score += 10f

            scoredCandidates.add(block to score)
        }

        if (scoredCandidates.isEmpty()) {
            return RoiDetectionResult(null, "")
        }

        val best = scoredCandidates.maxByOrNull { it.second }?.first ?: return RoiDetectionResult(null, "")
        val bestNormY = best.boundingBox.exactCenterY() / frameHeight.toFloat()

        val groupedBlocks = scoredCandidates
            .map { it.first }
            .filter { candidate ->
                val candidateNormY = candidate.boundingBox.exactCenterY() / frameHeight.toFloat()
                abs(candidateNormY - bestNormY) <= 0.11f
            }
            .sortedBy { it.boundingBox.top }

        val minTopPx = groupedBlocks.minOf { it.boundingBox.top }
        val maxBottomPx = groupedBlocks.maxOf { it.boundingBox.bottom }

        val normTop = ((minTopPx.toFloat() / frameHeight.toFloat()) - 0.05f).coerceIn(0.55f, 0.86f)
        val normBottom = ((maxBottomPx.toFloat() / frameHeight.toFloat()) + 0.05f).coerceIn(normTop + 0.10f, 0.97f)

        val lockedRoi = NormalizedRect(
            left = 0.06f,
            top = normTop,
            right = 0.94f,
            bottom = normBottom
        ).clamp()

        val combinedText = groupedBlocks.joinToString("\n") { it.text.trim() }
        return RoiDetectionResult(lockedRoi, combinedText)
    }

    /**
     * Filtra bloques dentro de una captura que ya está recortada a la ROI,
     * descartando elementos de UI del reproductor o letreros de escenario fuera de la zona inferior
     * si el usuario marcó una ROI muy alta.
     */
    fun filterBlocksInsideRoi(
        blocks: List<OcrBlockCandidate>,
        roiBitmapWidth: Int,
        roiBitmapHeight: Int,
        ignoreUpperSigns: Boolean = false
    ): String {
        if (blocks.isEmpty() || roiBitmapWidth <= 0 || roiBitmapHeight <= 0) return ""

        val validLines = mutableListOf<Pair<Int, String>>()

        for (block in blocks) {
            for (line in block.lines) {
                val text = line.text.trim()
                if (text.isEmpty()) continue
                if (isUiNoiseOrPlayerChrome(text)) continue

                val box = line.boundingBox ?: block.boundingBox
                val lineHeightRatio = box.height().toFloat() / roiBitmapHeight.toFloat()
                // Descartar ruido microscópico (< 6% de la altura de la ROI)
                if (lineHeightRatio < 0.06f) continue

                // Si la ROI elegida es muy alta (> 420px) e ignoreUpperSigns está activo,
                // descartar carteles pegados al borde superior de la ROI (< 18% superior).
                if (ignoreUpperSigns && roiBitmapHeight > 420) {
                    val normCenterYInRoi = box.exactCenterY() / roiBitmapHeight.toFloat()
                    if (normCenterYInRoi < 0.18f) continue
                }

                validLines.add(box.top to text)
            }
        }

        if (validLines.isEmpty()) return ""

        return validLines
            .sortedBy { it.first }
            .joinToString("\n") { it.second }
    }

    fun isUiNoiseOrPlayerChrome(rawText: String): Boolean {
        val text = rawText.trim()
        if (text.isEmpty()) return true
        if (TIMESTAMP_REGEX.matches(text)) return true
        if (URL_OR_UI_NOISE_REGEX.containsMatchIn(text)) return true

        val letters = text.count { it.isLetter() }
        val digits = text.count { it.isDigit() }
        if (letters == 0 && digits > 0 && !text.contains('¿') && !text.contains('¡')) {
            return true
        }
        return false
    }
}
