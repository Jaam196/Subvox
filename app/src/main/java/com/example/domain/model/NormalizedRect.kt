package com.example.domain.model

/**
 * Representa una región rectangular normalizada en el rango [0.0f .. 1.0f]
 * respecto al ancho y alto de la pantalla actual.
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0.05f)
    val height: Float get() = (bottom - top).coerceAtLeast(0.05f)
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    fun clamp(): NormalizedRect {
        val l = left.coerceIn(0.01f, 0.90f)
        val t = top.coerceIn(0.05f, 0.92f)
        val r = right.coerceIn(l + 0.08f, 0.99f)
        val b = bottom.coerceIn(t + 0.06f, 0.99f)
        return NormalizedRect(l, t, r, b)
    }

    fun toDisplayString(): String {
        val l = (left * 100).toInt()
        val t = (top * 100).toInt()
        val r = (right * 100).toInt()
        val b = (bottom * 100).toInt()
        return "X: $l%–$r% | Y: $t%–$b%"
    }

    companion object {
        /**
         * Región inferior estándar donde aparecen los subtítulos de anime (1 o 2 líneas).
         */
        val DEFAULT_SUBTITLE_ROI = NormalizedRect(
            left = 0.07f,
            top = 0.72f,
            right = 0.93f,
            bottom = 0.94f
        )
    }
}
