package com.example.filters

/**
 * Detecta letras de canciones (openings, endings, canciones insertadas) para no leerlas.
 *
 *  - Una línea marcada como canción (♪ ♫ #, o símbolos mal leídos por el OCR) se salta siempre.
 *  - Cuando ya van dos líneas marcadas seguidas, se confirma que hay una canción y también se
 *    saltan las líneas SIN marca que lleguen enseguida (muchas letras solo marcan algunas líneas).
 *  - Solo las líneas marcadas alargan la ventana; así nunca puede tragarse un diálogo largo.
 */
class SongSkipDetector(
    private val windowMs: Long = 7_000L,
    private val sessionGapMs: Long = 25_000L
) {
    private var lastMarkedAtMs = 0L
    private var markedInSession = 0

    fun reset() {
        lastMarkedAtMs = 0L
        markedInSession = 0
    }

    /** Devuelve el motivo si hay que saltarse esta línea, o null si se lee. */
    fun check(text: String, nowMs: Long): String? {
        if (lastMarkedAtMs != 0L && nowMs - lastMarkedAtMs > sessionGapMs) {
            markedInSession = 0
        }
        if (isSongMarked(text)) {
            lastMarkedAtMs = nowMs
            markedInSession++
            return "Letra de canción"
        }
        if (markedInSession >= 2 && lastMarkedAtMs != 0L && nowMs - lastMarkedAtMs <= windowMs) {
            return "Canción en curso (opening/ending)"
        }
        return null
    }

    companion object {
        private val MUSIC_SYMBOLS = charArrayOf('♪', '♫', '♬', '♩')
        // Símbolos que el OCR suele leer en lugar de ♪ cuando rodean una línea
        private val MISREAD_NOTE = setOf("J", "d", "7", "$", "#", "~", "♪", "♫", "¿")

        fun isSongMarked(text: String): Boolean {
            val t = text.trim()
            if (t.isEmpty()) return false
            if (t.any { it in MUSIC_SYMBOLS } || t.contains("🎵") || t.contains("🎶")) return true
            if (t.length > 3 && (t.startsWith("~") && t.endsWith("~"))) return true
            if (t.length > 3 && (t.startsWith("～") && t.endsWith("～"))) return true
            if (t.length > 3 && t.startsWith("#") && t.endsWith("#")) return true
            // "J Letra de la canción J": mismo símbolo suelto a ambos lados y varias palabras en medio
            val words = t.split(Regex("""\s+"""))
            if (words.size >= 4 && words.first() == words.last() && words.first() in MISREAD_NOTE) return true
            return false
        }
    }
}
