package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.AppSettings
import com.example.domain.model.CharacterVoiceMode
import com.example.domain.model.DesignedVoice
import com.example.domain.model.NewSubtitleBehavior
import com.example.domain.model.NormalizedRect
import com.example.domain.model.OcrScriptMode
import com.example.domain.model.RoiMode
import com.example.domain.model.TranslationReadMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "animesub_voz_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val PREFERRED_LANGUAGE_TAG = stringPreferencesKey("preferred_language_tag")
        val SELECTED_VOICE_NAME = stringPreferencesKey("selected_voice_name")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val PITCH = floatPreferencesKey("pitch")
        val VOLUME = floatPreferencesKey("volume")
        val VOLUME_BOOST_DB = intPreferencesKey("volume_boost_db")
        val DUCK_OTHER_AUDIO = booleanPreferencesKey("duck_other_audio")
        val ALLOW_NETWORK_VOICES = booleanPreferencesKey("allow_network_voices")
        val TTS_ENGINE_PACKAGE = stringPreferencesKey("tts_engine_package")
        val ROI_MODE = stringPreferencesKey("roi_mode")
        val ROI_LEFT = floatPreferencesKey("roi_left")
        val ROI_TOP = floatPreferencesKey("roi_top")
        val ROI_RIGHT = floatPreferencesKey("roi_right")
        val ROI_BOTTOM = floatPreferencesKey("roi_bottom")
        val OCR_INTERVAL_MS = longPreferencesKey("ocr_poll_interval_ms_v2")
        val SIMILARITY_THRESHOLD = floatPreferencesKey("similarity_threshold")
        val MIN_STABLE_FRAMES = intPreferencesKey("min_stable_frames_v2")
        val HISTORY_TTL_SECONDS = intPreferencesKey("history_ttl_seconds")
        val NEW_SUBTITLE_BEHAVIOR = stringPreferencesKey("new_subtitle_behavior")
        val HIGH_CONTRAST_PREPROCESSING = booleanPreferencesKey("high_contrast_preprocessing")
        val BINARIZE_OCR_IMAGE = booleanPreferencesKey("binarize_ocr_image")
        val BATTERY_SAVER_MODE = booleanPreferencesKey("battery_saver_mode")
        val SHOW_OVERLAY_CONTROLS = booleanPreferencesKey("show_overlay_controls")
        val SHOW_ROI_BOX_ON_OVERLAY = booleanPreferencesKey("show_roi_box_on_overlay")

        // Claves Fase 1
        val OCR_SCRIPT_MODE = stringPreferencesKey("ocr_script_mode")
        val LIVE_TRANSLATION_ENABLED = booleanPreferencesKey("live_translation_enabled")
        val TRANSLATION_SOURCE_LANG = stringPreferencesKey("translation_source_lang")
        val TRANSLATION_TARGET_LANG = stringPreferencesKey("translation_target_lang")
        val TRANSLATION_WIFI_ONLY = booleanPreferencesKey("translation_wifi_only")
        val TRANSLATION_READ_MODE = stringPreferencesKey("translation_read_mode")
        val PRONUNCIATION_DICT_ENABLED = booleanPreferencesKey("pronunciation_dict_enabled")
        val APP_PROFILES_ENABLED = booleanPreferencesKey("app_profiles_enabled")
        val AUTO_DETECT_FOREGROUND_APP = booleanPreferencesKey("auto_detect_foreground_app")
        val MANUAL_SELECTED_PROFILE_PKG = stringPreferencesKey("manual_selected_profile_pkg")
        val REPLAY_BUFFER_SIZE = intPreferencesKey("replay_buffer_size")

        // Claves Fase 2
        val CHARACTER_VOICES_MODE = stringPreferencesKey("character_voices_mode")
        val CHARACTER_PITCH_A = floatPreferencesKey("character_pitch_a")
        val CHARACTER_PITCH_B = floatPreferencesKey("character_pitch_b")
        val CHARACTER_PITCH_C = floatPreferencesKey("character_pitch_c")
        val CHARACTER_VOICE_A_ID = stringPreferencesKey("character_voice_a_id")
        val CHARACTER_VOICE_B_ID = stringPreferencesKey("character_voice_b_id")
        val CHARACTER_VOICE_C_ID = stringPreferencesKey("character_voice_c_id")
        val TEXT_FILTER_ENABLED = booleanPreferencesKey("text_filter_enabled")
        val MUTE_OPENING_ENDING_ACTIVE = booleanPreferencesKey("mute_opening_ending_active")
        val IGNORE_UPPER_SCREEN_SIGNS = booleanPreferencesKey("ignore_upper_screen_signs")
        val IGNORE_CREDITS_AND_STAFF = booleanPreferencesKey("ignore_credits_and_staff")
        val SKIP_SONG_LYRICS = booleanPreferencesKey("skip_song_lyrics")
        val MIN_SUBTITLE_CHARS_FILTER = intPreferencesKey("min_subtitle_chars_filter")
        val CUSTOM_FILTER_PATTERNS_RAW = stringPreferencesKey("custom_filter_patterns_raw")
        val SAVE_HISTORY_TO_DATABASE = booleanPreferencesKey("save_history_to_database")
        val SYNC_OFFSET_MS = intPreferencesKey("sync_offset_ms")
        val VOLUME_BUTTONS_SHORTCUT = booleanPreferencesKey("volume_buttons_shortcut")

        // Claves Fase 3
        val STUDY_MODE_ENABLED = booleanPreferencesKey("study_mode_enabled")
        val KARAOKE_HIGHLIGHT_ENABLED = booleanPreferencesKey("karaoke_highlight_enabled")
        val AUTO_COPY_TO_CLIPBOARD = booleanPreferencesKey("auto_copy_to_clipboard")
        val AUTO_PAUSE_ON_STATIC_PLAYER = booleanPreferencesKey("auto_pause_on_static_player")
        val AUTO_PAUSE_STATIC_THRESHOLD_SEC = intPreferencesKey("auto_pause_static_threshold_sec")

        // Claves Voz Natural (IA - Gemini TTS)
        val NATURAL_VOICE_ENABLED = booleanPreferencesKey("natural_voice_enabled")
        val NATURAL_VOICE_MODEL_NAME = stringPreferencesKey("natural_voice_model_name")
        val NATURAL_VOICE_INLINE_TAGS_ENABLED = booleanPreferencesKey("natural_voice_inline_tags_enabled")
        val NATURAL_VOICE_NAME = stringPreferencesKey("natural_voice_name")
        val DESIGNED_VOICES_JSON = stringPreferencesKey("designed_voices_json")
        val NATURAL_VOICE_STYLE = stringPreferencesKey("natural_voice_style")
        val NATURAL_VOICE_SPEED = floatPreferencesKey("natural_voice_speed")
        val NATURAL_CACHE_MAX_MB = intPreferencesKey("natural_cache_max_mb")
        val NATURAL_FALLBACK_TIMEOUT_MS = longPreferencesKey("natural_fallback_timeout_ms")
        val NATURAL_ONLY_ON_WIFI = booleanPreferencesKey("natural_only_on_wifi")
        // Claves Voz Neuronal Offline (Sherpa-ONNX)
        val OFFLINE_VOICE_ENABLED = booleanPreferencesKey("offline_voice_enabled_v2")
        val OFFLINE_VOICE_SELECTED_ID = stringPreferencesKey("offline_voice_selected_id_v2")
        val SELECTED_THEME_ID = stringPreferencesKey("selected_theme_id_v3")
        val THEME_ANIMATIONS_ENABLED = booleanPreferencesKey("theme_animations_enabled")
        val OFFLINE_VOICE_SPEED = floatPreferencesKey("offline_voice_speed")
        val OFFLINE_VOICE_THREADS = intPreferencesKey("offline_voice_threads")
        val OFFLINE_ONLY_ON_WIFI = booleanPreferencesKey("offline_only_on_wifi")
        val OFFLINE_CACHE_MAX_MB = intPreferencesKey("offline_cache_max_mb")
        val OFFLINE_PROMPT_DISMISSED = booleanPreferencesKey("offline_prompt_dismissed")
        val CATCH_UP_SPEED_ENABLED = booleanPreferencesKey("catch_up_speed_enabled")
        val SIMPLE_SETTINGS_MODE = booleanPreferencesKey("simple_settings_mode")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            val defaultRoi = NormalizedRect.DEFAULT_SUBTITLE_ROI
            val roiModeStr = prefs[Keys.ROI_MODE] ?: RoiMode.AUTO.name
            val behaviorStr = prefs[Keys.NEW_SUBTITLE_BEHAVIOR] ?: NewSubtitleBehavior.INTERRUPT_AND_READ_NEW.name

            AppSettings(
                ttsEnabled = prefs[Keys.TTS_ENABLED] ?: true,
                preferredLanguageTag = prefs[Keys.PREFERRED_LANGUAGE_TAG] ?: "es-ES",
                selectedVoiceName = prefs[Keys.SELECTED_VOICE_NAME] ?: "",
                speechRate = (prefs[Keys.SPEECH_RATE] ?: 1.08f).coerceIn(0.5f, 2.2f),
                pitch = (prefs[Keys.PITCH] ?: 1.0f).coerceIn(0.5f, 1.8f),
                volume = (prefs[Keys.VOLUME] ?: 1.0f).coerceIn(0.0f, 1.0f),
                volumeBoostDb = (prefs[Keys.VOLUME_BOOST_DB] ?: 0).coerceIn(0, 15),
                duckOtherAudio = prefs[Keys.DUCK_OTHER_AUDIO] ?: true,
                allowNetworkVoices = prefs[Keys.ALLOW_NETWORK_VOICES] ?: false,
                ttsEnginePackage = prefs[Keys.TTS_ENGINE_PACKAGE] ?: "",
                roiMode = runCatching { RoiMode.valueOf(roiModeStr) }.getOrDefault(RoiMode.AUTO),
                manualRoi = NormalizedRect(
                    left = prefs[Keys.ROI_LEFT] ?: defaultRoi.left,
                    top = prefs[Keys.ROI_TOP] ?: defaultRoi.top,
                    right = prefs[Keys.ROI_RIGHT] ?: defaultRoi.right,
                    bottom = prefs[Keys.ROI_BOTTOM] ?: defaultRoi.bottom
                ).clamp(),
                ocrIntervalMs = (prefs[Keys.OCR_INTERVAL_MS] ?: 140L).coerceIn(80L, 1000L),
                similarityThreshold = (prefs[Keys.SIMILARITY_THRESHOLD] ?: 0.82f).coerceIn(0.60f, 0.96f),
                minStableFrames = (prefs[Keys.MIN_STABLE_FRAMES] ?: 1).coerceIn(1, 5),
                historyTtlSeconds = (prefs[Keys.HISTORY_TTL_SECONDS] ?: 90).coerceIn(10, 600),
                newSubtitleBehavior = runCatching { NewSubtitleBehavior.valueOf(behaviorStr) }
                    .getOrDefault(NewSubtitleBehavior.INTERRUPT_AND_READ_NEW),
                highContrastPreprocessing = prefs[Keys.HIGH_CONTRAST_PREPROCESSING] ?: true,
                binarizeOcrImage = prefs[Keys.BINARIZE_OCR_IMAGE] ?: false,
                batterySaverMode = prefs[Keys.BATTERY_SAVER_MODE] ?: false,
                showOverlayControls = prefs[Keys.SHOW_OVERLAY_CONTROLS] ?: true,
                showRoiBoxOnOverlay = prefs[Keys.SHOW_ROI_BOX_ON_OVERLAY] ?: false,
                ocrScriptMode = OcrScriptMode.fromNameSafe(prefs[Keys.OCR_SCRIPT_MODE]),
                liveTranslationEnabled = prefs[Keys.LIVE_TRANSLATION_ENABLED] ?: false,
                translationSourceLanguageCode = prefs[Keys.TRANSLATION_SOURCE_LANG] ?: "auto",
                translationTargetLanguageCode = prefs[Keys.TRANSLATION_TARGET_LANG] ?: "es",
                translationWifiOnlyDownload = prefs[Keys.TRANSLATION_WIFI_ONLY] ?: true,
                translationReadMode = TranslationReadMode.fromNameSafe(prefs[Keys.TRANSLATION_READ_MODE]),
                pronunciationDictionaryEnabled = prefs[Keys.PRONUNCIATION_DICT_ENABLED] ?: true,
                appProfilesEnabled = prefs[Keys.APP_PROFILES_ENABLED] ?: false,
                autoDetectForegroundAppProfile = prefs[Keys.AUTO_DETECT_FOREGROUND_APP] ?: true,
                manualSelectedProfilePackage = prefs[Keys.MANUAL_SELECTED_PROFILE_PKG] ?: "",
                replayBufferSize = (prefs[Keys.REPLAY_BUFFER_SIZE] ?: 10).coerceIn(3, 30),
                characterVoicesMode = CharacterVoiceMode.fromNameSafe(prefs[Keys.CHARACTER_VOICES_MODE]),
                characterVoicePitchA = (prefs[Keys.CHARACTER_PITCH_A] ?: 1.0f).coerceIn(0.60f, 1.60f),
                characterVoicePitchB = (prefs[Keys.CHARACTER_PITCH_B] ?: 1.20f).coerceIn(0.60f, 1.60f),
                characterVoicePitchC = (prefs[Keys.CHARACTER_PITCH_C] ?: 0.84f).coerceIn(0.60f, 1.60f),
                characterVoiceAId = prefs[Keys.CHARACTER_VOICE_A_ID] ?: "",
                characterVoiceBId = prefs[Keys.CHARACTER_VOICE_B_ID] ?: "",
                characterVoiceCId = prefs[Keys.CHARACTER_VOICE_C_ID] ?: "",
                textFilterEnabled = prefs[Keys.TEXT_FILTER_ENABLED] ?: true,
                muteOpeningEndingActive = prefs[Keys.MUTE_OPENING_ENDING_ACTIVE] ?: false,
                ignoreUpperScreenSigns = prefs[Keys.IGNORE_UPPER_SCREEN_SIGNS] ?: true,
                ignoreCreditsAndStaff = prefs[Keys.IGNORE_CREDITS_AND_STAFF] ?: true,
                skipSongLyrics = prefs[Keys.SKIP_SONG_LYRICS] ?: true,
                minSubtitleCharsFilter = (prefs[Keys.MIN_SUBTITLE_CHARS_FILTER] ?: 2).coerceIn(1, 12),
                customFilterPatternsRaw = prefs[Keys.CUSTOM_FILTER_PATTERNS_RAW] ?: "",
                saveHistoryToDatabase = prefs[Keys.SAVE_HISTORY_TO_DATABASE] ?: true,
                syncOffsetMs = (prefs[Keys.SYNC_OFFSET_MS] ?: 0).coerceIn(-2000, 2000),
                volumeButtonsShortcutEnabled = prefs[Keys.VOLUME_BUTTONS_SHORTCUT] ?: false,
                studyModeEnabled = prefs[Keys.STUDY_MODE_ENABLED] ?: true,
                karaokeHighlightEnabled = prefs[Keys.KARAOKE_HIGHLIGHT_ENABLED] ?: true,
                autoCopyToClipboard = prefs[Keys.AUTO_COPY_TO_CLIPBOARD] ?: false,
                autoPauseOnStaticPlayer = prefs[Keys.AUTO_PAUSE_ON_STATIC_PLAYER] ?: false,
                autoPauseStaticThresholdSeconds = (prefs[Keys.AUTO_PAUSE_STATIC_THRESHOLD_SEC] ?: 12).coerceIn(5, 60),
                naturalVoiceEnabled = prefs[Keys.NATURAL_VOICE_ENABLED] ?: false,
                naturalVoiceModelName = run {
                    val raw = prefs[Keys.NATURAL_VOICE_MODEL_NAME]
                    when {
                        raw.isNullOrBlank() -> "gemini-3.8-flash-lite-tts"
                        raw == "gemini-2.5-flash-preview-tts" || raw.contains("2.5") -> "gemini-3.8-flash-lite-tts"
                        else -> raw.trim()
                    }
                },
                naturalVoiceInlineTagsEnabled = prefs[Keys.NATURAL_VOICE_INLINE_TAGS_ENABLED] ?: false,
                naturalVoiceName = (prefs[Keys.NATURAL_VOICE_NAME] ?: "Kore").ifBlank { "Kore" },
                designedVoices = DesignedVoice.listFromJsonString(prefs[Keys.DESIGNED_VOICES_JSON]),
                naturalVoiceStyle = prefs[Keys.NATURAL_VOICE_STYLE]
                    ?: "habla de forma natural, expresiva y cercana, como un doblaje de anime en español",
                naturalVoiceSpeed = (prefs[Keys.NATURAL_VOICE_SPEED] ?: 1.0f).coerceIn(0.65f, 1.50f),
                naturalCacheMaxMb = (prefs[Keys.NATURAL_CACHE_MAX_MB] ?: 60).coerceIn(10, 300),
                naturalFallbackTimeoutMs = (prefs[Keys.NATURAL_FALLBACK_TIMEOUT_MS] ?: 3500L).coerceIn(1200L, 9000L),
                naturalOnlyOnWifi = prefs[Keys.NATURAL_ONLY_ON_WIFI] ?: false,
                offlineVoiceEnabled = prefs[Keys.OFFLINE_VOICE_ENABLED] ?: true,
                offlineVoiceSelectedId = prefs[Keys.OFFLINE_VOICE_SELECTED_ID] ?: "vits-piper-es_MX-claude-high",
                selectedThemeId = prefs[Keys.SELECTED_THEME_ID] ?: "aurora",
                themeAnimationsEnabled = prefs[Keys.THEME_ANIMATIONS_ENABLED] ?: true,
                offlineVoiceSpeed = (prefs[Keys.OFFLINE_VOICE_SPEED] ?: 1.0f).coerceIn(0.65f, 1.50f),
                offlineVoiceThreads = (prefs[Keys.OFFLINE_VOICE_THREADS] ?: 2).coerceIn(2, 4),
                offlineOnlyOnWifi = prefs[Keys.OFFLINE_ONLY_ON_WIFI] ?: true,
                offlineCacheMaxMb = (prefs[Keys.OFFLINE_CACHE_MAX_MB] ?: 80).coerceIn(10, 500),
                offlinePromptDismissed = prefs[Keys.OFFLINE_PROMPT_DISMISSED] ?: false,
                catchUpSpeedEnabled = prefs[Keys.CATCH_UP_SPEED_ENABLED] ?: true,
                simpleSettingsMode = prefs[Keys.SIMPLE_SETTINGS_MODE] ?: true
            )
        }

    suspend fun updateTtsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.TTS_ENABLED] = enabled }
    }

    suspend fun updateLanguageTag(tag: String) {
        context.dataStore.edit { it[Keys.PREFERRED_LANGUAGE_TAG] = tag }
    }

    suspend fun updateVoiceName(voiceName: String) {
        context.dataStore.edit { it[Keys.SELECTED_VOICE_NAME] = voiceName }
    }

    suspend fun updateSpeechRate(rate: Float) {
        context.dataStore.edit { it[Keys.SPEECH_RATE] = rate.coerceIn(0.5f, 2.2f) }
    }

    suspend fun updatePitch(pitch: Float) {
        context.dataStore.edit { it[Keys.PITCH] = pitch.coerceIn(0.5f, 1.8f) }
    }

    suspend fun updateVolume(volume: Float) {
        context.dataStore.edit { it[Keys.VOLUME] = volume.coerceIn(0.0f, 1.0f) }
    }

    suspend fun updateVolumeBoostDb(boostDb: Int) {
        context.dataStore.edit { it[Keys.VOLUME_BOOST_DB] = boostDb.coerceIn(0, 15) }
    }

    suspend fun updateDuckOtherAudio(duck: Boolean) {
        context.dataStore.edit { it[Keys.DUCK_OTHER_AUDIO] = duck }
    }

    suspend fun updateAllowNetworkVoices(allow: Boolean) {
        context.dataStore.edit { it[Keys.ALLOW_NETWORK_VOICES] = allow }
    }

    suspend fun updateTtsEnginePackage(packageName: String) {
        context.dataStore.edit {
            it[Keys.TTS_ENGINE_PACKAGE] = packageName
            it[Keys.SELECTED_VOICE_NAME] = ""
        }
    }

    suspend fun adjustVolumeAndBoostStep(increase: Boolean) {
        context.dataStore.edit { prefs ->
            val currentVol = (prefs[Keys.VOLUME] ?: 1.0f).coerceIn(0.0f, 1.0f)
            val currentBoost = (prefs[Keys.VOLUME_BOOST_DB] ?: 0).coerceIn(0, 15)

            if (increase) {
                if (currentVol < 0.99f) {
                    prefs[Keys.VOLUME] = (currentVol + 0.10f).coerceAtMost(1.0f)
                } else {
                    prefs[Keys.VOLUME] = 1.0f
                    prefs[Keys.VOLUME_BOOST_DB] = (currentBoost + 2).coerceAtMost(15)
                }
            } else {
                if (currentBoost > 0) {
                    prefs[Keys.VOLUME_BOOST_DB] = (currentBoost - 2).coerceAtLeast(0)
                } else {
                    prefs[Keys.VOLUME] = (currentVol - 0.10f).coerceAtLeast(0.10f)
                }
            }
        }
    }

    suspend fun updateRoiMode(mode: RoiMode) {
        context.dataStore.edit { it[Keys.ROI_MODE] = mode.name }
    }

    suspend fun updateManualRoi(rect: NormalizedRect) {
        val clamped = rect.clamp()
        context.dataStore.edit {
            it[Keys.ROI_LEFT] = clamped.left
            it[Keys.ROI_TOP] = clamped.top
            it[Keys.ROI_RIGHT] = clamped.right
            it[Keys.ROI_BOTTOM] = clamped.bottom
        }
    }

    suspend fun updateOcrIntervalMs(intervalMs: Long) {
        context.dataStore.edit { it[Keys.OCR_INTERVAL_MS] = intervalMs.coerceIn(80L, 1000L) }
    }

    suspend fun updateSimilarityThreshold(threshold: Float) {
        context.dataStore.edit { it[Keys.SIMILARITY_THRESHOLD] = threshold.coerceIn(0.60f, 0.96f) }
    }

    suspend fun updateMinStableFrames(frames: Int) {
        context.dataStore.edit { it[Keys.MIN_STABLE_FRAMES] = frames.coerceIn(1, 5) }
    }

    suspend fun updateHistoryTtlSeconds(seconds: Int) {
        context.dataStore.edit { it[Keys.HISTORY_TTL_SECONDS] = seconds.coerceIn(10, 600) }
    }

    suspend fun updateNewSubtitleBehavior(behavior: NewSubtitleBehavior) {
        context.dataStore.edit { it[Keys.NEW_SUBTITLE_BEHAVIOR] = behavior.name }
    }

    suspend fun updateHighContrastPreprocessing(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HIGH_CONTRAST_PREPROCESSING] = enabled }
    }

    suspend fun updateBinarizeOcrImage(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BINARIZE_OCR_IMAGE] = enabled }
    }

    suspend fun updateBatterySaverMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BATTERY_SAVER_MODE] = enabled }
    }

    suspend fun updateShowOverlayControls(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_OVERLAY_CONTROLS] = show }
    }

    suspend fun updateShowRoiBoxOnOverlay(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_ROI_BOX_ON_OVERLAY] = show }
    }

    // --- Métodos Fase 1 ---

    suspend fun updateOcrScriptMode(mode: OcrScriptMode) {
        context.dataStore.edit { it[Keys.OCR_SCRIPT_MODE] = mode.name }
    }

    suspend fun updateLiveTranslationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LIVE_TRANSLATION_ENABLED] = enabled }
    }

    suspend fun updateTranslationSourceLanguage(langCode: String) {
        context.dataStore.edit { it[Keys.TRANSLATION_SOURCE_LANG] = langCode }
    }

    suspend fun updateTranslationTargetLanguage(langCode: String) {
        context.dataStore.edit { it[Keys.TRANSLATION_TARGET_LANG] = langCode }
    }

    suspend fun updateTranslationWifiOnlyDownload(wifiOnly: Boolean) {
        context.dataStore.edit { it[Keys.TRANSLATION_WIFI_ONLY] = wifiOnly }
    }

    suspend fun updateTranslationReadMode(mode: TranslationReadMode) {
        context.dataStore.edit { it[Keys.TRANSLATION_READ_MODE] = mode.name }
    }

    suspend fun updatePronunciationDictionaryEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.PRONUNCIATION_DICT_ENABLED] = enabled }
    }

    suspend fun updateAppProfilesEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.APP_PROFILES_ENABLED] = enabled }
    }

    suspend fun updateAutoDetectForegroundAppProfile(autoDetect: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_DETECT_FOREGROUND_APP] = autoDetect }
    }

    suspend fun updateManualSelectedProfilePackage(packageName: String) {
        context.dataStore.edit { it[Keys.MANUAL_SELECTED_PROFILE_PKG] = packageName }
    }

    suspend fun updateReplayBufferSize(size: Int) {
        context.dataStore.edit { it[Keys.REPLAY_BUFFER_SIZE] = size.coerceIn(3, 30) }
    }

    // --- Métodos Fase 2 ---

    suspend fun updateCharacterVoicesMode(mode: CharacterVoiceMode) {
        context.dataStore.edit { it[Keys.CHARACTER_VOICES_MODE] = mode.name }
    }

    suspend fun updateCharacterVoicePitches(pitchA: Float, pitchB: Float, pitchC: Float) {
        context.dataStore.edit {
            it[Keys.CHARACTER_PITCH_A] = pitchA.coerceIn(0.60f, 1.60f)
            it[Keys.CHARACTER_PITCH_B] = pitchB.coerceIn(0.60f, 1.60f)
            it[Keys.CHARACTER_PITCH_C] = pitchC.coerceIn(0.60f, 1.60f)
        }
    }

    suspend fun updateCharacterVoiceAId(voiceId: String) {
        context.dataStore.edit { it[Keys.CHARACTER_VOICE_A_ID] = voiceId.trim() }
    }

    suspend fun updateCharacterVoiceBId(voiceId: String) {
        context.dataStore.edit { it[Keys.CHARACTER_VOICE_B_ID] = voiceId.trim() }
    }

    suspend fun updateCharacterVoiceCId(voiceId: String) {
        context.dataStore.edit { it[Keys.CHARACTER_VOICE_C_ID] = voiceId.trim() }
    }

    suspend fun updateTextFilterEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.TEXT_FILTER_ENABLED] = enabled }
    }

    suspend fun updateMuteOpeningEndingActive(mute: Boolean) {
        context.dataStore.edit { it[Keys.MUTE_OPENING_ENDING_ACTIVE] = mute }
    }

    suspend fun updateIgnoreUpperScreenSigns(ignore: Boolean) {
        context.dataStore.edit { it[Keys.IGNORE_UPPER_SCREEN_SIGNS] = ignore }
    }

    suspend fun updateSkipSongLyrics(skip: Boolean) {
        context.dataStore.edit { it[Keys.SKIP_SONG_LYRICS] = skip }
    }

    suspend fun updateIgnoreCreditsAndStaff(ignore: Boolean) {
        context.dataStore.edit { it[Keys.IGNORE_CREDITS_AND_STAFF] = ignore }
    }

    suspend fun updateMinSubtitleCharsFilter(minChars: Int) {
        context.dataStore.edit { it[Keys.MIN_SUBTITLE_CHARS_FILTER] = minChars.coerceIn(1, 12) }
    }

    suspend fun updateCustomFilterPatternsRaw(patternsRaw: String) {
        context.dataStore.edit { it[Keys.CUSTOM_FILTER_PATTERNS_RAW] = patternsRaw }
    }

    suspend fun updateSaveHistoryToDatabase(save: Boolean) {
        context.dataStore.edit { it[Keys.SAVE_HISTORY_TO_DATABASE] = save }
    }

    suspend fun updateSyncOffsetMs(offsetMs: Int) {
        context.dataStore.edit { it[Keys.SYNC_OFFSET_MS] = offsetMs.coerceIn(-2000, 2000) }
    }

    suspend fun adjustSyncOffsetStep(stepDeltaMs: Int) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.SYNC_OFFSET_MS] ?: 0
            prefs[Keys.SYNC_OFFSET_MS] = (current + stepDeltaMs).coerceIn(-2000, 2000)
        }
    }

    suspend fun updateVolumeButtonsShortcutEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.VOLUME_BUTTONS_SHORTCUT] = enabled }
    }

    // --- Métodos Fase 3 ---

    suspend fun updateStudyModeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.STUDY_MODE_ENABLED] = enabled }
    }

    suspend fun updateKaraokeHighlightEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.KARAOKE_HIGHLIGHT_ENABLED] = enabled }
    }

    suspend fun updateAutoCopyToClipboard(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_COPY_TO_CLIPBOARD] = enabled }
    }

    suspend fun updateAutoPauseOnStaticPlayer(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_PAUSE_ON_STATIC_PLAYER] = enabled }
    }

    suspend fun updateAutoPauseStaticThresholdSeconds(seconds: Int) {
        context.dataStore.edit {
            it[Keys.AUTO_PAUSE_STATIC_THRESHOLD_SEC] = seconds.coerceIn(5, 60)
        }
    }

    // --- Métodos Voz Natural (IA - Gemini TTS) ---

    suspend fun updateNaturalVoiceEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NATURAL_VOICE_ENABLED] = enabled }
    }

    suspend fun updateNaturalVoiceName(voiceName: String) {
        context.dataStore.edit { it[Keys.NATURAL_VOICE_NAME] = voiceName.ifBlank { "Kore" } }
    }

    suspend fun updateNaturalVoiceStyle(style: String) {
        context.dataStore.edit { it[Keys.NATURAL_VOICE_STYLE] = style.trim() }
    }

    suspend fun updateNaturalVoiceSpeed(speed: Float) {
        context.dataStore.edit { it[Keys.NATURAL_VOICE_SPEED] = speed.coerceIn(0.65f, 1.50f) }
    }

    suspend fun updateNaturalVoiceModelName(modelName: String) {
        context.dataStore.edit { it[Keys.NATURAL_VOICE_MODEL_NAME] = modelName.trim() }
    }

    suspend fun updateNaturalVoiceInlineTagsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NATURAL_VOICE_INLINE_TAGS_ENABLED] = enabled }
    }

    suspend fun updateNaturalCacheMaxMb(maxMb: Int) {
        context.dataStore.edit { it[Keys.NATURAL_CACHE_MAX_MB] = maxMb.coerceIn(10, 300) }
    }

    suspend fun updateNaturalFallbackTimeoutMs(timeoutMs: Long) {
        context.dataStore.edit { it[Keys.NATURAL_FALLBACK_TIMEOUT_MS] = timeoutMs.coerceIn(1200L, 9000L) }
    }

    suspend fun updateNaturalOnlyOnWifi(onlyOnWifi: Boolean) {
        context.dataStore.edit { it[Keys.NATURAL_ONLY_ON_WIFI] = onlyOnWifi }
    }

    suspend fun saveDesignedVoice(voice: DesignedVoice) {
        context.dataStore.edit { prefs ->
            val currentList = DesignedVoice.listFromJsonString(prefs[Keys.DESIGNED_VOICES_JSON]).toMutableList()
            val index = currentList.indexOfFirst { it.id == voice.id }
            if (index >= 0) {
                currentList[index] = voice
            } else {
                currentList.add(0, voice)
            }
            prefs[Keys.DESIGNED_VOICES_JSON] = DesignedVoice.listToJsonString(currentList)
        }
    }

    suspend fun renameDesignedVoice(voiceId: String, newName: String) {
        context.dataStore.edit { prefs ->
            val currentList = DesignedVoice.listFromJsonString(prefs[Keys.DESIGNED_VOICES_JSON]).toMutableList()
            val index = currentList.indexOfFirst { it.id == voiceId }
            if (index >= 0) {
                currentList[index] = currentList[index].copy(name = newName.trim())
                prefs[Keys.DESIGNED_VOICES_JSON] = DesignedVoice.listToJsonString(currentList)
            }
        }
    }

    suspend fun deleteDesignedVoice(voiceId: String) {
        context.dataStore.edit { prefs ->
            val currentList = DesignedVoice.listFromJsonString(prefs[Keys.DESIGNED_VOICES_JSON]).filterNot { it.id == voiceId }
            prefs[Keys.DESIGNED_VOICES_JSON] = DesignedVoice.listToJsonString(currentList)
            if (prefs[Keys.NATURAL_VOICE_NAME] == voiceId) {
                prefs[Keys.NATURAL_VOICE_NAME] = "Kore"
            }
            if (prefs[Keys.CHARACTER_VOICE_A_ID] == voiceId) {
                prefs[Keys.CHARACTER_VOICE_A_ID] = ""
            }
            if (prefs[Keys.CHARACTER_VOICE_B_ID] == voiceId) {
                prefs[Keys.CHARACTER_VOICE_B_ID] = ""
            }
            if (prefs[Keys.CHARACTER_VOICE_C_ID] == voiceId) {
                prefs[Keys.CHARACTER_VOICE_C_ID] = ""
            }
        }
    }

    // --- Métodos Voz Neuronal Offline (Sherpa-ONNX) ---

    suspend fun updateOfflineVoiceEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.OFFLINE_VOICE_ENABLED] = enabled }
    }

    suspend fun updateSelectedThemeId(themeId: String) {
        context.dataStore.edit { it[Keys.SELECTED_THEME_ID] = themeId.trim() }
    }

    suspend fun updateThemeAnimationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.THEME_ANIMATIONS_ENABLED] = enabled }
    }

    suspend fun updateOfflineVoiceSelectedId(modelId: String) {
        context.dataStore.edit { it[Keys.OFFLINE_VOICE_SELECTED_ID] = modelId.trim() }
    }

    suspend fun updateOfflineVoiceSpeed(speed: Float) {
        context.dataStore.edit { it[Keys.OFFLINE_VOICE_SPEED] = speed.coerceIn(0.65f, 1.50f) }
    }

    suspend fun updateOfflineVoiceThreads(threads: Int) {
        context.dataStore.edit { it[Keys.OFFLINE_VOICE_THREADS] = threads.coerceIn(2, 4) }
    }

    suspend fun updateOfflineOnlyOnWifi(onlyOnWifi: Boolean) {
        context.dataStore.edit { it[Keys.OFFLINE_ONLY_ON_WIFI] = onlyOnWifi }
    }

    suspend fun updateOfflineCacheMaxMb(maxMb: Int) {
        context.dataStore.edit { it[Keys.OFFLINE_CACHE_MAX_MB] = maxMb.coerceIn(10, 500) }
    }

    suspend fun updateOfflinePromptDismissed(dismissed: Boolean) {
        context.dataStore.edit { it[Keys.OFFLINE_PROMPT_DISMISSED] = dismissed }
    }

    suspend fun updateCatchUpSpeedEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.CATCH_UP_SPEED_ENABLED] = enabled }
    }

    suspend fun updateSimpleSettingsMode(simpleMode: Boolean) {
        context.dataStore.edit { it[Keys.SIMPLE_SETTINGS_MODE] = simpleMode }
    }
}
