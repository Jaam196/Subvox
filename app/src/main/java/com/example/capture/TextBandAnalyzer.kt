package com.example.capture

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Banda de texto localizada dentro de la cuadrícula de análisis (filas/columnas de celdas).
 */
data class TextBand(
    val topRow: Int,
    val bottomRow: Int,
    val leftCol: Int,
    val rightCol: Int,
    val energy: Int
)

/**
 * Analiza la cuadrícula de "píxeles tipo texto" (píxeles claros con borde oscuro, típicos de un
 * subtítulo) para:
 *  1. Localizar dónde hay un subtítulo SIN ejecutar OCR (barato: solo aritmética sobre ~1.500 celdas).
 *  2. Detectar si el subtítulo ha cambiado comparando dos cuadrículas.
 *
 * Es lógica pura de Kotlin (sin Android) para poder probarla con tests unitarios.
 */
object TextBandAnalyzer {

    const val COLS = 64
    const val ROWS = 24

    /** Aciertos mínimos para considerar activa una celda. */
    const val MIN_ACTIVE_CELL_HITS = 6

    private const val MAX_GAP_ROWS_IN_BLOCK = 2

    /**
     * Rellena [counts] (COLS x ROWS) con el número de píxeles "tipo texto" por celda dentro de la zona
     * [zx0,zx1) x [zy0,zy1) de un frame RGBA_8888 (bytes R,G,B,A con el orden little-endian del buffer).
     *
     * Píxel tipo texto = píxel CLARO (blanco, amarillo, cian, rosa...) con algún vecino OSCURO a unos
     * píxeles de distancia (el contorno negro típico de los subtítulos). Las zonas claras grandes y
     * lisas (cielos, luces) solo aportan su perímetro, así que no se confunden con texto.
     *
     * El buffer debe estar en `ByteOrder.LITTLE_ENDIAN`. No modifica posición ni límite del buffer.
     */
    fun accumulateTextGrid(
        buffer: ByteBuffer,
        rowStride: Int,
        imgW: Int,
        imgH: Int,
        zx0: Int,
        zy0: Int,
        zx1: Int,
        zy1: Int,
        counts: IntArray,
        cols: Int = COLS,
        rows: Int = ROWS
    ) {
        java.util.Arrays.fill(counts, 0)
        val zw = zx1 - zx0
        val zh = zy1 - zy0
        if (zw <= 0 || zh <= 0) return

        val stepX = max(2, imgW / 640)
        val stepY = max(2, imgH / 540)
        // Distancias de sondeo (cerca y lejos) escaladas con el lado corto de la imagen: así sirve tanto
        // para letra grande a pantalla completa como para letra pequeña en un reproductor vertical.
        val shortSide = minOf(imgW, imgH)
        val d = max(2, (shortSide / 540.0f).roundToInt())
        val d2 = max(d + 1, (shortSide / 270.0f).roundToInt())

        var y = max(zy0 + d2, d2)
        val yEnd = minOf(zy1 - d2, imgH - 1 - d2)
        while (y < yEnd) {
            val row = ((y - zy0) * rows) / zh
            val rowOffset = y * rowStride
            var x = max(zx0 + d2, d2)
            val xEnd = minOf(zx1 - d2, imgW - d2)
            while (x < xEnd) {
                val v = buffer.getInt(rowOffset + x * 4)
                if (isBright(v)) {
                    val horizontalBoundary =
                        isDark(buffer.getInt(rowOffset + (x - d) * 4)) ||
                            isDark(buffer.getInt(rowOffset + (x + d) * 4)) ||
                            isDark(buffer.getInt(rowOffset + (x - d2) * 4)) ||
                            isDark(buffer.getInt(rowOffset + (x + d2) * 4))
                    val verticalBoundary =
                        isDark(buffer.getInt((y - d) * rowStride + x * 4)) ||
                            isDark(buffer.getInt((y + d) * rowStride + x * 4)) ||
                            isDark(buffer.getInt((y - d2) * rowStride + x * 4)) ||
                            isDark(buffer.getInt((y + d2) * rowStride + x * 4))
                    if (horizontalBoundary || verticalBoundary) {
                        val col = ((x - zx0) * cols) / zw
                        // Parte baja: contorno a izquierda/derecha. Parte alta: contorno arriba/abajo.
                        counts[row * cols + col] +=
                            (if (horizontalBoundary) 1 else 0) + (if (verticalBoundary) (1 shl 16) else 0)
                    }
                }
                x += stepX
            }
            y += stepY
        }

        // Puntuación por celda: el texto tiene bordes en AMBAS direcciones (trazos verticales y
        // horizontales), mientras que el borde de un cielo, una barra de progreso o un marco solo
        // tiene una dirección. Se premia el equilibrio entre ambas.
        val total = cols * rows
        for (i in 0 until total) {
            val c = counts[i]
            if (c == 0) continue
            val h = c and 0xFFFF
            val v = c ushr 16
            counts[i] = 2 * minOf(h, v) + (h + v) / 6
        }
    }

    /** Píxel claro: canal máximo alto y luminosidad alta. `v` = entero little-endian 0xAABBGGRR. */
    fun isBright(v: Int): Boolean {
        val r = v and 0xFF
        val g = (v shr 8) and 0xFF
        val b = (v shr 16) and 0xFF
        val mx = max(r, max(g, b))
        if (mx < 200) return false
        val luma = (r * 77 + g * 150 + b * 29) shr 8
        return luma >= 150
    }

    /** Píxel oscuro (contorno negro típico de subtítulos). */
    fun isDark(v: Int): Boolean {
        val r = v and 0xFF
        val g = (v shr 8) and 0xFF
        val b = (v shr 16) and 0xFF
        return ((r * 77 + g * 150 + b * 29) shr 8) <= 105
    }

    fun findBand(counts: IntArray, cols: Int = COLS, rows: Int = ROWS): TextBand? {
        if (counts.size < cols * rows) return null

        val rowEnergy = IntArray(rows)
        val rowActiveCells = IntArray(rows)
        for (r in 0 until rows) {
            var energy = 0
            var active = 0
            val base = r * cols
            for (c in 0 until cols) {
                val v = counts[base + c]
                if (v >= MIN_ACTIVE_CELL_HITS) {
                    energy += v
                    active++
                }
            }
            rowEnergy[r] = energy
            rowActiveCells[r] = active
        }

        val minCellsPerRow = max(3, cols / 16)

        // Agrupar filas activas en bloques (permitiendo huecos pequeños entre líneas de un mismo subtítulo)
        data class Block(val top: Int, val bottom: Int, val energy: Int)

        val blocks = mutableListOf<Block>()
        var r = 0
        while (r < rows) {
            if (rowActiveCells[r] >= minCellsPerRow) {
                val top = r
                var bottom = r
                var energy = rowEnergy[r]
                var gap = 0
                var k = r + 1
                while (k < rows && gap <= MAX_GAP_ROWS_IN_BLOCK) {
                    if (rowActiveCells[k] >= minCellsPerRow) {
                        bottom = k
                        energy += rowEnergy[k]
                        gap = 0
                    } else {
                        gap++
                    }
                    k++
                }
                blocks.add(Block(top, bottom, energy))
                r = bottom + 1
            } else {
                r++
            }
        }

        // Un subtítulo ocupa al menos 2 filas de celdas; una sola fila fina y ancha suele ser el borde
        // de un cielo, una barra de progreso o similar.
        blocks.removeAll { it.bottom - it.top < 1 }
        if (blocks.isEmpty()) return null

        // Elegir el bloque más bajo cuya energía sea relevante respecto al máximo (los subtítulos
        // suelen ser el texto más bajo de la zona; los carteles de escena suelen estar más arriba).
        val maxEnergy = blocks.maxOf { it.energy }
        val chosen = blocks
            .filter { it.energy >= maxEnergy * 0.4f }
            .maxByOrNull { it.bottom } ?: return null

        // Extensión horizontal robusta: ignorar el 1,5 % de energía en cada extremo (manchas sueltas)
        val colEnergy = IntArray(cols)
        var total = 0
        for (rr in chosen.top..chosen.bottom) {
            val base = rr * cols
            for (c in 0 until cols) {
                val v = counts[base + c]
                if (v >= MIN_ACTIVE_CELL_HITS) {
                    colEnergy[c] += v
                    total += v
                }
            }
        }
        if (total <= 0) return null

        val trim = (total * 0.015f).toInt()
        var left = 0
        var acc = 0
        for (c in 0 until cols) {
            acc += colEnergy[c]
            if (acc > trim) {
                left = c
                break
            }
        }
        var right = cols - 1
        acc = 0
        for (c in cols - 1 downTo 0) {
            acc += colEnergy[c]
            if (acc > trim) {
                right = c
                break
            }
        }
        if (right < left) {
            right = left
        }

        return TextBand(
            topRow = chosen.top,
            bottomRow = chosen.bottom,
            leftCol = left,
            rightCol = right,
            energy = chosen.energy
        )
    }

    /**
     * Diferencia normalizada [0..1] entre dos cuadrículas. 0 = idénticas, 1 = totalmente distintas.
     * Si falta una de las dos se considera cambio total (salvo que falten ambas).
     */
    fun difference(a: IntArray?, b: IntArray?): Float {
        if (a == null && b == null) return 0f
        if (a == null || b == null) return 1f
        val n = minOf(a.size, b.size)
        var diff = 0L
        var union = 0L
        for (i in 0 until n) {
            val x = if (a[i] >= MIN_ACTIVE_CELL_HITS) a[i] else 0
            val y = if (b[i] >= MIN_ACTIVE_CELL_HITS) b[i] else 0
            diff += abs(x - y)
            union += max(x, y)
        }
        if (union == 0L) return 0f
        return (diff.toFloat() / union.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Igual que [difference] pero solo entre las filas [fromRow]..[toRow]: así los cambios del fondo
     * fuera de la banda del subtítulo no provocan lecturas OCR innecesarias.
     */
    fun difference(
        a: IntArray?,
        b: IntArray?,
        fromRow: Int,
        toRow: Int,
        cols: Int = COLS,
        rows: Int = ROWS
    ): Float {
        if (a == null && b == null) return 0f
        if (a == null || b == null) return 1f
        val r0 = fromRow.coerceIn(0, rows - 1)
        val r1 = toRow.coerceIn(r0, rows - 1)
        var diff = 0L
        var union = 0L
        for (r in r0..r1) {
            val base = r * cols
            for (c in 0 until cols) {
                val i = base + c
                if (i >= a.size || i >= b.size) break
                val x = if (a[i] >= MIN_ACTIVE_CELL_HITS) a[i] else 0
                val y = if (b[i] >= MIN_ACTIVE_CELL_HITS) b[i] else 0
                diff += abs(x - y)
                union += max(x, y)
            }
        }
        if (union == 0L) return 0f
        return (diff.toFloat() / union.toFloat()).coerceIn(0f, 1f)
    }
}
