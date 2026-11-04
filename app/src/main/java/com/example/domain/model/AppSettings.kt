package com.example.domain.model

enum class RoiMode {
    AUTO,
    MANUAL
}

enum class NewSubtitleBehavior {
    INTERRUPT_AND_READ_NEW,
    FINISH_CURRENT_QUEUE
}

/**
 * Idioma/script del motor ML Kit Text Recognition v2.
 * [AUTO] evalúa automáticamente scripts CJK y Latino cuando el subtítulo está en idioma original.
 */
enum class OcrScriptMode(
    val displayName: String,
    val description: String
) {
    LATIN("Latino (ES / EN / PT / FR)", "Óptimo para subtítulos en español, inglés o romaji"),
    JAPANESE("Japonés (Kanji / Kana)", "Reconoce Hiragana, Katakana, Kanji y caracteres latinos"),
    KOREAN("Coreano (Hangul)", "Reconoce Hangul coreano y texto mixto"),
    CHINESE("Chino (Simplificado / Tradicional)", "Reconoce Hanzi chino y texto mixto"),
    AUTO("Automático Multi-Script", "Prioriza el script activo o detecta CJK + Latino automáticamente");

    companion object {
        fun fromNameSafe(name: String?): OcrScriptMode {
            if (name.isNullOrBlank()) return LATIN
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: LATIN
        }
    }
}

/**
 * Modo de lectura cuando la traducción en vivo está activa.
 */
enum class TranslationReadMode(
    val displayName: String,
    val description: String
) {
    TRANSLATED_ONLY("Leer solo traducción", "Traduce el subtítulo y lee únicamente el texto traducido"),
    ORIGINAL_ONLY("Leer solo original", "Muestra la traducción en pantalla pero pronuncia el texto original"),
    BOTH_ORIGINAL_AND_TRANSLATED("Leer original y traducción", "Pronuncia primero la frase original y seguida su traducción");

    companion object {
        fun fromNameSafe(name: String?): TranslationReadMode {
            if (name.isNullOrBlank()) return TRANSLATED_ONLY
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: TRANSLATED_ONLY
        }
    }
}

/**
 * Modo de Voces por Personaje (Fase 2).
 */
enum class CharacterVoiceMode(
    val displayName: String,
    val description: String
) {
    OFF("Desactivado (Voz única)", "Usa siempre la voz y el tono configurados por defecto"),
    COLOR_BASED("Por color dominante del subtítulo", "Detecta si el texto es blanco, amarillo, cian, rosa/rojo o verde en la ROI y cambia el tono"),
    ALTERNATE_AB("Alternar Voz A / Voz B por línea", "Alterna entre el tono principal (A) y el tono secundario (B) en cada diálogo nuevo");

    companion object {
        fun fromNameSafe(name: String?): CharacterVoiceMode {
            if (name.isNullOrBlank()) return OFF
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: OFF
        }
    }
}

data class AppSettings(
    val ttsEnabled: Boolean = true,
    val preferredLanguageTag: String = "es-ES",
    val selectedVoiceName: String = "",
    val speechRate: Float = 1.08f,
    val pitch: Float = 1.0f,
    val volume: Float = 1.0f,
    val volumeBoostDb: Int = 0,
    val duckOtherAudio: Boolean = true,
    val allowNetworkVoices: Boolean = false,
    val ttsEnginePackage: String = "",
    val roiMode: RoiMode = RoiMode.AUTO,
    val manualRoi: NormalizedRect = NormalizedRect.DEFAULT_SUBTITLE_ROI,
    /** Intervalo de sondeo de pantalla (ms). El OCR solo se ejecuta cuando el subtítulo cambia. */
    val ocrIntervalMs: Long = 140L,
    val similarityThreshold: Float = 0.82f,
    val minStableFrames: Int = 1,
    val historyTtlSeconds: Int = 90,
    val newSubtitleBehavior: NewSubtitleBehavior = NewSubtitleBehavior.INTERRUPT_AND_READ_NEW,
    val highContrastPreprocessing: Boolean = true,
    val binarizeOcrImage: Boolean = false,
    val batterySaverMode: Boolean = false,
    val showOverlayControls: Boolean = true,
    val showRoiBoxOnOverlay: Boolean = false,
    // --- Campos Fase 1 ---
    val ocrScriptMode: OcrScriptMode = OcrScriptMode.LATIN,
    val liveTranslationEnabled: Boolean = false,
    val translationSourceLanguageCode: String = "auto",
    val translationTargetLanguageCode: String = "es",
    val translationWifiOnlyDownload: Boolean = true,
    val translationReadMode: TranslationReadMode = TranslationReadMode.TRANSLATED_ONLY,
    val pronunciationDictionaryEnabled: Boolean = true,
    val appProfilesEnabled: Boolean = false,
    val autoDetectForegroundAppProfile: Boolean = true,
    val manualSelectedProfilePackage: String = "",
    val replayBufferSize: Int = 10,
    // --- Campos Fase 2 ---
    val characterVoicesMode: CharacterVoiceMode = CharacterVoiceMode.OFF,
    val characterVoicePitchA: Float = 1.0f,
    val characterVoicePitchB: Float = 1.20f,
    val characterVoicePitchC: Float = 0.84f,
    val characterVoiceAId: String = "",
    val characterVoiceBId: String = "",
    val characterVoiceCId: String = "",
    val textFilterEnabled: Boolean = true,
    val muteOpeningEndingActive: Boolean = false,
    val ignoreUpperScreenSigns: Boolean = true,
    val ignoreCreditsAndStaff: Boolean = true,
    val skipSongLyrics: Boolean = true,
    val minSubtitleCharsFilter: Int = 2,
    val customFilterPatternsRaw: String = "",
    val saveHistoryToDatabase: Boolean = true,
    val syncOffsetMs: Int = 0,
    val volumeButtonsShortcutEnabled: Boolean = false,
    // --- Campos Fase 3 (Extras: Estudio, Karaoke, Portapapeles, Autopausa) ---
    val studyModeEnabled: Boolean = true,
    val karaokeHighlightEnabled: Boolean = true,
    val autoCopyToClipboard: Boolean = false,
    val autoPauseOnStaticPlayer: Boolean = false,
    val autoPauseStaticThresholdSeconds: Int = 12,
    // --- Campos Voz Natural (IA - Gemini TTS) ---
    val naturalVoiceEnabled: Boolean = false,
    val naturalVoiceModelName: String = "gemini-3.8-flash-lite-tts",
    val naturalVoiceInlineTagsEnabled: Boolean = false,
    val naturalVoiceName: String = "Kore",
    val designedVoices: List<DesignedVoice> = emptyList(),
    val naturalVoiceStyle: String = "habla de forma natural, expresiva y cercana, como un doblaje de anime en español",
    val naturalVoiceSpeed: Float = 1.0f,
    val naturalCacheMaxMb: Int = 60,
    val naturalFallbackTimeoutMs: Long = 3500L,
    val naturalOnlyOnWifi: Boolean = false,
    // --- Campos Voz Neuronal Offline (Sherpa-ONNX) ---
    val offlineVoiceEnabled: Boolean = true,
    val offlineVoiceSelectedId: String = "vits-piper-es_MX-claude-high",
    val selectedThemeId: String = "aurora",
    val themeAnimationsEnabled: Boolean = true,
    val offlineVoiceSpeed: Float = 1.0f,
    val offlineVoiceThreads: Int = 2,
    val offlineOnlyOnWifi: Boolean = true,
    val offlineCacheMaxMb: Int = 80,
    val offlinePromptDismissed: Boolean = false,
    /** Acelera la voz neuronal automáticamente cuando los subtítulos llegan más rápido de lo que se pueden leer. */
    val catchUpSpeedEnabled: Boolean = true,
    // --- Modo Simple / Avanzado de la interfaz de Ajustes ---
    val simpleSettingsMode: Boolean = true
)
