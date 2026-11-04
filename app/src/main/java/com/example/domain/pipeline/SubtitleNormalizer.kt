package com.example.domain.pipeline

/**
 * Normaliza el texto bruto extraído por OCR para subtítulos en español, inglés y scripts CJK (Japonés/Coreano/Chino):
 * 1. Une subtítulos de dos o más líneas en una sola frase fluida.
 * 2. Limpia ruido típico de OCR (barras verticales '|', guiones sueltos, corchetes vacíos,
 *    notas musicales ♪/♫, etiquetas de sordos [Música], (Risas), 【...】).
 * 3. Corrige errores frecuentes de OCR en español cuando hay signos de apertura/cierre
 *    (p. ej. "iNo puede ser!" -> "¡No puede ser!").
 * 4. Soporta caracteres CJK (Hiragana, Katakana, Kanji, Hangul, Hanzi) sin descartarlos en la clave de comparación.
 */
object SubtitleNormalizer {

    data class NormalizedSubtitle(
        val speakableText: String,
        val comparisonKey: String
    ) {
        val isEmpty: Boolean get() = speakableText.isBlank() || comparisonKey.isBlank()
    }

    private val MULTI_WHITESPACE_REGEX = Regex("""\s+""")
    private val LEADING_DIALOGUE_DASH_REGEX = Regex("""(?m)^\s*[-–—―]+\s*""")
    private val BRACKETED_SFX_REGEX = Regex("""\[[^\]]{1,28}]|\([^\)]{1,24}\)|【[^】]{1,24}】|（[^）]{1,24}）""")
    private val STRAY_EDGE_ARTIFACTS_REGEX = Regex("""^[|_~`^•*+<>]+|[|_~`^•*+<>]+$""")
    private val MUSIC_SYMBOLS_REGEX = Regex("""[♪♫♬♩]+""")
    // Detecta cuando el OCR confunde '¡' inicial con 'i' o 'l' o '1' seguido de mayúscula y cerrando con '!'
    private val INVERTED_EXCLAMATION_OCR_FIX = Regex("""(^|\s)[il1]([A-ZÁÉÍÓÚÑÜ][^!?\n]*!)""")

    fun normalize(rawOcrText: String): NormalizedSubtitle {
        if (rawOcrText.isBlank()) {
            return NormalizedSubtitle("", "")
        }

        // 1. Procesar línea por línea para quitar guiones de diálogo y artefactos en los extremos
        val cleanedLines = rawOcrText
            .lines()
            .map { line ->
                var l = line.trim()
                l = MUSIC_SYMBOLS_REGEX.replace(l, "")
                l = BRACKETED_SFX_REGEX.replace(l, " ")
                l = LEADING_DIALOGUE_DASH_REGEX.replace(l, "")
                l = STRAY_EDGE_ARTIFACTS_REGEX.replace(l, "")
                l.trim()
            }
            .filter { it.isNotEmpty() }

        if (cleanedLines.isEmpty()) {
            return NormalizedSubtitle("", "")
        }

        // 2. Unir subtítulos de dos o más líneas en un único texto separado por espacio
        var joined = cleanedLines.joinToString(" ")
        joined = MULTI_WHITESPACE_REGEX.replace(joined, " ").trim()

        // 3. Corregir confusión típica de OCR en español: "iNo te rindas!" -> "¡No te rindas!"
        joined = INVERTED_EXCLAMATION_OCR_FIX.replace(joined) { match ->
            "${match.groupValues[1]}¡${match.groupValues[2]}"
        }

        // 4. Descartar si tras limpiar no hay al menos una letra, ideograma CJK o número legible
        val hasAlphanumericOrCjk = joined.any { it.isLetterOrDigit() || isCjkCharacter(it) }
        if (!hasAlphanumericOrCjk) {
            return NormalizedSubtitle("", "")
        }

        // Descartar basura de 1 solo carácter latino suelto que no sea vocal/conjunción o ideograma CJK válido
        val validCharCount = joined.count { it.isLetterOrDigit() || isCjkCharacter(it) }
        val hasCjk = joined.any { isCjkCharacter(it) }
        if (!hasCjk && validCharCount == 1 && joined.lowercase() !in setOf("a", "y", "o", "u", "e")) {
            return NormalizedSubtitle("", "")
        }

        val key = buildComparisonKey(joined)
        if (key.isEmpty()) {
            return NormalizedSubtitle("", "")
        }

        return NormalizedSubtitle(
            speakableText = joined,
            comparisonKey = key
        )
    }

    /**
     * Construye una clave canónica en minúsculas conservando letras latinas/españolas (incluyendo ñ),
     * dígitos y cualquier carácter CJK / Unicode alfabético (Hiragana, Katakana, Kanji, Hangul),
     * eliminando puntuación suelta y unificando tildes latinas.
     */
    fun buildComparisonKey(text: String): String {
        val sb = StringBuilder(text.length)
        var lastWasSpace = true

        for (ch in text.lowercase()) {
            val mapped: Char? = when {
                ch == 'á' || ch == 'à' || ch == 'ä' || ch == 'â' || ch == 'ã' -> 'a'
                ch == 'é' || ch == 'è' || ch == 'ë' || ch == 'ê' -> 'e'
                ch == 'í' || ch == 'ì' || ch == 'ï' || ch == 'î' -> 'i'
                ch == 'ó' || ch == 'ò' || ch == 'ö' || ch == 'ô' || ch == 'õ' -> 'o'
                ch == 'ú' || ch == 'ù' || ch == 'ü' || ch == 'û' -> 'u'
                ch == 'ñ' -> 'ñ'
                ch == 'ç' -> 'c'
                ch in 'a'..'z' || ch in '0'..'9' -> ch
                isCjkCharacter(ch) || ch.isLetter() -> ch
                ch.isWhitespace() -> ' '
                else -> null
            }

            if (mapped != null) {
                if (mapped == ' ') {
                    if (!lastWasSpace) {
                        sb.append(' ')
                        lastWasSpace = true
                    }
                } else {
                    sb.append(mapped)
                    lastWasSpace = false
                }
            }
        }

        return sb.toString().trim()
    }

    fun isCjkCharacter(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x3040..0x309F) || // Hiragana
            (code in 0x30A0..0x30FF) ||    // Katakana
            (code in 0x4E00..0x9FFF) ||    // CJK Unified Ideographs (Kanji / Hanzi)
            (code in 0x3400..0x4DBF) ||    // CJK Extension A
            (code in 0xAC00..0xD7AF) ||    // Hangul Syllables
            (code in 0x1100..0x11FF) ||    // Hangul Jamo
            (code in 0x3130..0x318F)       // Hangul Compatibility Jamo
    }

    fun containsCjkText(text: String): Boolean {
        return text.any { isCjkCharacter(it) }
    }
}
