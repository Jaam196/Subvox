package com.example.tts

import com.example.domain.model.AppSettings
import com.example.domain.model.CharacterVoiceMode
import com.example.domain.model.DesignedVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoiceDesignTest {

    @Test
    fun testDesignedVoiceSerializationRoundtrip() {
        val sampleBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x01, 0x02, 0x03)
        val sampleBase64 = android.util.Base64.encodeToString(sampleBytes, android.util.Base64.NO_WRAP)
        val voice = DesignedVoice(
            id = "voice_custom_anime_girl_01",
            name = "Chica Anime Dulce",
            description = "Voz femenina dulce y alegre de protagonista de anime",
            sampleAudioPcmBase64 = sampleBase64,
            sampleRateHz = 24000,
            createdAt = 1700000000000L
        )

        val jsonObj = voice.toJson()
        val restored = DesignedVoice.fromJson(jsonObj)

        assertNotNull(restored)
        assertEquals(voice.voiceId, restored.voiceId)
        assertEquals(voice.name, restored.name)
        assertEquals(voice.description, restored.description)
        assertEquals(voice.sampleRateHz, restored.sampleRateHz)
        assertEquals(voice.createdAtEpochMs, restored.createdAtEpochMs)
        assertEquals(voice.samplePcmAudio.size, restored.samplePcmAudio.size)
    }

    @Test
    fun testDesignedVoiceListJsonRoundtrip() {
        val list = listOf(
            DesignedVoice(
                id = "voice_1",
                name = "Voz 1",
                description = "Desc 1",
                sampleAudioPcmBase64 = android.util.Base64.encodeToString(byteArrayOf(1, 2, 3), android.util.Base64.NO_WRAP),
                sampleRateHz = 24000,
                createdAt = 1000L
            ),
            DesignedVoice(
                id = "voice_2",
                name = "Voz 2",
                description = "Desc 2",
                sampleAudioPcmBase64 = android.util.Base64.encodeToString(byteArrayOf(4, 5, 6), android.util.Base64.NO_WRAP),
                sampleRateHz = 24000,
                createdAt = 2000L
            )
        )

        val jsonString = DesignedVoice.listToJsonString(list)
        assertTrue(jsonString.contains("voice_1"))
        assertTrue(jsonString.contains("voice_2"))

        val restoredList = DesignedVoice.listFromJsonString(jsonString)
        assertEquals(2, restoredList.size)
        assertEquals("Voz 1", restoredList[0].name)
        assertEquals("Voz 2", restoredList[1].name)
    }

    @Test
    fun testPredefinedGeminiVoicesCatalogHas8VoicesWithGender() {
        val voices = GeminiNaturalVoiceOption.entries
        assertEquals(8, voices.size)

        val voiceIds = voices.map { it.voiceId }
        assertTrue(voiceIds.contains("Kore"))
        assertTrue(voiceIds.contains("Puck"))
        assertTrue(voiceIds.contains("Aoede"))
        assertTrue(voiceIds.contains("Charon"))
        assertTrue(voiceIds.contains("Fenrir"))
        assertTrue(voiceIds.contains("Leda"))
        assertTrue(voiceIds.contains("Orus"))
        assertTrue(voiceIds.contains("Zephyr"))

        voices.forEach { voice ->
            assertTrue("Voice ${voice.voiceId} should have non-empty gender", voice.gender.isNotBlank())
            assertTrue(
                "Voice ${voice.voiceId} gender should be Femenina or Masculina",
                voice.gender == "Femenina" || voice.gender == "Masculina"
            )
            assertTrue("Voice ${voice.voiceId} should have description", voice.description.isNotBlank())
        }
    }

    @Test
    fun testVoiceDesignClientPayloadBuilder() {
        val client = VoiceDesignClient()
        val payload = client.buildCreateVoicePayload(
            description = "Voz masculina calmada e intimidante",
            sampleText = "Yo soy el rey de las sombras."
        )

        assertTrue(payload.contains("voice_config"))
        assertTrue(payload.contains("Voz masculina calmada e intimidante"))
        assertTrue(payload.contains("Yo soy el rey de las sombras."))
    }

    @Test
    fun testParseCreateVoiceResponseSuccess() {
        val client = VoiceDesignClient()
        val dummyBase64 = android.util.Base64.encodeToString(
            byteArrayOf(0x00, 0x01, 0x02, 0x03),
            android.util.Base64.NO_WRAP
        )

        val mockResponseBody = """
            {
                "name": "voices/voice_xyz_12345",
                "voice_id": "voice_xyz_12345",
                "sample_audio": {
                    "audio_content": "$dummyBase64"
                }
            }
        """.trimIndent()

        val result = client.parseCreateVoiceResponse(
            responseBody = mockResponseBody,
            statusCode = 200,
            givenName = "Mi voz de prueba",
            description = "Prompt de prueba",
            sampleText = "Frase de prueba"
        )

        assertTrue(result is DesignedVoiceResult.Success)
        val success = result as DesignedVoiceResult.Success
        assertEquals("voice_xyz_12345", success.voice.voiceId)
        assertEquals("Mi voz de prueba", success.voice.name)
        assertEquals("Prompt de prueba", success.voice.description)
        assertTrue(success.voice.samplePcmAudio.isNotEmpty())
    }

    @Test
    fun testParseCreateVoiceResponseError() {
        val client = VoiceDesignClient()
        val mockErrorBody = """
            {
                "error": {
                    "code": 403,
                    "message": "API key not valid. Please pass a valid API key.",
                    "status": "PERMISSION_DENIED"
                }
            }
        """.trimIndent()

        val result = client.parseCreateVoiceResponse(
            responseBody = mockErrorBody,
            statusCode = 403,
            givenName = "Mi voz",
            description = "Prompt",
            sampleText = "Frase"
        )

        assertTrue(result is DesignedVoiceResult.Error)
        val error = result as DesignedVoiceResult.Error
        assertEquals(403, error.httpCode)
        assertTrue(error.message.contains("403"))
    }

    @Test
    fun testAppSettingsCharacterVoiceDefaults() {
        val settings = AppSettings()
        assertEquals("", settings.characterVoiceAId)
        assertEquals("", settings.characterVoiceBId)
        assertEquals("", settings.characterVoiceCId)
        assertTrue(settings.designedVoices.isEmpty())
    }
}
