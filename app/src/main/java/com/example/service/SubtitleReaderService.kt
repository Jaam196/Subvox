package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.capture.FrameProbe
import com.example.capture.RoiDetector
import com.example.capture.ScreenCaptureManager
import com.example.capture.TextBandAnalyzer
import com.example.data.preferences.SettingsRepository
import com.example.domain.model.AppSettings
import com.example.domain.model.CaptureWarning
import com.example.domain.model.NormalizedRect
import com.example.domain.model.RoiMode
import com.example.domain.model.ServiceStatus
import com.example.domain.pipeline.SubtitleDeduplicator
import com.example.filters.SongSkipDetector
import com.example.filters.SubtitleTextFilter
import com.example.ocr.OcrCorrection
import com.example.ocr.OcrCorrectionStore
import com.example.ocr.OcrCorrector
import com.example.history.SubtitleHistoryRecord
import com.example.history.SubtitleHistoryRepository
import com.example.ocr.ImagePreprocessor
import com.example.ocr.OcrResult
import com.example.ocr.SubtitleOcrEngine
import com.example.overlay.OverlayController
import com.example.profiles.AppProfile
import com.example.profiles.AppProfileRepository
import com.example.profiles.ForegroundAppDetector
import com.example.pronunciation.PronunciationProcessor
import com.example.pronunciation.PronunciationRepository
import com.example.pronunciation.PronunciationRule
import com.example.security.ApiKeyStore
import com.example.translate.SubtitleTranslatorManager
import com.example.tts.CharacterVoiceAnalyzer
import com.example.tts.LocalSpeechEngine
import com.example.tts.NaturalSpeechCache
import com.example.tts.NaturalSpeechEngine
import com.example.tts.SpeechEngine
import com.example.tts.SubtitleTtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SubtitleReaderService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var notificationManager: NotificationManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var pronunciationRepository: PronunciationRepository
    private lateinit var ocrCorrectionStore: OcrCorrectionStore
    private val songSkipDetector = SongSkipDetector()
    private lateinit var appProfileRepository: AppProfileRepository
    private lateinit var historyRepository: SubtitleHistoryRepository
    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var ocrEngine: SubtitleOcrEngine
    private lateinit var translatorManager: SubtitleTranslatorManager
    private lateinit var ttsManager: SubtitleTtsManager
    private lateinit var apiKeyStore: ApiKeyStore
    private lateinit var localSpeechEngine: LocalSpeechEngine
    private lateinit var offlineVoiceModelManager: com.example.tts.offline.OfflineVoiceModelManager
    private lateinit var offlineSpeechCache: com.example.tts.offline.OfflineSpeechCache
    private lateinit var offlineNeuralSpeechEngine: com.example.tts.offline.OfflineNeuralSpeechEngine
    private lateinit var naturalSpeechCache: NaturalSpeechCache
    private lateinit var naturalSpeechEngine: NaturalSpeechEngine
    private lateinit var threeTierSpeechEngine: com.example.tts.ThreeTierSpeechEngine
    private lateinit var speechEngine: SpeechEngine
    private lateinit var overlayController: OverlayController
    private val deduplicator = SubtitleDeduplicator()

    @Volatile
    private var baseSettings = AppSettings()

    @Volatile
    private var effectiveSettings = AppSettings()

    @Volatile
    private var activeOcrCorrections: List<OcrCorrection> = emptyList()

    @Volatile
    private var activePronunciationRules: List<PronunciationRule> =
        PronunciationProcessor.DEFAULT_ANIME_HONORIFIC_RULES

    @Volatile
    private var savedProfiles: List<AppProfile> = emptyList()

    @Volatile
    private var lastDetectedForegroundPkg: String = ""

    // --- Estado del escáner v2 (detección de texto barata + OCR solo cuando cambia el subtítulo) ---
    private var scanLastOcrSignature: IntArray? = null
    private var scanPrevSignature: IntArray? = null
    private var scanPendingSinceMs = 0L
    private var scanLastOcrAtMs = 0L
    private var scanEmptyOcrStreak = 0
    private var scanNoBandPolls = 0
    private var scanLastSafetyOcrAtMs = 0L
    private var scanForceRecheck = false
    private var scanLastFrameHash = 0L
    private var scanLastHashChangeAtMs = 0L
    private var scanBlackSinceMs = 0L
    private var captureLoopJob: Job? = null

    companion object {
        /** Zona de búsqueda en modo automático (pantalla horizontal): mitad inferior de la pantalla. */
        private val AUTO_SEARCH_ZONE = NormalizedRect(left = 0.03f, top = 0.50f, right = 0.97f, bottom = 0.98f)

        /** En vertical el vídeo suele ocupar la parte alta de la pantalla: se busca ahí. */
        private val AUTO_SEARCH_ZONE_PORTRAIT = NormalizedRect(left = 0.03f, top = 0.04f, right = 0.97f, bottom = 0.62f)

        private const val MIN_POLL_INTERVAL_MS = 80L
        private const val MIN_OCR_GAP_MS = 200L
        private const val SIGNATURE_CHANGE_THRESHOLD = 0.22f
        private const val SIGNATURE_STABLE_THRESHOLD = 0.12f
        private const val MAX_STABILITY_WAIT_MS = 450L
        private const val EMPTY_POLLS_BEFORE_CLOSE = 1
        private const val SAFETY_SCAN_IDLE_POLLS = 10
        private const val SAFETY_SCAN_INTERVAL_MS = 4_000L
        private const val BLACK_SCREEN_WARNING_MS = 4_000L
        private const val LOW_CONFIDENCE_LIMIT = 0.62f
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        settingsRepository = SettingsRepository(applicationContext)
        pronunciationRepository = PronunciationRepository(applicationContext)
        ocrCorrectionStore = OcrCorrectionStore(applicationContext)
        appProfileRepository = AppProfileRepository(applicationContext)
        historyRepository = SubtitleHistoryRepository(applicationContext)
        ocrEngine = SubtitleOcrEngine()
        translatorManager = SubtitleTranslatorManager()

        overlayController = OverlayController(
            context = applicationContext,
            onTogglePauseResume = {
                val cur = ReaderStateHolder.state.value.serviceStatus
                if (cur == ServiceStatus.PAUSED) {
                    ReaderStateHolder.updateServiceStatus(ServiceStatus.ACTIVE_SCANNING)
                    updateNotification(isPaused = false)
                } else if (cur == ServiceStatus.ACTIVE_SCANNING) {
                    ReaderStateHolder.updateServiceStatus(ServiceStatus.PAUSED)
                    speechEngine.stop()
                    deduplicator.clearActiveOnly()
                    updateNotification(isPaused = true)
                }
                refreshExternalQuickControls()
            },
            onSaveManualRoi = { newRoi ->
                serviceScope.launch {
                    settingsRepository.updateManualRoi(newRoi)
                    settingsRepository.updateRoiMode(RoiMode.MANUAL)
                    ReaderStateHolder.updateActiveRoi(newRoi, isAutoLocked = false)
                }
            },
            onStopServiceRequested = {
                stopReaderService(resetWarning = true)
            },
            onAdjustVolume = { increase ->
                serviceScope.launch {
                    settingsRepository.adjustVolumeAndBoostStep(increase = increase)
                }
            },
            onRepeatLastSubtitle = {
                repeatSubtitleFromBuffer(stepBack = false)
            },
            onRepeatPreviousSubtitle = {
                repeatSubtitleFromBuffer(stepBack = true)
            },
            onAdjustSyncOffset = { deltaMs ->
                serviceScope.launch {
                    settingsRepository.adjustSyncOffsetStep(deltaMs)
                }
            },
            onToggleMuteOpeningEnding = {
                serviceScope.launch {
                    val next = !baseSettings.muteOpeningEndingActive
                    settingsRepository.updateMuteOpeningEndingActive(next)
                    if (next) {
                        speechEngine.stop()
                    }
                }
            },
            onToggleExpressiveVoicePrimary = {
                serviceScope.launch {
                    val next = !baseSettings.naturalVoiceEnabled
                    settingsRepository.updateNaturalVoiceEnabled(next)
                    if (!next) {
                        ReaderStateHolder.updateNaturalVoiceFallbackNotice("")
                    }
                }
            }
        )

        screenCaptureManager = ScreenCaptureManager(
            context = applicationContext,
            onProjectionStoppedBySystem = {
                ReaderStateHolder.updateWarning(CaptureWarning.PROJECTION_REVOKED)
                stopReaderService(resetWarning = false)
            }
        )

        ttsManager = SubtitleTtsManager(
            context = applicationContext,
            onStateChanged = { ready, speaking, voiceDesc ->
                localSpeechEngine.updateObservedSpeakingState(speaking)
                if (!effectiveSettings.naturalVoiceEnabled || effectiveSettings.batterySaverMode || !apiKeyStore.hasKey() || speaking) {
                    ReaderStateHolder.updateTtsState(ready, speaking, voiceDesc)
                }
            },
            onSpanishVoiceMissing = {
                ReaderStateHolder.updateWarning(CaptureWarning.TTS_SPANISH_VOICE_MISSING)
            },
            onWordRangeStarted = { spokenText, startChar, endChar ->
                if ((!effectiveSettings.naturalVoiceEnabled || !apiKeyStore.hasKey()) && startChar >= 0 && endChar > startChar) {
                    ReaderStateHolder.updateKaraokeHighlightRange(spokenText, startChar, endChar)
                } else {
                    ReaderStateHolder.clearKaraokeHighlight()
                }
            }
        )

        apiKeyStore = ApiKeyStore(applicationContext)
        localSpeechEngine = LocalSpeechEngine(ttsManager)
        offlineVoiceModelManager = com.example.tts.offline.OfflineVoiceModelManager(applicationContext)
        offlineSpeechCache = com.example.tts.offline.OfflineSpeechCache(applicationContext)
        offlineNeuralSpeechEngine = com.example.tts.offline.OfflineNeuralSpeechEngine(
            context = applicationContext,
            modelManager = offlineVoiceModelManager,
            fallbackLocalEngine = localSpeechEngine,
            cache = offlineSpeechCache,
            onStateChanged = { ready, speaking, voiceDesc ->
                ReaderStateHolder.updateTtsState(ready, speaking, voiceDesc)
            },
            onFallbackNoticeChanged = { notice ->
                ReaderStateHolder.updateOfflineVoiceFallbackNotice(notice)
            },
            onCacheSizeUpdated = { bytes ->
                ReaderStateHolder.updateOfflineCacheUsedBytes(bytes)
            }
        )

        naturalSpeechCache = NaturalSpeechCache(applicationContext)
        naturalSpeechEngine = NaturalSpeechEngine(
            context = applicationContext,
            apiKeyStore = apiKeyStore,
            fallbackLocalEngine = offlineNeuralSpeechEngine,
            cache = naturalSpeechCache,
            onStateChanged = { ready, speaking, voiceDesc ->
                ReaderStateHolder.updateTtsState(ready, speaking, voiceDesc)
            },
            onFallbackNoticeChanged = { notice ->
                ReaderStateHolder.updateNaturalVoiceFallbackNotice(notice)
            },
            onCacheSizeUpdated = { bytes ->
                ReaderStateHolder.updateNaturalCacheUsedBytes(bytes)
            },
            onClearKaraokeHighlight = {
                ReaderStateHolder.clearKaraokeHighlight()
            }
        )

        threeTierSpeechEngine = com.example.tts.ThreeTierSpeechEngine(
            geminiEngine = naturalSpeechEngine,
            offlineEngine = offlineNeuralSpeechEngine,
            localEngine = localSpeechEngine,
            apiKeyStore = apiKeyStore,
            modelManager = offlineVoiceModelManager
        )
        speechEngine = threeTierSpeechEngine

        serviceScope.launch {
            combine(
                threeTierSpeechEngine.currentTierState,
                threeTierSpeechEngine.tierReasonState
            ) { tier, reason ->
                tier to reason
            }.collectLatest { (tier, reason) ->
                ReaderStateHolder.updateActiveSpeechTier(tier.displayName, reason)
            }
        }

        serviceScope.launch {
            offlineVoiceModelManager.statesFlow.collectLatest { _ ->
                val isReady = offlineVoiceModelManager.isModelInstalled(effectiveSettings.offlineVoiceSelectedId)
                ReaderStateHolder.updateOfflineModelDownloaded(isReady)
                threeTierSpeechEngine.recomputeActiveTier()
            }
        }

        serviceScope.launch {
            apiKeyStore.keyVersionFlow.collectLatest {
                naturalSpeechEngine.onApiKeyChangedOrCleared()
            }
        }

        serviceScope.launch {
            ocrCorrectionStore.rulesFlow.collectLatest { rules ->
                activeOcrCorrections = rules
            }
        }

        serviceScope.launch {
            pronunciationRepository.rulesFlow.collectLatest { rules ->
                activePronunciationRules = rules
            }
        }

        serviceScope.launch {
            combine(
                settingsRepository.settingsFlow,
                appProfileRepository.profilesFlow
            ) { settings, profiles ->
                settings to profiles
            }.collectLatest { (settings, profiles) ->
                baseSettings = settings
                savedProfiles = profiles
                recomputeAndApplyEffectiveSettings()
            }
        }

        serviceScope.launch {
            combine(ReaderStateHolder.state, settingsRepository.settingsFlow) { state, _ ->
                state to effectiveSettings
            }.collectLatest { (state, activeSettings) ->
                withContext(Dispatchers.Main) {
                    overlayController.syncWithState(state, activeSettings)
                }
            }
        }
    }

    private fun refreshExternalQuickControls() {
        SubtitleQuickTileService.requestListeningUpdate(applicationContext)
        SubtitleReaderWidgetProvider.notifyWidgetsChanged(applicationContext)
    }

    private fun recomputeAndApplyEffectiveSettings() {
        val raw = baseSettings
        var resolved = raw
        var activeProfileLabel = "Por defecto"

        if (raw.appProfilesEnabled) {
            val targetPkg = if (raw.autoDetectForegroundAppProfile && lastDetectedForegroundPkg.isNotBlank()) {
                lastDetectedForegroundPkg
            } else {
                raw.manualSelectedProfilePackage
            }

            if (targetPkg.isNotBlank()) {
                val matchedProfile = savedProfiles.firstOrNull {
                    it.enabled && it.packageName.equals(targetPkg, ignoreCase = true)
                }
                if (matchedProfile != null) {
                    resolved = matchedProfile.applyOverlayToSettings(raw)
                    activeProfileLabel = matchedProfile.appDisplayName
                } else {
                    activeProfileLabel = targetPkg.substringAfterLast('.')
                }
            }
        }

        effectiveSettings = resolved
        ReaderStateHolder.updateActiveProfileState(
            foregroundPkg = lastDetectedForegroundPkg,
            profileName = activeProfileLabel
        )

        val leadAdjustment = if (resolved.syncOffsetMs < 0) 1 else 0
        deduplicator.similarityThreshold = resolved.similarityThreshold
        deduplicator.minStableFrames = (resolved.minStableFrames - leadAdjustment).coerceAtLeast(1)
        deduplicator.historyTtlMs = resolved.historyTtlSeconds * 1000L
        speechEngine.applySettings(resolved)

        if (!resolved.autoPauseOnStaticPlayer && ReaderStateHolder.state.value.isAutoPausedByStaticVideo) {
            ReaderStateHolder.updateAutoPausedByStaticVideo(false)
        }

        if (resolved.roiMode == RoiMode.MANUAL) {
            ReaderStateHolder.updateActiveRoi(resolved.manualRoi, isAutoLocked = false)
        } else {
            if (!ReaderStateHolder.state.value.isAutoRoiLocked) {
                ReaderStateHolder.updateActiveRoi(NormalizedRect.DEFAULT_SUBTITLE_ROI, isAutoLocked = false)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ServiceContracts.ACTION_START_CAPTURE -> {
                val resultCode = intent.getIntExtra(
                    ServiceContracts.EXTRA_RESULT_CODE,
                    Activity.RESULT_CANCELED
                )
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(ServiceContracts.EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(ServiceContracts.EXTRA_RESULT_DATA)
                }

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    promoteToForeground(isPaused = false)
                    val started = screenCaptureManager.startProjection(
                        resultCode = resultCode,
                        resultData = resultData,
                        batterySaver = effectiveSettings.batterySaverMode
                    )
                    if (started) {
                        deduplicator.reset()
                        CharacterVoiceAnalyzer.resetAlternation()
                        resetScannerState()
                        ReaderStateHolder.setOverlayHidden(false)
                        ReaderStateHolder.setOverlayMinimized(false)
                        ReaderStateHolder.updateWarning(CaptureWarning.NONE)
                        ReaderStateHolder.updateServiceStatus(ServiceStatus.ACTIVE_SCANNING)
                        refreshExternalQuickControls()
                        startScanningLoop()
                    } else {
                        ReaderStateHolder.updateWarning(CaptureWarning.PROJECTION_REVOKED)
                        stopReaderService(resetWarning = false)
                    }
                } else {
                    stopReaderService(resetWarning = true)
                }
            }

            ServiceContracts.ACTION_PAUSE_CAPTURE -> {
                ReaderStateHolder.updateServiceStatus(ServiceStatus.PAUSED)
                speechEngine.stop()
                deduplicator.clearActiveOnly()
                updateNotification(isPaused = true)
                refreshExternalQuickControls()
            }

            ServiceContracts.ACTION_RESUME_CAPTURE -> {
                if (ReaderStateHolder.state.value.serviceStatus != ServiceStatus.INACTIVE) {
                    scanLastHashChangeAtMs = SystemClock.elapsedRealtime()
                    ReaderStateHolder.updateAutoPausedByStaticVideo(false)
                    ReaderStateHolder.updateServiceStatus(ServiceStatus.ACTIVE_SCANNING)
                    updateNotification(isPaused = false)
                    refreshExternalQuickControls()
                }
            }

            ServiceContracts.ACTION_RECALIBRATE_AUTO_ROI -> {
                resetScannerState()
                ReaderStateHolder.updateActiveRoi(NormalizedRect.DEFAULT_SUBTITLE_ROI, isAutoLocked = false)
            }

            ServiceContracts.ACTION_TOGGLE_ROI_EDITOR -> {
                val current = ReaderStateHolder.state.value.isEditingRoiOnOverlay
                ReaderStateHolder.setEditingRoiOnOverlay(!current)
            }

            ServiceContracts.ACTION_SHOW_OVERLAY -> {
                ReaderStateHolder.setOverlayHidden(false)
                ReaderStateHolder.setOverlayMinimized(false)
            }

            ServiceContracts.ACTION_HIDE_OVERLAY -> {
                ReaderStateHolder.setOverlayHidden(true)
            }

            ServiceContracts.ACTION_REPEAT_LAST_SUBTITLE -> {
                repeatSubtitleFromBuffer(stepBack = false)
            }

            ServiceContracts.ACTION_REPEAT_PREVIOUS_SUBTITLE -> {
                repeatSubtitleFromBuffer(stepBack = true)
            }

            ServiceContracts.ACTION_STOP_CAPTURE -> {
                stopReaderService(resetWarning = true)
            }
        }
        return START_NOT_STICKY
    }

    private fun repeatSubtitleFromBuffer(stepBack: Boolean) {
        val currentState = ReaderStateHolder.state.value
        val history = currentState.recentSubtitles
        if (history.isEmpty()) {
            val fallback = currentState.lastDetectedSubtitle
            if (fallback.isNotBlank()) {
                speechEngine.speak(fallback, forceSpeakEvenIfDisabled = true)
            }
            return
        }

        val maxIdx = (history.size - 1).coerceAtLeast(0)
        val targetIdx = if (stepBack) {
            (currentState.replayCursorIndex + 1).coerceAtMost(maxIdx)
        } else {
            0
        }
        ReaderStateHolder.updateReplayCursorIndex(targetIdx)
        val chosen = history.getOrNull(targetIdx) ?: history.first()
        speechEngine.speak(
            text = chosen.text,
            forceSpeakEvenIfDisabled = true,
            characterLabel = chosen.characterTag
        )
    }

    private fun resetScannerState() {
        scanLastOcrSignature = null
        scanPrevSignature = null
        scanPendingSinceMs = 0L
        scanLastOcrAtMs = 0L
        scanEmptyOcrStreak = 0
        scanNoBandPolls = 0
        scanLastSafetyOcrAtMs = 0L
        scanForceRecheck = false
        scanLastFrameHash = 0L
        scanLastHashChangeAtMs = SystemClock.elapsedRealtime()
        scanBlackSinceMs = 0L
    }

    /**
     * Bucle de escaneo v2.
     *
     * Antes: cada ~400 ms capturaba + preprocesaba + ejecutaba OCR SIEMPRE, y exigía 2 lecturas
     * iguales (≥ 1 s de retraso, mucha CPU y batería).
     *
     * Ahora: sondea la pantalla varias veces por segundo con un análisis barato de "píxeles tipo
     * texto" y solo lanza el OCR cuando el subtítulo aparece o cambia y ya se ha estabilizado.
     */
    private fun startScanningLoop() {
        captureLoopJob?.cancel()
        resetScannerState()
        captureLoopJob = serviceScope.launch {
            delay(250L)
            var pollCounter = 0

            while (isActive) {
                val readerState = ReaderStateHolder.state.value
                if (readerState.serviceStatus != ServiceStatus.ACTIVE_SCANNING || readerState.isEditingRoiOnOverlay) {
                    scanLastHashChangeAtMs = SystemClock.elapsedRealtime()
                    delay(250L)
                    continue
                }

                pollCounter++
                if (baseSettings.appProfilesEnabled && baseSettings.autoDetectForegroundAppProfile && pollCounter % 20 == 1) {
                    val detectedPkg = ForegroundAppDetector.detectForegroundPackage(
                        context = applicationContext,
                        previousKnownPackage = lastDetectedForegroundPkg
                    )
                    if (detectedPkg.isNotBlank() && detectedPkg != lastDetectedForegroundPkg) {
                        lastDetectedForegroundPkg = detectedPkg
                        recomputeAndApplyEffectiveSettings()
                    }
                }

                val settings = effectiveSettings
                val cycleStart = SystemClock.elapsedRealtime()
                val isPortrait = resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_PORTRAIT
                val zone = when {
                    settings.roiMode == RoiMode.MANUAL -> settings.manualRoi
                    isPortrait -> AUTO_SEARCH_ZONE_PORTRAIT
                    else -> AUTO_SEARCH_ZONE
                }

                val probe = screenCaptureManager.probe(zone, settings.batterySaverMode)
                if (probe != null) {
                    try {
                        scanStep(probe, settings, cycleStart)
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        // Un frame defectuoso nunca debe tumbar el servicio
                    }
                }

                val elapsed = SystemClock.elapsedRealtime() - cycleStart
                var interval = settings.ocrIntervalMs.coerceIn(MIN_POLL_INTERVAL_MS, 1000L)
                if (settings.batterySaverMode) {
                    interval = (interval * 2L).coerceAtMost(1000L)
                    if (scanNoBandPolls > 8) interval = (interval * 3L / 2L).coerceAtMost(1200L)
                }
                if (ReaderStateHolder.state.value.isAutoPausedByStaticVideo) {
                    interval = 800L
                }
                delay((interval - elapsed).coerceAtLeast(30L))
            }
        }
    }

    private suspend fun scanStep(probe: FrameProbe, settings: AppSettings, now: Long) {
        // 1. Pantalla negra sostenida (DRM / FLAG_SECURE)
        if (probe.isMostlyBlack) {
            if (scanBlackSinceMs == 0L) {
                scanBlackSinceMs = now
            } else if (now - scanBlackSinceMs >= BLACK_SCREEN_WARNING_MS) {
                ReaderStateHolder.updateWarning(CaptureWarning.BLACK_SCREEN_OR_DRM)
            }
        } else if (scanBlackSinceMs != 0L) {
            scanBlackSinceMs = 0L
            if (ReaderStateHolder.state.value.captureWarning == CaptureWarning.BLACK_SCREEN_OR_DRM) {
                ReaderStateHolder.updateWarning(CaptureWarning.NONE)
            }
        }

        // 2. Autopausa cuando el reproductor está detenido (pantalla congelada)
        if (probe.isNewFrame && probe.frameHash != scanLastFrameHash) {
            scanLastFrameHash = probe.frameHash
            scanLastHashChangeAtMs = now
            if (ReaderStateHolder.state.value.isAutoPausedByStaticVideo) {
                ReaderStateHolder.updateAutoPausedByStaticVideo(false)
            }
        }
        if (settings.autoPauseOnStaticPlayer) {
            val staticSeconds = (now - scanLastHashChangeAtMs) / 1000L
            if (staticSeconds >= settings.autoPauseStaticThresholdSeconds) {
                if (!ReaderStateHolder.state.value.isAutoPausedByStaticVideo) {
                    ReaderStateHolder.updateAutoPausedByStaticVideo(true)
                }
                return
            }
        } else if (ReaderStateHolder.state.value.isAutoPausedByStaticVideo) {
            ReaderStateHolder.updateAutoPausedByStaticVideo(false)
        }

        // 3. ¿Hay algo que parezca un subtítulo?
        val band = probe.band
        if (band == null) {
            scanNoBandPolls++
            scanPendingSinceMs = 0L
            scanLastOcrSignature = null
            scanPrevSignature = null
            scanForceRecheck = false

            if (scanNoBandPolls >= EMPTY_POLLS_BEFORE_CLOSE) {
                deduplicator.onFrameText("", System.currentTimeMillis())
            }

            // Red de seguridad: de vez en cuando se hace un OCR de toda la zona por si el subtítulo
            // tiene un estilo atípico (texto oscuro, sin contorno...) que el detector no reconoce.
            if (!settings.batterySaverMode &&
                scanNoBandPolls >= SAFETY_SCAN_IDLE_POLLS &&
                now - scanLastSafetyOcrAtMs >= SAFETY_SCAN_INTERVAL_MS
            ) {
                scanLastSafetyOcrAtMs = now
                val wholeZone = screenCaptureManager.cropBand(probe, useWholeZone = true)
                if (wholeZone != null) {
                    runOcrPipeline(wholeZone, settings)
                }
            }
            return
        }
        scanNoBandPolls = 0

        // 4. ¿Ha cambiado el subtítulo desde el último OCR?
        val signature = probe.signature
        val rowFrom = band.topRow - 1
        val rowTo = band.bottomRow + 1
        val changedSinceOcr = TextBandAnalyzer.difference(signature, scanLastOcrSignature, rowFrom, rowTo)
        val movedSincePrevPoll = TextBandAnalyzer.difference(signature, scanPrevSignature, rowFrom, rowTo)
        scanPrevSignature = signature

        val needsOcr = scanLastOcrSignature == null ||
            changedSinceOcr > SIGNATURE_CHANGE_THRESHOLD ||
            scanForceRecheck
        if (!needsOcr) {
            scanPendingSinceMs = 0L
            return
        }

        // 5. Esperar a que termine el fundido / animación de entrada (texto estable)
        if (scanPendingSinceMs == 0L) {
            scanPendingSinceMs = now
        }
        val isStable = movedSincePrevPoll < SIGNATURE_STABLE_THRESHOLD
        val waitedMs = now - scanPendingSinceMs
        if (!scanForceRecheck && !isStable && waitedMs < MAX_STABILITY_WAIT_MS) {
            return
        }

        // 6. Límite de frecuencia del OCR (más estricto si los últimos OCR salieron vacíos)
        var minGapMs = if (scanEmptyOcrStreak >= 2) 750L else MIN_OCR_GAP_MS
        if (settings.batterySaverMode) minGapMs = minGapMs * 8L / 5L
        if (now - scanLastOcrAtMs < minGapMs) {
            return
        }

        scanPendingSinceMs = 0L
        scanForceRecheck = false
        scanLastOcrSignature = signature
        scanLastOcrAtMs = now

        val detectedRect = probe.bandRect
        if (detectedRect != null && settings.roiMode == RoiMode.AUTO) {
            ReaderStateHolder.updateActiveRoi(detectedRect, isAutoLocked = true)
        }

        val bandBitmap = screenCaptureManager.cropBand(probe) ?: return
        runOcrPipeline(bandBitmap, settings)
    }

    /**
     * OCR + filtrado de UI + entrada al pipeline de lectura. Recicla [roiBitmap] al terminar.
     */
    private suspend fun runOcrPipeline(roiBitmap: Bitmap, settings: AppSettings) {
        ReaderStateHolder.updateOcrProcessing(true)
        val ocrStart = SystemClock.elapsedRealtime()
        val preprocessed = ImagePreprocessor.preprocessRoiBitmap(
            source = roiBitmap,
            highContrast = settings.highContrastPreprocessing,
            binarize = settings.binarizeOcrImage
        )

        try {
            when (val ocrResult = ocrEngine.recognizeBitmap(preprocessed, settings.ocrScriptMode)) {
                is OcrResult.Success -> {
                    if (ReaderStateHolder.state.value.captureWarning == CaptureWarning.OCR_ENGINE_DOWNLOADING) {
                        ReaderStateHolder.updateWarning(CaptureWarning.NONE)
                    }
                    val filteredText = RoiDetector.filterBlocksInsideRoi(
                        blocks = ocrResult.blocks,
                        roiBitmapWidth = preprocessed.width,
                        roiBitmapHeight = preprocessed.height,
                        ignoreUpperSigns = settings.textFilterEnabled && settings.ignoreUpperScreenSigns
                    )
                    if (filteredText.isBlank()) {
                        scanEmptyOcrStreak++
                    } else {
                        scanEmptyOcrStreak = 0
                    }
                    val lowConfidence = ocrResult.meanConfidence in 0.01f..LOW_CONFIDENCE_LIMIT
                    handleExtractedSubtitleText(
                        rawText = filteredText,
                        rawRoiBitmap = roiBitmap,
                        settings = settings,
                        lowConfidence = lowConfidence
                    )
                    // Si el deduplicador espera una segunda lectura, se fuerza otro OCR en el siguiente sondeo
                    scanForceRecheck = deduplicator.hasPendingCandidate
                }
                is OcrResult.ModelDownloading -> {
                    ReaderStateHolder.updateWarning(CaptureWarning.OCR_ENGINE_DOWNLOADING)
                }
                is OcrResult.Error -> Unit
            }
        } finally {
            if (preprocessed !== roiBitmap && !preprocessed.isRecycled) {
                preprocessed.recycle()
            }
            if (!roiBitmap.isRecycled) {
                roiBitmap.recycle()
            }
            ReaderStateHolder.updateOcrProcessing(
                false,
                latencyMs = SystemClock.elapsedRealtime() - ocrStart
            )
        }
    }

    /**
     * Pipeline completo Fase 1 + Fase 2 + Fase 3:
     * OCR -> Normalizar/Deduplicar -> Filtro de Texto -> Voces por Personaje ->
     * Traducción en vivo -> Diccionario de pronunciación -> Portapapeles opcional ->
     * Historial Room -> Sincronía -> TTS con Karaoke.
     */
    private suspend fun handleExtractedSubtitleText(
        rawText: String,
        rawRoiBitmap: Bitmap?,
        settings: AppSettings,
        lowConfidence: Boolean = false
    ) {
        ReaderStateHolder.updateRawOcrCandidate(rawText)
        val now = System.currentTimeMillis()
        val stableNeeded = if (lowConfidence) {
            maxOf(2, deduplicator.minStableFrames)
        } else {
            deduplicator.minStableFrames
        }

        when (val decision = deduplicator.onFrameText(rawText, now, stableNeeded)) {
            is SubtitleDeduplicator.Decision.SpeakNew -> {
                // Correcciones aprendidas del OCR (antes de filtrar, traducir y leer)
                val originalNormalized = OcrCorrector.apply(decision.speakableText, activeOcrCorrections)

                if (settings.textFilterEnabled && settings.skipSongLyrics) {
                    val songReason = songSkipDetector.check(originalNormalized, now)
                    if (songReason != null) {
                        ReaderStateHolder.onSubtitleFilteredOut(songReason)
                        return
                    }
                }

                when (val filterResult = SubtitleTextFilter.evaluate(originalNormalized, settings)) {
                    is SubtitleTextFilter.FilterDecision.Ignored -> {
                        ReaderStateHolder.onSubtitleFilteredOut(filterResult.reason)
                        return
                    }
                    SubtitleTextFilter.FilterDecision.Allowed -> Unit
                }

                val characterAssignment = CharacterVoiceAnalyzer.resolveForNewSubtitle(
                    roiBitmap = rawRoiBitmap,
                    settings = settings
                )

                val translationOutcome = if (settings.liveTranslationEnabled) {
                    translatorManager.processSubtitle(
                        originalText = originalNormalized,
                        sourceLanguageSetting = settings.translationSourceLanguageCode,
                        targetLanguageSetting = settings.translationTargetLanguageCode,
                        readMode = settings.translationReadMode,
                        allowAutoDownloadIfMissing = true,
                        requireWifiForDownload = settings.translationWifiOnlyDownload
                    )
                } else {
                    null
                }

                val textAfterTranslation = translationOutcome?.textToSpeak ?: originalNormalized

                val finalSpeakableText = if (settings.pronunciationDictionaryEnabled) {
                    PronunciationProcessor.applyRules(textAfterTranslation, activePronunciationRules)
                } else {
                    textAfterTranslation
                }

                ReaderStateHolder.onSubtitleSpoken(
                    text = finalSpeakableText,
                    normalizedKey = decision.comparisonKey,
                    timestampMs = now,
                    originalText = originalNormalized,
                    translatedText = translationOutcome?.translatedText.orEmpty(),
                    detectedSourceLang = translationOutcome?.detectedSourceLanguage.orEmpty(),
                    sourceAppPackage = lastDetectedForegroundPkg,
                    characterTag = characterAssignment.characterLabel,
                    appliedPitch = characterAssignment.targetPitch,
                    maxHistorySize = settings.replayBufferSize
                )

                // Función 14 (Fase 3): Copia automática al portapapeles de cada subtítulo leído
                if (settings.autoCopyToClipboard) {
                    withContext(Dispatchers.Main) {
                        runCatching {
                            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cb?.setPrimaryClip(ClipData.newPlainText("AnimeSub Voz", finalSpeakableText))
                        }
                    }
                }

                if (settings.saveHistoryToDatabase) {
                    serviceScope.launch(Dispatchers.IO) {
                        historyRepository.insert(
                            SubtitleHistoryRecord(
                                spokenText = finalSpeakableText,
                                originalText = originalNormalized,
                                translatedText = translationOutcome?.translatedText.orEmpty(),
                                sourceAppPackage = lastDetectedForegroundPkg,
                                characterLabel = characterAssignment.characterLabel,
                                timestampMs = now
                            )
                        )
                    }
                }

                val positiveDelayMs = settings.syncOffsetMs.coerceIn(0, 2000).toLong()
                if (positiveDelayMs > 0L) {
                    serviceScope.launch {
                        delay(positiveDelayMs)
                        if (ReaderStateHolder.state.value.serviceStatus == ServiceStatus.ACTIVE_SCANNING) {
                            speechEngine.speak(
                                text = finalSpeakableText,
                                pitchOverride = characterAssignment.targetPitch,
                                characterLabel = characterAssignment.characterLabel
                            )
                        }
                    }
                } else {
                    speechEngine.speak(
                        text = finalSpeakableText,
                        pitchOverride = characterAssignment.targetPitch,
                        characterLabel = characterAssignment.characterLabel
                    )
                }

                updateNotification(isPaused = false)
                refreshExternalQuickControls()
            }
            SubtitleDeduplicator.Decision.SubtitleClosed -> Unit
            SubtitleDeduplicator.Decision.IgnoreDuplicateOrUnstable -> Unit
        }
    }

    private fun promoteToForeground(isPaused: Boolean) {
        val notification = buildNotification(isPaused)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                ServiceContracts.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(ServiceContracts.NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(isPaused: Boolean) {
        notificationManager.notify(ServiceContracts.NOTIFICATION_ID, buildNotification(isPaused))
    }

    private fun buildNotification(isPaused: Boolean): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleActionIntent = ServiceContracts.commandIntent(
            this,
            if (isPaused) ServiceContracts.ACTION_RESUME_CAPTURE else ServiceContracts.ACTION_PAUSE_CAPTURE
        )
        val togglePendingIntent = PendingIntent.getService(
            this,
            101,
            toggleActionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val repeatActionIntent = ServiceContracts.commandIntent(
            this,
            ServiceContracts.ACTION_REPEAT_LAST_SUBTITLE
        )
        val repeatPendingIntent = PendingIntent.getService(
            this,
            104,
            repeatActionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopActionIntent = ServiceContracts.commandIntent(
            this,
            ServiceContracts.ACTION_STOP_CAPTURE
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            102,
            stopActionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val lastSub = ReaderStateHolder.state.value.lastDetectedSubtitle
        val contentText = when {
            isPaused -> "Detección de subtítulos en pausa"
            lastSub.isNotBlank() -> "Último: $lastSub"
            else -> "Escaneando zona de subtítulos..."
        }

        return NotificationCompat.Builder(this, ServiceContracts.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_subtitles)
            .setContentTitle(
                getString(if (isPaused) R.string.notif_title_paused else R.string.notif_title_active)
            )
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentPendingIntent)
            .addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                getString(if (isPaused) R.string.notif_action_resume else R.string.notif_action_pause),
                togglePendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_revert,
                "Repetir",
                repeatPendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notif_action_stop),
                stopPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ServiceContracts.NOTIFICATION_CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun stopReaderService(resetWarning: Boolean) {
        captureLoopJob?.cancel()
        captureLoopJob = null
        speechEngine.stop()
        screenCaptureManager.stopProjection()
        overlayController.removeAllOverlays()
        deduplicator.reset()
        if (resetWarning) {
            ReaderStateHolder.updateWarning(CaptureWarning.NONE)
        }
        ReaderStateHolder.resetSessionStats()
        refreshExternalQuickControls()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        captureLoopJob?.cancel()
        overlayController.removeAllOverlays()
        screenCaptureManager.stopProjection()
        speechEngine.shutdown()
        translatorManager.close()
        ocrEngine.close()
        ReaderStateHolder.resetSessionStats()
        refreshExternalQuickControls()
        serviceScope.cancel()
        super.onDestroy()
    }
}
