package com.example.domain.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Representa una voz personalizada generada a través de la API Gemini Voice Design (`POST /v1beta/voices`).
 */
data class DesignedVoice(
    val id: String,
    val name: String,
    val description: String,
    val sampleText: String = "Hola, esta es una prueba de mi voz personalizada para subtítulos de anime.",
    val sampleAudioPcmBase64: String? = null,
    val sampleRateHz: Int = 24_000,
    val createdAt: Long = System.currentTimeMillis()
) {
    val voiceId: String get() = id
    val samplePcmAudio: ByteArray
        get() = sampleAudioPcmBase64?.let {
            android.util.Base64.decode(it, android.util.Base64.DEFAULT)
        } ?: ByteArray(0)
    val createdAtEpochMs: Long get() = createdAt

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("sampleText", sampleText)
        if (sampleAudioPcmBase64 != null) {
            put("sampleAudioPcmBase64", sampleAudioPcmBase64)
        }
        put("sampleRateHz", sampleRateHz)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(json: JSONObject): DesignedVoice {
            return DesignedVoice(
                id = json.optString("id", ""),
                name = json.optString("name", "Voz diseñada"),
                description = json.optString("description", ""),
                sampleText = json.optString("sampleText", "Hola, esta es una prueba de mi voz personalizada."),
                sampleAudioPcmBase64 = json.optString("sampleAudioPcmBase64").takeIf { it.isNotBlank() },
                sampleRateHz = json.optInt("sampleRateHz", 24_000),
                createdAt = json.optLong("createdAt", System.currentTimeMillis())
            )
        }

        fun listToJsonString(voices: List<DesignedVoice>): String {
            val arr = JSONArray()
            voices.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJsonString(jsonString: String?): List<DesignedVoice> {
            if (jsonString.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(jsonString)
                val list = mutableListOf<DesignedVoice>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val voice = fromJson(obj)
                    if (voice.id.isNotBlank()) {
                        list.add(voice)
                    }
                }
                list
            }.getOrElse { emptyList() }
        }
    }
}
