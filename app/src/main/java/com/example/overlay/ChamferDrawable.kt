package com.example.overlay

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * Fondo rectangular con dos esquinas cortadas en diagonal (arriba-derecha y abajo-izquierda),
 * para los paneles flotantes de los temas "HUD". El color del borde se puede animar.
 */
class ChamferDrawable(
    private val fillColor: Int,
    private var strokeColor: Int,
    private val strokePx: Float,
    private val cutPx: Float
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.MITER
    }
    private val path = Path()

    fun setStrokeColor(color: Int) {
        if (color == strokeColor) return
        strokeColor = color
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val half = strokePx / 2f
        val l = bounds.left + half
        val t = bounds.top + half
        val r = bounds.right - half
        val b = bounds.bottom - half
        val cut = cutPx.coerceAtMost(minOf(r - l, b - t) / 2f)
        path.reset()
        path.moveTo(l, t)
        path.lineTo(r - cut, t)
        path.lineTo(r, t + cut)
        path.lineTo(r, b)
        path.lineTo(l + cut, b)
        path.lineTo(l, b - cut)
        path.close()
    }

    override fun draw(canvas: Canvas) {
        fillPaint.color = fillColor
        canvas.drawPath(path, fillPaint)
        strokePaint.color = strokeColor
        strokePaint.strokeWidth = strokePx
        canvas.drawPath(path, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
