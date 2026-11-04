package com.example.tts

import com.example.domain.model.AppSettings

/**
 * Interfaz común para motores de síntesis de voz de subtítulos.
 * Permite alternar de forma transparente entre el motor local [SubtitleTtsManager]
 * envuelto en [LocalSpeechEngine] y el motor neuronal [NaturalSpeechEngine] (Gemini TTS).
 */
interface SpeechEngine {
    val isSpeaking: Boolean

    fun applySettings(settings: AppSettings)

    fun speak(
        text: String,
        forceSpeakEvenIfDisabled: Boolean = false,
        pitchOverride: Float? = null,
        characterLabel: String = ""
    )

    fun stop()

    fun shutdown()
}

/**
 * Opciones de voces neuronales preconstruidas de Gemini TTS.
 */
enum class GeminiNaturalVoiceOption(
    val voiceId: String,
    val gender: String,
    val displayName: String,
    val description: String
) {
    KORE("Kore", "Femenina", "Kore (Femenina • Clara y Expresiva)", "Ideal para protagonistas, diálogos ágiles y narración de anime"),
    PUCK("Puck", "Masculina", "Puck (Masculina • Dinámica y Juvenil)", "Voz enérgica estilo shonen y personajes principales"),
    AOEDE("Aoede", "Femenina", "Aoede (Femenina • Cálida y Melódica)", "Tono suave, emotivo y natural para slice-of-life o drama"),
    CHARON("Charon", "Masculina", "Charon (Masculina • Profunda y Seria)", "Voz grave para narradores, mentores o escenas intensas"),
    FENRIR("Fenrir", "Masculina", "Fenrir (Masculina • Firme y Épica)", "Voz con carácter para acción, fantasía y antagonistas"),
    LEDA("Leda", "Femenina", "Leda (Femenina • Dulce y Serena)", "Voz serena y articulada con excelente dicción en español"),
    ORUS("Orus", "Masculina", "Orus (Masculina • Versátil y Equilibrada)", "Tono natural, neutro y adaptable para múltiples personajes"),
    ZEPHYR("Zephyr", "Femenina", "Zephyr (Femenina • Ágil y Vivaz)", "Voz moderna, rápida y juvenil para comedia y aventura");

    companion object {
        fun fromIdSafe(id: String?): GeminiNaturalVoiceOption {
            if (id.isNullOrBlank()) return KORE
            return entries.firstOrNull { it.voiceId.equals(id, ignoreCase = true) } ?: KORE
        }
    }
}
