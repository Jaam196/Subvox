package com.example.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Preprocesa el Bitmap de la ROI antes de pasarlo a ML Kit Text Recognition:
 * 1. Ajusta el tamaño del recorte solo si es muy pequeño (amplía hasta 3x) o muy grande (reduce).
 * 2. Aplica realce de contraste para destacar texto blanco/amarillo con borde negro.
 * 3. Opcionalmente aplica un filtro de binarización con detección de contorno oscuro (típico
 *    de subtítulos de anime hardsub/softsub) para eliminar fondos complejos brillantes.
 */
object ImagePreprocessor {

    // El recorte ya viene ajustado al texto (1-2 líneas): solo se amplía si es muy pequeño
    // y se reduce si es enorme. No se toca el tamaño en el rango intermedio para no perder nitidez.
    private const val MIN_OPTIMAL_HEIGHT = 80
    private const val MAX_OPTIMAL_HEIGHT = 380
    private const val MAX_WIDTH = 2200
    private const val MAX_UPSCALE = 3.0f

    fun preprocessRoiBitmap(
        source: Bitmap,
        highContrast: Boolean,
        binarize: Boolean
    ): Bitmap {
        val scaled = scaleToOptimalHeight(source)

        if (!highContrast && !binarize) {
            return scaled
        }

        if (binarize) {
            val binarized = binarizeSubtitleWithOutline(scaled)
            if (scaled !== source && scaled !== binarized) {
                scaled.recycle()
            }
            return binarized
        }

        val contrasted = applyHighContrastFilter(scaled)
        if (scaled !== source && scaled !== contrasted) {
            scaled.recycle()
        }
        return contrasted
    }

    private fun scaleToOptimalHeight(source: Bitmap): Bitmap {
        val h = source.height
        val w = source.width
        if (h <= 0 || w <= 0) return source

        var scaleFactor = 1.0f
        if (h < MIN_OPTIMAL_HEIGHT) {
            scaleFactor = min(MAX_UPSCALE, MIN_OPTIMAL_HEIGHT.toFloat() / h.toFloat())
        } else if (h > MAX_OPTIMAL_HEIGHT || w > MAX_WIDTH) {
            scaleFactor = min(
                MAX_OPTIMAL_HEIGHT.toFloat() / h.toFloat(),
                MAX_WIDTH.toFloat() / w.toFloat()
            )
        }

        if (scaleFactor in 0.95f..1.05f) {
            return source
        }

        val targetW = (w * scaleFactor).roundToInt().coerceAtLeast(64)
        val targetH = (h * scaleFactor).roundToInt().coerceAtLeast(32)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }

    private fun applyHighContrastFilter(input: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Matriz de alto contraste y ligera desaturación para resaltar letras claras y bordes oscuros
        val contrast = 1.35f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val matrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(input, 0f, 0f, paint)
        return output
    }

    /**
     * Binarización adaptativa pensada para subtítulos con borde negro:
     * Conserva los píxeles de alta luminancia (texto blanco/amarillo) que tienen contraste
     * local o vecindad oscura, convirtiendo el texto a negro sobre fondo blanco limpio para OCR.
     */
    private fun binarizeSubtitleWithOutline(input: Bitmap): Bitmap {
        val w = input.width
        val h = input.height
        val pixels = IntArray(w * h)
        input.getPixels(pixels, 0, w, 0, 0, w, h)

        val luma = IntArray(w * h)
        var sumLuma = 0L
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val y = (r * 77 + g * 150 + b * 29) shr 8
            luma[i] = y
            sumLuma += y
        }

        val avgLuma = (sumLuma / pixels.size.coerceAtLeast(1)).toInt()
        val brightThreshold = max(175, min(225, avgLuma + 55))

        val outPixels = IntArray(w * h)
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()

        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                val idx = rowOffset + x
                val lum = luma[idx]
                if (lum >= brightThreshold) {
                    // Texto brillante -> lo pintamos negro sobre fondo blanco para máxima legibilidad OCR
                    outPixels[idx] = black
                } else {
                    outPixels[idx] = white
                }
            }
        }

        return Bitmap.createBitmap(outPixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
