package com.example.tts

import com.example.domain.model.AppSettings
import com.example.security.ApiKeyStore
import com.example.tts.offline.OfflineNeuralSpeechEngine
import com.example.tts.offline.OfflineVoiceModelManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ActiveSpeechTier(val displayName: String, val levelNumber: Int) {
    GEMINI_38("Voz Natural Gemini 3.8 (IA en la nube)", 1),
    OFFLINE_NEURAL("Voz Neuronal Offline (Sherpa-ONNX)", 2),
    LOCAL_ANDROID("Voz Local de Android (TTS del sistema)", 3)
}

/**
 * Orquestador en TRES NIVELES con prioridad y respaldo automático continuo:
 *
 * Nivel 1: Gemini TTS (opcional, solo si el usuario puso su API key y lo activó).
 * Nivel 2: Neuronal offline (Sherpa-ONNX con voz Piper/VITS en español): sin Internet, sin cuenta, sin clave.
 * Nivel 3: TTS local de Android (siempre disponible).
 *
 * Prioridad automática:
 * Gemini (si hay clave válida y activado) -> Neuronal offline (si hay modelo descargado y activado) -> TTS local.
 * Si un motor falla o es muy lento, cae al siguiente sin crashear, sin repetir ni cortar líneas.
 */
class ThreeTierSpeechEngine(
    private val geminiEngine: NaturalSpeechEngine,
    private val offlineEngine: OfflineNeuralSpeechEngine,
    private val localEngine: LocalSpeechEngine,
    private val apiKeyStore: ApiKeyStore,
    private val modelManager: OfflineVoiceModelManager
) : SpeechEngine {

    private val _currentTierState = MutableStateFlow(ActiveSpeechTier.LOCAL_ANDROID)
    val currentTierState: StateFlow<ActiveSpeechTier> = _currentTierState.asStateFlow()

    private val _tierReasonState = MutableStateFlow("TTS local siempre disponible.")
    val tierReasonState: StateFlow<String> = _tierReasonState.asStateFlow()

    @Volatile
    private var currentSettings = AppSettings()

    override val isSpeaking: Boolean
        get() = geminiEngine.isSpeaking || offlineEngine.isSpeaking || localEngine.isSpeaking

    override fun applySettings(settings: AppSettings) {
        currentSettings = settings
        geminiEngine.applySettings(settings)
        offlineEngine.applySettings(settings)
        localEngine.applySettings(settings)
        recomputeActiveTier()
    }

    fun recomputeActiveTier() {
        val hasKey = apiKeyStore.hasKey()
        val geminiActive = currentSettings.naturalVoiceEnabled && hasKey
        val modelReady = modelManager.isModelInstalled(currentSettings.offlineVoiceSelectedId)
        val offlineActive = currentSettings.offlineVoiceEnabled && modelReady

        when {
            geminiActive -> {
                _currentTierState.value = ActiveSpeechTier.GEMINI_38
                _tierReasonState.value = "Nivel 1 activo: API key configurada y Voz Natural activada."
            }
            offlineActive -> {
                _currentTierState.value = ActiveSpeechTier.OFFLINE_NEURAL
                _tierReasonState.value = "Nivel 2 activo: modelo descargado y síntesis offline sin conexión activada."
            }
            else -> {
                _currentTierState.value = ActiveSpeechTier.LOCAL_ANDROID
                _tierReasonState.value = when {
                    currentSettings.naturalVoiceEnabled && !hasKey -> "Gemini configurado pero falta API key. Respaldo a voz local."
                    currentSettings.offlineVoiceEnabled && !modelReady -> "Voz offline activada pero modelo aún no descargado. Usando voz local."
                    else -> "Voz local del sistema de Android activa."
                }
            }
        }
    }

    override fun speak(
        text: String,
        forceSpeakEvenIfDisabled: Boolean,
        pitchOverride: Float?,
        characterLabel: String
    ) {
        val hasKey = apiKeyStore.hasKey()
        val geminiActive = currentSettings.naturalVoiceEnabled && hasKey
        val modelReady = modelManager.isModelInstalled(currentSettings.offlineVoiceSelectedId)
        val offlineActive = currentSettings.offlineVoiceEnabled && modelReady

        when {
            geminiActive -> {
                geminiEngine.speak(text, forceSpeakEvenIfDisabled, pitchOverride, characterLabel)
            }
            offlineActive -> {
                offlineEngine.speak(text, forceSpeakEvenIfDisabled, pitchOverride, characterLabel)
            }
            else -> {
                localEngine.speak(text, forceSpeakEvenIfDisabled, pitchOverride, characterLabel)
            }
        }
    }

    override fun stop() {
        geminiEngine.stop()
        offlineEngine.stop()
        localEngine.stop()
    }

    override fun shutdown() {
        geminiEngine.shutdown()
        offlineEngine.shutdown()
        localEngine.shutdown()
    }
}
