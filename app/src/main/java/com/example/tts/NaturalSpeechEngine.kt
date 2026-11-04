package com.example.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.PlaybackParams
import android.media.audiofx.LoudnessEnhancer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import com.example.BuildConfig
import com.example.domain.model.AppSettings
import com.example.domain.model.CharacterVoiceMode
import com.example.domain.model.NewSubtitleBehavior
import com.example.security.ApiKeyStore
import com.example.security.ApiKeyValidationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Excepción interna tipada para errores HTTP de Gemini sin exponer nunca la API Key ni la URL.
 */
class GeminiHttpException(
    val statusCode: Int,
    val safeMessage: String
) : IOException("HTTP $statusCode: $safeMessage")

/**
 * Motor de síntesis de voz neuronal (IA) que utiliza Gemini TTS (`gemini-2.5-flash-preview-tts`)
 * vía REST con OkHttp y reproduce el audio PCM 16-bit mono (24 kHz) en modo streaming con [AudioTrack].
 *
 * Características clave:
 * - Lee la API key exclusivamente de [ApiKeyStore] (cifrada con Android Keystore). NO usa `BuildConfig`.
 * - Si no hay clave configurada, delega silenciosamente al [LocalSpeechEngine] sin errores ni avisos molestos.
 * - Usa los mismos [AudioAttributes] (`USAGE_ASSISTANCE_ACCESSIBILITY` + `CONTENT_TYPE_SPEECH`),
 *   un `audioSessionId` dedicado con [LoudnessEnhancer] (0..15 dB) y ducking de [AudioFocusRequest].
 * - Respeta [NewSubtitleBehavior]: interrumpe petición HTTP y reproducción en curso o encola y
 *   **pre-sintetiza** en segundo plano la siguiente línea mientras suena la actual para reducir latencia.
 * - Caché en disco [NaturalSpeechCache] indexada por hash SHA-256 de (texto + voz + estilo).
 * - Respaldo automático transparente al [LocalSpeechEngine] si no hay red, se exige solo Wi-Fi,
 *   la clave es inválida (401/403), se supera la cuota HTTP 429 o vence `naturalFallbackTimeoutMs`.
 * - Mapea los tonos de Voces por Personaje (`pitchOverride`) a voces/estilos diferenciados en IA.
 */
class NaturalSpeechEngine(
    context: Context,
    private val apiKeyStore: ApiKeyStore,
    private val fallbackLocalEngine: SpeechEngine,
    private val cache: NaturalSpeechCache,
    private val onStateChanged: (ready: Boolean, speaking: Boolean, voiceDesc: String) -> Unit,
    private val onFallbackNoticeChanged: (notice: String) -> Unit,
    private val onCacheSizeUpdated: (bytes: Long) -> Unit,
    private val onClearKaraokeHighlight: () -> Unit = {}
) : SpeechEngine {

    companion object {
        const val MODEL_LITE_TTS = "gemini-3.8-flash-lite-tts"
        const val MODEL_FLASH_TTS = "gemini-3.8-flash-tts"
        const val DEFAULT_AUDIO_FORMAT = "audio/l16"
        private const val GEMINI_REST_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val DEFAULT_SAMPLE_RATE_HZ = 24_000
        private const val STREAM_CHUNK_BYTES = 4096

        fun isValidModelName(modelName: String): Boolean {
            val trimmed = modelName.trim()
            if (trimmed.isEmpty()) return false
            if (trimmed.contains(' ') || trimmed.contains('/')) return false
            return Regex("""^[a-zA-Z0-9._-]+$""").matches(trimmed)
        }
    }

    private data class QueuedNaturalItem(
        val text: String,
        val forceSpeakEvenIfDisabled: Boolean,
        val forceExpressiveTry: Boolean = false,
        val pitchOverride: Float?,
        val characterLabel: String
    )

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val speechAudioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    private var audioSessionId: Int = AudioManager.ERROR
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var activeAudioFocusRequest: AudioFocusRequest? = null

    @Volatile
    private var currentSettings: AppSettings = AppSettings()

    @Volatile
    private var currentlySpeaking: Boolean = false

    @Volatile
    private var activeHttpCall: Call? = null

    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    private var workerJob: Job? = null
    private val pendingQueue = ConcurrentLinkedQueue<QueuedNaturalItem>()
    private val prefetchTasks = ConcurrentHashMap<String, Deferred<CachedPcmAudio?>>()

    override val isSpeaking: Boolean
        get() = currentlySpeaking || fallbackLocalEngine.isSpeaking

    init {
        initAudioSessionAndEnhancer()
        onCacheSizeUpdated(cache.currentSizeBytes())
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

    private fun resolveEffectiveApiKey(): String {
        return apiKeyStore.getKey().trim()
    }

    fun hasAnyAvailableApiKey(): Boolean {
        return resolveEffectiveApiKey().isNotEmpty()
    }

    override fun applySettings(settings: AppSettings) {
        currentSettings = settings
        updateLoudnessBoost(settings.volumeBoostDb)
        if (!settings.duckOtherAudio && !currentlySpeaking) {
            abandonDuckingAudioFocus()
        }
        // Siempre sincronizamos el motor local por si actúa como respaldo o modo principal
        fallbackLocalEngine.applySettings(settings)

        if (!settings.naturalVoiceEnabled) {
            onFallbackNoticeChanged("")
        } else if (isNaturalModeEffective(settings)) {
            val boostSuffix = if (settings.volumeBoostDb > 0) " +${settings.volumeBoostDb}dB" else ""
            val modeLabel = if (hasAnyAvailableApiKey()) {
                "Voz Expresiva Primaria (${settings.naturalVoiceName})$boostSuffix"
            } else {
                "Voz Expresiva (${settings.naturalVoiceName} • Respaldo Local Activo)$boostSuffix"
            }
            onStateChanged(true, isSpeaking, modeLabel)
        }
    }

    /**
     * Comprueba si la voz natural/expresiva está configurada como voz primaria (sin ahorro de batería).
     */
    fun isNaturalModeEffective(settings: AppSettings = currentSettings): Boolean {
        return settings.naturalVoiceEnabled && !settings.batterySaverMode
    }

    /**
     * Cancela al instante cualquier petición en curso o cola pendiente cuando el usuario cambia o borra la API key.
     */
    fun onApiKeyChangedOrCleared() {
        val hasKey = hasAnyAvailableApiKey()
        if (!hasKey) {
            cancelAllPrefetches()
            runCatching { activeHttpCall?.cancel() }
            activeHttpCall = null
            onFallbackNoticeChanged("")
        } else {
            cancelAllPrefetches()
            onFallbackNoticeChanged("")
            applySettings(currentSettings)
        }
    }

    /**
     * Calcula un tono expresivo diferenciado para el motor TTS local cuando actúa como respaldo
     * automático de la voz expresiva seleccionada (`Kore`, `Puck`, `Aoede`, `Leda`, `Fenrir`, `Charon`, `Zephyr`).
     */
    private fun computeExpressiveFallbackPitch(
        voiceName: String,
        explicitPitchOverride: Float?,
        settings: AppSettings
    ): Float {
        if (explicitPitchOverride != null) {
            return explicitPitchOverride.coerceIn(0.60f, 1.60f)
        }
        val base = settings.pitch.coerceIn(0.65f, 1.45f)
        val styleLower = settings.naturalVoiceStyle.lowercase()
        val styleDelta = when {
            styleLower.contains("épico") || styleLower.contains("shonen") || styleLower.contains("energía") -> 0.06f
            styleLower.contains("cálido") || styleLower.contains("suave") -> 0.03f
            styleLower.contains("sereno") || styleLower.contains("grave") -> -0.06f
            else -> 0.04f
        }
        val voiceDelta = when (voiceName.lowercase()) {
            "leda" -> 0.14f
            "aoede" -> 0.09f
            "puck" -> 0.07f
            "kore" -> 0.04f
            "zephyr" -> 0.02f
            "fenrir" -> -0.09f
            "charon" -> -0.14f
            else -> 0.04f
        }
        return (base + voiceDelta + styleDelta).coerceIn(0.65f, 1.48f)
    }

    /**
     * Permite escuchar de inmediato la voz expresiva (por ejemplo al pulsar "Probar voz expresiva" en Ajustes)
     * incluso antes de haber activado el interruptor principal, y si falla o no hay API key pasa
     * automáticamente a la voz local con entonación expresiva para que SIEMPRE se escuche.
     */
    fun speakExpressivePreview(text: String) {
        if (text.isBlank()) return
        onClearKaraokeHighlight()

        val item = QueuedNaturalItem(
            text = text,
            forceSpeakEvenIfDisabled = true,
            forceExpressiveTry = true,
            pitchOverride = null,
            characterLabel = "Prueba Expresiva"
        )

        cancelCurrentPlaybackAndRequests()
        pendingQueue.clear()
        cancelAllPrefetches()
        fallbackLocalEngine.stop()
        pendingQueue.offer(item)
        ensureQueueWorkerRunning()
    }

    /**
     * Prueba la API key (la escrita en el campo o la guardada en [ApiKeyStore]) haciendo una petición
     * mínima de síntesis a Gemini TTS y devolviendo un resultado claro en español.
     * Nunca imprime ni incluye la clave en mensajes de error.
     */
    suspend fun validateApiKey(
        candidateKeyOverride: String? = null,
        voiceName: String = currentSettings.naturalVoiceName
    ): ApiKeyValidationResult = withContext(Dispatchers.IO) {
        val keyToTest = (candidateKeyOverride?.trim()?.takeIf { it.isNotEmpty() } ?: resolveEffectiveApiKey()).trim()
        if (keyToTest.isEmpty()) {
            return@withContext ApiKeyValidationResult.InvalidKey(
                "Sin clave configurada. Pega o escribe tu API key de Google AI Studio primero."
            )
        }

        val netCheck = checkNetworkAvailability(requireWifi = false)
        if (!netCheck.allowed) {
            return@withContext ApiKeyValidationResult.NoConnection(
                "Sin conexión a Internet. Verifica tu red Wi-Fi o datos móviles e inténtalo de nuevo."
            )
        }

        val resolvedVoice = GeminiNaturalVoiceOption.fromIdSafe(voiceName).voiceId
        runCatching {
            withTimeout(10_000L) {
                val request = buildGeminiSynthesisRequest(
                    apiKey = keyToTest,
                    text = "Hola.",
                    voiceName = resolvedVoice,
                    styleInstruction = ""
                )
                val responseJson = executeCallCancellable(request, trackAsActivePlaybackCall = false)
                parseGeminiAudioResponse(responseJson)
            }
        }.fold(
            onSuccess = { audio ->
                if (audio.pcmBytes.isNotEmpty()) {
                    ApiKeyValidationResult.Valid(
                        "Clave válida y operativa. Voz neuronal Gemini TTS ($resolvedVoice) lista."
                    )
                } else {
                    ApiKeyValidationResult.OtherError(
                        "La API respondió sin audio PCM. Inténtalo de nuevo en unos segundos."
                    )
                }
            },
            onFailure = { error ->
                classifyValidationError(error, keyToTest)
            }
        )
    }

    private fun classifyValidationError(error: Throwable, testedKey: String): ApiKeyValidationResult {
        return when (error) {
            is GeminiHttpException -> {
                val bodyLower = error.safeMessage.lowercase()
                when {
                    error.statusCode == 401 || error.statusCode == 403 ||
                        (error.statusCode == 400 && (bodyLower.contains("api_key_invalid") || bodyLower.contains("api key not valid") || bodyLower.contains("key"))) -> {
                        ApiKeyValidationResult.InvalidKey(
                            "Clave inválida o sin permisos (HTTP ${error.statusCode}). Revisa que hayas copiado la clave completa desde Google AI Studio."
                        )
                    }
                    error.statusCode == 404 -> {
                        ApiKeyValidationResult.OtherError(
                            "El modelo de voz configurado ya no está disponible. Cámbialo en Ajustes > Voz natural."
                        )
                    }
                    error.statusCode == 429 || bodyLower.contains("quota") || bodyLower.contains("rate_limit") -> {
                        ApiKeyValidationResult.QuotaExceeded(
                            "Sin cuota disponible o límite de peticiones alcanzado (HTTP 429). Espera un momento o revisa tu plan en Google AI Studio."
                        )
                    }
                    else -> {
                        val safeDetail = ApiKeyStore.sanitizeErrorMessage(error.safeMessage, testedKey)
                        ApiKeyValidationResult.OtherError(
                            "Error del servidor Gemini (HTTP ${error.statusCode}): $safeDetail"
                        )
                    }
                }
            }
            is UnknownHostException -> {
                ApiKeyValidationResult.NoConnection(
                    "No se pudo conectar con el servidor de Gemini. Comprueba tu conexión a Internet."
                )
            }
            is SocketTimeoutException, is kotlinx.coroutines.TimeoutCancellationException -> {
                ApiKeyValidationResult.NoConnection(
                    "Tiempo de espera agotado al contactar con Gemini TTS. Revisa tu conexión."
                )
            }
            else -> {
                val safeMsg = ApiKeyStore.sanitizeErrorMessage(error.message, testedKey)
                ApiKeyValidationResult.OtherError("No se pudo verificar la clave: $safeMsg")
            }
        }
    }

    override fun speak(
        text: String,
        forceSpeakEvenIfDisabled: Boolean,
        pitchOverride: Float?,
        characterLabel: String
    ) {
        if (text.isBlank()) return
        val settings = currentSettings
        if (!settings.ttsEnabled && !forceSpeakEvenIfDisabled) return

        // Si la voz natural/expresiva está desactivada, lee directamente con la voz local estándar.
        if (!settings.naturalVoiceEnabled) {
            onFallbackNoticeChanged("")
            fallbackLocalEngine.speak(
                text = text,
                forceSpeakEvenIfDisabled = forceSpeakEvenIfDisabled,
                pitchOverride = pitchOverride,
                characterLabel = characterLabel
            )
            return
        }

        if (settings.batterySaverMode) {
            onFallbackNoticeChanged("Modo ahorro de batería activo: pasando automáticamente a voz local")
            val expressivePitch = computeExpressiveFallbackPitch(
                voiceName = settings.naturalVoiceName,
                explicitPitchOverride = pitchOverride,
                settings = settings
            )
            fallbackLocalEngine.speak(
                text = text,
                forceSpeakEvenIfDisabled = forceSpeakEvenIfDisabled,
                pitchOverride = expressivePitch,
                characterLabel = characterLabel
            )
            return
        }

        // El resaltado karaoke depende de onRangeStart del TTS local; con voz natural se limpia sin errores
        onClearKaraokeHighlight()

        val item = QueuedNaturalItem(
            text = text,
            forceSpeakEvenIfDisabled = forceSpeakEvenIfDisabled,
            forceExpressiveTry = false,
            pitchOverride = pitchOverride,
            characterLabel = characterLabel
        )

        when (settings.newSubtitleBehavior) {
            NewSubtitleBehavior.INTERRUPT_AND_READ_NEW -> {
                cancelCurrentPlaybackAndRequests()
                pendingQueue.clear()
                cancelAllPrefetches()
                fallbackLocalEngine.stop()
                pendingQueue.offer(item)
                ensureQueueWorkerRunning()
            }

            NewSubtitleBehavior.FINISH_CURRENT_QUEUE -> {
                while (pendingQueue.size >= 2) {
                    pendingQueue.poll()
                }
                pendingQueue.offer(item)
                // Si ya está sonando una frase actual, pre-cargamos/sintetizamos en paralelo la nueva línea encolada
                if (currentlySpeaking || workerJob?.isActive == true) {
                    triggerPrefetchForItem(item, settings)
                }
                ensureQueueWorkerRunning()
            }
        }
    }

    private fun ensureQueueWorkerRunning() {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch {
            while (isActive) {
                val nextItem = pendingQueue.poll() ?: break
                // Si hay otro elemento detrás en la cola, lanzamos su precarga mientras procesamos este
                pendingQueue.peek()?.let { upcoming ->
                    triggerPrefetchForItem(upcoming, currentSettings)
                }
                processAndPlayItem(nextItem)
            }
            currentlySpeaking = false
            abandonDuckingAudioFocus()
            val settings = currentSettings
            if (isNaturalModeEffective(settings)) {
                val boostSuffix = if (settings.volumeBoostDb > 0) " +${settings.volumeBoostDb}dB" else ""
                val modeDesc = if (hasAnyAvailableApiKey()) {
                    "Voz Expresiva Primaria (${settings.naturalVoiceName})$boostSuffix"
                } else {
                    "Voz Expresiva (${settings.naturalVoiceName} • Respaldo Local Activo)$boostSuffix"
                }
                onStateChanged(true, false, modeDesc)
            }
        }
    }

    private fun triggerPrefetchForItem(item: QueuedNaturalItem, settings: AppSettings) {
        val currentApiKey = resolveEffectiveApiKey()
        if (currentApiKey.isBlank()) return

        val (targetVoice, targetStyle) = resolveVoiceAndStyleForCharacter(item, settings)
        val model = settings.naturalVoiceModelName.ifBlank { MODEL_LITE_TTS }
        val format = DEFAULT_AUDIO_FORMAT
        val cacheKey = NaturalSpeechCache.computeKeyHash(item.text, model, targetVoice, targetStyle, format)
        if (prefetchTasks.containsKey(cacheKey)) return

        val deferred = scope.async {
            runCatching {
                cache.get(item.text, model, targetVoice, targetStyle, format)
                    ?: fetchFromGeminiWithTimeout(
                        apiKey = currentApiKey,
                        text = item.text,
                        voiceName = targetVoice,
                        styleInstruction = targetStyle,
                        settings = settings,
                        characterLabel = item.characterLabel
                    )
            }.getOrNull()
        }
        prefetchTasks[cacheKey] = deferred
        deferred.invokeOnCompletion {
            prefetchTasks.remove(cacheKey)
        }
    }

    private suspend fun processAndPlayItem(item: QueuedNaturalItem) {
        val settings = currentSettings
        val (targetVoice, targetStyle) = resolveVoiceAndStyleForCharacter(item, settings)
        val model = settings.naturalVoiceModelName.ifBlank { MODEL_LITE_TTS }
        val format = DEFAULT_AUDIO_FORMAT
        val cacheKey = NaturalSpeechCache.computeKeyHash(item.text, model, targetVoice, targetStyle, format)

        // 1. Primero comprobamos si ya está en la caché en disco (instantáneo incluso sin red ni API key)
        val cachedAudio = cache.get(item.text, model, targetVoice, targetStyle, format)
        if (cachedAudio != null) {
            onFallbackNoticeChanged("")
            playPcmStreaming(cachedAudio.pcmBytes, cachedAudio.sampleRateHz, targetVoice, settings)
            return
        }

        // 2. Comprobaciones de API key (Keystore o entorno) y red antes de llamar a Gemini REST API
        val apiKey = resolveEffectiveApiKey()
        if (apiKey.isBlank()) {
            // Si está en modo primario o en prueba de voz expresiva y no hay API key,
            // pasa automáticamente a la voz local aplicando entonación expresiva según la voz elegida.
            val fallbackNotice = if (item.forceExpressiveTry) {
                "Reproduciendo con respaldo de voz local expresiva (${targetVoice}) • Añade tu API key para audio IA en la nube"
            } else {
                ""
            }
            onFallbackNoticeChanged(fallbackNotice)
            val expressivePitch = computeExpressiveFallbackPitch(
                voiceName = targetVoice,
                explicitPitchOverride = item.pitchOverride,
                settings = settings
            )
            fallbackLocalEngine.speak(
                text = item.text,
                forceSpeakEvenIfDisabled = item.forceSpeakEvenIfDisabled,
                pitchOverride = expressivePitch,
                characterLabel = item.characterLabel
            )
            return
        }

        val networkStatus = checkNetworkAvailability(requireWifi = settings.naturalOnlyOnWifi && !item.forceExpressiveTry)
        if (!networkStatus.allowed) {
            fallbackToLocal(
                item = item,
                targetVoice = targetVoice,
                reason = networkStatus.reason
            )
            return
        }

        // 3. Reutilizar tarea de precarga en curso o sintetizar ahora con timeout configurable
        val audioResult = runCatching {
            val existingPrefetch = prefetchTasks.remove(cacheKey)
            if (existingPrefetch != null && !existingPrefetch.isCancelled) {
                withTimeout(settings.naturalFallbackTimeoutMs.coerceIn(1200L, 9000L)) {
                    existingPrefetch.await()
                }
            } else {
                fetchFromGeminiWithTimeout(
                    apiKey = apiKey,
                    text = item.text,
                    voiceName = targetVoice,
                    styleInstruction = targetStyle,
                    settings = settings,
                    characterLabel = item.characterLabel
                )
            }
        }

        audioResult.fold(
            onSuccess = { audio ->
                if (audio != null && audio.pcmBytes.isNotEmpty()) {
                    onFallbackNoticeChanged("")
                    playPcmStreaming(audio.pcmBytes, audio.sampleRateHz, targetVoice, settings)
                } else {
                    fallbackToLocal(
                        item = item,
                        targetVoice = targetVoice,
                        reason = "Respuesta de audio vacía de Gemini TTS: pasando automáticamente a voz local"
                    )
                }
            },
            onFailure = { error ->
                if (error is CancellationException && activeHttpCall?.isCanceled() == true) {
                    // Cancelación intencional por nuevo subtítulo entrante o cambio de clave
                    return
                }
                val friendlyReason = when {
                    error is GeminiHttpException && error.statusCode == 404 ->
                        "El modelo de voz configurado ya no está disponible. Cámbialo en Ajustes > Voz natural."
                    error is GeminiHttpException && (error.statusCode == 401 || error.statusCode == 403 ||
                        (error.statusCode == 400 && error.safeMessage.contains("key", ignoreCase = true))) ->
                        "API key inválida o rechazada (HTTP ${error.statusCode}): pasando automáticamente a voz local"
                    error is GeminiHttpException && error.statusCode == 429 ->
                        "Límite de cuota Gemini alcanzado (429): pasando automáticamente a voz local"
                    error is kotlinx.coroutines.TimeoutCancellationException ->
                        "Tiempo de espera agotado (>${settings.naturalFallbackTimeoutMs} ms): pasando automáticamente a voz local"
                    else -> {
                        val sanitized = ApiKeyStore.sanitizeErrorMessage(error.message, apiKey).take(38)
                        "Voz IA no disponible ($sanitized): pasando automáticamente a voz local"
                    }
                }
                fallbackToLocal(item = item, targetVoice = targetVoice, reason = friendlyReason)
            }
        )
    }

    private fun fallbackToLocal(item: QueuedNaturalItem, targetVoice: String, reason: String) {
        currentlySpeaking = false
        abandonDuckingAudioFocus()
        onFallbackNoticeChanged(reason)
        val expressivePitch = computeExpressiveFallbackPitch(
            voiceName = targetVoice,
            explicitPitchOverride = item.pitchOverride,
            settings = currentSettings
        )
        fallbackLocalEngine.speak(
            text = item.text,
            forceSpeakEvenIfDisabled = item.forceSpeakEvenIfDisabled,
            pitchOverride = expressivePitch,
            characterLabel = item.characterLabel
        )
    }

    private fun resolveVoiceIdOrName(candidate: String, settings: AppSettings): String {
        val trimmed = candidate.trim()
        if (trimmed.startsWith("voice_") || trimmed.startsWith("voices/")) {
            return trimmed
        }
        val designed = settings.designedVoices.firstOrNull {
            it.id.equals(trimmed, ignoreCase = true) || it.name.equals(trimmed, ignoreCase = true)
        }
        if (designed != null) {
            return designed.id
        }
        return GeminiNaturalVoiceOption.fromIdSafe(trimmed).voiceId
    }

    /**
     * Mapea el pitch o personaje detectado a una voz predefinida o diseñada en Gemini TTS
     * cuando Voces por Personaje está activo.
     */
    private fun resolveVoiceAndStyleForCharacter(
        item: QueuedNaturalItem,
        settings: AppSettings
    ): Pair<String, String> {
        val baseVoice = resolveVoiceIdOrName(settings.naturalVoiceName, settings)
        val baseStyle = settings.naturalVoiceStyle.trim()
        val pitch = item.pitchOverride

        if (settings.characterVoicesMode == CharacterVoiceMode.OFF || pitch == null) {
            return baseVoice to baseStyle
        }

        return when {
            pitch >= 1.12f -> {
                val assignedB = settings.characterVoiceBId.takeIf { it.isNotBlank() }
                val targetVoice = assignedB ?: if (baseVoice.equals("Leda", ignoreCase = true)) "Aoede" else "Leda"
                val extraStyle = "$baseStyle, con tono juvenil, agudo y expresivo"
                targetVoice to extraStyle
            }
            pitch <= 0.90f -> {
                val assignedC = settings.characterVoiceCId.takeIf { it.isNotBlank() }
                val targetVoice = assignedC ?: if (baseVoice.equals("Charon", ignoreCase = true)) "Fenrir" else "Charon"
                val extraStyle = "$baseStyle, con voz grave, serena y profunda"
                targetVoice to extraStyle
            }
            else -> {
                val assignedA = settings.characterVoiceAId.takeIf { it.isNotBlank() }
                (assignedA ?: baseVoice) to baseStyle
            }
        }
    }

    fun playSamplePcm(pcmBytes: ByteArray, sampleRateHz: Int, voiceName: String) {
        scope.launch {
            cancelCurrentPlaybackAndRequests()
            playPcmStreaming(pcmBytes, sampleRateHz, voiceName, currentSettings)
        }
    }

    private suspend fun fetchFromGeminiWithTimeout(
        apiKey: String,
        text: String,
        voiceName: String,
        styleInstruction: String,
        settings: AppSettings,
        characterLabel: String = ""
    ): CachedPcmAudio = withTimeout(settings.naturalFallbackTimeoutMs.coerceIn(1200L, 9000L)) {
        val request = buildGeminiSynthesisRequest(
            apiKey = apiKey,
            text = text,
            voiceName = voiceName,
            styleInstruction = styleInstruction,
            characterLabel = characterLabel,
            settings = settings
        )

        val responseString = executeCallCancellable(request, trackAsActivePlaybackCall = true)
        val parsed = parseGeminiAudioResponse(responseString)

        val model = settings.naturalVoiceModelName.ifBlank { MODEL_LITE_TTS }
        val updatedBytes = cache.put(
            text = text,
            modelName = model,
            voiceName = voiceName,
            styleInstruction = styleInstruction,
            audioFormat = DEFAULT_AUDIO_FORMAT,
            pcmBytes = parsed.pcmBytes,
            sampleRateHz = parsed.sampleRateHz,
            maxCacheMb = settings.naturalCacheMaxMb
        )
        onCacheSizeUpdated(updatedBytes)
        parsed
    }

    internal fun buildGeminiSynthesisRequest(
        apiKey: String,
        text: String,
        voiceName: String,
        styleInstruction: String,
        characterLabel: String = "",
        settings: AppSettings = currentSettings
    ): Request {
        val model = if (isValidModelName(settings.naturalVoiceModelName)) {
            settings.naturalVoiceModelName.trim()
        } else {
            MODEL_LITE_TTS
        }
        val url = "$GEMINI_REST_BASE_URL/$model:generateContent"

        // El texto enviado debe ser SOLO el subtítulo (verbatim).
        // Si el usuario no activó "Pausas y expresiones" (inline tags), se limpian posibles etiquetas
        val verbatimText = if (!settings.naturalVoiceInlineTagsEnabled) {
            text.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        } else {
            text.trim()
        }

        // El estilo y el speaker van en speech_metadata por parte, no concatenados al texto
        val partObj = JSONObject().apply {
            put("text", verbatimText)
            if (styleInstruction.isNotBlank() || characterLabel.isNotBlank()) {
                val metadata = JSONObject().apply {
                    if (styleInstruction.isNotBlank()) {
                        put("style", styleInstruction.trim())
                    }
                    if (characterLabel.isNotBlank()) {
                        put("speaker", characterLabel.trim())
                    }
                }
                put("speech_metadata", metadata)
            }
        }

        val payloadJson = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(partObj)
                    )
                )
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("responseMimeType", DEFAULT_AUDIO_FORMAT)
                    put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put(
                                "prebuiltVoiceConfig",
                                JSONObject().put("voiceName", voiceName)
                            )
                        )
                    )
                }
            )
        }

        // Pasamos la API key en la cabecera x-goog-api-key en lugar de la URL para que jamás
        // pueda filtrarse en trazas de URLs de red o excepciones de OkHttp.
        return Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey.trim())
            .post(payloadJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    private suspend fun executeCallCancellable(
        request: Request,
        trackAsActivePlaybackCall: Boolean
    ): String = suspendCancellableCoroutine { cont ->
        val call = okHttpClient.newCall(request)
        if (trackAsActivePlaybackCall) {
            activeHttpCall = call
        }

        cont.invokeOnCancellation {
            runCatching { call.cancel() }
        }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (trackAsActivePlaybackCall && activeHttpCall === call) {
                    activeHttpCall = null
                }
                if (cont.isActive) {
                    cont.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (trackAsActivePlaybackCall && activeHttpCall === call) {
                    activeHttpCall = null
                }
                response.use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        if (cont.isActive) {
                            val safeMsg = extractSafeErrorSummary(bodyStr)
                            cont.resumeWithException(
                                GeminiHttpException(
                                    statusCode = resp.code,
                                    safeMessage = safeMsg
                                )
                            )
                        }
                        return
                    }
                    if (cont.isActive) {
                        cont.resume(bodyStr)
                    }
                }
            }
        })
    }

    private fun extractSafeErrorSummary(errorJsonBody: String): String {
        val raw = runCatching {
            val root = JSONObject(errorJsonBody)
            val errObj = root.optJSONObject("error")
            val status = errObj?.optString("status").orEmpty()
            val message = errObj?.optString("message").orEmpty()
            if (status.isNotBlank() || message.isNotBlank()) {
                "$status $message".trim()
            } else {
                errorJsonBody.take(120)
            }
        }.getOrElse {
            errorJsonBody.take(120)
        }
        return ApiKeyStore.sanitizeErrorMessage(raw)
    }

    internal fun parseGeminiAudioResponse(jsonString: String): CachedPcmAudio {
        val root = JSONObject(jsonString)
        val candidates = root.optJSONArray("candidates")
            ?: throw IOException("Sin candidates en respuesta Gemini")
        val firstCandidate = candidates.optJSONObject(0)
            ?: throw IOException("Candidate vacío")
        val content = firstCandidate.optJSONObject("content")
            ?: throw IOException("Content vacío")
        val parts = content.optJSONArray("parts")
            ?: throw IOException("Parts vacío")

        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val inlineData = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
            if (inlineData != null) {
                val base64Data = inlineData.optString("data", "")
                val mimeType = inlineData.optString("mimeType", inlineData.optString("mime_type", "audio/pcm;rate=24000"))
                if (base64Data.isNotBlank()) {
                    val rawDecodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val mimeSampleRate = extractSampleRateFromMime(mimeType)
                    val parsed = WavPcmExtractor.extractPcm(rawDecodedBytes, mimeSampleRate)
                    return CachedPcmAudio(pcmBytes = parsed.pcmBytes, sampleRateHz = parsed.sampleRateHz)
                }
            }
        }
        throw IOException("No se encontró inlineData de audio PCM en la respuesta")
    }

    private fun extractSampleRateFromMime(mimeType: String): Int {
        val regex = Regex("""rate\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
        val match = regex.find(mimeType)
        val parsed = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        return if (parsed != null && parsed in 8_000..96_000) parsed else DEFAULT_SAMPLE_RATE_HZ
    }

    /**
     * Reproduce el buffer PCM 16-bit mono en modo `MODE_STREAM` mediante [AudioTrack],
     * aplicando el `audioSessionId` (para [LoudnessEnhancer]), [AudioAttributes] de accesibilidad/voz,
     * el volumen configurado, la velocidad (`naturalVoiceSpeed`) y el foco de audio con ducking.
     */
    private fun playPcmStreaming(
        pcmBytes: ByteArray,
        sampleRateHz: Int,
        activeVoiceName: String,
        settings: AppSettings
    ) {
        if (pcmBytes.isEmpty()) return

        updateLoudnessBoost(settings.volumeBoostDb)
        if (settings.duckOtherAudio) {
            requestDuckingAudioFocus()
        }

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(STREAM_CHUNK_BYTES)

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRateHz)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val trackBuilder = AudioTrack.Builder()
            .setAudioAttributes(speechAudioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBufSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)

        if (audioSessionId != AudioManager.ERROR && audioSessionId > 0) {
            trackBuilder.setSessionId(audioSessionId)
        }

        val track = runCatching { trackBuilder.build() }.getOrNull() ?: return
        activeAudioTrack = track

        val boostSuffix = if (settings.volumeBoostDb > 0) " +${settings.volumeBoostDb}dB" else ""
        val voiceSummary = "IA Gemini ($activeVoiceName)$boostSuffix"

        try {
            track.setVolume(settings.volume.coerceIn(0f, 1f))
            val speed = settings.naturalVoiceSpeed.coerceIn(0.65f, 1.50f)
            if (speed != 1.0f) {
                runCatching {
                    track.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1.0f)
                }
            }

            currentlySpeaking = true
            onStateChanged(true, true, voiceSummary)
            track.play()

            var offset = 0
            // Aseguramos alineación par de 16-bit (2 bytes por muestra)
            val alignedLength = pcmBytes.size - (pcmBytes.size % 2)
            while (offset < alignedLength && activeAudioTrack === track) {
                val bytesToWrite = minOf(STREAM_CHUNK_BYTES, alignedLength - offset)
                val written = track.write(pcmBytes, offset, bytesToWrite, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
        } catch (_: Exception) {
            // Ignorar interrupciones al detener la pista
        } finally {
            runCatching {
                track.stop()
            }
            runCatching {
                track.release()
            }
            if (activeAudioTrack === track) {
                activeAudioTrack = null
            }
            currentlySpeaking = false
        }
    }

    private data class NetworkCheckResult(
        val allowed: Boolean,
        val reason: String = ""
    )

    private fun checkNetworkAvailability(requireWifi: Boolean): NetworkCheckResult {
        val cm = connectivityManager
            ?: return NetworkCheckResult(false, "Sin servicio de red: usando voz local")
        val activeNetwork = cm.activeNetwork
            ?: return NetworkCheckResult(false, "Sin conexión a Internet: usando voz local")
        val caps = cm.getNetworkCapabilities(activeNetwork)
            ?: return NetworkCheckResult(false, "Red sin capacidades activas: usando voz local")

        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (!hasInternet) {
            return NetworkCheckResult(false, "Sin conexión a Internet: usando voz local")
        }

        if (requireWifi) {
            val isWifiOrEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!isWifiOrEthernet) {
                return NetworkCheckResult(
                    allowed = false,
                    reason = "Voz IA configurada en 'Solo Wi-Fi': usando voz local en datos móviles"
                )
            }
        }

        return NetworkCheckResult(allowed = true)
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

    private fun cancelCurrentPlaybackAndRequests() {
        runCatching { activeHttpCall?.cancel() }
        activeHttpCall = null
        runCatching {
            activeAudioTrack?.pause()
            activeAudioTrack?.flush()
            activeAudioTrack?.stop()
        }
        activeAudioTrack = null
        workerJob?.cancel()
        workerJob = null
        currentlySpeaking = false
    }

    private fun cancelAllPrefetches() {
        prefetchTasks.values.forEach { deferred ->
            runCatching { deferred.cancel() }
        }
        prefetchTasks.clear()
    }

    override fun stop() {
        pendingQueue.clear()
        cancelAllPrefetches()
        cancelCurrentPlaybackAndRequests()
        abandonDuckingAudioFocus()
        fallbackLocalEngine.stop()
        onClearKaraokeHighlight()
        val settings = currentSettings
        if (isNaturalModeEffective(settings)) {
            val boostSuffix = if (settings.volumeBoostDb > 0) " +${settings.volumeBoostDb}dB" else ""
            onStateChanged(true, false, "IA Gemini (${settings.naturalVoiceName})$boostSuffix")
        }
    }

    override fun shutdown() {
        stop()
        releaseLoudnessEnhancer()
        fallbackLocalEngine.shutdown()
        scope.cancel()
    }
}
