package com.example.service

import com.example.domain.model.CaptureWarning
import com.example.domain.model.NormalizedRect
import com.example.domain.model.ReaderState
import com.example.domain.model.ServiceStatus
import com.example.domain.model.SubtitleHistoryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Bus reactivo en memoria que sincroniza el estado en tiempo real entre
 * SubtitleReaderService, OverlayController y la UI en Jetpack Compose.
 */
object ReaderStateHolder {
    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    fun updateServiceStatus(status: ServiceStatus) {
        _state.update { current ->
            val sessionStart = when {
                status == ServiceStatus.ACTIVE_SCANNING && current.sessionStartTimestampMs == 0L ->
                    System.currentTimeMillis()
                status == ServiceStatus.INACTIVE -> 0L
                else -> current.sessionStartTimestampMs
            }
            current.copy(
                serviceStatus = status,
                sessionStartTimestampMs = sessionStart,
                isAutoPausedByStaticVideo = if (status != ServiceStatus.ACTIVE_SCANNING) false else current.isAutoPausedByStaticVideo
            )
        }
    }

    fun updateOcrProcessing(processing: Boolean, latencyMs: Long = _state.value.lastLatencyMs) {
        _state.update {
            it.copy(
                isOcrProcessing = processing,
                lastLatencyMs = latencyMs,
                framesProcessedCount = if (!processing) it.framesProcessedCount + 1 else it.framesProcessedCount
            )
        }
    }

    fun updateActiveRoi(roi: NormalizedRect, isAutoLocked: Boolean) {
        _state.update {
            it.copy(
                activeRoi = roi,
                isAutoRoiLocked = isAutoLocked
            )
        }
    }

    fun setEditingRoiOnOverlay(editing: Boolean) {
        _state.update { it.copy(isEditingRoiOnOverlay = editing) }
    }

    fun setOverlayHidden(hidden: Boolean) {
        _state.update { it.copy(isOverlayHidden = hidden) }
    }

    fun setOverlayMinimized(minimized: Boolean) {
        _state.update { it.copy(isOverlayMinimized = minimized) }
    }

    fun onSubtitleSpoken(
        text: String,
        normalizedKey: String,
        timestampMs: Long,
        originalText: String = text,
        translatedText: String = "",
        detectedSourceLang: String = "",
        sourceAppPackage: String = "",
        characterTag: String = "",
        appliedPitch: Float = 1.0f,
        maxHistorySize: Int = 20
    ) {
        _state.update { current ->
            val newEntry = SubtitleHistoryEntry(
                text = text,
                normalizedKey = normalizedKey,
                timestampMs = timestampMs,
                originalText = originalText,
                translatedText = translatedText,
                detectedSourceLang = detectedSourceLang,
                sourceAppPackage = sourceAppPackage,
                characterTag = characterTag
            )
            val updatedHistory = (listOf(newEntry) + current.recentSubtitles)
                .take(maxHistorySize.coerceIn(5, 30))
            val wordsInLine = text.trim()
                .split(Regex("""\s+"""))
                .count { it.isNotBlank() }
                .coerceAtLeast(1)

            current.copy(
                lastDetectedSubtitle = text,
                lastOriginalSubtitle = originalText,
                lastTranslatedSubtitle = translatedText,
                lastDetectedLanguageCode = detectedSourceLang,
                subtitlesReadCount = current.subtitlesReadCount + 1,
                recentSubtitles = updatedHistory,
                replayCursorIndex = 0,
                lastCharacterColorLabel = characterTag,
                lastCharacterAppliedPitch = appliedPitch,
                karaokeSpokenText = text,
                karaokeWordStartChar = -1,
                karaokeWordEndChar = -1,
                isAutoPausedByStaticVideo = false,
                totalWordsSpokenInSession = current.totalWordsSpokenInSession + wordsInLine
            )
        }
    }

    fun updateKaraokeHighlightRange(spokenText: String, startChar: Int, endChar: Int) {
        _state.update {
            it.copy(
                karaokeSpokenText = spokenText,
                karaokeWordStartChar = startChar,
                karaokeWordEndChar = endChar
            )
        }
    }

    fun clearKaraokeHighlight() {
        _state.update {
            it.copy(
                karaokeWordStartChar = -1,
                karaokeWordEndChar = -1
            )
        }
    }

    fun updateAutoPausedByStaticVideo(autoPaused: Boolean) {
        _state.update { it.copy(isAutoPausedByStaticVideo = autoPaused) }
    }

    fun onSubtitleFilteredOut(reason: String) {
        _state.update {
            it.copy(
                filteredOutSubtitlesCount = it.filteredOutSubtitlesCount + 1,
                lastFilteredReason = reason
            )
        }
    }

    fun updateReplayCursorIndex(index: Int) {
        _state.update { current ->
            val maxIdx = (current.recentSubtitles.size - 1).coerceAtLeast(0)
            current.copy(replayCursorIndex = index.coerceIn(0, maxIdx))
        }
    }

    fun updateActiveProfileState(foregroundPkg: String, profileName: String) {
        _state.update {
            it.copy(
                detectedForegroundPackage = foregroundPkg,
                activeProfileName = profileName
            )
        }
    }

    fun updateRawOcrCandidate(raw: String) {
        _state.update { it.copy(lastRawOcrCandidate = raw) }
    }

    fun updateTtsState(ready: Boolean, speaking: Boolean, voiceDescription: String) {
        _state.update {
            it.copy(
                ttsReady = ready,
                isTtsSpeaking = speaking,
                activeVoiceDescription = voiceDescription,
                karaokeWordStartChar = if (!speaking) -1 else it.karaokeWordStartChar,
                karaokeWordEndChar = if (!speaking) -1 else it.karaokeWordEndChar
            )
        }
    }

    fun updateWarning(warning: CaptureWarning) {
        _state.update { it.copy(captureWarning = warning) }
    }

    fun updateNaturalVoiceFallbackNotice(notice: String) {
        _state.update { it.copy(naturalVoiceFallbackNotice = notice) }
    }

    fun updateNaturalCacheUsedBytes(usedBytes: Long) {
        _state.update { it.copy(naturalCacheUsedBytes = usedBytes.coerceAtLeast(0L)) }
    }

    fun updateActiveSpeechTier(tierName: String, reason: String) {
        _state.update { it.copy(activeSpeechTierName = tierName, activeSpeechTierReason = reason) }
    }

    fun updateOfflineVoiceFallbackNotice(notice: String) {
        _state.update { it.copy(offlineVoiceFallbackNotice = notice) }
    }

    fun updateOfflineCacheUsedBytes(usedBytes: Long) {
        _state.update { it.copy(offlineCacheUsedBytes = usedBytes.coerceAtLeast(0L)) }
    }

    fun updateOfflineModelDownloaded(downloaded: Boolean) {
        _state.update { it.copy(isOfflineModelDownloaded = downloaded) }
    }

    fun resetSessionStats() {
        _state.update {
            it.copy(
                serviceStatus = ServiceStatus.INACTIVE,
                isOcrProcessing = false,
                isAutoRoiLocked = false,
                isEditingRoiOnOverlay = false,
                isTtsSpeaking = false,
                replayCursorIndex = 0,
                karaokeWordStartChar = -1,
                karaokeWordEndChar = -1,
                isAutoPausedByStaticVideo = false,
                sessionStartTimestampMs = 0L
            )
        }
    }
}
