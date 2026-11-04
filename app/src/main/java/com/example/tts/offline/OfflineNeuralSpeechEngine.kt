package com.example.tts.offline

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.LoudnessEnhancer
import com.example.domain.model.AppSettings
import com.example.domain.model.NewSubtitleBehavior
import com.example.tts.LocalSpeechEngine
import com.example.tts.NaturalSpeechProsodyCleaner
import com.example.tts.ProsodyPlanner
import com.example.tts.SpeechEngine
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Motor de síntesis de voz neuronal local y 100 % offline basado en Sherpa-ONNX
 * con modelos Piper VITS en español.
 *
 * Mejoras v2 respecto a la versión anterior:
 * - Parámetros VITS recomendados por Piper (noise 0.667 / noise-w 0.8). Los valores anteriores
 *   (0.333 / 0.45) aplanaban la entonación y daban el timbre "robótico".
 * - Reproducción en STREAMING: el audio empieza a sonar en cuanto se sintetiza la primera frase,
 *   sin esperar a la frase completa.
 * - Velocidad adaptativa ("alcanzar"): si los subtítulos siguen cortando la voz, acelera hasta un
 *   30 % de forma suave; cuando va holgada, vuelve a la velocidad normal.
 * - Carga del modelo protegida con Mutex (antes podía cargarse dos veces) y síntesis serializada.
 * - Caché persistente en disco indexada por (texto + voz + velocidad).
 */
class OfflineNeuralSpeechEngine(
    private val context: Context,
    private val modelManager: OfflineVoiceModelManager,
    private val fallbackLocalEngine: LocalSpeechEngine,
    private val cache: OfflineSpeechCache,
    private val onStateChanged: (ready: Boolean, speaking: Boolean, voiceDesc: String) -> Unit,
    private val onFallbackNoticeChanged: (notice: String) -> Unit,
    private val onCacheSizeUpdated: (bytes: Long) -> Unit,
    /** false = el modelo solo se carga cuando hace falta (p. ej. el motor de vista previa de Ajustes). */
    private val preloadModel: Boolean = true
) : SpeechEngine {

    companion object {
        private const val STREAM_CHUNK_BYTES = 4096
        private const val MAX_CONSECUTIVE_SLOW_SYNTHESIS = 3

        // Valores por defecto de Piper (están en el .onnx.json de cada voz)
        private const val VITS_NOISE_SCALE = 0.667f
        private const val VITS_NOISE_SCALE_W = 0.8f
        private const val VITS_SILENCE_SCALE = 0.2f

        private const val MAX_CATCH_UP = 0.30f
        private const val CATCH_UP_STEP_UP = 0.06f
        private const val CATCH_UP_STEP_DOWN = 0.05f
    }

    private data class QueuedItem(
        val text: String,
        val forceSpeakEvenIfDisabled: Boolean,
        val forceOfflineTry: Boolean = false,
        val pitchOverride: Float?,
        val characterLabel: String
    )

    private val engineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
                com.example.diagnostics.CrashReporter.record(context, "Error en el motor de voz neuronal", t)
                onFallbackNoticeChanged("Error de voz neuronal: ${t.javaClass.simpleName}: ${t.message}")
            }
    )

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var audioSessionId: Int = AudioManager.ERROR

    private val speechAudioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var audioFocusRequest: AudioFocusRequest? = null

    @Volatile
    private var currentSettings = AppSettings()

    @Volatile
    private var activeTtsInstance: OfflineTts? = null

    @Volatile
    private var loadedModelId: String = ""

    @Volatile
    private var loadedThreads: Int = 2

    @Volatile
    private var currentlySpeaking: Boolean = false

    @Volatile
    private var currentlySpeakingText: String = ""

    override val isSpeaking: Boolean
        get() = currentlySpeaking || fallbackLocalEngine.isSpeaking

    private val pendingQueue = ConcurrentLinkedQueue<QueuedItem>()
    private var queueWorkerJob: Job? = null

    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    private val prefetchedAudioMap = ConcurrentHashMap<String, Deferred<Pair<ByteArray, Int>>>()
    private var consecutiveSlowCount = 0

    private val loadMutex = Mutex()
    private val synthMutex = Mutex()

    /** 0..MAX_CATCH_UP: cuánto se acelera la voz porque los subtítulos la están alcanzando. */
    @Volatile
    private var catchUpLevel = 0f

    /** Caracteres por segundo de audio a velocidad 1.0 (media móvil, se ajusta sola con cada frase). */
    @Volatile
    private var charsPerSecondAtUnitSpeed = 14.0f

    init {
        initializeAudioSession()
        onCacheSizeUpdated(cache.getUsedSizeBytes())
    }

    private fun initializeAudioSession() {
        runCatching {
            audioSessionId = audioManager.generateAudioSessionId()
            if (audioSessionId != AudioManager.ERROR && audioSessionId > 0) {
                loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                    val boostDb = currentSettings.volumeBoostDb.coerceIn(0, 15)
                    if (boostDb > 0) {
                        setTargetGain(boostDb * 100)
                        enabled = true
                    } else {
                        enabled = false
                    }
                }
            }
        }
    }

    private fun updateLoudnessBoost(boostDb: Int) {
        runCatching {
            loudnessEnhancer?.let { enhancer ->
                if (boostDb > 0) {
                    enhancer.setTargetGain(boostDb.coerceIn(0, 15) * 100)
                    enhancer.enabled = true
                } else {
                    enhancer.setTargetGain(0)
                    enhancer.enabled = false
                }
            }
        }
    }

    private fun requestDuckingAudioFocus() {
        runCatching {
            if (audioFocusRequest == null) {
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(speechAudioAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { /* no-op */ }
                    .build()
            }
            audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
        }
    }

    private fun abandonDuckingAudioFocus() {
        runCatching {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        }
    }

    override fun applySettings(settings: AppSettings) {
        currentSettings = settings
        fallbackLocalEngine.applySettings(settings)

        updateLoudnessBoost(settings.volumeBoostDb)

        val targetModelId = settings.offlineVoiceSelectedId
        val targetThreads = settings.offlineVoiceThreads.coerceIn(2, 4)

        if (settings.offlineVoiceEnabled && modelManager.isModelInstalled(targetModelId)) {
            if (preloadModel &&
                (activeTtsInstance == null || loadedModelId != targetModelId || loadedThreads != targetThreads)
            ) {
                engineScope.launch { ensureTts(targetModelId, targetThreads) }
            }
        } else {
            if (activeTtsInstance != null) {
                releaseTtsInstance()
            }
        }
    }

    /**
     * Devuelve la instancia de TTS lista, cargando el modelo si hace falta.
     * Protegido con Mutex: nunca se carga el mismo modelo dos veces a la vez.
     */
    private suspend fun ensureTts(modelId: String, threads: Int): OfflineTts? = loadMutex.withLock {
        val existing = activeTtsInstance
        if (existing != null && loadedModelId == modelId && loadedThreads == threads) {
            return@withLock existing
        }

        val spec = OfflineVoiceCatalog.getModelById(modelId)
        if (!OfflineVoiceCatalog.isModelReady(context, spec)) {
            return@withLock null
        }

        withContext(Dispatchers.Default) {
            try {
                val modelFile = OfflineVoiceCatalog.getModelFile(context, spec)
                val tokensFile = OfflineVoiceCatalog.getTokensFile(context, spec)
                val dataDir = OfflineVoiceCatalog.getDataDir(context, spec)

                val vitsConfig = OfflineTtsVitsModelConfig(
                    model = modelFile.absolutePath,
                    lexicon = "",
                    tokens = tokensFile.absolutePath,
                    dataDir = dataDir.absolutePath,
                    dictDir = "",
                    noiseScale = VITS_NOISE_SCALE,
                    noiseScaleW = VITS_NOISE_SCALE_W,
                    lengthScale = 1.0f
                )

                val modelConfig = OfflineTtsModelConfig(
                    vits = vitsConfig,
                    numThreads = threads,
                    debug = false,
                    provider = "cpu"
                )

                val ttsConfig = OfflineTtsConfig(
                    model = modelConfig,
                    ruleFsts = "",
                    ruleFars = "",
                    maxNumSentences = 1,
                    silenceScale = VITS_SILENCE_SCALE
                )

                // Si el código nativo mata la app durante la carga, este archivo queda en disco y al
                // reabrir la voz se retira sola (en vez de cerrar la app una y otra vez).
                OfflineVoiceCatalog.beginLoadGuard(context, spec.id)
                val newInstance = try {
                    OfflineTts(assetManager = null, config = ttsConfig)
                } finally {
                    OfflineVoiceCatalog.endLoadGuard(context, spec.id)
                }

                // Sustituir la instancia anterior solo cuando no haya una síntesis en curso usándola
                synthMutex.withLock {
                    releaseTtsInstanceLocked()
                    activeTtsInstance = newInstance
                    loadedModelId = modelId
                    loadedThreads = threads
                }

                onStateChanged(true, false, "Voz Neuronal Offline (${spec.displayName})")
                newInstance
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Incluye UnsatisfiedLinkError / OutOfMemoryError, que no son "Exception"
                com.example.diagnostics.CrashReporter.record(context, "No se pudo cargar la voz ${spec.id}", e)
                synthMutex.withLock { releaseTtsInstanceLocked() }
                onFallbackNoticeChanged("No se pudo cargar el modelo neuronal offline: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
    }

    private fun releaseTtsInstance() {
        engineScope.launch {
            loadMutex.withLock {
                synthMutex.withLock { releaseTtsInstanceLocked() }
            }
        }
    }

    private fun releaseTtsInstanceLocked() {
        runCatching {
            activeTtsInstance?.free()
        }
        activeTtsInstance = null
        loadedModelId = ""
    }

    override fun speak(
        text: String,
        forceSpeakEvenIfDisabled: Boolean,
        pitchOverride: Float?,
        characterLabel: String
    ) {
        val cleaned = NaturalSpeechProsodyCleaner.cleanForSpeech(text)
        val trimmed = cleaned.ifBlank { text.trim() }
        if (trimmed.isEmpty()) return

        val isOfflineActive = currentSettings.offlineVoiceEnabled &&
                modelManager.isModelInstalled(currentSettings.offlineVoiceSelectedId)

        val modulatedPitch = pitchOverride ?: NaturalSpeechProsodyCleaner
            .calculateEmotionalPitchModulation(text, currentSettings.pitch)

        // Si está en ahorro de batería o no está listo el modelo, respaldo a voz local
        if (currentSettings.batterySaverMode && !forceSpeakEvenIfDisabled) {
            fallbackLocalEngine.speak(trimmed, forceSpeakEvenIfDisabled, modulatedPitch, characterLabel)
            return
        }

        if (!isOfflineActive && !forceSpeakEvenIfDisabled) {
            fallbackLocalEngine.speak(trimmed, forceSpeakEvenIfDisabled, modulatedPitch, characterLabel)
            return
        }

        // PROTECCIÓN ANTI-REINICIO: si ya se está leyendo esta misma frase (o casi idéntica), no reiniciar.
        if (currentlySpeaking && currentlySpeakingText.isNotEmpty()) {
            val keySpeaking = com.example.domain.pipeline.SubtitleNormalizer.buildComparisonKey(currentlySpeakingText)
            val keyNew = com.example.domain.pipeline.SubtitleNormalizer.buildComparisonKey(trimmed)
            if (keySpeaking.isNotEmpty() && keyNew.isNotEmpty()) {
                if (keySpeaking == keyNew ||
                    keySpeaking.contains(keyNew) || keyNew.contains(keySpeaking) ||
                    com.example.domain.pipeline.TextSimilarityCalculator.computeSimilarity(keySpeaking, keyNew) >= 0.70f
                ) {
                    return
                }
            }
        }

        val item = QueuedItem(
            text = trimmed,
            forceSpeakEvenIfDisabled = forceSpeakEvenIfDisabled,
            forceOfflineTry = false,
            pitchOverride = modulatedPitch,
            characterLabel = characterLabel
        )

        when (currentSettings.newSubtitleBehavior) {
            NewSubtitleBehavior.INTERRUPT_AND_READ_NEW -> {
                if (currentlySpeaking) {
                    // La voz todavía no había terminado y llega otro subtítulo: vamos justos de tiempo
                    catchUpLevel = min(MAX_CATCH_UP, catchUpLevel + CATCH_UP_STEP_UP)
                }
                cancelCurrentPlayback()
                pendingQueue.clear()
                prefetchedAudioMap.clear()
                fallbackLocalEngine.stop()
                pendingQueue.offer(item)
                ensureQueueWorkerRunning()
            }
            NewSubtitleBehavior.FINISH_CURRENT_QUEUE -> {
                if (currentlySpeaking || pendingQueue.isNotEmpty()) {
                    catchUpLevel = min(MAX_CATCH_UP, catchUpLevel + CATCH_UP_STEP_UP)
                }
                pendingQueue.offer(item)
                triggerPreSynthesis(item)
                ensureQueueWorkerRunning()
            }
        }
    }

    fun speakOfflinePreview(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val item = QueuedItem(
            text = trimmed,
            forceSpeakEvenIfDisabled = true,
            forceOfflineTry = true,
            pitchOverride = null,
            characterLabel = "Prueba Offline"
        )
        cancelCurrentPlayback()
        pendingQueue.clear()
        prefetchedAudioMap.clear()
        fallbackLocalEngine.stop()
        pendingQueue.offer(item)
        ensureQueueWorkerRunning()
    }

    private fun ensureQueueWorkerRunning() {
        if (queueWorkerJob?.isActive == true) return
        queueWorkerJob = engineScope.launch {
            while (isActive) {
                val nextItem = pendingQueue.poll()
                if (nextItem == null) {
                    currentlySpeaking = false
                    abandonDuckingAudioFocus()
                    break
                }

                // Pre-sintetizar la línea siguiente en segundo plano mientras suena esta
                val upcoming = pendingQueue.peek()
                if (upcoming != null) {
                    triggerPreSynthesis(upcoming)
                }

                processItem(nextItem)
            }
        }
    }

    /**
     * Velocidad final de síntesis = velocidad elegida × aceleración adaptativa (si está activada).
     * Se cuantiza en pasos de 0,05 para que la caché pueda reutilizar audios.
     */
    private fun effectiveSpeed(text: String): Float {
        val settings = currentSettings
        val base = settings.offlineVoiceSpeed.coerceIn(0.65f, 1.50f)
        if (!settings.catchUpSpeedEnabled) return quantizeSpeed(base)

        // Frase larga: calcular si cabe en el tiempo que el subtítulo suele estar en pantalla
        val chars = max(1, text.length).toFloat()
        val naturalSec = chars / (charsPerSecondAtUnitSpeed * base)
        val displaySec = max(1.3f, chars / 17f + 0.7f)
        val lengthBoost = if (naturalSec > displaySec) {
            min(1.25f, naturalSec / displaySec)
        } else {
            1f
        }

        val boost = max(lengthBoost, 1f + catchUpLevel).coerceAtMost(1f + MAX_CATCH_UP)
        return quantizeSpeed((base * boost).coerceIn(0.65f, 1.85f))
    }

    private fun quantizeSpeed(speed: Float): Float = (speed * 20f).roundToInt() / 20f

    private suspend fun processItem(item: QueuedItem) {
        val spec = OfflineVoiceCatalog.getModelById(currentSettings.offlineVoiceSelectedId)
        val speed = effectiveSpeed(item.text)

        currentlySpeakingText = item.text

        // 1. Caché en disco
        val cached = cache.get(item.text, spec.id, speed)
        if (cached != null) {
            val (pcmBytes, sampleRate) = cached
            playPcm(pcmBytes, sampleRate, spec.displayName, currentSettings)
            return
        }

        // 2. Audio ya sintetizado por anticipado
        val prefetched = prefetchedAudioMap.remove(item.text)
        if (prefetched != null) {
            val data = runCatching { prefetched.await() }.getOrNull()
            if (data != null && data.first.isNotEmpty()) {
                playPcm(data.first, data.second, spec.displayName, currentSettings)
                return
            }
        }

        // 3. Síntesis + reproducción en streaming (empieza a sonar con la primera frase)
        val played = streamSynthesisAndPlay(item.text, spec, speed, currentSettings)
        if (!played) {
            currentlySpeakingText = ""
            // Caída suave y transparente al motor local
            fallbackLocalEngine.speak(
                text = item.text,
                forceSpeakEvenIfDisabled = item.forceSpeakEvenIfDisabled,
                pitchOverride = item.pitchOverride,
                characterLabel = item.characterLabel
            )
        }
    }

    private fun triggerPreSynthesis(item: QueuedItem) {
        val spec = OfflineVoiceCatalog.getModelById(currentSettings.offlineVoiceSelectedId)
        val speed = effectiveSpeed(item.text)
        if (cache.get(item.text, spec.id, speed) != null) return
        if (prefetchedAudioMap.containsKey(item.text)) return

        val deferred = engineScope.async(Dispatchers.Default) {
            synthesizeToPcm(item.text, spec.id, speed) ?: (ByteArray(0) to 22050)
        }
        prefetchedAudioMap[item.text] = deferred
    }

    private fun silencePcm(ms: Int, sampleRate: Int): ByteArray =
        ByteArray(((sampleRate.toLong() * ms / 1000L).toInt()).coerceAtLeast(0) * 2)

    private fun floatsToPcm16(
        samples: FloatArray,
        fadeInSamples: Int,
        fadeOutSamples: Int,
        gain: Float = 1f
    ): ByteArray {
        val n = samples.size
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            var f = (samples[i] * gain).coerceIn(-1.0f, 1.0f)
            if (fadeInSamples > 0 && i < fadeInSamples) {
                f *= i.toFloat() / fadeInSamples.toFloat()
            }
            if (fadeOutSamples > 0 && i > n - fadeOutSamples) {
                f *= (n - i).toFloat() / fadeOutSamples.toFloat()
            }
            val s = (f * 32767.0f).toInt()
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun registerSynthesisStats(text: String, speed: Float, synthMs: Long, audioMs: Long) {
        if (audioMs <= 0L) return

        // Ajustar la estimación de velocidad de habla de la voz (caracteres por segundo a velocidad 1.0)
        val measured = text.length / ((audioMs / 1000f) * speed)
        if (measured in 4f..40f) {
            charsPerSecondAtUnitSpeed = charsPerSecondAtUnitSpeed * 0.7f + measured * 0.3f
        }

        // Control de Real-Time Factor (RTF)
        if (audioMs > 400 && synthMs > audioMs) {
            consecutiveSlowCount++
            if (consecutiveSlowCount >= MAX_CONSECUTIVE_SLOW_SYNTHESIS) {
                onFallbackNoticeChanged("Voz offline lenta en este dispositivo. Prueba una voz de calidad media o menos hilos.")
            }
        } else {
            consecutiveSlowCount = 0
        }
    }

    /** Síntesis completa (sin reproducir). Se usa para la anticipación de la siguiente línea en cola. */
    private suspend fun synthesizeToPcm(text: String, voiceId: String, speed: Float): Pair<ByteArray, Int>? {
        ensureTts(voiceId, currentSettings.offlineVoiceThreads.coerceIn(2, 4)) ?: return null
        return withContext(Dispatchers.Default) {
            synthMutex.withLock {
                // Instancia viva bajo el cerrojo: nadie puede liberarla mientras se usa
                val live = activeTtsInstance ?: return@withLock null
                val startTime = System.currentTimeMillis()
                val sampleRate = live.sampleRate().takeIf { it > 0 } ?: 22050
                OfflineVoiceCatalog.beginLoadGuard(context, voiceId, "síntesis de «${text.take(40)}»")
                val fade = (sampleRate * 0.004f).toInt().coerceIn(8, 160)
                val out = ByteArrayOutputStream()
                var totalSamples = 0
                for (seg in ProsodyPlanner.plan(text)) {
                    val generated = runCatching {
                        live.generate(text = seg.text, sid = 0, speed = (speed * seg.speedFactor).coerceIn(0.5f, 2.2f))
                    }.getOrNull() ?: continue
                    if (generated.samples.isEmpty()) continue
                    if (seg.pauseBeforeMs > 0 && totalSamples > 0) out.write(silencePcm(seg.pauseBeforeMs, sampleRate))
                    val segPcm = floatsToPcm16(generated.samples, fade, fade, seg.gain)
                    out.write(segPcm, 0, segPcm.size)
                    totalSamples += generated.samples.size
                    if (seg.pauseAfterMs > 0) out.write(silencePcm(seg.pauseAfterMs, sampleRate))
                }
                OfflineVoiceCatalog.endLoadGuard(context, voiceId)
                if (totalSamples == 0) return@withLock null

                val synthMs = System.currentTimeMillis() - startTime
                val audioMs = ((totalSamples.toDouble() / sampleRate.toDouble()) * 1000.0).toLong()
                registerSynthesisStats(text, speed, synthMs, audioMs)

                val pcm = out.toByteArray()

                cache.put(text, voiceId, speed, sampleRate, pcm, currentSettings.offlineCacheMaxMb)
                onCacheSizeUpdated(cache.getUsedSizeBytes())
                pcm to sampleRate
            }
        }
    }

    private fun buildAudioTrack(sampleRateHz: Int): AudioTrack? {
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
        return runCatching { trackBuilder.build() }.getOrNull()
    }

    /**
     * Sintetiza y reproduce al mismo tiempo. Devuelve false si no se pudo reproducir NADA
     * (para que el llamador use la voz local de respaldo).
     */
    private suspend fun streamSynthesisAndPlay(
        text: String,
        spec: OfflineVoiceModelSpec,
        speed: Float,
        settings: AppSettings
    ): Boolean {
        ensureTts(spec.id, settings.offlineVoiceThreads.coerceIn(2, 4)) ?: return false

        return withContext(Dispatchers.Default) {
            synthMutex.withLock {
                val live = activeTtsInstance ?: return@withLock false
                val sampleRate = live.sampleRate().takeIf { it > 0 } ?: 22050
                val track = buildAudioTrack(sampleRate) ?: return@withLock false

                val boostSuffix = if (settings.volumeBoostDb > 0) " +${settings.volumeBoostDb}dB" else ""
                val voiceSummary = "Offline Piper (${spec.displayName})$boostSuffix"

                updateLoudnessBoost(settings.volumeBoostDb)
                if (settings.duckOtherAudio) {
                    requestDuckingAudioFocus()
                }

                activeAudioTrack = track
                val pcmCollector = ByteArrayOutputStream()
                var totalSamples = 0
                var aborted = false
                var startedPlaying = false
                val startTime = System.currentTimeMillis()
                val fade = (sampleRate * 0.004f).toInt().coerceIn(8, 160)

                try {
                    track.setVolume(settings.volume.coerceIn(0f, 1f))
                    currentlySpeaking = true
                    onStateChanged(true, true, voiceSummary)
                    track.play()

                    // Escribe PCM en la pista; false si se interrumpió la reproducción
                    fun writeToTrack(pcm: ByteArray): Boolean {
                        var offset = 0
                        while (offset < pcm.size) {
                            if (activeAudioTrack !== track) return false
                            val toWrite = min(STREAM_CHUNK_BYTES, pcm.size - offset)
                            val written = track.write(pcm, offset, toWrite, AudioTrack.WRITE_BLOCKING)
                            if (written <= 0) return false
                            offset += written
                        }
                        return true
                    }

                    OfflineVoiceCatalog.beginLoadGuard(context, spec.id, "síntesis en streaming de «${text.take(40)}»")
                    for (seg in ProsodyPlanner.plan(text)) {
                        if (aborted) break
                        if (seg.pauseBeforeMs > 0 && totalSamples > 0) {
                            val gap = silencePcm(seg.pauseBeforeMs, sampleRate)
                            if (!writeToTrack(gap)) { aborted = true; break }
                            totalSamples += gap.size / 2
                            pcmCollector.write(gap, 0, gap.size)
                        }
                        // Se usa generate() (sin callback nativo): el callback JNI de generateWithCallback
                        // provocaba SIGABRT dentro de Sherpa en algunos móviles (Samsung, Android 17).
                        if (activeAudioTrack !== track) { aborted = true; break }
                        val samples = live.generate(
                            text = seg.text,
                            sid = 0,
                            speed = (speed * seg.speedFactor).coerceIn(0.5f, 2.2f)
                        ).samples
                        if (samples.isEmpty()) continue
                        val pcm = floatsToPcm16(
                            samples,
                            fadeInSamples = fade,
                            fadeOutSamples = 0,
                            gain = seg.gain
                        )
                        if (!writeToTrack(pcm)) { aborted = true; break }
                        startedPlaying = true
                        totalSamples += samples.size
                        pcmCollector.write(pcm, 0, pcm.size)
                        if (!aborted && seg.pauseAfterMs > 0) {
                            val gap = silencePcm(seg.pauseAfterMs, sampleRate)
                            if (!writeToTrack(gap)) { aborted = true; break }
                            totalSamples += gap.size / 2
                            pcmCollector.write(gap, 0, gap.size)
                        }
                    }

                    OfflineVoiceCatalog.endLoadGuard(context, spec.id)
                    val synthMs = System.currentTimeMillis() - startTime

                    if (!aborted && totalSamples > 0) {
                        val audioMs = ((totalSamples.toDouble() / sampleRate.toDouble()) * 1000.0).toLong()
                        registerSynthesisStats(text, speed, synthMs, audioMs)

                        // Esperar a que el búfer termine de sonar para no cortar la última palabra
                        var attempts = 0
                        while (activeAudioTrack === track &&
                            track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                            attempts < 80
                        ) {
                            val head = runCatching { track.playbackHeadPosition }.getOrDefault(0)
                            if (head >= totalSamples - (sampleRate * 0.04f)) break
                            delay(30)
                            attempts++
                        }
                        delay(50)

                        if (activeAudioTrack === track) {
                            // La frase se leyó entera antes de que llegara otra: vamos holgados
                            catchUpLevel = max(0f, catchUpLevel - CATCH_UP_STEP_DOWN)
                            val bytes = pcmCollector.toByteArray()
                            cache.put(text, spec.id, speed, sampleRate, bytes, settings.offlineCacheMaxMb)
                            onCacheSizeUpdated(cache.getUsedSizeBytes())
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    OfflineVoiceCatalog.endLoadGuard(context, spec.id)
                    throw e
                } catch (t: Throwable) {
                    OfflineVoiceCatalog.endLoadGuard(context, spec.id)
                    com.example.diagnostics.CrashReporter.record(context, "Fallo al sintetizar con la voz neuronal", t)
                    onFallbackNoticeChanged("Fallo de la voz neuronal: ${t.javaClass.simpleName}: ${t.message}")
                } finally {
                    runCatching { track.stop() }
                    runCatching { track.release() }
                    if (activeAudioTrack === track) {
                        activeAudioTrack = null
                    }
                    currentlySpeakingText = ""
                    currentlySpeaking = false
                }

                // true si llegó a sonar algo (aunque se interrumpiera) para no duplicar con la voz local
                startedPlaying || aborted
            }
        }
    }

    private suspend fun playPcm(
        pcmBytes: ByteArray,
        sampleRateHz: Int,
        voiceName: String,
        settings: AppSettings
    ) = withContext(Dispatchers.IO) {
        if (pcmBytes.isEmpty()) return@withContext

        updateLoudnessBoost(settings.volumeBoostDb)
        if (settings.duckOtherAudio) {
            requestDuckingAudioFocus()
        }

        val track = buildAudioTrack(sampleRateHz) ?: return@withContext
        activeAudioTrack = track

        val boostSuffix = if (settings.volumeBoostDb > 0) " +${settings.volumeBoostDb}dB" else ""
        val voiceSummary = "Offline Piper ($voiceName)$boostSuffix"

        try {
            track.setVolume(settings.volume.coerceIn(0f, 1f))
            currentlySpeaking = true
            onStateChanged(true, true, voiceSummary)
            track.play()

            var offset = 0
            val alignedLength = pcmBytes.size - (pcmBytes.size % 2)
            while (offset < alignedLength && activeAudioTrack === track) {
                val bytesToWrite = min(STREAM_CHUNK_BYTES, alignedLength - offset)
                val written = track.write(pcmBytes, offset, bytesToWrite, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }

            // Esperar a que el búfer de AudioTrack termine de drenar hacia los altavoces
            if (activeAudioTrack === track && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                val totalFrames = alignedLength / 2
                var attempts = 0
                while (activeAudioTrack === track && track.playState == AudioTrack.PLAYSTATE_PLAYING && attempts < 80) {
                    val head = runCatching { track.playbackHeadPosition }.getOrDefault(0)
                    if (head >= totalFrames - (sampleRateHz * 0.04f)) {
                        break
                    }
                    delay(30)
                    attempts++
                }
                delay(50)
                if (activeAudioTrack === track) {
                    catchUpLevel = max(0f, catchUpLevel - CATCH_UP_STEP_DOWN)
                }
            }
        } catch (_: Exception) {
            // Ignorado al cancelar
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            if (activeAudioTrack === track) {
                activeAudioTrack = null
            }
            currentlySpeakingText = ""
            currentlySpeaking = false
        }
    }

    private fun cancelCurrentPlayback() {
        currentlySpeakingText = ""
        currentlySpeaking = false
        val track = activeAudioTrack
        activeAudioTrack = null
        runCatching {
            track?.pause()
            track?.flush()
            track?.stop()
        }
    }

    override fun stop() {
        currentlySpeaking = false
        pendingQueue.clear()
        prefetchedAudioMap.clear()
        cancelCurrentPlayback()
        fallbackLocalEngine.stop()
        abandonDuckingAudioFocus()
    }

    override fun shutdown() {
        stop()
        engineScope.cancel()
        runCatching { activeTtsInstance?.free() }
        activeTtsInstance = null
        loadedModelId = ""
        runCatching {
            loudnessEnhancer?.release()
        }
        loudnessEnhancer = null
        fallbackLocalEngine.shutdown()
    }
}
