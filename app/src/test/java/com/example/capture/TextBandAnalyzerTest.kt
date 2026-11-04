package com.example.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextBandAnalyzerTest {

    private val cols = TextBandAnalyzer.COLS
    private val rows = TextBandAnalyzer.ROWS

    private fun grid(vararg rects: IntArray): IntArray {
        // rect = [fila0, fila1, col0, col1, valor]
        val g = IntArray(cols * rows)
        for (r in rects) for (row in r[0]..r[1]) for (col in r[2]..r[3]) g[row * cols + col] = r[4]
        return g
    }

    @Test
    fun `una cuadricula vacia no tiene banda`() {
        assertNull(TextBandAnalyzer.findBand(IntArray(cols * rows)))
    }

    @Test
    fun `detecta una linea de subtitulo`() {
        val band = TextBandAnalyzer.findBand(grid(intArrayOf(19, 20, 12, 51, 12)))
        assertNotNull(band)
        assertEquals(19, band!!.topRow)
        assertEquals(20, band.bottomRow)
    }

    @Test
    fun `fusiona dos lineas separadas por un hueco pequeno`() {
        val band = TextBandAnalyzer.findBand(grid(intArrayOf(17, 18, 14, 49, 12), intArrayOf(21, 22, 10, 53, 12)))
        assertNotNull(band)
        assertEquals(17, band!!.topRow)
        assertEquals(22, band.bottomRow)
    }

    @Test
    fun `prefiere el bloque inferior frente a un cartel alto`() {
        val band = TextBandAnalyzer.findBand(grid(intArrayOf(3, 4, 20, 40, 12), intArrayOf(20, 21, 12, 51, 12)))
        assertNotNull(band)
        assertEquals(20, band!!.topRow)
    }

    @Test
    fun `una sola fila fina no es un subtitulo`() {
        assertNull(TextBandAnalyzer.findBand(grid(intArrayOf(10, 10, 5, 58, 30))))
    }

    @Test
    fun `puntos aislados no son un subtitulo`() {
        val g = IntArray(cols * rows)
        g[5 * cols + 5] = 20
        g[10 * cols + 30] = 20
        assertNull(TextBandAnalyzer.findBand(g))
    }

    @Test
    fun `la diferencia distingue mismo texto de texto distinto`() {
        val a = grid(intArrayOf(19, 20, 12, 51, 12))
        val sameWithNoise = a.copyOf().also { it[19 * cols + 20] = 11 }
        val other = grid(intArrayOf(17, 18, 14, 49, 12), intArrayOf(21, 22, 10, 53, 12))
        assertEquals(0f, TextBandAnalyzer.difference(a, a.copyOf()), 0.0001f)
        assertTrue(TextBandAnalyzer.difference(a, sameWithNoise) < 0.12f)
        assertTrue(TextBandAnalyzer.difference(a, other) > 0.4f)
        assertEquals(1f, TextBandAnalyzer.difference(null, a), 0.0001f)
    }

    @Test
    fun `la diferencia por filas ignora cambios fuera de la banda`() {
        val a = grid(intArrayOf(19, 20, 12, 51, 12), intArrayOf(2, 3, 5, 20, 12))
        val b = grid(intArrayOf(19, 20, 12, 51, 12), intArrayOf(5, 6, 30, 55, 12))
        assertTrue(TextBandAnalyzer.difference(a, b) > 0.1f)
        assertEquals(0f, TextBandAnalyzer.difference(a, b, 18, 21), 0.0001f)
    }
}
