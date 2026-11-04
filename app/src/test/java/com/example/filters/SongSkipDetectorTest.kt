package com.example.filters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SongSkipDetectorTest {
    @Test fun markedLineSkipped() {
        assertNotNull(SongSkipDetector().check("♪ Cantando bajo la lluvia ♪", 1000))
    }
    @Test fun unmarkedDialogueRead() {
        assertNull(SongSkipDetector().check("Tenemos que irnos ya", 1000))
    }
    @Test fun unmarkedLineAfterTwoMarkedSkipped() {
        val d = SongSkipDetector()
        d.check("♪ uno ♪", 1000); d.check("♪ dos ♪", 3000)
        assertNotNull(d.check("sigue la letra sin marca", 5000))
        assertNull(d.check("sigue la letra sin marca", 20000))
    }
    @Test fun singleMarkDoesNotSwallowDialogue() {
        val d = SongSkipDetector()
        d.check("♪ uno ♪", 1000)
        assertNull(d.check("Hola, ¿qué tal?", 2000))
    }
    @Test fun misreadNoteSymbols() {
        assertEquals(true, SongSkipDetector.isSongMarked("J canta conmigo hoy J"))
    }
}
