package com.example.study

import com.example.domain.pipeline.SubtitleNormalizer
import com.example.history.SubtitleHistoryRecord
import java.util.Locale

data class StudyWordEntry(
    val term: String,
    val reading: String,
    val meaningEs: String,
    val category: String = "Vocabulario Anime"
)

data class SessionLearningStats(
    val totalSubtitlesRead: Int,
    val totalWordsSpoken: Int,
    val activeDurationSeconds: Long,
    val averageWordsPerMinute: Int,
    val topWords: List<Pair<String, Int>>,
    val appBreakdown: List<Pair<String, Int>>
)

/**
 * Función 11 y 12 (Fase 3):
 * - Modo Estudio: tokeniza el último subtítulo (español, inglés, romaji o japonés/CJK) en palabras
 *   interactivas y busca su lectura y significado en un glosario offline integrado (con soporte de
 *   traducción en vivo ML Kit cuando se solicita).
 * - Estadísticas de sesión y aprendizaje: calcula palabras por minuto (WPM), tiempo activo,
 *   desglose por aplicación y vocabulario más frecuente.
 */
object StudyGlossaryManager {

    private val BUILT_IN_ANIME_GLOSSARY: List<StudyWordEntry> = listOf(
        StudyWordEntry("nakama", "nakama (仲間)", "Compañero inseparable, camarada o amigo del alma.", "Término Anime"),
        StudyWordEntry("仲間", "nakama", "Compañero de equipo, camarada o amigo leal.", "Japonés Kanji"),
        StudyWordEntry("sensei", "sensei (先生)", "Maestro, profesor, doctor o persona de gran experiencia.", "Honorífico"),
        StudyWordEntry("先生", "sensei", "Maestro, instructor o mentor.", "Japonés Kanji"),
        StudyWordEntry("senpai", "senpai (先輩)", "Compañero con mayor antigüedad o experiencia.", "Honorífico"),
        StudyWordEntry("先輩", "senpai", "Superior o veterano en escuela o trabajo.", "Japonés Kanji"),
        StudyWordEntry("kohai", "kōhai (後輩)", "Compañero más joven o de menor antigüedad.", "Honorífico"),
        StudyWordEntry("sama", "-sama (様)", "Sufijo honorífico de máximo respeto (señor/a, alteza).", "Honorífico"),
        StudyWordEntry("chan", "-chan (ちゃん)", "Sufijo afectuoso para amigos cercanos, niños o mascotas.", "Honorífico"),
        StudyWordEntry("kun", "-kun (くん)", "Sufijo cercano usado con jóvenes o compañeros.", "Honorífico"),
        StudyWordEntry("san", "-san (さん)", "Sufijo cortés equivalente a señor/señora.", "Honorífico"),
        StudyWordEntry("dono", "-dono (殿)", "Sufijo arcaico formal propio de samuráis (mi señor).", "Honorífico"),
        StudyWordEntry("oniisan", "onii-san (お兄さん)", "Hermano mayor (también usado con respeto hacia chicos mayores).", "Familia"),
        StudyWordEntry("兄さん", "nii-san", "Hermano mayor.", "Japonés Kanji"),
        StudyWordEntry("oneesan", "onee-san (お姉さん)", "Hermana mayor.", "Familia"),
        StudyWordEntry("otouto", "otōto (弟)", "Hermano menor.", "Familia"),
        StudyWordEntry("imouto", "imōto (妹)", "Hermana menor.", "Familia"),
        StudyWordEntry("kokoro", "kokoro (心)", "Corazón, espíritu, mente o sentimiento interior.", "Vocabulario"),
        StudyWordEntry("心", "kokoro", "Corazón, espíritu o alma.", "Japonés Kanji"),
        StudyWordEntry("hikari", "hikari (光)", "Luz, destello o esperanza.", "Vocabulario"),
        StudyWordEntry("yami", "yami (闇)", "Oscuridad o tinieblas.", "Vocabulario"),
        StudyWordEntry("chikara", "chikara (力)", "Fuerza, poder o habilidad.", "Vocabulario"),
        StudyWordEntry("力", "chikara", "Poder, fuerza física o espiritual.", "Japonés Kanji"),
        StudyWordEntry("sekai", "sekai (世界)", "El mundo o universo.", "Vocabulario"),
        StudyWordEntry("世界", "sekai", "Mundo, sociedad o universo.", "Japonés Kanji"),
        StudyWordEntry("mirai", "mirai (未来)", "El futuro o porvenir.", "Vocabulario"),
        StudyWordEntry("kizuna", "kizuna (絆)", "Vínculo inquebrantable o lazo emocional entre personas.", "Vocabulario"),
        StudyWordEntry("yakusoku", "yakusoku (約束)", "Promesa o pacto.", "Vocabulario"),
        StudyWordEntry("約束", "yakusoku", "Promesa.", "Japonés Kanji"),
        StudyWordEntry("mamoru", "mamoru (守る)", "Proteger, defender o cumplir una promesa.", "Verbo"),
        StudyWordEntry("akiramenaide", "akiramenaide (諦めないで)", "¡No te rindas!", "Expresión"),
        StudyWordEntry("諦めないで", "akiramenaide", "¡No te rindas!", "Japonés Kanji"),
        StudyWordEntry("masaka", "masaka (まさか)", "¡No puede ser! / ¡Imposible!", "Expresión"),
        StudyWordEntry("naruhodo", "naruhodo (なるほど)", "Ya veo, entiendo, tiene sentido.", "Expresión"),
        StudyWordEntry("sugoi", "sugoi (すごい)", "¡Increíble! / ¡Asombroso!", "Expresión"),
        StudyWordEntry("baka", "baka (馬鹿)", "Tonto, insensato o cabeza hueca.", "Expresión"),
        StudyWordEntry("shinigami", "shinigami (死神)", "Dios de la muerte o segador de almas.", "Fantasía"),
        StudyWordEntry("youkai", "yōkai (妖怪)", "Espíritu, criatura sobrenatural o demonio del folclore japonés.", "Fantasía"),
        StudyWordEntry("isekai", "isekai (異世界)", "Mundo paralelo o género de transporte a otro mundo.", "Género Anime"),
        StudyWordEntry("mahou", "mahō (魔法)", "Magia o hechicería.", "Fantasía"),
        StudyWordEntry("jutsu", "jutsu (術)", "Técnica, arte marcial o hechizo.", "Acción"),
        StudyWordEntry("katana", "katana (刀)", "Espada curva tradicional de los samuráis.", "Armas"),
        StudyWordEntry("bushido", "bushidō (武士道)", "El camino del guerrero: código ético samurái.", "Cultura"),
        StudyWordEntry("arigatou", "arigatō (ありがとう)", "Gracias / Muchas gracias.", "Expresión"),
        StudyWordEntry("gomen", "gomen (ごめん)", "Perdón / Lo siento.", "Expresión"),
        StudyWordEntry("tasukete", "tasukete (助けて)", "¡Ayúdame! / ¡Socorro!", "Expresión"),
        StudyWordEntry("ike", "ike (行け)", "¡Ve! / ¡Adelante!", "Expresión"),
        StudyWordEntry("tatakae", "tatakae (戦え)", "¡Lucha! / ¡Pelea!", "Expresión")
    )

    private val SPANISH_STOP_WORDS = setOf(
        "el", "la", "los", "las", "un", "una", "unos", "unas", "de", "del", "al",
        "en", "con", "por", "para", "sin", "sobre", "que", "como", "pero", "mas",
        "más", "no", "si", "sí", "ya", "es", "son", "fue", "era", "está", "esta",
        "este", "esto", "ese", "esa", "eso", "mi", "tu", "su", "me", "te", "se",
        "lo", "le", "nos", "os", "les", "y", "o", "u", "a", "ha", "han", "hay"
    )

    /**
     * Extrae las palabras o bloques clicables de un subtítulo para mostrarlos en el Modo Estudio.
     */
    fun tokenizeSubtitleForStudy(subtitle: String): List<String> {
        val trimmed = subtitle.trim()
        if (trimmed.isEmpty()) return emptyList()

        val tokens = mutableListOf<String>()
        val rawParts = trimmed.split(Regex("""\s+"""))
        for (part in rawParts) {
            val cleaned = part.trim { ch ->
                !ch.isLetterOrDigit() && !SubtitleNormalizer.isCjkCharacter(ch) && ch != '-'
            }
            if (cleaned.isNotBlank()) {
                tokens.add(cleaned)
            }
        }
        return tokens.distinct()
    }

    /**
     * Busca una palabra en el glosario offline integrado o genera una ficha gramatical descriptiva.
     */
    fun lookupWordOffline(rawWord: String): StudyWordEntry {
        val cleaned = rawWord.trim { ch ->
            !ch.isLetterOrDigit() && !SubtitleNormalizer.isCjkCharacter(ch)
        }
        val normalizedLower = cleaned.lowercase(Locale.ROOT)

        val exactMatch = BUILT_IN_ANIME_GLOSSARY.firstOrNull { entry ->
            entry.term.equals(cleaned, ignoreCase = true) ||
                entry.term.equals(normalizedLower, ignoreCase = true) ||
                cleaned.contains(entry.term, ignoreCase = true) && entry.term.length >= 4
        }
        if (exactMatch != null) {
            return exactMatch
        }

        val isCjk = SubtitleNormalizer.containsCjkText(cleaned)
        val syllableReading = if (isCjk) {
            "Texto CJK (${cleaned.length} ideograma/s)"
        } else {
            estimateSpanishPhoneticReading(cleaned)
        }

        val morphDescription = when {
            isCjk -> "Término en escritura japonesa/coreana/china detectado por OCR. Usa el botón 'Traducir con ML Kit' o 'Pronunciar' para escuchar su lectura."
            normalizedLower.endsWith("mente") && normalizedLower.length > 6 ->
                "Adverbio de modo en español formado a partir del adjetivo '${cleaned.dropLast(5)}'."
            normalizedLower.endsWith("ando") || normalizedLower.endsWith("iendo") ->
                "Verbo en gerundio (acción en curso durante la escena)."
            normalizedLower.endsWith("ción") || normalizedLower.endsWith("sion") ->
                "Sustantivo de acción o efecto en español (${cleaned.length} letras)."
            cleaned.firstOrNull()?.isUpperCase() == true ->
                "Nombre propio, personaje, lugar o inicio de oración en el diálogo."
            else ->
                "Palabra del subtítulo actual (${cleaned.length} caracteres). Pulsa 'Escuchar pronunciación' o 'Traducir palabra' para consultar su equivalencia."
        }

        return StudyWordEntry(
            term = cleaned.ifBlank { rawWord },
            reading = syllableReading,
            meaningEs = morphDescription,
            category = if (isCjk) "Ideograma CJK" else "Término del Subtítulo"
        )
    }

    private fun estimateSpanishPhoneticReading(word: String): String {
        if (word.length <= 3) return word.lowercase(Locale.ROOT)
        val vowels = "aeiouáéíóúüAEIOUÁÉÍÓÚÜ"
        val sb = StringBuilder()
        for (i in word.indices) {
            val c = word[i]
            sb.append(c)
            if (c in vowels && i < word.lastIndex - 1) {
                val next = word[i + 1]
                val afterNext = word.getOrNull(i + 2)
                if (next !in vowels && afterNext != null && afterNext in vowels) {
                    sb.append('·')
                }
            }
        }
        return sb.toString()
    }

    /**
     * Calcula estadísticas detalladas de sesión y aprendizaje a partir del estado y del historial Room.
     */
    fun computeSessionStats(
        subtitlesReadInSession: Int,
        wordsSpokenInSession: Int,
        sessionStartMs: Long,
        nowMs: Long,
        historyRecords: List<SubtitleHistoryRecord>
    ): SessionLearningStats {
        val elapsedSec = if (sessionStartMs > 0L && nowMs > sessionStartMs) {
            ((nowMs - sessionStartMs) / 1000L).coerceAtLeast(1L)
        } else {
            0L
        }

        val wpm = if (elapsedSec >= 5L && wordsSpokenInSession > 0) {
            ((wordsSpokenInSession * 60L) / elapsedSec).toInt()
        } else if (historyRecords.isNotEmpty()) {
            val totalWordsHistory = historyRecords.sumOf {
                it.spokenText.trim().split(Regex("""\s+""")).count { w -> w.isNotBlank() }
            }
            (totalWordsHistory / historyRecords.size.coerceAtLeast(1)) * 18
        } else {
            0
        }

        val wordCounter = mutableMapOf<String, Int>()
        val appCounter = mutableMapOf<String, Int>()

        for (rec in historyRecords) {
            val appLabel = rec.sourceAppPackage.substringAfterLast('.').ifBlank { "General" }
            appCounter[appLabel] = (appCounter[appLabel] ?: 0) + 1

            val tokens = tokenizeSubtitleForStudy(rec.spokenText)
            for (token in tokens) {
                val lower = token.lowercase(Locale.ROOT)
                if (lower.length >= 3 && lower !in SPANISH_STOP_WORDS) {
                    wordCounter[lower] = (wordCounter[lower] ?: 0) + 1
                }
            }
        }

        val topWords = wordCounter.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { it.key to it.value }

        val appBreakdown = appCounter.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key to it.value }

        val effectiveWords = if (wordsSpokenInSession > 0) {
            wordsSpokenInSession
        } else {
            historyRecords.sumOf {
                it.spokenText.trim().split(Regex("""\s+""")).count { w -> w.isNotBlank() }
            }
        }

        val effectiveSubtitles = if (subtitlesReadInSession > 0) {
            subtitlesReadInSession
        } else {
            historyRecords.size
        }

        return SessionLearningStats(
            totalSubtitlesRead = effectiveSubtitles,
            totalWordsSpoken = effectiveWords,
            activeDurationSeconds = elapsedSec,
            averageWordsPerMinute = wpm,
            topWords = topWords,
            appBreakdown = appBreakdown
        )
    }
}
