package com.example.tts

import java.util.Locale

/**
 * Optimizador de prosodia y limpieza fonética para lectura de subtítulos en voz alta.
 *
 * Transforma texto crudo de OCR (que a menudo contiene artefactos, guiones tartamudeantes,
 * repetición excesiva de signos o notas entre corchetes como "[Música]") en un flujo
 * de texto natural, fluido y humano para sintetizadores offline (Piper VITS y Android TTS).
 */
object NaturalSpeechProsodyCleaner {

    // Patrones de notas de audio / subtítulos cerrados que no son diálogos hablados
    private val BRACKETED_SFX_REGEX = Regex("""(?i)\[(?:música|canción|risas|suspiro|gritos|aplausos|llanto|silencio|ruido|pasos|golpe|disparo|staff|traducción)[^\]]*\]""")
    private val PAREN_SFX_REGEX = Regex("""(?i)\((?:música|canción|risas|suspiro|gritos|aplausos|llanto|silencio|ruido|pasos|golpe|disparo)[^)]*\)""")
    private val ASTERISK_SFX_REGEX = Regex("""\*[^*]+\*""")

    // Tartamudeos típicos de anime: "N-No", "Q-Qué", "E-Espera" -> "No", "Qué", "Espera"
    // Los motores TTS offline leen el guión ("ene guión no"), lo cual suena sumamente robótico.
    private val STUTTER_REGEX = Regex("""\b([a-zA-ZáéíóúÁÉÍÓÚñÑ])-\1""", RegexOption.IGNORE_CASE)
    private val DOUBLE_STUTTER_REGEX = Regex("""\b([a-zA-ZáéíóúÁÉÍÓÚñÑ])-([a-zA-ZáéíóúÁÉÍÓÚñÑ]{2,})\b""", RegexOption.IGNORE_CASE)

    // Signos de puntuación duplicados: "¡¡¡Oye!!!" -> "¡Oye!"
    private val MULTI_EXCLAMATION_REGEX = Regex("""¡+""")
    private val MULTI_EXCLAMATION_END_REGEX = Regex("""!+""")
    private val MULTI_QUESTION_REGEX = Regex("""¿+""")
    private val MULTI_QUESTION_END_REGEX = Regex("""\?+""")

    // Puntos suspensivos y rayas
    private val ELLIPSIS_REGEX = Regex("""\.{2,}""")
    private val DASH_PAUSE_REGEX = Regex("""\s*[-–—]{1,}\s*""")

    /**
     * Limpia y optimiza el texto para que la voz natural offline suene fluida,
     * expresiva y sin tropiezos fonéticos.
     */
    fun cleanForSpeech(rawText: String): String {
        if (rawText.isBlank()) return ""

        var text = rawText.trim()

        // 1. Eliminar efectos de sonido entre corchetes o paréntesis
        text = text.replace(BRACKETED_SFX_REGEX, "")
            .replace(PAREN_SFX_REGEX, "")
            .replace(ASTERISK_SFX_REGEX, "")

        // 2. Normalizar tartamudeos para evitar que pronuncie "guión"
        text = text.replace(STUTTER_REGEX) { matchResult ->
            matchResult.value.substring(2)
        }
        text = text.replace(DOUBLE_STUTTER_REGEX) { matchResult ->
            val first = matchResult.groupValues[1]
            val rest = matchResult.groupValues[2]
            if (rest.startsWith(first, ignoreCase = true)) {
                rest
            } else {
                "$first $rest"
            }
        }

        // 3. Normalizar comillas y caracteres gráficos raros de OCR
        text = text.replace("«", "\"")
            .replace("»", "\"")
            .replace("“", "\"")
            .replace("”", "\"")
            .replace("`", "'")
            .replace("´", "'")
            .replace("~", " ")
            .replace("_", " ")
            .replace("|", " ")

        // 4. Transformar puntos suspensivos ("...") en una pausa natural (coma suave)
        // para que el motor respire en lugar de decir "punto punto punto".
        text = text.replace(ELLIPSIS_REGEX, ", ")

        // 5. Reemplazar guiones largos por comas para generar pausas naturales
        text = text.replace(DASH_PAUSE_REGEX, ", ")

        // 6. Normalizar exclamaciones e interrogaciones consecutivas
        text = text.replace(MULTI_EXCLAMATION_REGEX, "¡")
            .replace(MULTI_EXCLAMATION_END_REGEX, "!")
            .replace(MULTI_QUESTION_REGEX, "¿")
            .replace(MULTI_QUESTION_END_REGEX, "?")

        // 7. Colapsar espacios múltiples y limpiar comas redundantes
        text = text.replace(Regex("""\s+"""), " ")
            .replace(Regex(""",\s*,"""), ",")
            .replace(Regex("""\s+,"""), ",")
            .replace(Regex("""\s+\."""), ".")
            .trim()

        // Si quedó solo puntuación, descartar
        val hasLetters = text.any { it.isLetter() }
        if (!hasLetters) return ""

        // Una frase sin puntuación final suena "colgada" (entonación sin cerrar) en las voces
        // neuronales y en muchas voces del sistema: se cierra con un punto.
        if (text.last().isLetterOrDigit()) {
            text += "."
        }

        return text
    }

    /**
     * Calcula una modulación sutil de tono emocional basada en la puntuación del texto.
     * En exclamaciones (¡!), una leve elevación (+4%) da energía típica de anime;
     * en preguntas (¿?), una inflexión interrogativa ascendente (+2%) suena mucho más natural.
     */
    fun calculateEmotionalPitchModulation(text: String, basePitch: Float): Float {
        val hasExclamation = text.contains("!") || text.contains("¡")
        val hasQuestion = text.contains("?") || text.contains("¿")

        return when {
            hasExclamation && hasQuestion -> (basePitch * 1.09f).coerceIn(0.5f, 2.0f)
            hasExclamation -> (basePitch * 1.07f).coerceIn(0.5f, 2.0f)
            hasQuestion -> (basePitch * 1.06f).coerceIn(0.5f, 2.0f)
            else -> basePitch
        }
    }
}
