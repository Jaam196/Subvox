package com.example.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import com.example.domain.model.NormalizedRect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Resultado de "sondear" la pantalla (barato, sin OCR):
 * - [signature]: cuadrícula de píxeles tipo texto dentro de la zona de búsqueda.
 * - [band]: banda de texto localizada (o null si no hay nada que parezca un subtítulo).
 * - [bandRect]: esa banda en coordenadas normalizadas de pantalla (para mostrarla en el overlay).
 * - [frameHash]: huella muy gruesa de TODA la pantalla (detecta vídeo pausado).
 */
class FrameProbe(
    val frameWidth: Int,
    val frameHeight: Int,
    val isNewFrame: Boolean,
    val isMostlyBlack: Boolean,
    val frameHash: Long,
    val signature: IntArray,
    val band: TextBand?,
    val bandRect: NormalizedRect?
)

/**
 * Gestiona MediaProjection + VirtualDisplay + ImageReader.
 *
 * Diseño v2 (tiempo real):
 *  1. [probe] se llama muchas veces por segundo. Solo copia la franja de la zona de búsqueda con una
 *     única copia de memoria (`copyPixelsFromBuffer`) y muestrea los píxeles directamente del buffer
 *     para construir la cuadrícula de texto. No crea bitmaps nuevos ni ejecuta OCR.
 *  2. [cropBand] recorta (solo cuando hay que hacer OCR) el rectángulo ajustado al texto detectado.
 *  3. Cumple Android 14+: Callback registrado antes de createVirtualDisplay y redimensionado sin
 *     recrear la MediaProjection.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val onProjectionStoppedBySystem: () -> Unit
) {
    private val projectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var captureWidth: Int = 720
    private var captureHeight: Int = 1280
    private var screenDensityDpi: Int = DisplayMetrics.DENSITY_HIGH
    private var maxDimensionTarget: Int = MAX_DIMENSION_NORMAL
    private var lastDimensionCheckMs: Long = 0L

    // Franja reutilizable con la última zona copiada del frame
    private var stripBitmap: Bitmap? = null
    private var stripOriginX = 0
    private var stripOriginY = 0
    private var stripValidWidth = 0
    private var stripValidHeight = 0

    private var lastProbe: FrameProbe? = null

    private val gridCounts = IntArray(TextBandAnalyzer.COLS * TextBandAnalyzer.ROWS)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            releaseInternal()
            onProjectionStoppedBySystem()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            super.onCapturedContentResize(width, height)
            ensureDisplayDimensionsMatchesScreen(maxDimensionTarget <= MAX_DIMENSION_SAVER, force = true)
        }
    }

    @Synchronized
    fun startProjection(
        resultCode: Int,
        resultData: Intent,
        batterySaver: Boolean = false
    ): Boolean {
        releaseInternal()

        val thread = HandlerThread("SubVoxCaptureThread").apply { start() }
        handlerThread = thread
        val handler = Handler(thread.looper)
        backgroundHandler = handler

        val projection = runCatching {
            projectionManager.getMediaProjection(resultCode, resultData)
        }.getOrNull() ?: return false

        // OBLIGATORIO en Android 14 (API 34+): registrar Callback antes de createVirtualDisplay
        projection.registerCallback(projectionCallback, handler)
        mediaProjection = projection

        computeScaledDisplayMetrics(if (batterySaver) MAX_DIMENSION_SAVER else MAX_DIMENSION_NORMAL)

        val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        virtualDisplay = runCatching {
            projection.createVirtualDisplay(
                "SubVoxVirtualDisplay",
                captureWidth,
                captureHeight,
                screenDensityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
        }.getOrNull()

        return virtualDisplay != null
    }

    /**
     * Detecta rotación o cambio de tamaño y adapta VirtualDisplay + ImageReader
     * sin invalidar el token de MediaProjection. Se limita a 1 comprobación por segundo.
     */
    @Synchronized
    fun ensureDisplayDimensionsMatchesScreen(batterySaver: Boolean, force: Boolean = false) {
        val projection = mediaProjection ?: return
        val vDisplay = virtualDisplay ?: return

        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastDimensionCheckMs < 1000L) return
        lastDimensionCheckMs = now

        val oldWidth = captureWidth
        val oldHeight = captureHeight
        computeScaledDisplayMetrics(if (batterySaver) MAX_DIMENSION_SAVER else MAX_DIMENSION_NORMAL)

        if (oldWidth != captureWidth || oldHeight != captureHeight) {
            invalidateCachedFrame()
            imageReader?.close()
            val newReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
            imageReader = newReader
            runCatching {
                vDisplay.resize(captureWidth, captureHeight, screenDensityDpi)
                vDisplay.surface = newReader.surface
            }.onFailure {
                runCatching { vDisplay.release() }
                virtualDisplay = runCatching {
                    projection.createVirtualDisplay(
                        "SubVoxVirtualDisplay",
                        captureWidth,
                        captureHeight,
                        screenDensityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        newReader.surface,
                        null,
                        backgroundHandler
                    )
                }.getOrNull()
            }
        }
    }

    /**
     * Sondea la pantalla. Devuelve null si la captura no está activa o todavía no ha llegado ningún frame.
     * Si no hay frame nuevo (pantalla estática) devuelve el último sondeo con `isNewFrame = false`.
     */
    @Synchronized
    fun probe(zone: NormalizedRect, batterySaver: Boolean): FrameProbe? {
        ensureDisplayDimensionsMatchesScreen(batterySaver)
        val reader = imageReader ?: return null

        val image: Image? = runCatching { reader.acquireLatestImage() }.getOrNull()
        if (image == null) {
            val cached = lastProbe ?: return null
            return FrameProbe(
                frameWidth = cached.frameWidth,
                frameHeight = cached.frameHeight,
                isNewFrame = false,
                isMostlyBlack = cached.isMostlyBlack,
                frameHash = cached.frameHash,
                signature = cached.signature,
                band = cached.band,
                bandRect = cached.bandRect
            )
        }

        return try {
            analyzeImage(image, zone)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { image.close() }
        }
    }

    private fun analyzeImage(image: Image, zone: NormalizedRect): FrameProbe? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer: ByteBuffer = plane.buffer
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride != 4) return null

        val imgW = image.width
        val imgH = image.height

        val z = zone.clamp()
        val zx0 = (z.left * imgW).roundToInt().coerceIn(0, imgW - 32)
        val zy0 = (z.top * imgH).roundToInt().coerceIn(0, imgH - 16)
        val zx1 = (z.right * imgW).roundToInt().coerceIn(zx0 + 32, imgW)
        // La última fila del buffer puede no tener relleno completo: se excluye por seguridad.
        val zy1 = (z.bottom * imgH).roundToInt().coerceIn(zy0 + 16, imgH - 1)
        val zw = zx1 - zx0
        val zh = zy1 - zy0

        // 1) Copia de la franja de la zona (una sola operación de memoria)
        val stripPixelsPerRow = rowStride / 4
        var strip = stripBitmap
        if (strip == null || strip.width != stripPixelsPerRow || strip.height != zh || strip.isRecycled) {
            runCatching { strip?.recycle() }
            strip = Bitmap.createBitmap(stripPixelsPerRow, zh, Bitmap.Config.ARGB_8888)
            stripBitmap = strip
        }
        val stripBytes = rowStride * zh
        buffer.limit(min(buffer.capacity(), zy0 * rowStride + stripBytes))
        buffer.position(zy0 * rowStride)
        if (buffer.remaining() < stripBytes) return null
        strip!!.copyPixelsFromBuffer(buffer)
        stripOriginX = 0
        stripOriginY = zy0
        stripValidWidth = imgW
        stripValidHeight = zh
        buffer.clear()
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        // 2) Cuadrícula de píxeles tipo texto (claros con vecino oscuro) sobre la zona
        val cols = TextBandAnalyzer.COLS
        val rows = TextBandAnalyzer.ROWS
        TextBandAnalyzer.accumulateTextGrid(
            buffer = buffer,
            rowStride = rowStride,
            imgW = imgW,
            imgH = imgH,
            zx0 = zx0,
            zy0 = zy0,
            zx1 = zx1,
            zy1 = zy1,
            counts = gridCounts,
            cols = cols,
            rows = rows
        )

        val signature = gridCounts.copyOf()
        val band = TextBandAnalyzer.findBand(signature, cols, rows)

        var bandRect: NormalizedRect? = null
        if (band != null) {
            val cellW = zw.toFloat() / cols
            val cellH = zh.toFloat() / rows
            val padX = cellW * 2.0f
            val padY = cellH * 1.0f + 4f
            val bx0 = max(0f, zx0 + band.leftCol * cellW - padX)
            val bx1 = min(imgW.toFloat(), zx0 + (band.rightCol + 1) * cellW + padX)
            val by0 = max(zy0.toFloat(), zy0 + band.topRow * cellH - padY)
            val by1 = min(zy1.toFloat(), zy0 + (band.bottomRow + 1) * cellH + padY)
            bandRect = NormalizedRect(
                left = bx0 / imgW,
                top = by0 / imgH,
                right = bx1 / imgW,
                bottom = by1 / imgH
            )
        }

        // 3) Huella gruesa de toda la pantalla + detección de pantalla negra (DRM / FLAG_SECURE)
        var hash = 1125899906842597L
        var blackSamples = 0
        var totalSamples = 0
        val gridX = 40
        val gridY = 24
        for (gy in 0 until gridY) {
            val sy = min(imgH - 2, ((gy + 0.5f) * imgH / gridY).toInt())
            val rowOff = sy * rowStride
            for (gx in 0 until gridX) {
                val sx = min(imgW - 1, ((gx + 0.5f) * imgW / gridX).toInt())
                val v = buffer.getInt(rowOff + sx * 4)
                val r = v and 0xFF
                val g = (v shr 8) and 0xFF
                val b = (v shr 16) and 0xFF
                totalSamples++
                if (r < 4 && g < 4 && b < 4) blackSamples++
                val q = ((r + g + b) / 3) shr 4
                hash = hash * 31L + q.toLong()
            }
        }
        val mostlyBlack = totalSamples > 0 && blackSamples.toFloat() / totalSamples >= 0.985f

        val probe = FrameProbe(
            frameWidth = imgW,
            frameHeight = imgH,
            isNewFrame = true,
            isMostlyBlack = mostlyBlack,
            frameHash = hash,
            signature = signature,
            band = band,
            bandRect = bandRect
        )
        lastProbe = probe
        return probe
    }

    /**
     * Recorta de la última franja copiada el rectángulo ajustado al texto detectado
     * (o toda la zona si [useWholeZone] es true). El llamador debe reciclar el bitmap devuelto.
     */
    @Synchronized
    fun cropBand(probe: FrameProbe, useWholeZone: Boolean = false): Bitmap? {
        val strip = stripBitmap ?: return null
        if (strip.isRecycled) return null
        val imgW = probe.frameWidth
        val imgH = probe.frameHeight
        if (imgW <= 0 || imgH <= 0) return null

        val rect = if (useWholeZone) null else probe.bandRect
        val maxX = min(stripValidWidth, strip.width)

        var x0 = 0
        var y0 = 0
        var x1 = maxX
        var y1 = strip.height
        if (rect != null) {
            x0 = (rect.left * imgW).roundToInt().coerceIn(0, maxX)
            x1 = (rect.right * imgW).roundToInt().coerceIn(0, maxX)
            y0 = ((rect.top * imgH).roundToInt() - stripOriginY).coerceIn(0, strip.height)
            y1 = ((rect.bottom * imgH).roundToInt() - stripOriginY).coerceIn(0, strip.height)
        }
        if (x1 - x0 < 8 || y1 - y0 < 8) return null

        return runCatching { Bitmap.createBitmap(strip, x0, y0, x1 - x0, y1 - y0) }.getOrNull()
    }

    private fun invalidateCachedFrame() {
        lastProbe = null
        runCatching { stripBitmap?.recycle() }
        stripBitmap = null
    }

    private fun computeScaledDisplayMetrics(maxDimension: Int) {
        val rawWidth: Int
        val rawHeight: Int
        val densityDpi: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            rawWidth = bounds.width().coerceAtLeast(320)
            rawHeight = bounds.height().coerceAtLeast(320)
            densityDpi = context.resources.configuration.densityDpi
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            rawWidth = metrics.widthPixels.coerceAtLeast(320)
            rawHeight = metrics.heightPixels.coerceAtLeast(320)
            densityDpi = metrics.densityDpi
        }

        val largestSide = max(rawWidth, rawHeight)
        val scale = if (largestSide > maxDimension) {
            maxDimension.toFloat() / largestSide.toFloat()
        } else {
            1.0f
        }

        captureWidth = ((rawWidth * scale).roundToInt() and 0xFFFE).coerceAtLeast(320)
        captureHeight = ((rawHeight * scale).roundToInt() and 0xFFFE).coerceAtLeast(320)
        screenDensityDpi = densityDpi
        maxDimensionTarget = maxDimension
    }

    @Synchronized
    fun stopProjection() {
        releaseInternal()
    }

    @Synchronized
    private fun releaseInternal() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { imageReader?.close() }
        imageReader = null

        runCatching {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        }
        mediaProjection = null

        invalidateCachedFrame()

        runCatching { handlerThread?.quitSafely() }
        handlerThread = null
        backgroundHandler = null
    }

    companion object {
        /** Resolución máxima (lado largo) de captura: el texto pequeño necesita resolución para el OCR. */
        const val MAX_DIMENSION_NORMAL = 1920
        const val MAX_DIMENSION_SAVER = 1280
    }
}
