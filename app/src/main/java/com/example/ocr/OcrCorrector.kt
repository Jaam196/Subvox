package com.example.ocr

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Una corrección aprendida: lo que el OCR lee mal y lo que realmente pone. */
data class OcrCorrection(
    val id: String = UUID.randomUUID().toString(),
    val wrong: String,
    val right: String
)

/**
 * Aprende de las correcciones manuales y las aplica a cada texto leído por el OCR.
 * Lógica pura (sin Android) para poder probarla en el ordenador.
 */
object OcrCorrector {

    private const val MAX_RULES_PER_LEARN = 6
    private const val MAX_SPAN_TOKENS = 4

    private val regexCache = ConcurrentHashMap<String, Regex>()

    private fun tokens(text: String): List<String> =
        text.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }

    /** Quita la puntuación idéntica por delante y por detrás (p. ej. la coma final de ambos). */
    private fun trimShared(a: String, b: String): Pair<String, String> {
        var x = a
        var y = b
        while (x.isNotEmpty() && y.isNotEmpty() && !x.first().isLetterOrDigit() && x.first() == y.first()) {
            x = x.drop(1); y = y.drop(1)
        }
        while (x.isNotEmpty() && y.isNotEmpty() && !x.last().isLetterOrDigit() && x.last() == y.last()) {
            x = x.dropLast(1); y = y.dropLast(1)
        }
        return x to y
    }

    /**
     * Compara el texto leído con el corregido y devuelve pares (mal → bien) reutilizables.
     * Si el usuario reescribió la frase entera, no se aprende nada (no es un fallo de OCR).
     */
    fun learn(original: String, corrected: String): List<Pair<String, String>> {
        val a = tokens(original)
        val b = tokens(corrected)
        if (a.isEmpty() || b.isEmpty() || a == b) return emptyList()

        val pairs = mutableListOf<Pair<String, String>>()

        if (a.size == b.size) {
            // Mismo número de palabras: se corrige palabra por palabra
            for (i in a.indices) {
                if (a[i] != b[i]) {
                    val (w, r) = trimShared(a[i], b[i])
                    if (w.isNotEmpty() && w != r) pairs += w to r
                }
            }
        } else {
            var start = 0
            while (start < a.size && start < b.size && a[start] == b[start]) start++
            var endA = a.size
            var endB = b.size
            while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) {
                endA--; endB--
            }
            val wrongSpan = a.subList(start, endA)
            val rightSpan = b.subList(start, endB)
            if (wrongSpan.isEmpty() || wrongSpan.size > MAX_SPAN_TOKENS || rightSpan.size > MAX_SPAN_TOKENS) {
                return emptyList()
            }
            val (w, r) = trimShared(wrongSpan.joinToString(" "), rightSpan.joinToString(" "))
            if (w.isNotEmpty() && w != r) pairs += w to r
        }
        return pairs.distinct().take(MAX_RULES_PER_LEARN)
    }

    private fun regexFor(wrong: String): Regex = regexCache.getOrPut(wrong) {
        Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(wrong) + "(?![\\p{L}\\p{N}])")
    }

    /** Aplica las correcciones (primero las más largas) respetando palabras completas. */
    fun apply(text: String, rules: List<OcrCorrection>): String {
        if (text.isEmpty() || rules.isEmpty()) return text
        var result = text
        for (rule in rules.sortedByDescending { it.wrong.length }) {
            if (rule.wrong.isEmpty() || rule.wrong == rule.right) continue
            result = regexFor(rule.wrong).replace(result, Regex.escapeReplacement(rule.right))
        }
        return result
    }
}
