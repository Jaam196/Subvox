package com.example.tts

import android.util.Base64
import com.example.domain.model.DesignedVoice
import com.example.security.ApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

sealed class DesignedVoiceResult {
    data class Success(val voice: DesignedVoice) : DesignedVoiceResult()
    data class Error(val message: String, val statusCode: Int = 0) : DesignedVoiceResult() {
        val httpCode: Int get() = statusCode
    }
}

typealias VoiceDesignResult = DesignedVoiceResult

/**
 * Cliente para la API oficial de Gemini Voice Design (`POST /v1beta/voices`),
 * permitiendo crear voces personalizadas mediante descripción en lenguaje natural en español.
 */
class VoiceDesignClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val VOICES_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/voices"
    }

    fun buildCreateVoicePayload(description: String, sampleText: String): String {
        return JSONObject().apply {
            put(
                "voice_config",
                JSONObject().apply {
                    put("description", description.trim())
                    put("sample_text", sampleText.trim())
                }
            )
            put("description", description.trim())
            put("sample_text", sampleText.trim())
        }.toString()
    }

    fun buildCreateVoiceRequest(
        apiKey: String,
        description: String,
        sampleText: String
    ): Request {
        val payloadStr = buildCreateVoicePayload(description, sampleText)

        return Request.Builder()
            .url(VOICES_ENDPOINT)
            .addHeader("x-goog-api-key", apiKey.trim())
            .addHeader("Content-Type", "application/json")
            .post(payloadStr.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    suspend fun createVoice(
        apiKey: String,
        name: String,
        description: String,
        sampleText: String = "Hola, esta es una prueba de mi voz personalizada para subtítulos de anime."
    ): DesignedVoiceResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext DesignedVoiceResult.Error("Requiere clave de Google AI Studio")
        }

        if (description.isBlank()) {
            return@withContext DesignedVoiceResult.Error("Por favor describe las características de la voz que deseas crear.")
        }

        val request = buildCreateVoiceRequest(
            apiKey = apiKey,
            description = description,
            sampleText = sampleText
        )

        val responseResult = runCatching {
            executeRequest(request)
        }.getOrElse { exception ->
            val sanitized = ApiKeyStore.sanitizeErrorMessage(exception.message, apiKey)
            return@withContext DesignedVoiceResult.Error("Error al contactar con Voice Design: $sanitized")
        }

        parseCreateVoiceResponse(
            responseBody = responseResult.first,
            statusCode = responseResult.second,
            givenName = name,
            description = description,
            sampleText = sampleText,
            apiKey = apiKey
        )
    }

    private suspend fun executeRequest(request: Request): Pair<String, Int> =
        suspendCancellableCoroutine { cont ->
            val call = okHttpClient.newCall(request)
            cont.invokeOnCancellation {
                runCatching { call.cancel() }
            }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) {
                        cont.resumeWith(Result.failure(e))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (cont.isActive) {
                            cont.resume(Pair(body, resp.code))
                        }
                    }
                }
            })
        }

    internal fun parseCreateVoiceResponse(
        responseBody: String,
        statusCode: Int,
        givenName: String,
        description: String,
        sampleText: String,
        apiKey: String = ""
    ): DesignedVoiceResult {
        if (statusCode !in 200..299) {
            val safeSummary = ApiKeyStore.sanitizeErrorMessage(responseBody.take(150), apiKey)
            val friendlyMsg = when (statusCode) {
                401, 403 -> "API key inválida o sin permisos para Voice Design (HTTP $statusCode)."
                429 -> "Límite de cuota excedido (HTTP 429). Espera un momento antes de diseñar otra voz."
                404 -> "El servicio de Voice Design no está disponible en este momento."
                else -> "Error en el diseño de voz (HTTP $statusCode): $safeSummary"
            }
            return DesignedVoiceResult.Error(friendlyMsg, statusCode)
        }

        return runCatching {
            val root = JSONObject(responseBody)
            val rawName = root.optString("name", "")
            val rawVoiceId = root.optString("voice_id", "")
            val id = when {
                rawVoiceId.isNotBlank() -> rawVoiceId
                rawName.startsWith("voices/") -> rawName.removePrefix("voices/")
                rawName.isNotBlank() -> rawName
                else -> "voice_${UUID.randomUUID().toString().take(8)}"
            }

            var samplePcmBase64: String? = null
            var sampleRate = 24_000

            // Buscar si se incluye muestra de audio en la respuesta
            val sampleAudio = root.optJSONObject("sample_audio") ?: root.optJSONObject("sampleAudio")
            val inlineData = sampleAudio?.optJSONObject("inline_data") ?: sampleAudio?.optJSONObject("inlineData")
            val base64Data = inlineData?.optString("data", "")?.takeIf { it.isNotBlank() }
                ?: sampleAudio?.optString("audio_content", "")?.takeIf { it.isNotBlank() }
                ?: sampleAudio?.optString("audioContent", "")?.takeIf { it.isNotBlank() }
                ?: root.optString("audio_content", "").takeIf { it.isNotBlank() }

            if (!base64Data.isNullOrBlank()) {
                val rawAudio = Base64.decode(base64Data, Base64.DEFAULT)
                val parsed = WavPcmExtractor.extractPcm(rawAudio, fallbackSampleRate = 24_000)
                samplePcmBase64 = Base64.encodeToString(parsed.pcmBytes, Base64.NO_WRAP)
                sampleRate = parsed.sampleRateHz
            }

            val finalVoiceName = if (givenName.isNotBlank()) givenName.trim() else "Voz ${id.takeLast(6)}"
            DesignedVoiceResult.Success(
                DesignedVoice(
                    id = id,
                    name = finalVoiceName,
                    description = description.trim(),
                    sampleText = sampleText.trim(),
                    sampleAudioPcmBase64 = samplePcmBase64,
                    sampleRateHz = sampleRate,
                    createdAt = System.currentTimeMillis()
                )
            )
        }.getOrElse { error ->
            val safe = ApiKeyStore.sanitizeErrorMessage(error.message, apiKey)
            DesignedVoiceResult.Error("No se pudo interpretar la respuesta de Voice Design: $safe")
        }
    }
}
