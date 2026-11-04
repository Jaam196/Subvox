package com.example.domain.pipeline

import kotlin.math.max
import kotlin.math.min

/**
 * Calcula la similitud entre dos subtítulos normalizados combinando:
 * - Similitud de edición de Levenshtein a nivel de caracteres (tolerancia a 1-2 letras fallidas en OCR).
 * - Similitud de Jaccard a nivel de tokens/palabras (o de bigramas de caracteres cuando el texto es CJK
 *   sin espacios entre palabras, como japonés o chino).
 */
object TextSimilarityCalculator {

    /**
     * Devuelve un valor en [0.0f .. 1.0f] donde 1.0f indica textos equivalentes.
     */
    fun computeSimilarity(firstRawOrKey: String, secondRawOrKey: String): Float {
        val keyA = SubtitleNormalizer.buildComparisonKey(firstRawOrKey)
        val keyB = SubtitleNormalizer.buildComparisonKey(secondRawOrKey)

        if (keyA.isEmpty() && keyB.isEmpty()) return 1.0f
        if (keyA.isEmpty() || keyB.isEmpty()) return 0.0f
        if (keyA == keyB) return 1.0f

        val levSim = levenshteinSimilarity(keyA, keyB)
        val hasCjk = SubtitleNormalizer.containsCjkText(keyA) || SubtitleNormalizer.containsCjkText(keyB)
        val jaccardSim = if (hasCjk) {
            characterBigramJaccardSimilarity(keyA.replace(" ", ""), keyB.replace(" ", ""))
        } else {
            tokenJaccardSimilarity(keyA, keyB)
        }

        // Si uno de los textos está contenido casi por completo dentro del otro (p.ej. segunda línea
        // que aparece 1 frame después o un carácter de borde), damos mayor peso a Levenshtein.
        return (levSim * 0.78f + jaccardSim * 0.22f).coerceIn(0.0f, 1.0f)
    }

    fun levenshteinSimilarity(a: String, b: String): Float {
        if (a == b) return 1.0f
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 1.0f
        val dist = levenshteinDistance(a, b)
        return (1.0f - dist.toFloat() / maxLen.toFloat()).coerceIn(0.0f, 1.0f)
    }

    fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)

        for (i in 1..a.length) {
            curr[0] = i
            val charA = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (charA == b[j - 1]) 0 else 1
                curr[j] = min(
                    min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    fun tokenJaccardSimilarity(a: String, b: String): Float {
        val tokensA = a.split(' ').filter { it.isNotBlank() }.toSet()
        val tokensB = b.split(' ').filter { it.isNotBlank() }.toSet()
        if (tokensA.isEmpty() && tokensB.isEmpty()) return 1.0f
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0f

        val intersectionSize = tokensA.intersect(tokensB).size
        val unionSize = tokensA.union(tokensB).size
        return if (unionSize == 0) 1.0f else intersectionSize.toFloat() / unionSize.toFloat()
    }

    /**
     * Similitud Jaccard por caracteres/bigramas para idiomas sin espacios (Japonés, Chino, Coreano compacto).
     */
    fun characterBigramJaccardSimilarity(a: String, b: String): Float {
        if (a.isEmpty() && b.isEmpty()) return 1.0f
        if (a.isEmpty() || b.isEmpty()) return 0.0f
        val gramsA = if (a.length == 1) setOf(a) else a.windowed(size = 2, step = 1).toSet()
        val gramsB = if (b.length == 1) setOf(b) else b.windowed(size = 2, step = 1).toSet()
        val inter = gramsA.intersect(gramsB).size
        val union = gramsA.union(gramsB).size
        return if (union == 0) 1.0f else inter.toFloat() / union.toFloat()
    }
}
