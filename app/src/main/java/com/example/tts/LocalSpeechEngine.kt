package com.example.tts

import com.example.domain.model.AppSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * Adaptador que envuelve el [SubtitleTtsManager] existente sin modificar ni una sola línea
 * de su código interno ni su comportamiento.
 */
class LocalSpeechEngine(
    private val delegate: SubtitleTtsManager
) : SpeechEngine {

    @Volatile
    private var currentlySpeakingState: Boolean = false

    override val isSpeaking: Boolean
        get() = currentlySpeakingState

    val availableSpanishVoices: StateFlow<List<SpanishVoiceOption>>
        get() = delegate.availableSpanishVoices

    val availableEngines: StateFlow<List<TtsEngineOption>>
        get() = delegate.availableEngines

    fun updateObservedSpeakingState(speaking: Boolean) {
        currentlySpeakingState = speaking
    }

    override fun applySettings(settings: AppSettings) {
        delegate.applySettings(settings)
    }

    override fun speak(
        text: String,
        forceSpeakEvenIfDisabled: Boolean,
        pitchOverride: Float?,
        characterLabel: String
    ) {
        delegate.speakSubtitle(
            text = text,
            forceSpeakEvenIfDisabled = forceSpeakEvenIfDisabled,
            pitchOverride = pitchOverride
        )
    }

    override fun stop() {
        currentlySpeakingState = false
        delegate.stopSpeaking()
    }

    override fun shutdown() {
        currentlySpeakingState = false
        delegate.shutdown()
    }
}
