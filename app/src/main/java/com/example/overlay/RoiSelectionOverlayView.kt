package com.example.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.example.domain.model.NormalizedRect
import kotlin.math.hypot
import kotlin.math.min

/**
 * Vista de superposición para seleccionar, arrastrar y redimensionar manualmente la región (ROI)
 * donde aparecen los subtítulos, o para previsualizar el borde de la ROI en pantalla sin interceptar toques.
 * Usa la paleta de SubVox.
 */
@SuppressLint("ViewConstructor")
class RoiSelectionOverlayView(
    context: Context,
    initialRoi: NormalizedRect,
    private val isInteractiveEditMode: Boolean,
    private val onRoiSaved: (NormalizedRect) -> Unit,
    private val onCancelEdit: () -> Unit
) : View(context) {

    private var currentRoi: NormalizedRect = initialRoi.clamp()
    private var currentOverlayStyle: OverlayStyle = SubVoxOverlayStyle

    private val density = resources.displayMetrics.density
    private val cornerHandleRadiusPx = 18f * density
    private val cornerTouchSlopPx = 36f * density
    private val buttonHeightPx = 44f * density
    private val buttonWidthPx = 132f * density

    private enum class DragTarget {
        NONE,
        MOVE_WHOLE_RECT,
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    private var activeDragTarget = DragTarget.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val dimScrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 8, 12, 24)
        style = Paint.Style.FILL
    }

    private val roiBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(56, 189, 248)
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        pathEffect = if (!isInteractiveEditMode) {
            DashPathEffect(floatArrayOf(14f * density, 10f * density), 0f)
        } else {
            null
        }
    }

    private val roiFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(35, 56, 189, 248)
        style = Paint.Style.FILL
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(251, 113, 133)
        style = Paint.Style.FILL
    }

    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 15, 23, 42)
        style = Paint.Style.FILL
    }

    private val saveBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(16, 185, 129)
        style = Paint.Style.FILL
    }

    private val cancelBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 29, 72)
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13.5f * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private val rectPx = RectF()
    private val saveBtnRect = RectF()
    private val cancelBtnRect = RectF()
    private val headerPillRect = RectF()
    private val chamferPath = Path()

    fun updateNormalizedRoi(roi: NormalizedRect) {
        currentRoi = roi.clamp()
        invalidate()
    }

    fun applyOverlayStyle(style: OverlayStyle) {
        currentOverlayStyle = style
        roiBorderPaint.color = style.primaryAccentArgb
        roiBorderPaint.strokeWidth = style.strokeWidthDp * density
        roiBorderPaint.pathEffect = if (!isInteractiveEditMode && style.dashedRoiGuide) {
            DashPathEffect(floatArrayOf(14f * density, 10f * density), 0f)
        } else {
            null
        }

        val r = Color.red(style.primaryAccentArgb)
        val g = Color.green(style.primaryAccentArgb)
        val b = Color.blue(style.primaryAccentArgb)
        roiFillPaint.color = Color.argb(38, r, g, b)

        handlePaint.color = style.secondaryAccentArgb
        labelBgPaint.color = style.panelBackgroundColorArgb
        cancelBtnPaint.color = style.dangerBgArgb
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        rectPx.set(
            currentRoi.left * w,
            currentRoi.top * h,
            currentRoi.right * w,
            currentRoi.bottom * h
        )

        val cornerRadiusPx = currentOverlayStyle.cornerRadiusDp * density

        if (isInteractiveEditMode) {
            // Oscurecer exterior de la ROI para que el usuario vea con nitidez la zona seleccionada
            canvas.drawRect(0f, 0f, w, rectPx.top, dimScrimPaint)
            canvas.drawRect(0f, rectPx.bottom, w, h, dimScrimPaint)
            canvas.drawRect(0f, rectPx.top, rectPx.left, rectPx.bottom, dimScrimPaint)
            canvas.drawRect(rectPx.right, rectPx.top, w, rectPx.bottom, dimScrimPaint)

            canvas.drawRoundRect(rectPx, cornerRadiusPx, cornerRadiusPx, roiFillPaint)
            drawThemedRoiBorder(canvas, rectPx, cornerRadiusPx)

            // Dibujar los 4 tiradores de las esquinas
            drawHandle(canvas, rectPx.left, rectPx.top)
            drawHandle(canvas, rectPx.right, rectPx.top)
            drawHandle(canvas, rectPx.left, rectPx.bottom)
            drawHandle(canvas, rectPx.right, rectPx.bottom)

            // Etiqueta de instrucciones + botones Guardar / Cancelar encima o debajo de la ROI
            val controlsY = if (rectPx.top > 120f * density) {
                rectPx.top - 64f * density
            } else {
                min(h - 70f * density, rectPx.bottom + 20f * density)
            }

            val centerX = w * 0.5f
            val gap = 16f * density
            cancelBtnRect.set(
                centerX - buttonWidthPx - gap * 0.5f,
                controlsY,
                centerX - gap * 0.5f,
                controlsY + buttonHeightPx
            )
            saveBtnRect.set(
                centerX + gap * 0.5f,
                controlsY,
                centerX + buttonWidthPx + gap * 0.5f,
                controlsY + buttonHeightPx
            )

            val pillTop = (controlsY - 40f * density).coerceAtLeast(16f * density)
            headerPillRect.set(
                centerX - 165f * density,
                pillTop,
                centerX + 165f * density,
                pillTop + 32f * density
            )
            val btnCorner = cornerRadiusPx.coerceIn(0f, 16f * density)
            canvas.drawRoundRect(headerPillRect, btnCorner, btnCorner, labelBgPaint)
            val fontMetrics = textPaint.fontMetrics
            val textOffset = (fontMetrics.descent + fontMetrics.ascent) / 2f
            canvas.drawText(
                "Arrastra la caja o esquinas sobre los subtítulos",
                headerPillRect.centerX(),
                headerPillRect.centerY() - textOffset,
                textPaint
            )

            canvas.drawRoundRect(cancelBtnRect, btnCorner, btnCorner, cancelBtnPaint)
            canvas.drawText(
                "CANCELAR",
                cancelBtnRect.centerX(),
                cancelBtnRect.centerY() - textOffset,
                textPaint
            )

            canvas.drawRoundRect(saveBtnRect, btnCorner, btnCorner, saveBtnPaint)
            canvas.drawText(
                "GUARDAR ROI",
                saveBtnRect.centerX(),
                saveBtnRect.centerY() - textOffset,
                textPaint
            )
        } else {
            // Solo guía visual pasiva (sin relleno para no interferir con el OCR)
            // Dibujamos 4px por fuera para que nunca entre en el recorte del Bitmap
            val inset = -4f * density
            rectPx.inset(inset, inset)
            drawThemedRoiBorder(canvas, rectPx, cornerRadiusPx)
        }
    }

    private fun drawThemedRoiBorder(canvas: Canvas, rect: RectF, cornerRadiusPx: Float) {
        if (currentOverlayStyle.cutCornersInRoi) {
            val cut = 12f * density
            chamferPath.reset()
            chamferPath.moveTo(rect.left + cut, rect.top)
            chamferPath.lineTo(rect.right - cut, rect.top)
            chamferPath.lineTo(rect.right, rect.top + cut)
            chamferPath.lineTo(rect.right, rect.bottom - cut)
            chamferPath.lineTo(rect.right - cut, rect.bottom)
            chamferPath.lineTo(rect.left + cut, rect.bottom)
            chamferPath.lineTo(rect.left, rect.bottom - cut)
            chamferPath.lineTo(rect.left, rect.top + cut)
            chamferPath.close()
            canvas.drawPath(chamferPath, roiBorderPaint)
        } else {
            canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, roiBorderPaint)
        }
    }

    private fun drawHandle(canvas: Canvas, cx: Float, cy: Float) {
        if (currentOverlayStyle.cornerRadiusDp <= 1f) {
            val r = cornerHandleRadiusPx * 0.85f
            canvas.drawRect(cx - r, cy - r, cx + r, cy + r, handlePaint)
            canvas.drawRect(cx - r, cy - r, cx + r, cy + r, handleStrokePaint)
        } else {
            canvas.drawCircle(cx, cy, cornerHandleRadiusPx, handlePaint)
            canvas.drawCircle(cx, cy, cornerHandleRadiusPx, handleStrokePaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isInteractiveEditMode) return false

        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (saveBtnRect.contains(x, y)) {
                    onRoiSaved(currentRoi.clamp())
                    return true
                }
                if (cancelBtnRect.contains(x, y)) {
                    onCancelEdit()
                    return true
                }

                activeDragTarget = resolveDragTarget(x, y)
                lastTouchX = x
                lastTouchY = y
                return activeDragTarget != DragTarget.NONE
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeDragTarget == DragTarget.NONE) return false
                val dxNorm = (x - lastTouchX) / w
                val dyNorm = (y - lastTouchY) / h
                lastTouchX = x
                lastTouchY = y

                val minW = 0.18f
                val minH = 0.07f

                currentRoi = when (activeDragTarget) {
                    DragTarget.MOVE_WHOLE_RECT -> {
                        val curW = currentRoi.width
                        val curH = currentRoi.height
                        val newLeft = (currentRoi.left + dxNorm).coerceIn(0.01f, 0.99f - curW)
                        val newTop = (currentRoi.top + dyNorm).coerceIn(0.05f, 0.99f - curH)
                        NormalizedRect(newLeft, newTop, newLeft + curW, newTop + curH)
                    }
                    DragTarget.TOP_LEFT -> {
                        val newLeft = (currentRoi.left + dxNorm).coerceIn(0.01f, currentRoi.right - minW)
                        val newTop = (currentRoi.top + dyNorm).coerceIn(0.05f, currentRoi.bottom - minH)
                        currentRoi.copy(left = newLeft, top = newTop)
                    }
                    DragTarget.TOP_RIGHT -> {
                        val newRight = (currentRoi.right + dxNorm).coerceIn(currentRoi.left + minW, 0.99f)
                        val newTop = (currentRoi.top + dyNorm).coerceIn(0.05f, currentRoi.bottom - minH)
                        currentRoi.copy(right = newRight, top = newTop)
                    }
                    DragTarget.BOTTOM_LEFT -> {
                        val newLeft = (currentRoi.left + dxNorm).coerceIn(0.01f, currentRoi.right - minW)
                        val newBottom = (currentRoi.bottom + dyNorm).coerceIn(currentRoi.top + minH, 0.99f)
                        currentRoi.copy(left = newLeft, bottom = newBottom)
                    }
                    DragTarget.BOTTOM_RIGHT -> {
                        val newRight = (currentRoi.right + dxNorm).coerceIn(currentRoi.left + minW, 0.99f)
                        val newBottom = (currentRoi.bottom + dyNorm).coerceIn(currentRoi.top + minH, 0.99f)
                        currentRoi.copy(right = newRight, bottom = newBottom)
                    }
                    DragTarget.NONE -> currentRoi
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeDragTarget = DragTarget.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun resolveDragTarget(x: Float, y: Float): DragTarget {
        if (hypot(x - rectPx.left, y - rectPx.top) <= cornerTouchSlopPx) return DragTarget.TOP_LEFT
        if (hypot(x - rectPx.right, y - rectPx.top) <= cornerTouchSlopPx) return DragTarget.TOP_RIGHT
        if (hypot(x - rectPx.left, y - rectPx.bottom) <= cornerTouchSlopPx) return DragTarget.BOTTOM_LEFT
        if (hypot(x - rectPx.right, y - rectPx.bottom) <= cornerTouchSlopPx) return DragTarget.BOTTOM_RIGHT
        if (rectPx.contains(x, y)) return DragTarget.MOVE_WHOLE_RECT
        return DragTarget.NONE
    }
}
