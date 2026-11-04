package com.example.ui

import com.example.ocr.OcrCorrection
import com.example.ocr.OcrCorrectionStore
import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.preferences.SettingsRepository
import com.example.domain.model.AppSettings
import com.example.domain.model.CharacterVoiceMode
import com.example.domain.model.NewSubtitleBehavior
import com.example.domain.model.NormalizedRect
import com.example.domain.model.OcrScriptMode
import com.example.domain.model.ReaderState
import com.example.domain.model.RoiMode
import com.example.domain.model.TranslationReadMode
import com.example.history.SubtitleHistoryRecord
import com.example.history.SubtitleHistoryRepository
import com.example.profiles.AppProfile
import com.example.profiles.AppProfileRepository
import com.example.domain.model.DesignedVoice
import com.example.pronunciation.PronunciationRepository
import com.example.pronunciation.PronunciationRule
import com.example.security.ApiKeyStore
import com.example.security.ApiKeyValidationResult
import com.example.service.ReaderStateHolder
import com.example.service.ServiceContracts
import com.example.translate.SubtitleTranslatorManager
import com.example.translate.TranslationLanguageModelStatus
import com.example.tts.LocalSpeechEngine
import com.example.tts.NaturalSpeechCache
import com.example.tts.NaturalSpeechEngine
import com.example.tts.SpanishVoiceOption
import com.example.tts.SpeechEngine
import com.example.tts.SubtitleTtsManager
import com.example.tts.TtsEngineOption
import com.example.tts.DesignedVoiceResult
import com.example.tts.VoiceDesignClient
import com.example.tts.VoiceDesignResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class VoiceDesignUiState {
    data object Idle : VoiceDesignUiState()
    data object Designing : VoiceDesignUiState()
    data class Success(val voice: DesignedVoice, val message: String) : VoiceDesignUiState()
    data class Error(val message: String) : VoiceDesignUiState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application.applicationContext)
    private val apiKeyStore = ApiKeyStore(application.applicationContext)
    private val pronunciationRepository = PronunciationRepository(application.applicationContext)
    private val ocrCorrectionStore = OcrCorrectionStore(application.applicationContext)
    private val appProfileRepository = AppProfileRepository(application.applicationContext)
    private val historyRepository = SubtitleHistoryRepository(application.applicationContext)
    private val translatorManager = SubtitleTranslatorManager()
    private val voiceDesignClient = VoiceDesignClient()

    val readerState: StateFlow<ReaderState> = ReaderStateHolder.state

    val hasApiKeyState: StateFlow<Boolean> = apiKeyStore.hasKeyFlow
    val maskedApiKeyState: StateFlow<String> = apiKeyStore.maskedKeyFlow
    val userApiKeyState: StateFlow<com.example.security.UserApiKeyState> = apiKeyStore.keyStateFlow

    private val _apiKeyValidationState = MutableStateFlow<ApiKeyValidationResult>(ApiKeyValidationResult.Idle)
    val apiKeyValidationState: StateFlow<ApiKeyValidationResult> = _apiKeyValidationState.asStateFlow()

    private val _voiceDesignState = MutableStateFlow<VoiceDesignUiState>(VoiceDesignUiState.Idle)
    val voiceDesignState: StateFlow<VoiceDesignUiState> = _voiceDesignState.asStateFlow()

    val settingsState: StateFlow<AppSettings> = settingsRepository.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings()
    )

    val pronunciationRulesState: StateFlow<List<PronunciationRule>> =
        pronunciationRepository.rulesFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val ocrCorrectionsState: StateFlow<List<OcrCorrection>> =
        ocrCorrectionStore.rulesFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /** Aprende de una frase corregida a mano; devuelve cuántas reglas nuevas ha sacado. */
    fun learnOcrCorrection(original: String, corrected: String, onResult: (Int) -> Unit = {}) {
        viewModelScope.launch { onResult(ocrCorrectionStore.learn(original, corrected).size) }
    }

    fun addOcrCorrection(wrong: String, right: String) {
        viewModelScope.launch { ocrCorrectionStore.add(wrong, right) }
    }

    fun deleteOcrCorrection(id: String) {
        viewModelScope.launch { ocrCorrectionStore.delete(id) }
    }

    fun clearOcrCorrections() {
        viewModelScope.launch { ocrCorrectionStore.clear() }
    }

    val appProfilesState: StateFlow<List<AppProfile>> =
        appProfileRepository.profilesFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val historyRecordsState: StateFlow<List<SubtitleHistoryRecord>> =
        historyRepository.allRecordsFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val translationModelsState: StateFlow<List<TranslationLanguageModelStatus>> =
        translatorManager.modelsState

    // Instancia de TTS para vista previa y listado de voces y motores instalados en la pantalla de ajustes
    private val previewTtsManager = SubtitleTtsManager(
        context = application.applicationContext,
        onStateChanged = { ready, speaking, voiceDesc ->
            previewLocalEngine.updateObservedSpeakingState(speaking)
            if (readerState.value.serviceStatus == com.example.domain.model.ServiceStatus.INACTIVE) {
                val s = settingsState.value
                if (!s.naturalVoiceEnabled || s.batterySaverMode || !apiKeyStore.hasKey() || speaking) {
                    ReaderStateHolder.updateTtsState(ready, speaking, voiceDesc)
                }
            }
        },
        onSpanishVoiceMissing = {}
    )

    private val previewLocalEngine: LocalSpeechEngine = LocalSpeechEngine(previewTtsManager)
    val offlineModelManager = com.example.tts.offline.OfflineVoiceModelManager(application.applicationContext)
    val offlineSpeechCache = com.example.tts.offline.OfflineSpeechCache(application.applicationContext)
    val offlineDownloadStates: StateFlow<Map<String, com.example.tts.offline.OfflineDownloadState>> = offlineModelManager.statesFlow

    private val previewOfflineEngine = com.example.tts.offline.OfflineNeuralSpeechEngine(
        context = application.applicationContext,
        modelManager = offlineModelManager,
        fallbackLocalEngine = previewLocalEngine,
        cache = offlineSpeechCache,
        onStateChanged = { ready, speaking, voiceDesc ->
            if (readerState.value.serviceStatus == com.example.domain.model.ServiceStatus.INACTIVE) {
                ReaderStateHolder.updateTtsState(ready, speaking, voiceDesc)
            }
        },
        onFallbackNoticeChanged = { notice ->
            ReaderStateHolder.updateOfflineVoiceFallbackNotice(notice)
        },
        onCacheSizeUpdated = { bytes ->
            ReaderStateHolder.updateOfflineCacheUsedBytes(bytes)
        },
        preloadModel = false
    )

    private val naturalSpeechCache = NaturalSpeechCache(application.applicationContext)
    private val previewNaturalEngine = NaturalSpeechEngine(
        context = application.applicationContext,
        apiKeyStore = apiKeyStore,
        fallbackLocalEngine = previewOfflineEngine,
        cache = naturalSpeechCache,
        onStateChanged = { ready, speaking, voiceDesc ->
            if (readerState.value.serviceStatus == com.example.domain.model.ServiceStatus.INACTIVE) {
                ReaderStateHolder.updateTtsState(ready, speaking, voiceDesc)
            }
        },
        onFallbackNoticeChanged = { notice ->
            ReaderStateHolder.updateNaturalVoiceFallbackNotice(notice)
        },
        onCacheSizeUpdated = { bytes ->
            ReaderStateHolder.updateNaturalCacheUsedBytes(bytes)
        }
    )
    private val previewSpeechEngine: SpeechEngine = previewNaturalEngine

    val availableVoices: StateFlow<List<SpanishVoiceOption>> = previewTtsManager.availableSpanishVoices
    val availableEngines: StateFlow<List<TtsEngineOption>> = previewTtsManager.availableEngines

    init {
        viewModelScope.launch {
            settingsRepository.settingsFlow.collectLatest { settings ->
                previewSpeechEngine.applySettings(settings)
            }
        }
        viewModelScope.launch {
            ReaderStateHolder.updateNaturalCacheUsedBytes(naturalSpeechCache.currentSizeBytes())
            translatorManager.refreshDownloadedModels()
        }
    }

    fun startCaptureWithPermissionResult(resultCode: Int, resultData: Intent) {
        ServiceContracts.dispatchStartCapture(
            context = getApplication(),
            resultCode = resultCode,
            resultData = resultData
        )
    }

    fun pauseReader() {
        ServiceContracts.dispatchCommand(getApplication(), ServiceContracts.ACTION_PAUSE_CAPTURE)
    }

    fun resumeReader() {
        ServiceContracts.dispatchCommand(getApplication(), ServiceContracts.ACTION_RESUME_CAPTURE)
    }

    fun stopReader() {
        ServiceContracts.dispatchCommand(getApplication(), ServiceContracts.ACTION_STOP_CAPTURE)
    }

    fun recalibrateAutoRoi() {
        ServiceContracts.dispatchCommand(getApplication(), ServiceContracts.ACTION_RECALIBRATE_AUTO_ROI)
    }

    fun toggleInteractiveRoiEditor() {
        ServiceContracts.dispatchCommand(getApplication(), ServiceContracts.ACTION_TOGGLE_ROI_EDITOR)
    }

    fun showOverlayControls() {
        ServiceContracts.dispatchCommand(getApplication(), ServiceContracts.ACTION_SHOW_OVERLAY)
    }

    fun hideOverlayControls() {
        ServiceContracts.dispatchCommand(getApplication(), ServiceContracts.ACTION_HIDE_OVERLAY)
    }

    fun repeatLastSubtitle() {
        val state = readerState.value
        if (state.serviceStatus != com.example.domain.model.ServiceStatus.INACTIVE) {
            ServiceContracts.dispatchCommand(getApplication(), ServiceContracts.ACTION_REPEAT_LAST_SUBTITLE)
        } else {
            val text = state.recentSubtitles.firstOrNull()?.text ?: state.lastDetectedSubtitle
            if (text.isNotBlank()) {
                ReaderStateHolder.updateReplayCursorIndex(0)
                previewSpeechEngine.speak(text, forceSpeakEvenIfDisabled = true)
            }
        }
    }

    fun repeatPreviousSubtitle() {
        val state = readerState.value
        if (state.serviceStatus != com.example.domain.model.ServiceStatus.INACTIVE) {
            ServiceContracts.dispatchCommand(getApplication(), ServiceContracts.ACTION_REPEAT_PREVIOUS_SUBTITLE)
        } else {
            val history = state.recentSubtitles
            if (history.isNotEmpty()) {
                val nextIdx = (state.replayCursorIndex + 1).coerceAtMost(history.size - 1)
                ReaderStateHolder.updateReplayCursorIndex(nextIdx)
                previewSpeechEngine.speak(history[nextIdx].text, forceSpeakEvenIfDisabled = true)
            }
        }
    }

    fun speakSpecificHistoryEntry(text: String, index: Int) {
        if (text.isBlank()) return
        ReaderStateHolder.updateReplayCursorIndex(index)
        previewSpeechEngine.speak(text, forceSpeakEvenIfDisabled = true)
    }

    fun speakTextSample(text: String) {
        if (text.isBlank()) return
        previewSpeechEngine.speak(text, forceSpeakEvenIfDisabled = true)
    }

    fun testSpanishVoiceSample() {
        previewSpeechEngine.speak(
            text = "¡Hola! El lector de subtítulos en español está listo para tu episodio de anime.",
            forceSpeakEvenIfDisabled = true
        )
    }

    fun testNaturalVoiceSample() {
        previewNaturalEngine.speakExpressivePreview(
            text = "¡Increíble! Así suena la voz expresiva con doblaje para tus subtítulos, pasando automáticamente a la voz local si falla."
        )
    }

    fun toggleExpressivePrimaryVoice() {
        val next = !settingsState.value.naturalVoiceEnabled
        setNaturalVoiceEnabled(next)
    }

    fun setCatchUpSpeedEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateCatchUpSpeedEnabled(enabled) }
    }

    fun setTtsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateTtsEnabled(enabled) }
    }

    fun setLanguageTag(tag: String) {
        viewModelScope.launch { settingsRepository.updateLanguageTag(tag) }
    }

    fun setVoiceName(voiceName: String) {
        viewModelScope.launch { settingsRepository.updateVoiceName(voiceName) }
    }

    fun setSpeechRate(rate: Float) {
        viewModelScope.launch { settingsRepository.updateSpeechRate(rate) }
    }

    fun setPitch(pitch: Float) {
        viewModelScope.launch { settingsRepository.updatePitch(pitch) }
    }

    fun setVolume(volume: Float) {
        viewModelScope.launch { settingsRepository.updateVolume(volume) }
    }

    fun setVolumeBoostDb(boostDb: Int) {
        viewModelScope.launch { settingsRepository.updateVolumeBoostDb(boostDb) }
    }

    fun setDuckOtherAudio(duck: Boolean) {
        viewModelScope.launch { settingsRepository.updateDuckOtherAudio(duck) }
    }

    fun setAllowNetworkVoices(allow: Boolean) {
        viewModelScope.launch { settingsRepository.updateAllowNetworkVoices(allow) }
    }

    fun setTtsEnginePackage(packageName: String) {
        viewModelScope.launch { settingsRepository.updateTtsEnginePackage(packageName) }
    }

    fun adjustVolumeStep(increase: Boolean) {
        viewModelScope.launch { settingsRepository.adjustVolumeAndBoostStep(increase) }
    }

    fun setRoiMode(mode: RoiMode) {
        viewModelScope.launch { settingsRepository.updateRoiMode(mode) }
    }

    fun setManualRoi(rect: NormalizedRect) {
        viewModelScope.launch { settingsRepository.updateManualRoi(rect) }
    }

    fun resetManualRoiToDefault() {
        viewModelScope.launch {
            settingsRepository.updateManualRoi(NormalizedRect.DEFAULT_SUBTITLE_ROI)
        }
    }

    fun setOcrIntervalMs(intervalMs: Long) {
        viewModelScope.launch { settingsRepository.updateOcrIntervalMs(intervalMs) }
    }

    fun setSimilarityThreshold(threshold: Float) {
        viewModelScope.launch { settingsRepository.updateSimilarityThreshold(threshold) }
    }

    fun setMinStableFrames(frames: Int) {
        viewModelScope.launch { settingsRepository.updateMinStableFrames(frames) }
    }

    fun setHistoryTtlSeconds(seconds: Int) {
        viewModelScope.launch { settingsRepository.updateHistoryTtlSeconds(seconds) }
    }

    fun setNewSubtitleBehavior(behavior: NewSubtitleBehavior) {
        viewModelScope.launch { settingsRepository.updateNewSubtitleBehavior(behavior) }
    }

    fun setHighContrastPreprocessing(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateHighContrastPreprocessing(enabled) }
    }

    fun setBinarizeOcrImage(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateBinarizeOcrImage(enabled) }
    }

    fun setBatterySaverMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateBatterySaverMode(enabled) }
    }

    fun setShowOverlayControls(show: Boolean) {
        viewModelScope.launch { settingsRepository.updateShowOverlayControls(show) }
    }

    fun setShowRoiBoxOnOverlay(show: Boolean) {
        viewModelScope.launch { settingsRepository.updateShowRoiBoxOnOverlay(show) }
    }

    // --- Acciones Fase 1 ---

    fun setOcrScriptMode(mode: OcrScriptMode) {
        viewModelScope.launch { settingsRepository.updateOcrScriptMode(mode) }
    }

    fun setLiveTranslationEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateLiveTranslationEnabled(enabled) }
    }

    fun setTranslationSourceLanguage(langCode: String) {
        viewModelScope.launch { settingsRepository.updateTranslationSourceLanguage(langCode) }
    }

    fun setTranslationTargetLanguage(langCode: String) {
        viewModelScope.launch { settingsRepository.updateTranslationTargetLanguage(langCode) }
    }

    fun setTranslationWifiOnlyDownload(wifiOnly: Boolean) {
        viewModelScope.launch { settingsRepository.updateTranslationWifiOnlyDownload(wifiOnly) }
    }

    fun setTranslationReadMode(mode: TranslationReadMode) {
        viewModelScope.launch { settingsRepository.updateTranslationReadMode(mode) }
    }

    fun downloadTranslationModel(languageCode: String) {
        viewModelScope.launch {
            translatorManager.downloadLanguageModel(
                languageCode = languageCode,
                requireWifi = settingsState.value.translationWifiOnlyDownload
            )
        }
    }

    fun deleteTranslationModel(languageCode: String) {
        viewModelScope.launch {
            translatorManager.deleteLanguageModel(languageCode)
        }
    }

    fun refreshTranslationModels() {
        viewModelScope.launch {
            translatorManager.refreshDownloadedModels()
        }
    }

    fun setPronunciationDictionaryEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updatePronunciationDictionaryEnabled(enabled) }
    }

    fun upsertPronunciationRule(rule: PronunciationRule) {
        viewModelScope.launch { pronunciationRepository.upsertRule(rule) }
    }

    fun deletePronunciationRule(ruleId: String) {
        viewModelScope.launch { pronunciationRepository.deleteRule(ruleId) }
    }

    fun restoreDefaultPronunciationRules() {
        viewModelScope.launch { pronunciationRepository.restoreDefaultHonorifics() }
    }

    fun importPronunciationRulesJson(json: String) {
        viewModelScope.launch { pronunciationRepository.importRulesJson(json, mergeWithExisting = true) }
    }

    fun setAppProfilesEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateAppProfilesEnabled(enabled) }
    }

    fun setAutoDetectForegroundAppProfile(autoDetect: Boolean) {
        viewModelScope.launch { settingsRepository.updateAutoDetectForegroundAppProfile(autoDetect) }
    }

    fun setManualSelectedProfilePackage(packageName: String) {
        viewModelScope.launch { settingsRepository.updateManualSelectedProfilePackage(packageName) }
    }

    fun upsertAppProfile(profile: AppProfile) {
        viewModelScope.launch { appProfileRepository.upsertProfile(profile) }
    }

    fun deleteAppProfile(packageName: String) {
        viewModelScope.launch { appProfileRepository.deleteProfile(packageName) }
    }

    fun restoreDefaultAppProfiles() {
        viewModelScope.launch { appProfileRepository.restorePresetProfiles() }
    }

    fun setReplayBufferSize(size: Int) {
        viewModelScope.launch { settingsRepository.updateReplayBufferSize(size) }
    }

    // --- Acciones Fase 2 (Voces por personaje, Filtro, Historial Room, Sincronía, Atajos) ---

    fun setCharacterVoicesMode(mode: CharacterVoiceMode) {
        viewModelScope.launch { settingsRepository.updateCharacterVoicesMode(mode) }
    }

    fun setCharacterVoicePitches(pitchA: Float, pitchB: Float, pitchC: Float) {
        viewModelScope.launch { settingsRepository.updateCharacterVoicePitches(pitchA, pitchB, pitchC) }
    }

    fun setTextFilterEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateTextFilterEnabled(enabled) }
    }

    fun setMuteOpeningEndingActive(mute: Boolean) {
        viewModelScope.launch { settingsRepository.updateMuteOpeningEndingActive(mute) }
    }

    fun setIgnoreUpperScreenSigns(ignore: Boolean) {
        viewModelScope.launch { settingsRepository.updateIgnoreUpperScreenSigns(ignore) }
    }

    fun setSkipSongLyrics(skip: Boolean) {
        viewModelScope.launch { settingsRepository.updateSkipSongLyrics(skip) }
    }

    fun setIgnoreCreditsAndStaff(ignore: Boolean) {
        viewModelScope.launch { settingsRepository.updateIgnoreCreditsAndStaff(ignore) }
    }

    fun setMinSubtitleCharsFilter(minChars: Int) {
        viewModelScope.launch { settingsRepository.updateMinSubtitleCharsFilter(minChars) }
    }

    fun setCustomFilterPatternsRaw(patternsRaw: String) {
        viewModelScope.launch { settingsRepository.updateCustomFilterPatternsRaw(patternsRaw) }
    }

    fun setSaveHistoryToDatabase(save: Boolean) {
        viewModelScope.launch { settingsRepository.updateSaveHistoryToDatabase(save) }
    }

    fun deleteHistoryRecord(id: Long) {
        viewModelScope.launch { historyRepository.deleteById(id) }
    }

    fun clearAllHistoryRecords() {
        viewModelScope.launch { historyRepository.clearAll() }
    }

    fun setSyncOffsetMs(offsetMs: Int) {
        viewModelScope.launch { settingsRepository.updateSyncOffsetMs(offsetMs) }
    }

    fun adjustSyncOffsetStep(stepDeltaMs: Int) {
        viewModelScope.launch { settingsRepository.adjustSyncOffsetStep(stepDeltaMs) }
    }

    fun setVolumeButtonsShortcutEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateVolumeButtonsShortcutEnabled(enabled) }
    }

    // --- Acciones Fase 3 (Modo Estudio, Karaoke, Portapapeles, Autopausa) ---

    fun setStudyModeEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateStudyModeEnabled(enabled) }
    }

    fun setKaraokeHighlightEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateKaraokeHighlightEnabled(enabled) }
    }

    fun setAutoCopyToClipboard(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateAutoCopyToClipboard(enabled) }
    }

    fun setAutoPauseOnStaticPlayer(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateAutoPauseOnStaticPlayer(enabled) }
    }

    fun setAutoPauseStaticThresholdSeconds(seconds: Int) {
        viewModelScope.launch { settingsRepository.updateAutoPauseStaticThresholdSeconds(seconds) }
    }

    // --- Acciones Voz Natural (IA - Gemini TTS) ---

    fun setNaturalVoiceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateNaturalVoiceEnabled(enabled)
            if (!enabled) {
                ReaderStateHolder.updateNaturalVoiceFallbackNotice("")
            }
        }
    }

    fun setNaturalVoiceModelName(modelName: String) {
        viewModelScope.launch { settingsRepository.updateNaturalVoiceModelName(modelName) }
    }

    fun setNaturalVoiceInlineTagsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateNaturalVoiceInlineTagsEnabled(enabled) }
    }

    fun setNaturalVoiceName(voiceName: String) {
        viewModelScope.launch { settingsRepository.updateNaturalVoiceName(voiceName) }
    }

    fun setNaturalVoiceStyle(style: String) {
        viewModelScope.launch { settingsRepository.updateNaturalVoiceStyle(style) }
    }

    fun setNaturalVoiceSpeed(speed: Float) {
        viewModelScope.launch { settingsRepository.updateNaturalVoiceSpeed(speed) }
    }

    fun setNaturalCacheMaxMb(maxMb: Int) {
        viewModelScope.launch {
            settingsRepository.updateNaturalCacheMaxMb(maxMb)
            val updatedBytes = naturalSpeechCache.trimToMaxSize(maxMb)
            ReaderStateHolder.updateNaturalCacheUsedBytes(updatedBytes)
        }
    }

    fun setNaturalFallbackTimeoutMs(timeoutMs: Long) {
        viewModelScope.launch { settingsRepository.updateNaturalFallbackTimeoutMs(timeoutMs) }
    }

    fun setNaturalOnlyOnWifi(onlyOnWifi: Boolean) {
        viewModelScope.launch { settingsRepository.updateNaturalOnlyOnWifi(onlyOnWifi) }
    }

    fun setSimpleSettingsMode(simpleMode: Boolean) {
        viewModelScope.launch { settingsRepository.updateSimpleSettingsMode(simpleMode) }
    }

    fun clearNaturalSpeechCache() {
        viewModelScope.launch {
            val bytes = naturalSpeechCache.clearAll()
            ReaderStateHolder.updateNaturalCacheUsedBytes(bytes)
        }
    }

    // --- Gestión de la API Key del Usuario (Android Keystore) ---

    fun saveUserApiKey(rawKey: String) {
        viewModelScope.launch {
            val trimmed = rawKey.trim()
            val result = apiKeyStore.saveKey(trimmed)
            previewNaturalEngine.onApiKeyChangedOrCleared()
            _apiKeyValidationState.value = when (result) {
                is com.example.security.SaveKeyResult.Success -> {
                    ApiKeyValidationResult.Valid("Clave guardada de forma cifrada en este dispositivo.")
                }
                is com.example.security.SaveKeyResult.HardwareUnavailable -> {
                    ApiKeyValidationResult.OtherError("Este dispositivo no permite guardar la clave de forma segura.")
                }
                is com.example.security.SaveKeyResult.EmptyKey -> {
                    ApiKeyValidationResult.InvalidKey("La clave estaba vacía. Sin clave configurada.")
                }
                is com.example.security.SaveKeyResult.EncryptionError -> {
                    ApiKeyValidationResult.OtherError(result.message)
                }
            }
        }
    }

    fun clearUserApiKey() {
        viewModelScope.launch {
            apiKeyStore.clearKey()
            previewNaturalEngine.onApiKeyChangedOrCleared()
            ReaderStateHolder.updateNaturalVoiceFallbackNotice("")
            _apiKeyValidationState.value = ApiKeyValidationResult.Idle
        }
    }

    fun validateUserApiKey(candidateDraftKey: String) {
        viewModelScope.launch {
            _apiKeyValidationState.value = ApiKeyValidationResult.Testing
            val draftTrimmed = candidateDraftKey.trim()
            if (draftTrimmed.isNotEmpty()) {
                apiKeyStore.saveKey(draftTrimmed)
                previewNaturalEngine.onApiKeyChangedOrCleared()
            }
            val result = previewNaturalEngine.validateApiKey(
                candidateKeyOverride = draftTrimmed.takeIf { it.isNotEmpty() },
                voiceName = settingsState.value.naturalVoiceName
            )
            _apiKeyValidationState.value = result
        }
    }

    // --- Voice Design (Diseñar mi voz con Gemini API) ---

    fun resetVoiceDesignState() {
        _voiceDesignState.value = VoiceDesignUiState.Idle
    }

    fun designNewVoice(name: String, description: String, sampleText: String) {
        viewModelScope.launch {
            val apiKey = apiKeyStore.getKey()
            if (apiKey.isNullOrBlank()) {
                _voiceDesignState.value = VoiceDesignUiState.Error(
                    "Requiere clave de Google AI Studio para diseñar voces con Gemini."
                )
                return@launch
            }
            val trimmedName = name.trim()
            val trimmedDesc = description.trim()
            if (trimmedName.isEmpty() || trimmedDesc.isEmpty()) {
                _voiceDesignState.value = VoiceDesignUiState.Error(
                    "El nombre y la descripción de la voz no pueden estar vacíos."
                )
                return@launch
            }
            _voiceDesignState.value = VoiceDesignUiState.Designing
            val effectiveSample = sampleText.trim().ifEmpty {
                "Hola, soy una voz diseñada para el doblaje de anime en español."
            }
            val result = voiceDesignClient.createVoice(
                apiKey = apiKey,
                name = trimmedName,
                description = trimmedDesc,
                sampleText = effectiveSample
            )
            when (result) {
                is DesignedVoiceResult.Success -> {
                    val designedVoice = result.voice
                    settingsRepository.saveDesignedVoice(designedVoice)
                    _voiceDesignState.value = VoiceDesignUiState.Success(
                        voice = designedVoice,
                        message = "Voz '${designedVoice.name}' creada exitosamente."
                    )
                }
                is DesignedVoiceResult.Error -> {
                    _voiceDesignState.value = VoiceDesignUiState.Error(result.message)
                }
            }
        }
    }

    fun deleteDesignedVoice(voiceId: String) {
        viewModelScope.launch {
            settingsRepository.deleteDesignedVoice(voiceId)
        }
    }

    fun renameDesignedVoice(voiceId: String, newName: String) {
        viewModelScope.launch {
            settingsRepository.renameDesignedVoice(voiceId, newName)
        }
    }

    fun setActiveDesignedVoice(voice: DesignedVoice) {
        viewModelScope.launch {
            settingsRepository.updateNaturalVoiceName(voice.voiceId)
        }
    }

    fun playDesignedVoiceSample(voice: DesignedVoice) {
        previewNaturalEngine.playSamplePcm(
            pcmBytes = voice.samplePcmAudio,
            sampleRateHz = voice.sampleRateHz,
            voiceName = voice.name
        )
    }

    fun setCharacterVoiceAId(voiceId: String) {
        viewModelScope.launch {
            settingsRepository.updateCharacterVoiceAId(voiceId)
        }
    }

    fun setCharacterVoiceBId(voiceId: String) {
        viewModelScope.launch {
            settingsRepository.updateCharacterVoiceBId(voiceId)
        }
    }

    fun setCharacterVoiceCId(voiceId: String) {
        viewModelScope.launch {
            settingsRepository.updateCharacterVoiceCId(voiceId)
        }
    }

    // --- Gestión de Voz Neuronal Offline (Sherpa-ONNX) ---

    fun onOfflineVoiceEnabledChange(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateOfflineVoiceEnabled(enabled)
            if (enabled && !offlineModelManager.isModelInstalled(settingsState.value.offlineVoiceSelectedId)) {
                offlineModelManager.enqueueDownload(
                    settingsState.value.offlineVoiceSelectedId,
                    settingsState.value.offlineOnlyOnWifi
                )
            }
        }
    }

    fun setSelectedThemeId(themeId: String) {
        viewModelScope.launch { settingsRepository.updateSelectedThemeId(themeId) }
    }

    fun setThemeAnimationsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateThemeAnimationsEnabled(enabled) }
    }

    fun onOfflineVoiceSelectedIdChange(modelId: String) {
        viewModelScope.launch {
            settingsRepository.updateOfflineVoiceSelectedId(modelId)
        }
    }

    fun onOfflineVoiceSpeedChange(speed: Float) {
        viewModelScope.launch {
            settingsRepository.updateOfflineVoiceSpeed(speed)
        }
    }

    fun onOfflineVoiceThreadsChange(threads: Int) {
        viewModelScope.launch {
            settingsRepository.updateOfflineVoiceThreads(threads)
        }
    }

    fun onOfflineOnlyOnWifiChange(onlyOnWifi: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateOfflineOnlyOnWifi(onlyOnWifi)
        }
    }

    fun startOfflineModelDownload(modelId: String? = null) {
        val targetId = modelId ?: settingsState.value.offlineVoiceSelectedId
        offlineModelManager.enqueueDownload(targetId, settingsState.value.offlineOnlyOnWifi)
    }

    fun cancelOfflineModelDownload(modelId: String? = null) {
        val targetId = modelId ?: settingsState.value.offlineVoiceSelectedId
        offlineModelManager.cancelDownload(targetId)
    }

    fun deleteOfflineModel(modelId: String? = null) {
        val targetId = modelId ?: settingsState.value.offlineVoiceSelectedId
        offlineModelManager.deleteModel(targetId)
        ReaderStateHolder.updateOfflineModelDownloaded(false)
    }

    fun clearOfflineCache() {
        offlineSpeechCache.clearCache()
        ReaderStateHolder.updateOfflineCacheUsedBytes(offlineSpeechCache.getUsedSizeBytes())
    }

    fun dismissOfflinePrompt() {
        viewModelScope.launch {
            settingsRepository.updateOfflinePromptDismissed(true)
        }
    }

    fun testOfflineVoice(sampleText: String = "Prueba de voz neuronal offline con Sherpa y síntesis local en español.") {
        previewOfflineEngine.speakOfflinePreview(sampleText)
    }

    enum class VoiceModeOption {
        NEURAL_OFFLINE,
        SYSTEM_TTS,
        GEMINI_AI
    }

    fun setPrimaryVoiceType(mode: VoiceModeOption) {
        viewModelScope.launch {
            when (mode) {
                VoiceModeOption.NEURAL_OFFLINE -> {
                    settingsRepository.updateOfflineVoiceEnabled(true)
                    settingsRepository.updateNaturalVoiceEnabled(false)
                    if (!offlineModelManager.isModelInstalled(settingsState.value.offlineVoiceSelectedId)) {
                        offlineModelManager.enqueueDownload(
                            settingsState.value.offlineVoiceSelectedId,
                            settingsState.value.offlineOnlyOnWifi
                        )
                    }
                }
                VoiceModeOption.SYSTEM_TTS -> {
                    settingsRepository.updateOfflineVoiceEnabled(false)
                    settingsRepository.updateNaturalVoiceEnabled(false)
                }
                VoiceModeOption.GEMINI_AI -> {
                    settingsRepository.updateNaturalVoiceEnabled(true)
                    settingsRepository.updateOfflineVoiceEnabled(false)
                }
            }
        }
    }

    fun applyPresetMode(presetName: String) {
        viewModelScope.launch {
            when (presetName) {
                "anime" -> {
                    settingsRepository.updateRoiMode(com.example.domain.model.RoiMode.AUTO)
                    settingsRepository.updateIgnoreUpperScreenSigns(true)
                    settingsRepository.updateIgnoreCreditsAndStaff(true)
                    settingsRepository.updateOfflineVoiceEnabled(true)
                }
                "translate" -> {
                    settingsRepository.updateLiveTranslationEnabled(true)
                    settingsRepository.updateOcrScriptMode(com.example.domain.model.OcrScriptMode.JAPANESE)
                    settingsRepository.updateTranslationSourceLanguage("ja")
                    settingsRepository.updateTranslationTargetLanguage("es")
                }
                "battery" -> {
                    settingsRepository.updateBatterySaverMode(true)
                    settingsRepository.updateHighContrastPreprocessing(false)
                    settingsRepository.updateBinarizeOcrImage(false)
                }
            }
        }
    }

    override fun onCleared() {
        previewSpeechEngine.shutdown()
        previewOfflineEngine.shutdown()
        translatorManager.close()
        super.onCleared()
    }
}
