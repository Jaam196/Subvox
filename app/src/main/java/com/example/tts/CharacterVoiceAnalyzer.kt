package com.example.tts

import android.graphics.Bitmap
import android.graphics.Color
import com.example.domain.model.AppSettings
import com.example.domain.model.CharacterVoiceMode

enum class DominantSubtitleColor(val label: String) {
    WHITE("Blanco (Principal)"),
    YELLOW("Amarillo (Personaje B)"),
    CYAN("Cian (Personaje C)"),
    GREEN("Verde (Secundario)"),
    MAGENTA_PINK("Rosa/Coral (Personaje B)")
}

data class CharacterVoiceAssignment(
    val characterLabel: String,
    val targetPitch: Float
)

/**
 * Detector de color dominante de los píxeles brillantes del subtítulo dentro de la ROI
 * y selector de voz/tono por personaje (Fase 2 - Función 6):
 * - Modo [CharacterVoiceMode.COLOR_BASED]: Muestrea la ROI original antes de binarizar,
 *   clasifica el tinte de los píxeles de texto (blanco, amarillo, cian, rosa, verde)
 *   y asigna el tono correspondiente (Pitch A, B o C).
 * - Modo [CharacterVoiceMode.ALTERNATE_AB]: Alterna entre Tono A y Tono B en cada diálogo nuevo.
 */
object CharacterVoiceAnalyzer {

    @Volatile
    private var alternateToggleState: Boolean = false

    fun resetAlternation() {
        alternateToggleState = false
    }

    fun resolveForNewSubtitle(
        roiBitmap: Bitmap?,
        settings: AppSettings
    ): CharacterVoiceAssignment {
        return when (settings.characterVoicesMode) {
            CharacterVoiceMode.OFF -> CharacterVoiceAssignment(
                characterLabel = "Voz única",
                targetPitch = settings.pitch
            )

            CharacterVoiceMode.ALTERNATE_AB -> {
                val useVoiceB = alternateToggleState
                alternateToggleState = !alternateToggleState
                if (useVoiceB) {
                    CharacterVoiceAssignment(
                        characterLabel = "Voz B (Alterna)",
                        targetPitch = settings.characterVoicePitchB
                    )
                } else {
                    CharacterVoiceAssignment(
                        characterLabel = "Voz A (Alterna)",
                        targetPitch = settings.characterVoicePitchA
                    )
                }
            }

            CharacterVoiceMode.COLOR_BASED -> {
                val dominantColor = if (roiBitmap != null && !roiBitmap.isRecycled) {
                    detectDominantBrightTextColor(roiBitmap)
                } else {
                    DominantSubtitleColor.WHITE
                }
                val pitch = when (dominantColor) {
                    DominantSubtitleColor.WHITE -> settings.characterVoicePitchA
                    DominantSubtitleColor.YELLOW, DominantSubtitleColor.MAGENTA_PINK -> settings.characterVoicePitchB
                    DominantSubtitleColor.CYAN, DominantSubtitleColor.GREEN -> settings.characterVoicePitchC
                }
                CharacterVoiceAssignment(
                    characterLabel = dominantColor.label,
                    targetPitch = pitch
                )
            }
        }
    }

    /**
     * Analiza una rejilla rápida de píxeles en la zona central de la ROI buscando trazos brillantes
     * (luminancia/valor HSV alto) que corresponden a las letras del subtítulo y clasifica su matiz (Hue).
     */
    fun detectDominantBrightTextColor(bitmap: Bitmap): DominantSubtitleColor {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 8 || h < 8) return DominantSubtitleColor.WHITE

        var whiteVotes = 0
        var yellowVotes = 0
        var cyanVotes = 0
        var greenVotes = 0
        var pinkVotes = 0

        val hsv = FloatArray(3)
        val stepX = (w / 24).coerceAtLeast(2)
        val stepY = (h / 10).coerceAtLeast(2)

        for (y in (h / 6) until (h * 5 / 6) step stepY) {
            for (x in (w / 8) until (w * 7 / 8) step stepX) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // Solo consideramos píxeles suficientemente brillantes (texto del subtítulo)
                val maxChannel = maxOf(r, g, b)
                if (maxChannel < 165) continue

                Color.RGBToHSV(r, g, b, hsv)
                val hue = hsv[0]
                val sat = hsv[1]
                val value = hsv[2]

                if (value < 0.65f) continue

                if (sat < 0.24f) {
                    whiteVotes++
                } else {
                    when {
                        hue in 35f..75f -> yellowVotes += 2
                        hue in 76f..160f -> greenVotes += 2
                        hue in 161f..225f -> cyanVotes += 2
                        hue >= 290f || hue <= 34f -> pinkVotes += 2
                        else -> whiteVotes++
                    }
                }
            }
        }

        val maxColored = maxOf(yellowVotes, cyanVotes, greenVotes, pinkVotes)
        // Si hay suficientes píxeles coloreados en proporción al blanco, gana el color del personaje
        if (maxColored >= 4 && maxColored >= whiteVotes / 3) {
            return when (maxColored) {
                yellowVotes -> DominantSubtitleColor.YELLOW
                cyanVotes -> DominantSubtitleColor.CYAN
                greenVotes -> DominantSubtitleColor.GREEN
                else -> DominantSubtitleColor.MAGENTA_PINK
            }
        }

        return DominantSubtitleColor.WHITE
    }
}
