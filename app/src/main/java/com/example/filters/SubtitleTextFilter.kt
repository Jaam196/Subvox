package com.example.filters

import com.example.domain.model.AppSettings
import com.example.domain.pipeline.SubtitleNormalizer

/**
 * Motor de Filtrado de Texto para subtítulos (Fase 2 - Función 7):
 * Permite ignorar selectivamente:
 * 1. Modo manual "Silenciar Opening/Ending mientras activo".
 * 2. Créditos de staff, fansub y patrocinadores ("Traducción:", "Subtítulos por", "Kono bangumi wa...").
 * 3. Textos demasiado cortos o compuestos solo por símbolos/puntuación.
 * 4. Patrones o palabras personalizadas del usuario (texto literal o expresiones regulares Regex, una por línea).
 */
object SubtitleTextFilter {

    sealed class FilterDecision {
        data object Allowed : FilterDecision()
        data class Ignored(val reason: String) : FilterDecision()
    }

    private val CREDITS_AND_SPONSOR_PATTERNS: List<Regex> = listOf(
        Regex("""(?i)\b(subt[íi]tulos\s+por|traducci[óo]n\s*:|traducido\s+por|correcci[óo]n\s*:|sincronizaci[óo]n\s*:|encoder\s*:|karaoke\s*:|fansub)\b"""),
        Regex("""(?i)\b(kono\s+bangumi\s+wa|este\s+programa\s+es\s+patrocinado\s+por|provided\s+by\s+the\s+following\s+sponsors)\b"""),
        Regex("""(?i)\b(pr[óo]ximo\s+episodio|avance\s+del\s+pr[óo]ximo|to\s+be\s+continued|continuar[áa])\b"""),
        Regex("""(?i)^\s*(opening|ending|tema\s+de\s+apertura|tema\s+de\s+cierre)\s*$"""),
        // Rótulos de staff con "Cargo: nombre" (español e inglés). Se exige el cargo conocido para no
        // confundirlos con un diálogo del tipo "Naruto: ¡vamos!".
        Regex("""(?i)^\s*(dirigid[oa]\s+por|direcci[óo]n|director(a)?|gui[óo]n(\s+de)?|guionista|m[úu]sica(\s+(de|por))?|compositor(a)?|dise[ñn]o\s+de\s+(personajes|producci[óo]n|arte)|animaci[óo]n|producci[óo]n|productor(a)?|estudio|basado\s+en|obra\s+original|original\s+de|int[ée]rprete|interpretado\s+por|canci[óo]n|letra|arreglos?|cantante|ilustraci[óo]n|colaboraci[óo]n|reparto|doblaje)\s*[:：]"""),
        Regex("""(?i)^\s*(directed\s+by|written\s+by|music\s+by|original\s+(story|work)|produced\s+by|animation\s+(by|production)|performed\s+by|lyrics\s+by|composed\s+by|arranged\s+by|character\s+design|series\s+composition)\b"""),
        Regex("""(?i)\b(opening|ending)\s+(theme|song)\b|^\s*(op|ed)\s*\d*\s*[:：「"“]""")
    )

    fun evaluate(
        speakableText: String,
        settings: AppSettings
    ): FilterDecision {
        if (!settings.textFilterEnabled) {
            return FilterDecision.Allowed
        }

        // 1. Modo manual "Silenciar mientras Opening/Ending está activo"
        if (settings.muteOpeningEndingActive) {
            return FilterDecision.Ignored("Silenciado por modo Opening/Ending activo")
        }

        val trimmed = speakableText.trim()
        if (trimmed.isEmpty()) {
            return FilterDecision.Ignored("Texto vacío")
        }

        // 2. Filtro de longitud mínima de caracteres legibles (letras, dígitos o ideogramas CJK)
        val meaningfulCount = trimmed.count {
            it.isLetterOrDigit() || SubtitleNormalizer.isCjkCharacter(it)
        }
        val hasCjk = SubtitleNormalizer.containsCjkText(trimmed)
        val effectiveMinChars = if (hasCjk) 1 else settings.minSubtitleCharsFilter.coerceIn(1, 12)
        if (meaningfulCount < effectiveMinChars) {
            return FilterDecision.Ignored("Muy corto ($meaningfulCount < $effectiveMinChars car.)")
        }

        // 3. Filtro de créditos de episodio, staff y patrocinadores
        if (settings.ignoreCreditsAndStaff) {
            for (creditRegex in CREDITS_AND_SPONSOR_PATTERNS) {
                if (creditRegex.containsMatchIn(trimmed)) {
                    return FilterDecision.Ignored("Créditos / Staff detectado")
                }
            }
        }

        // 4. Patrones o palabras Regex personalizadas del usuario (una por línea o separadas por ';')
        val rawCustom = settings.customFilterPatternsRaw.trim()
        if (rawCustom.isNotEmpty()) {
            val rules = rawCustom
                .split('\n', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            for (patternStr in rules) {
                val matched = runCatching {
                    Regex(patternStr, RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
                }.getOrElse {
                    trimmed.contains(patternStr, ignoreCase = true)
                }
                if (matched) {
                    return FilterDecision.Ignored("Patrón de usuario: $patternStr")
                }
            }
        }

        return FilterDecision.Allowed
    }
}
