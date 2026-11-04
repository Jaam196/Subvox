package com.example.domain.model

enum class ServiceStatus {
    INACTIVE,
    ACTIVE_SCANNING,
    PAUSED
}

enum class CaptureWarning {
    NONE,
    BLACK_SCREEN_OR_DRM,
    PROJECTION_REVOKED,
    TTS_SPANISH_VOICE_MISSING,
    OCR_ENGINE_DOWNLOADING
}

data class SubtitleHistoryEntry(
    val text: String,
    val normalizedKey: String,
    val timestampMs: Long,
    val originalText: String = text,
    val translatedText: String = "",
    val detectedSourceLang: String = "",
    val sourceAppPackage: String = "",
    val characterTag: String = ""
)

data class ReaderState(
    val serviceStatus: ServiceStatus = ServiceStatus.INACTIVE,
    val isOcrProcessing: Boolean = false,
    val activeRoi: NormalizedRect = NormalizedRect.DEFAULT_SUBTITLE_ROI,
    val isAutoRoiLocked: Boolean = false,
    val isEditingRoiOnOverlay: Boolean = false,
    val isOverlayHidden: Boolean = false,
    val isOverlayMinimized: Boolean = false,
    val lastDetectedSubtitle: String = "",
    val lastOriginalSubtitle: String = "",
    val lastTranslatedSubtitle: String = "",
    val lastDetectedLanguageCode: String = "",
    val lastRawOcrCandidate: String = "",
    val subtitlesReadCount: Int = 0,
    val framesProcessedCount: Int = 0,
    val isTtsSpeaking: Boolean = false,
    val ttsReady: Boolean = false,
    val activeVoiceDescription: String = "Inicializando TTS...",
    val captureWarning: CaptureWarning = CaptureWarning.NONE,
    val lastLatencyMs: Long = 0L,
    val recentSubtitles: List<SubtitleHistoryEntry> = emptyList(),
    // --- Estado Fase 1 ---
    val detectedForegroundPackage: String = "",
    val activeProfileName: String = "Por defecto",
    val replayCursorIndex: Int = 0,
    // --- Estado Fase 2 (Voces por personaje, Filtro, Sincronía) ---
    val lastCharacterColorLabel: String = "",
    val lastCharacterAppliedPitch: Float = 1.0f,
    val filteredOutSubtitlesCount: Int = 0,
    val lastFilteredReason: String = "",
    // --- Estado Fase 3 (Karaoke, Estadísticas, Autopausa por reproductor detenido) ---
    val karaokeSpokenText: String = "",
    val karaokeWordStartChar: Int = -1,
    val karaokeWordEndChar: Int = -1,
    val isAutoPausedByStaticVideo: Boolean = false,
    val sessionStartTimestampMs: Long = 0L,
    val totalWordsSpokenInSession: Int = 0,
    // --- Estado Voz Natural (IA - Gemini TTS) ---
    val naturalVoiceFallbackNotice: String = "",
    val naturalCacheUsedBytes: Long = 0L,
    // --- Estado Sistema en Tres Niveles y Voz Offline ---
    val activeSpeechTierName: String = "Voz Local (TTS Android)",
    val activeSpeechTierReason: String = "TTS local siempre disponible.",
    val offlineVoiceFallbackNotice: String = "",
    val offlineCacheUsedBytes: Long = 0L,
    val isOfflineModelDownloaded: Boolean = false
)
