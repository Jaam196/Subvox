package com.example.tts

import java.util.Locale

/**
 * Convierte una frase ya limpia en tramos con su propia expresión (velocidad, volumen y pausas).
 *
 * Las voces Piper no permiten cambiar el tono por frase, pero sí la velocidad, el volumen y
 * las pausas entre tramos. Con eso se consigue interpretar:
 *  - lo que va entre comillas: más peso (algo más lento y más fuerte, con pausas alrededor);
 *  - lo que va entre paréntesis: depende del contenido; un grito o exclamación suena fuerte,
 *    un pensamiento o aparte suena más suave y algo más rápido;
 *  - preguntas: algo más pausadas, con pausa al final (el "?" lo entona el propio motor);
 *  - exclamaciones y palabras en MAYÚSCULAS: más energía.
 *
 * Si la frase no tiene nada especial devuelve un único tramo neutro: el comportamiento es
 * idéntico al de siempre.
 */
object ProsodyPlanner {

    data class Segment(
        val text: String,
        val speedFactor: Float = 1f,
        val gain: Float = 1f,
        val pauseBeforeMs: Int = 0,
        val pauseAfterMs: Int = 0
    ) {
        val isNeutral: Boolean
            get() = speedFactor == 1f && gain == 1f && pauseBeforeMs == 0 && pauseAfterMs == 0
    }

    private val SPAN_REGEX = Regex("""\"([^\"]+)\"|\(([^)]+)\)""")
    private val SENTENCE_REGEX = Regex("""[^.!?¡¿]*[¡¿]?[^.!?]*[.!?]+|[^.!?]+$""")

    private const val QUOTE_SPEED = 0.94f
    private const val QUOTE_GAIN = 1.10f
    private const val SHOUT_GAIN = 1.12f
    private const val ASIDE_SPEED = 1.05f
    private const val ASIDE_GAIN = 0.78f
    private const val QUESTION_SPEED = 0.96f
    private const val EXCLAIM_SPEED = 1.03f
    private const val EXCLAIM_GAIN = 1.08f

    fun plan(cleanText: String): List<Segment> {
        val text = cleanText.trim()
        if (text.isEmpty()) return emptyList()

        // Comillas sin pareja: se ignoran (se quitan) para no leer nada raro
        val quoteCount = text.count { it == '"' }
        val prepared = if (quoteCount % 2 != 0) text.replace("\"", "") else text

        val hasSpan = SPAN_REGEX.containsMatchIn(prepared)
        val hasSpecialPunct = prepared.any { it == '?' || it == '!' || it == '¿' || it == '¡' }
        val shouting = isShouting(prepared)
        if (!hasSpan && !hasSpecialPunct && !shouting) {
            return listOf(Segment(prepared.replace("\"", "")))
        }

        val out = mutableListOf<Segment>()
        var cursor = 0
        for (m in SPAN_REGEX.findAll(prepared)) {
            if (m.range.first > cursor) {
                addPlain(out, prepared.substring(cursor, m.range.first), continues = true)
            }
            val quoted = m.groupValues[1]
            val aside = m.groupValues[2]
            if (quoted.isNotEmpty()) addQuoted(out, quoted) else addAside(out, aside)
            cursor = m.range.last + 1
        }
        if (cursor < prepared.length) addPlain(out, prepared.substring(cursor))

        val merged = out.filter { it.text.any { c -> c.isLetterOrDigit() } }
        return if (merged.isEmpty()) listOf(Segment(prepared.replace("\"", "").replace("(", "").replace(")", ""))) else merged
    }

    private fun closeSentence(s: String): String {
        val t = s.trim().trim(',', ';', ':').trim()
        if (t.isEmpty()) return t
        return if (t.last().isLetterOrDigit()) "$t." else t
    }

    /** Texto fuera de comillas y paréntesis: se trocea en frases para tratar preguntas/exclamaciones. */
    private fun addPlain(out: MutableList<Segment>, raw: String, continues: Boolean = false) {
        val chunk = raw.trim().trim(',', ';', ':').trim()
        if (chunk.none { it.isLetterOrDigit() }) return
        val sentences = SENTENCE_REGEX.findAll(chunk).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
        val parts = if (sentences.isEmpty()) listOf(chunk) else sentences
        for ((i, sentence) in parts.withIndex()) {
            if (sentence.none { it.isLetterOrDigit() }) continue
            // El tramo que precede a una cita/aparte no cierra la frase: acaba en coma (pausa breve
            // y entonación que continúa) en vez de punto.
            val isLastBeforeSpan = continues && i == parts.lastIndex
            out += if (isLastBeforeSpan && sentence.last().isLetterOrDigit()) {
                Segment("$sentence,")
            } else {
                styleSentence(closeSentence(sentence))
            }
        }
    }

    private fun styleSentence(sentence: String): Segment {
        val isQuestion = sentence.contains('?')
        val isExclaim = sentence.contains('!')
        val shout = isShouting(sentence)
        return when {
            isQuestion && isExclaim -> Segment(sentence, 1.0f, EXCLAIM_GAIN, 0, 140)
            isQuestion -> Segment(sentence, QUESTION_SPEED, if (shout) SHOUT_GAIN else 1f, 0, 140)
            isExclaim || shout -> Segment(sentence, EXCLAIM_SPEED, if (shout) SHOUT_GAIN else EXCLAIM_GAIN, 0, 90)
            else -> Segment(sentence)
        }
    }

    private fun addQuoted(out: MutableList<Segment>, inner: String) {
        val sentence = closeSentence(inner)
        if (sentence.none { it.isLetterOrDigit() }) return
        val loud = sentence.contains('!') || isShouting(sentence)
        out += Segment(
            text = sentence,
            speedFactor = QUOTE_SPEED,
            gain = if (loud) SHOUT_GAIN else QUOTE_GAIN,
            pauseBeforeMs = 140,
            pauseAfterMs = 160
        )
    }

    private fun addAside(out: MutableList<Segment>, inner: String) {
        val sentence = closeSentence(inner)
        if (sentence.none { it.isLetterOrDigit() }) return
        val strong = sentence.contains('!') || isShouting(sentence) || hasShoutWord(sentence)
        out += if (strong) {
            Segment(sentence, 1.0f, SHOUT_GAIN, 100, 120)
        } else {
            Segment(sentence, ASIDE_SPEED, ASIDE_GAIN, 110, 130)
        }
    }

    /** Verdadero si casi todo el texto está en mayúsculas (grito). Exige al menos 3 letras. */
    fun isShouting(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.length < 3) return false
        val upper = letters.count { it.isUpperCase() }
        return upper.toFloat() / letters.length >= 0.7f
    }

    private val SHOUT_WORDS = setOf(
        "cuidado", "socorro", "ayuda", "corre", "corran", "fuego", "alto", "detente", "maldición",
        "muere", "ataca", "basta"
    )

    private fun hasShoutWord(text: String): Boolean {
        val words = text.lowercase(Locale.ROOT).split(Regex("""[^\p{L}]+""")).filter { it.isNotEmpty() }
        // Una sola palabra imperativa o muy corta suele ser un grito/orden
        return words.size <= 2 && words.any { it in SHOUT_WORDS }
    }
}
