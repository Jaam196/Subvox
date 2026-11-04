package com.example.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.AppSettings
import com.example.security.ApiKeyStore
import com.example.security.ApiKeyValidationResult
import com.example.security.InsecureTestKeyProvider
import okhttp3.RequestBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Gemini38TtsTest {

    private lateinit var context: Context
    private lateinit var apiKeyStore: ApiKeyStore
    private lateinit var naturalSpeechEngine: NaturalSpeechEngine

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val keyProvider = InsecureTestKeyProvider()
        apiKeyStore = ApiKeyStore(context, keyProvider)
        apiKeyStore.saveKey("AIzaSyTestApiKeyForGemini38Models12345")

        val dummyLocal = object : SpeechEngine {
            override val isSpeaking: Boolean = false
            override fun applySettings(settings: AppSettings) {}
            override fun speak(text: String, forceSpeakEvenIfDisabled: Boolean, pitchOverride: Float?, characterLabel: String) {}
            override fun stop() {}
            override fun shutdown() {}
        }

        val cache = NaturalSpeechCache(context)
        naturalSpeechEngine = NaturalSpeechEngine(
            context = context,
            apiKeyStore = apiKeyStore,
            fallbackLocalEngine = dummyLocal,
            cache = cache,
            onStateChanged = { _, _, _ -> },
            onFallbackNoticeChanged = { _ -> },
            onCacheSizeUpdated = { _ -> }
        )
    }

    @Test
    fun testModelNameValidation() {
        assertTrue(NaturalSpeechEngine.isValidModelName("gemini-3.8-flash-lite-tts"))
        assertTrue(NaturalSpeechEngine.isValidModelName("gemini-3.8-flash-tts"))
        assertTrue(NaturalSpeechEngine.isValidModelName("custom.model-3.8_v1"))

        // Inválidos: vacíos, con espacios o con barras
        assertFalse(NaturalSpeechEngine.isValidModelName(""))
        assertFalse(NaturalSpeechEngine.isValidModelName("   "))
        assertFalse(NaturalSpeechEngine.isValidModelName("gemini 3.8 flash"))
        assertFalse(NaturalSpeechEngine.isValidModelName("models/gemini-3.8-flash-tts"))
        assertFalse(NaturalSpeechEngine.isValidModelName("gemini/tts"))
        assertFalse(NaturalSpeechEngine.isValidModelName("invalid@char"))
    }

    @Test
    fun testRequestBodyContainsVerbatimTextAndSpeechMetadataSeparated() {
        val settings = AppSettings(
            naturalVoiceModelName = "gemini-3.8-flash-lite-tts",
            naturalVoiceInlineTagsEnabled = false
        )

        val rawSubtitle = "¿De verdad crees que vas a vencer al Rey Demonio? <short pause> ¡No me hagas reír!"
        val styleInstruction = "tono épico y desafiante"
        val speakerLabel = "Villano"

        val request = naturalSpeechEngine.buildGeminiSynthesisRequest(
            apiKey = "AIzaSyTestKey123",
            text = rawSubtitle,
            voiceName = "Charon",
            styleInstruction = styleInstruction,
            characterLabel = speakerLabel,
            settings = settings
        )

        // 1. API key via header, jamás en URL
        assertEquals("AIzaSyTestKey123", request.header("x-goog-api-key"))
        assertFalse(request.url.toString().contains("AIzaSyTestKey123"))
        assertTrue(request.url.toString().contains("gemini-3.8-flash-lite-tts:generateContent"))

        // 2. Extraer cuerpo JSON
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        val bodyJson = JSONObject(buffer.readUtf8())

        // 3. Inspeccionar contents -> parts
        val contents = bodyJson.getJSONArray("contents")
        val firstContent = contents.getJSONObject(0)
        val parts = firstContent.getJSONArray("parts")
        val part0 = parts.getJSONObject(0)

        // El texto debe ser SOLO el subtítulo verbatim (con etiquetas eliminadas si inlineTags está desactivado)
        val textSent = part0.getString("text")
        assertEquals("¿De verdad crees que vas a vencer al Rey Demonio? ¡No me hagas reír!", textSent)
        assertFalse(textSent.contains(styleInstruction))
        assertFalse(textSent.contains(speakerLabel))

        // El estilo y el speaker van en speech_metadata por parte
        assertTrue(part0.has("speech_metadata"))
        val speechMeta = part0.getJSONObject("speech_metadata")
        assertEquals(styleInstruction, speechMeta.getString("style"))
        assertEquals(speakerLabel, speechMeta.getString("speaker"))

        // 4. generationConfig solicita audio/l16
        val genConfig = bodyJson.getJSONObject("generationConfig")
        assertEquals("audio/l16", genConfig.getString("responseMimeType"))
        val voiceName = genConfig.getJSONObject("speechConfig")
            .getJSONObject("voiceConfig")
            .getJSONObject("prebuiltVoiceConfig")
            .getString("voiceName")
        assertEquals("Charon", voiceName)
    }

    @Test
    fun testInlineTagsPreservedWhenEnabled() {
        val settings = AppSettings(
            naturalVoiceModelName = "gemini-3.8-flash-tts",
            naturalVoiceInlineTagsEnabled = true
        )

        val textWithTags = "Espera... <short pause> ¿quién anda ahí?"
        val request = naturalSpeechEngine.buildGeminiSynthesisRequest(
            apiKey = "AIzaSyKey",
            text = textWithTags,
            voiceName = "Kore",
            styleInstruction = "",
            settings = settings
        )

        val buffer = Buffer()
        request.body?.writeTo(buffer)
        val bodyJson = JSONObject(buffer.readUtf8())

        val textSent = bodyJson.getJSONArray("contents")
            .getJSONObject(0)
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")

        assertEquals(textWithTags, textSent)
    }

    @Test
    fun testWavPcmExtractorStripsRiffHeaderAndReadsSampleRate() {
        // Construir un WAV sintético de 22050 Hz con cabecera RIFF válida
        val sampleRate = 22050
        val pcmPayload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        val dataChunkSize = pcmPayload.size
        val fmtChunkSize = 16
        val riffChunkSize = 4 + (8 + fmtChunkSize) + (8 + dataChunkSize)

        val wavBuffer = ByteBuffer.allocate(riffChunkSize + 8).order(ByteOrder.LITTLE_ENDIAN)
        // RIFF header
        wavBuffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        wavBuffer.putInt(riffChunkSize)
        wavBuffer.put("WAVE".toByteArray(Charsets.US_ASCII))

        // fmt chunk
        wavBuffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        wavBuffer.putInt(fmtChunkSize)
        wavBuffer.putShort(1.toShort()) // PCM format
        wavBuffer.putShort(1.toShort()) // Mono (1 channel)
        wavBuffer.putInt(sampleRate)     // 22050 Hz
        wavBuffer.putInt(sampleRate * 2) // Byte rate
        wavBuffer.putShort(2.toShort()) // Block align
        wavBuffer.putShort(16.toShort())// Bits per sample

        // data chunk
        wavBuffer.put("data".toByteArray(Charsets.US_ASCII))
        wavBuffer.putInt(dataChunkSize)
        wavBuffer.put(pcmPayload)

        val rawWavBytes = wavBuffer.array()

        // Extraer usando WavPcmExtractor
        val result = WavPcmExtractor.extractPcm(rawWavBytes, fallbackSampleRate = 24000)

        // Se extrajo la frecuencia exacta de la cabecera (22050) en vez de asumir 24000
        assertEquals(22050, result.sampleRateHz)
        assertEquals(1, result.channels)
        // Se quitó la cabecera RIFF completa y quedaron solo los bytes de audio PCM puros
        assertArrayEquals(pcmPayload, result.pcmBytes)
    }

    @Test
    fun testWavPcmExtractorWithRawPcmReturnsDataIntact() {
        val rawPcm = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80)
        val result = WavPcmExtractor.extractPcm(rawPcm, fallbackSampleRate = 24000)

        assertEquals(24000, result.sampleRateHz)
        assertArrayEquals(rawPcm, result.pcmBytes)
    }

    @Test
    fun testCacheKeyIncludesModelVoiceStyleAndFormat() {
        val text = "Subtítulo para doblaje"
        val key1 = NaturalSpeechCache.computeKeyHash(
            text = text,
            modelName = "gemini-3.8-flash-lite-tts",
            voiceName = "Kore",
            styleInstruction = "doblaje estándar",
            audioFormat = "audio/l16"
        )

        // Cambio de modelo altera la clave de caché
        val keyModelDifferent = NaturalSpeechCache.computeKeyHash(
            text = text,
            modelName = "gemini-3.8-flash-tts",
            voiceName = "Kore",
            styleInstruction = "doblaje estándar",
            audioFormat = "audio/l16"
        )
        assertNotEquals(key1, keyModelDifferent)

        // Cambio de formato altera la clave de caché
        val keyFormatDifferent = NaturalSpeechCache.computeKeyHash(
            text = text,
            modelName = "gemini-3.8-flash-lite-tts",
            voiceName = "Kore",
            styleInstruction = "doblaje estándar",
            audioFormat = "audio/pcm;rate=24000"
        )
        assertNotEquals(key1, keyFormatDifferent)

        // Claves idénticas con mismos parámetros
        val keyIdentical = NaturalSpeechCache.computeKeyHash(
            text = text,
            modelName = "gemini-3.8-flash-lite-tts",
            voiceName = "Kore",
            styleInstruction = "doblaje estándar",
            audioFormat = "audio/l16"
        )
        assertEquals(key1, keyIdentical)
    }
}
