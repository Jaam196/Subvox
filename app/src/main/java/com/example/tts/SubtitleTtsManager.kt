package com.example.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.example.domain.model.AppSettings
import com.example.domain.model.NewSubtitleBehavior
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

data class SpanishVoiceOption(
    val name: String,
    val localeTag: String,
    val displayName: String,
    val isNetworkRequired: Boolean,
    val quality: Int = Voice.QUALITY_NORMAL,
    val qualityLabel: String = "Normal"
)

data class TtsEngineOption(
    val packageName: String,
    val label: String
)

private data class QueuedSpeechItem(
    val text: String,
    val pitchOverride: Float? = null
)

/**
 * Motor Android TextToSpeech local con soporte para español (es-ES, es-419, es-MX, es-US),
 * ajuste de velocidad, tono (incluyendo override por personaje), volumen, refuerzo de ganancia
 * (LoudnessEnhancer), AudioFocus ducking, selector de motor TTS, cola configurable y
 * resaltado Karaoke palabra por palabra mediante `onRangeStart`.
 */
class SubtitleTtsManager(
    context: Context,
    private val onStateChanged: (ready: Boolean, speaking: Boolean, voiceDesc: String) -> Unit,
    private val onSpanishVoiceMissing: () -> Unit,
    private val onWordRangeStarted: (spokenText: String, startChar: Int, endChar: Int) -> Unit = { _, _, _ -> }
) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var activeEnginePackage: String = ""

    @Volatile
    private var isCurrentlySpeaking = false

    @Volatile
    private var currentlySpeakingText: String = ""

    private var currentSettings = AppSettings()
    private val pendingQueue = ConcurrentLinkedQueue<QueuedSpeechItem>()
    private val utteranceTextMap = ConcurrentHashMap<String, String>()

    private var audioSessionId: Int = AudioManager.ERROR
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var activeAudioFocusRequest: AudioFocusRequest? = null

    private val speechAudioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    private val _availableSpanishVoices = MutableStateFlow<List<SpanishVoiceOption>>(emptyList())
    val availableSpanishVoices: StateFlow<List<SpanishVoiceOption>> = _availableSpanishVoices.asStateFlow()

    private val _availableEngines = MutableStateFlow<List<TtsEngineOption>>(emptyList())
    val availableEngines: StateFlow<List<TtsEngineOption>> = _availableEngines.asStateFlow()

    private var activeVoiceSummary: String = "Inicializando motor TTS..."

    init {
        initAudioSessionAndEnhancer()
        createTtsInstance("")
    }

    private fun initAudioSessionAndEnhancer() {
        runCatching {
            val generatedId = audioManager?.generateAudioSessionId() ?: AudioManager.ERROR
            if (generatedId != AudioManager.ERROR && generatedId > 0) {
                audioSessionId = generatedId
                loudnessEnhancer = LoudnessEnhancer(generatedId).apply {
                    setTargetGain(0)
                    enabled = false
                }
            }
        }
    }

    private fun updateLoudnessBoost(boostDb: Int) {
        val clampedDb = boostDb.coerceIn(0, 15)
        runCatching {
            if (loudnessEnhancer == null && audioSessionId > 0) {
                loudnessEnhancer = LoudnessEnhancer(audioSessionId)
            }
            loudnessEnhancer?.let { enhancer ->
                if (clampedDb > 0) {
                    enhancer.setTargetGain(clampedDb * 100)
                    enhancer.enabled = true
                } else {
                    enhancer.setTargetGain(0)
                    enhancer.enabled = false
                }
            }
        }
    }

    private fun releaseLoudnessEnhancer() {
        runCatching {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
        }
        loudnessEnhancer = null
    }

    private fun createTtsInstance(enginePackage: String) {
        pendingQueue.clear()
        utteranceTextMap.clear()
        abandonDuckingAudioFocus()
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        isInitialized = false
        isCurrentlySpeaking = false
        activeEnginePackage = enginePackage

        tts = runCatching {
            if (enginePackage.isBlank()) {
                TextToSpeech(appContext, this)
            } else {
                TextToSpeech(appContext, this, enginePackage)
            }
        }.getOrElse {
            activeEnginePackage = ""
            TextToSpeech(appContext, this)
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            isInitialized = false
            activeVoiceSummary = "Motor TTS no disponible en el dispositivo"
            onStateChanged(false, false, activeVoiceSummary)
            return
        }

        val engine = tts ?: return
        runCatching {
            engine.setAudioAttributes(speechAudioAttributes)
        }

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isCurrentlySpeaking = true
                if (currentSettings.duckOtherAudio) {
                    requestDuckingAudioFocus()
                }
                onStateChanged(isInitialized, true, activeVoiceSummary)
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                super.onRangeStart(utteranceId, start, end, frame)
                val fullText = utteranceId?.let { utteranceTextMap[it] } ?: return
                if (currentSettings.karaokeHighlightEnabled) {
                    onWordRangeStarted(fullText, start, end)
                }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId != null) {
                    utteranceTextMap.remove(utteranceId)
                }
                val next = pendingQueue.poll()
                if (next != null) {
                    speakInternal(next.text, flush = false, pitchOverride = next.pitchOverride)
                } else {
                    isCurrentlySpeaking = false
                    abandonDuckingAudioFocus()
                    onWordRangeStarted("", -1, -1)
                    onStateChanged(isInitialized, false, activeVoiceSummary)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId != null) {
                    utteranceTextMap.remove(utteranceId)
                }
                isCurrentlySpeaking = false
                abandonDuckingAudioFocus()
                onWordRangeStarted("", -1, -1)
                onStateChanged(isInitialized, false, activeVoiceSummary)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId != null) {
                    utteranceTextMap.remove(utteranceId)
                }
                isCurrentlySpeaking = false
                abandonDuckingAudioFocus()
                onWordRangeStarted("", -1, -1)
                onStateChanged(isInitialized, false, activeVoiceSummary)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId != null) {
                    utteranceTextMap.remove(utteranceId)
                }
                if (pendingQueue.isEmpty()) {
                    isCurrentlySpeaking = false
                    abandonDuckingAudioFocus()
                    onWordRangeStarted("", -1, -1)
                    onStateChanged(isInitialized, false, activeVoiceSummary)
                }
            }
        })

        isInitialized = true
        refreshAvailableEngines()
        refreshAvailableSpanishVoices(allowNetworkVoices = currentSettings.allowNetworkVoices)
        applySettings(currentSettings)
    }

    private fun refreshAvailableEngines() {
        val engine = tts ?: return
        val enginesList = runCatching { engine.engines }.getOrNull().orEmpty()
        val mapped = buildList {
            add(TtsEngineOption(packageName = "", label = "Predeterminado del sistema"))
            enginesList.forEach { info ->
                val cleanLabel = info.label.ifBlank { info.name }
                add(TtsEngineOption(packageName = info.name, label = cleanLabel))
            }
        }.distinctBy { it.packageName }
        _availableEngines.value = mapped
    }

    private fun refreshAvailableSpanishVoices(allowNetworkVoices: Boolean) {
        val engine = tts ?: return
        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
        val spanishVoices = voices
            .filter { voice ->
                voice.locale.language.equals("es", ignoreCase = true) &&
                    (allowNetworkVoices || !voice.isNetworkConnectionRequired)
            }
            .sortedWith(
                compareByDescending<Voice> { it.quality }
                    .thenBy { it.isNetworkConnectionRequired }
                    .thenBy { it.locale.toLanguageTag() }
                    .thenBy { it.name }
            )
            .map { voice ->
                val tag = voice.locale.toLanguageTag()
                val country = voice.locale.getDisplayCountry(Locale.forLanguageTag("es-ES")).ifBlank { tag }
                val modeLabel = if (voice.isNetworkConnectionRequired) "Red" else "Local"
                val qLabel = mapQualityToLabel(voice.quality)
                SpanishVoiceOption(
                    name = voice.name,
                    localeTag = tag,
                    displayName = "[$qLabel • $modeLabel] Español ($country) • ${voice.name.takeLast(14)}",
                    isNetworkRequired = voice.isNetworkConnectionRequired,
                    quality = voice.quality,
                    qualityLabel = qLabel
                )
            }

        _availableSpanishVoices.value = spanishVoices
    }

    private fun mapQualityToLabel(quality: Int): String {
        return when {
            quality >= Voice.QUALITY_VERY_HIGH -> "Natural++"
            quality >= Voice.QUALITY_HIGH -> "Natural"
            quality >= Voice.QUALITY_NORMAL -> "Normal"
            else -> "Básica"
        }
    }

    fun applySettings(settings: AppSettings) {
        val previousAllowNetwork = currentSettings.allowNetworkVoices
        currentSettings = settings

        updateLoudnessBoost(settings.volumeBoostDb)
        if (!settings.duckOtherAudio) {
            abandonDuckingAudioFocus()
        }

        if (settings.ttsEnginePackage != activeEnginePackage) {
            createTtsInstance(settings.ttsEnginePackage)
            return
        }

        val engine = tts ?: return
        if (!isInitialized) return

        if (previousAllowNetwork != settings.allowNetworkVoices || _availableSpanishVoices.value.isEmpty()) {
            refreshAvailableSpanishVoices(allowNetworkVoices = settings.allowNetworkVoices)
        }

        engine.setSpeechRate(settings.speechRate.coerceIn(0.5f, 2.2f))
        engine.setPitch(settings.pitch.coerceIn(0.5f, 1.8f))

        val preferredLocale = Locale.forLanguageTag(settings.preferredLanguageTag)
        val fallbackLocales = listOf(
            preferredLocale,
            Locale.forLanguageTag("es-ES"),
            Locale.forLanguageTag("es-419"),
            Locale.forLanguageTag("es-MX"),
            Locale.forLanguageTag("es-US"),
            Locale.forLanguageTag("es")
        )

        var selectedLocale: Locale? = null
        for (candidate in fallbackLocales) {
            val availability = engine.isLanguageAvailable(candidate)
            if (availability >= TextToSpeech.LANG_AVAILABLE) {
                engine.language = candidate
                selectedLocale = candidate
                break
            }
        }

        if (selectedLocale == null) {
            activeVoiceSummary = "Sin voz en español instalada"
            onSpanishVoiceMissing()
            onStateChanged(false, isCurrentlySpeaking, activeVoiceSummary)
            return
        }

        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
        val eligibleVoices = voices.filter { voice ->
            voice.locale.language.equals("es", ignoreCase = true) &&
                (settings.allowNetworkVoices || !voice.isNetworkConnectionRequired)
        }

        val explicitVoice = eligibleVoices.firstOrNull { it.name == settings.selectedVoiceName }
        val bestAutoSpanishVoice = eligibleVoices.maxByOrNull { voice ->
            val localeMatchBonus =
                if (voice.locale.toLanguageTag().equals(settings.preferredLanguageTag, ignoreCase = true)) 1000 else 0
            val localBonus = if (!voice.isNetworkConnectionRequired) 50 else 0
            localeMatchBonus + voice.quality + localBonus
        }

        val chosenVoice = explicitVoice ?: bestAutoSpanishVoice
        val boostSuffix = if (settings.volumeBoostDb > 0) " +${settings.volumeBoostDb}dB" else ""
        if (chosenVoice != null) {
            runCatching { engine.voice = chosenVoice }
            val qLabel = mapQualityToLabel(chosenVoice.quality)
            activeVoiceSummary = "${chosenVoice.locale.toLanguageTag()} $qLabel (${chosenVoice.name.takeLast(8)})$boostSuffix"
        } else {
            activeVoiceSummary = "Español (${selectedLocale.toLanguageTag()})$boostSuffix"
        }

        onStateChanged(true, isCurrentlySpeaking, activeVoiceSummary)
    }

    fun speakSubtitle(
        text: String,
        forceSpeakEvenIfDisabled: Boolean = false,
        pitchOverride: Float? = null
    ) {
        val cleaned = NaturalSpeechProsodyCleaner.cleanForSpeech(text)
        val textToSpeak = cleaned.ifBlank { text.trim() }
        if (textToSpeak.isBlank()) return
        if (!currentSettings.ttsEnabled && !forceSpeakEvenIfDisabled) return
        if (!isInitialized) return

        val modulatedPitch = pitchOverride ?: NaturalSpeechProsodyCleaner
            .calculateEmotionalPitchModulation(text, currentSettings.pitch)

        when (currentSettings.newSubtitleBehavior) {
            NewSubtitleBehavior.INTERRUPT_AND_READ_NEW -> {
                pendingQueue.clear()
                speakInternal(textToSpeak, flush = true, pitchOverride = modulatedPitch)
            }
            NewSubtitleBehavior.FINISH_CURRENT_QUEUE -> {
                if (!isCurrentlySpeaking) {
                    speakInternal(textToSpeak, flush = false, pitchOverride = modulatedPitch)
                } else {
                    while (pendingQueue.size >= 2) {
                        pendingQueue.poll()
                    }
                    pendingQueue.offer(QueuedSpeechItem(text = textToSpeak, pitchOverride = modulatedPitch))
                }
            }
        }
    }

    private fun speakInternal(text: String, flush: Boolean, pitchOverride: Float? = null) {
        val engine = tts ?: return
        val targetPitch = (pitchOverride ?: currentSettings.pitch).coerceIn(0.5f, 1.8f)
        runCatching { engine.setPitch(targetPitch) }

        updateLoudnessBoost(currentSettings.volumeBoostDb)
        if (currentSettings.duckOtherAudio) {
            requestDuckingAudioFocus()
        }

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, currentSettings.volume.coerceIn(0f, 1f))
            if (audioSessionId != AudioManager.ERROR && audioSessionId > 0) {
                putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, audioSessionId)
            }
        }
        val utteranceId = "sub_${UUID.randomUUID()}"
        if (utteranceTextMap.size > 16) {
            utteranceTextMap.clear()
        }
        utteranceTextMap[utteranceId] = text
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        engine.speak(text, queueMode, params, utteranceId)
    }

    private fun requestDuckingAudioFocus() {
        val am = audioManager ?: return
        runCatching {
            val request = activeAudioFocusRequest ?: AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
                .setAudioAttributes(speechAudioAttributes)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener { }
                .build()
                .also { activeAudioFocusRequest = it }

            am.requestAudioFocus(request)
        }
    }

    private fun abandonDuckingAudioFocus() {
        val am = audioManager ?: return
        val req = activeAudioFocusRequest ?: return
        runCatching {
            am.abandonAudioFocusRequest(req)
        }
    }

    fun stopSpeaking() {
        pendingQueue.clear()
        utteranceTextMap.clear()
        runCatching { tts?.stop() }
        isCurrentlySpeaking = false
        abandonDuckingAudioFocus()
        onWordRangeStarted("", -1, -1)
        onStateChanged(isInitialized, false, activeVoiceSummary)
    }

    fun shutdown() {
        pendingQueue.clear()
        utteranceTextMap.clear()
        abandonDuckingAudioFocus()
        releaseLoudnessEnhancer()
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        isInitialized = false
        isCurrentlySpeaking = false
    }
}
