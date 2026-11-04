package com.example.capture

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas del detector de texto con fotogramas RGBA sintéticos (sin Android): texto blanco, amarillo
 * y cian con contorno negro sobre fondos de escena, cielos blancos y barras de progreso sin texto,
 * y subtítulos pequeños en un reproductor vertical.
 */
class SyntheticFrameDetectionTest {

    class Frame(val w: Int, val h: Int) {
        val rowStride = w * 4
        val buf: ByteBuffer = ByteBuffer.allocateDirect(rowStride * h).order(ByteOrder.LITTLE_ENDIAN)
        fun set(x: Int, y: Int, r: Int, g: Int, b: Int) {
            if (x < 0 || y < 0 || x >= w || y >= h) return
            val o = y * rowStride + x * 4
            buf.put(o, r.toByte()); buf.put(o + 1, g.toByte()); buf.put(o + 2, b.toByte()); buf.put(o + 3, 255.toByte())
        }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, r: Int, g: Int, b: Int) {
            for (y in y0 until y1) for (x in x0 until x1) set(x, y, r, g, b)
        }
    }

    /** Fondo "anime": degradado + manchas de ruido suave. */
    fun background(f: Frame, rnd: Random, base: Int = 90) {
        for (y in 0 until f.h) for (x in 0 until f.w) {
            val v = (base + 40 * Math.sin(x / 90.0) * Math.cos(y / 70.0)).toInt().coerceIn(0, 255) + rnd.nextInt(-6, 7)
            f.set(x, y, v.coerceIn(0, 255), (v * 0.9).toInt().coerceIn(0, 255), (v * 1.1).toInt().coerceIn(0, 255))
        }
    }

    /** Texto sintético: tallos verticales + barras horizontales (como letras), relleno claro con contorno negro. */
    fun drawText(f: Frame, x0: Int, x1: Int, yTop: Int, glyphH: Int, stem: Int, fill: IntArray, outline: Int = 3) {
        var x = x0
        var i = 0
        while (x < x1) {
            val gw = glyphH * 3 / 5
            // contorno
            f.rect(x - outline, yTop - outline, x + stem + outline, yTop + glyphH + outline, 0, 0, 0)
            f.rect(x - outline, yTop - outline, x + gw + outline, yTop + stem + outline, 0, 0, 0)
            if (i % 2 == 0) f.rect(x - outline, yTop + glyphH / 2 - stem / 2 - outline, x + gw + outline, yTop + glyphH / 2 + stem / 2 + outline, 0, 0, 0)
            // relleno
            f.rect(x, yTop, x + stem, yTop + glyphH, fill[0], fill[1], fill[2])
            f.rect(x, yTop, x + gw, yTop + stem, fill[0], fill[1], fill[2])
            if (i % 2 == 0) f.rect(x, yTop + glyphH / 2 - stem / 2, x + gw, yTop + glyphH / 2 + stem / 2, fill[0], fill[1], fill[2])
            x += gw + glyphH / 3
            i++
            if (i % 6 == 0) x += glyphH / 2 // espacio entre palabras
        }
    }

    fun analyze(f: Frame, top: Float = 0.50f, bottom: Float = 0.98f): Pair<TextBand?, IntArray> {
        val zx0 = (0.03f * f.w).toInt(); val zx1 = (0.97f * f.w).toInt()
        val zy0 = (top * f.h).toInt(); val zy1 = (bottom * f.h).toInt().coerceAtMost(f.h - 1)
        val g = IntArray(TextBandAnalyzer.COLS * TextBandAnalyzer.ROWS)
        TextBandAnalyzer.accumulateTextGrid(f.buf, f.rowStride, f.w, f.h, zx0, zy0, zx1, zy1, g)
        return TextBandAnalyzer.findBand(g) to g
    }

    fun cellRow(y: Int, h: Int, top: Float = 0.50f, bottom: Float = 0.98f): Int {
        val zy0 = (top * h).toInt(); val zy1 = (bottom * h).toInt().coerceAtMost(h - 1)
        return ((y - zy0) * TextBandAnalyzer.ROWS) / (zy1 - zy0)
    }



    private val sizes = listOf(1920 to 864, 1280 to 576, 2400 to 1080)

    @Test
    fun `detecta subtitulos blancos de dos lineas en varias resoluciones`() {
        val rnd = Random(7)
        for ((w, h) in sizes) {
            val scale = h / 864.0
            val glyph = (38 * scale).toInt()
            val stem = (6 * scale).toInt().coerceAtLeast(3)
            val f = Frame(w, h)
            background(f, rnd)
            val y1 = (h * 0.82).toInt()
            val y2 = y1 + glyph + (14 * scale).toInt()
            drawText(f, (w * 0.22).toInt(), (w * 0.78).toInt(), y1, glyph, stem, intArrayOf(255, 255, 255))
            drawText(f, (w * 0.30).toInt(), (w * 0.70).toInt(), y2, glyph, stem, intArrayOf(255, 255, 255))
            val (band, _) = analyze(f)
            assertNotNull("sin banda en ${w}x$h", band)
            assertTrue(band!!.topRow <= cellRow(y1, h) + 1)
            assertTrue(band.bottomRow >= cellRow(y2 + glyph, h) - 1)
        }
    }

    @Test
    fun `detecta subtitulos amarillos y cian`() {
        val rnd = Random(3)
        for ((w, h) in sizes) {
            val scale = h / 864.0
            val glyph = (38 * scale).toInt()
            val stem = (6 * scale).toInt().coerceAtLeast(3)
            val y1 = (h * 0.82).toInt()
            for (color in listOf(intArrayOf(255, 235, 60), intArrayOf(80, 240, 255))) {
                val f = Frame(w, h)
                background(f, rnd, 75)
                drawText(f, (w * 0.28).toInt(), (w * 0.72).toInt(), y1, glyph, stem, color)
                assertNotNull(analyze(f).first)
            }
        }
    }

    @Test
    fun `un cielo blanco grande no es un subtitulo`() {
        val rnd = Random(5)
        for ((w, h) in sizes) {
            val f = Frame(w, h)
            background(f, rnd)
            f.rect(0, (h * 0.52).toInt(), w, (h * 0.70).toInt(), 250, 250, 255)
            assertNull(analyze(f).first)
        }
    }

    @Test
    fun `detecta subtitulo pequeno en reproductor vertical y no confunde una barra de progreso`() {
        val rnd = Random(11)
        val w = 886
        val h = 1920
        val f = Frame(w, h)
        background(f, rnd)
        val yT = (h * 0.30).toInt()
        drawText(f, (w * 0.12).toInt(), (w * 0.88).toInt(), yT, 26, 3, intArrayOf(255, 255, 255), outline = 2)
        drawText(f, (w * 0.18).toInt(), (w * 0.82).toInt(), yT + 36, 26, 3, intArrayOf(255, 255, 255), outline = 2)
        assertNotNull(analyze(f, 0.04f, 0.62f).first)

        val bar = Frame(w, h)
        background(bar, rnd)
        val y = (h * 0.55).toInt()
        bar.rect((w * 0.05).toInt(), y - 3, (w * 0.95).toInt(), y, 0, 0, 0)
        bar.rect((w * 0.05).toInt(), y, (w * 0.95).toInt(), y + 5, 255, 255, 255)
        bar.rect((w * 0.05).toInt(), y + 5, (w * 0.95).toInt(), y + 8, 0, 0, 0)
        assertNull(analyze(bar, 0.04f, 0.62f).first)
    }

    @Test
    fun `la firma es estable con el mismo texto y distinta con otro texto`() {
        val w = 1920
        val h = 864
        val y1 = (h * 0.82).toInt()
        val a = Frame(w, h).also { background(it, Random(1), 80) }
        val b = Frame(w, h).also { background(it, Random(2), 110) }
        val c = Frame(w, h).also { background(it, Random(1), 80) }
        drawText(a, (w * 0.22).toInt(), (w * 0.78).toInt(), y1, 38, 6, intArrayOf(255, 255, 255))
        drawText(b, (w * 0.22).toInt(), (w * 0.78).toInt(), y1, 38, 6, intArrayOf(255, 255, 255))
        drawText(c, (w * 0.30).toInt(), (w * 0.55).toInt(), y1, 38, 6, intArrayOf(255, 255, 255))
        val ga = analyze(a).second
        val gb = analyze(b).second
        val gc = analyze(c).second
        assertTrue(TextBandAnalyzer.difference(ga, gb) < 0.22f)
        assertTrue(TextBandAnalyzer.difference(ga, gc) > 0.22f)
    }
}
