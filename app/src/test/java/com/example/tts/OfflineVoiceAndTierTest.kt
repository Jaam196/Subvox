package com.example.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.AppSettings
import com.example.security.ApiKeyStore
import com.example.security.InsecureTestKeyProvider
import com.example.tts.offline.OfflineSpeechCache
import com.example.tts.offline.OfflineVoiceCatalog
import com.example.tts.offline.OfflineVoiceModelManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineVoiceAndTierTest {

    private lateinit var context: Context
    private lateinit var cache: OfflineSpeechCache

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        cache = OfflineSpeechCache(context)
        cache.clearCache()
    }

    @Test
    fun testOfflineVoiceCatalogHasRealVerifiedModelsAndHashes() {
        val candidates = OfflineVoiceCatalog.CANDIDATE_VOICES
        assertEquals(5, candidates.size)

        val female = OfflineVoiceCatalog.SHARVARD_FEMALE
        assertEquals("vits-piper-es_ES-sharvard-medium", female.id)
        assertEquals(80318184L, female.archiveSizeBytes)
        assertEquals("b30a7a83df0518f0ee1c7039506648cade99f1f9b498fc49ed2ced2e2536bb5a", female.sha256Hex)

        val latin = OfflineVoiceCatalog.LATIN_NEUTRAL
        assertEquals("vits-piper-es_MX-ald-medium", latin.id)
        assertEquals(67196497L, latin.archiveSizeBytes)
        assertEquals("c60cf7bc853eb01a875fba0756523fecf9d9f46c763b19785d223fc981fb4fb6", latin.sha256Hex)
        assertTrue(latin.archiveUrl.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"))

        val castilian = OfflineVoiceCatalog.SPAIN_CASTILIAN
        assertEquals("vits-piper-es_ES-davefx-medium", castilian.id)
        assertEquals(67184952L, castilian.archiveSizeBytes)
        assertEquals("a3f6beb54a9cb893279f72978a22f807a4d9fc9c7848157b524d5cc7b7f58b22", castilian.sha256Hex)
    }

    @Test
    fun testOfflineSpeechCachePutGetAndClear() {
        val testText = "Hola anime, esta es una prueba de subtítulos."
        val voiceId = "vits-piper-es_MX-ald-medium"
        val speed = 1.0f
        val sampleRate = 22050
        val dummyPcm = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80)

        // Inicialmente no está en caché
        assertNull(cache.get(testText, voiceId, speed))

        // Guardar en caché
        cache.put(testText, voiceId, speed, sampleRate, dummyPcm, maxCacheMb = 50)

        // Recuperar
        val result = cache.get(testText, voiceId, speed)
        assertNotNull(result)
        assertEquals(sampleRate, result!!.second)
        assertArrayEquals(dummyPcm, result.first)

        // Variar la velocidad produce clave distinta
        assertNull(cache.get(testText, voiceId, 1.25f))

        // Limpieza de caché
        cache.clearCache()
        assertNull(cache.get(testText, voiceId, speed))
    }

    @Test
    fun testThreeTierSpeechEnginePrioritySelection() {
        val testKeyProvider = InsecureTestKeyProvider()
        val apiKeyStore = ApiKeyStore(context, testKeyProvider)
        apiKeyStore.clearKey()

        val modelManager = OfflineVoiceModelManager(context)

        // Mock engines
        var lastSpokenEngine = ""

        val localMock = object : SpeechEngine {
            override val isSpeaking: Boolean = false
            override fun applySettings(settings: AppSettings) {}
            override fun speak(text: String, forceSpeakEvenIfDisabled: Boolean, pitchOverride: Float?, characterLabel: String) {
                lastSpokenEngine = "LOCAL"
            }
            override fun stop() {}
            override fun shutdown() {}
        }

        val offlineMock = object : SpeechEngine {
            override val isSpeaking: Boolean = false
            override fun applySettings(settings: AppSettings) {}
            override fun speak(text: String, forceSpeakEvenIfDisabled: Boolean, pitchOverride: Float?, characterLabel: String) {
                lastSpokenEngine = "OFFLINE"
            }
            override fun stop() {}
            override fun shutdown() {}
        }

        // 1. Caso por defecto: Sin clave y sin modelo offline -> LOCAL
        var settings = AppSettings(
            naturalVoiceEnabled = false,
            offlineVoiceEnabled = false
        )

        val localEngineWrapped = LocalSpeechEngine(SubtitleTtsManager(context, onStateChanged = { _, _, _ -> }, onSpanishVoiceMissing = {}))

        val threeTier = ThreeTierSpeechEngine(
            geminiEngine = NaturalSpeechEngine(
                context = context,
                apiKeyStore = apiKeyStore,
                fallbackLocalEngine = offlineMock,
                cache = NaturalSpeechCache(context),
                onStateChanged = { _, _, _ -> },
                onFallbackNoticeChanged = {},
                onCacheSizeUpdated = {}
            ),
            offlineEngine = com.example.tts.offline.OfflineNeuralSpeechEngine(
                context = context,
                modelManager = modelManager,
                fallbackLocalEngine = localEngineWrapped,
                cache = cache,
                onStateChanged = { _, _, _ -> },
                onFallbackNoticeChanged = {},
                onCacheSizeUpdated = {}
            ),
            localEngine = LocalSpeechEngine(SubtitleTtsManager(context, onStateChanged = { _, _, _ -> }, onSpanishVoiceMissing = {})),
            apiKeyStore = apiKeyStore,
            modelManager = modelManager
        )

        threeTier.applySettings(settings)
        assertEquals(ActiveSpeechTier.LOCAL_ANDROID, threeTier.currentTierState.value)

        // 2. Si el usuario activa Gemini pero no hay API key -> Cae a LOCAL (o Offline si estuviera)
        settings = settings.copy(naturalVoiceEnabled = true)
        threeTier.applySettings(settings)
        assertEquals(ActiveSpeechTier.LOCAL_ANDROID, threeTier.currentTierState.value)
        assertTrue(threeTier.tierReasonState.value.contains("falta API key"))

        // 3. Cuando el usuario añade API key y Gemini está activado -> Nivel 1 Gemini
        apiKeyStore.saveKey("AIzaSyFakeKeyValidFormat1234567890ABC")
        threeTier.applySettings(settings)
        assertEquals(ActiveSpeechTier.GEMINI_38, threeTier.currentTierState.value)
    }
}
