package com.example.domain.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleDeduplicatorTest {

    private fun SubtitleDeduplicator.Decision.isSpeak() = this is SubtitleDeduplicator.Decision.SpeakNew

    @Test
    fun `un subtitulo nuevo se lee a la primera y no se repite mientras sigue en pantalla`() {
        val d = SubtitleDeduplicator()
        var t = 1_000_000L
        assertTrue(d.onFrameText("¡No puedo creer lo que estás diciendo!", t).isSpeak())
        t += 150
        assertFalse(d.onFrameText("¡No puedo creer lo que estás diciendo!", t).isSpeak())
    }

    @Test
    fun `una lectura parecida pero distinta del activo exige confirmacion`() {
        val d = SubtitleDeduplicator()
        var t = 1_000_000L
        d.onFrameText("¡No puedo creer lo que estás diciendo!", t)
        t += 150
        assertFalse(d.onFrameText("No puedo creer lo estás diciendo", t).isSpeak())
    }

    @Test
    fun `la misma frase tras desaparecer mas de 1,2 segundos se vuelve a leer`() {
        val d = SubtitleDeduplicator()
        var t = 1_000_000L
        d.onFrameText("Hola, ¿cómo estás hoy amigo?", t)
        repeat(4) {
            t += 120
            d.onFrameText("", t)
        }
        t += 2_000
        assertTrue(d.onFrameText("Hola, ¿cómo estás hoy amigo?", t).isSpeak())
    }

    @Test
    fun `un parpadeo breve del mismo subtitulo no se repite`() {
        val d = SubtitleDeduplicator()
        var t = 1_000_000L
        d.onFrameText("Tenemos que irnos de aquí ahora mismo", t)
        repeat(3) {
            t += 100
            d.onFrameText("", t)
        }
        t += 300
        assertFalse(d.onFrameText("Tenemos que irnos de aquí ahora mismo", t).isSpeak())
    }

    @Test
    fun `si el subtitulo se leyo incompleto la frase completa se lee entera`() {
        val d = SubtitleDeduplicator()
        var t = 1_000_000L
        d.onFrameText("No puedo", t)
        t += 200
        assertTrue(d.onFrameText("No puedo creer que esto esté pasando de verdad", t).isSpeak())
    }

    @Test
    fun `con confianza baja se exigen dos lecturas`() {
        val d = SubtitleDeduplicator()
        var t = 1_000_000L
        assertFalse(d.onFrameText("Esta es una frase de prueba larga", t, requiredStableFrames = 2).isSpeak())
        assertTrue(d.hasPendingCandidate)
        t += 150
        assertTrue(d.onFrameText("Esta es una frase de prueba larga", t, requiredStableFrames = 2).isSpeak())
    }

    @Test
    fun remainderOfExtendedPhrase() {
        val d = SubtitleDeduplicator()
        org.junit.Assert.assertEquals(
            "¿vienes conmigo?",
            d.remainderAfter("Oye, Naruto", "Oye, Naruto, ¿vienes conmigo?")
        )
        org.junit.Assert.assertNull(d.remainderAfter("Hola", "Adiós, hola"))
    }

    @Test
    fun extendedPhraseSpeaksOnlyNewPart() {
        val d = SubtitleDeduplicator(minStableFrames = 1)
        val first = d.onFrameText("Tenemos que irnos", 1_000)
        org.junit.Assert.assertTrue(first is SubtitleDeduplicator.Decision.SpeakNew)
        val second = d.onFrameText("Tenemos que irnos de aquí antes de que llegue la tormenta", 1_300)
        org.junit.Assert.assertTrue(second is SubtitleDeduplicator.Decision.SpeakNew)
        org.junit.Assert.assertEquals(
            "de aquí antes de que llegue la tormenta",
            (second as SubtitleDeduplicator.Decision.SpeakNew).speakableText
        )
    }
}
