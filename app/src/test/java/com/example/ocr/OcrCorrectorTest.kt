package com.example.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrCorrectorTest {
    @Test fun learnsSingleWordFix() {
        assertEquals(listOf("Ia" to "la"), OcrCorrector.learn("Ia casa es grande", "la casa es grande"))
    }
    @Test fun ignoresSharedPunctuation() {
        assertEquals(listOf("Ia" to "la"), OcrCorrector.learn("Dijo Ia, no", "Dijo la, no"))
    }
    @Test fun learnsInsertedSplit() {
        assertEquals(listOf("nose" to "no sé"), OcrCorrector.learn("yo nose nada", "yo no sé nada"))
    }
    @Test fun rewriteLearnsNothing() {
        assertTrue(OcrCorrector.learn("uno dos tres cuatro cinco seis", "a b c d e f g h").isEmpty())
    }
    @Test fun appliesWholeWordsOnly() {
        val rules = listOf(OcrCorrection(wrong = "Ia", right = "la"))
        assertEquals("la casa Iago", OcrCorrector.apply("Ia casa Iago", rules))
    }
    @Test fun appliesLongestFirstAndEscapes() {
        val rules = listOf(OcrCorrection(wrong = "a", right = "X"), OcrCorrection(wrong = "a b", right = "$1"))
        assertEquals("\$1 c", OcrCorrector.apply("a b c", rules))
    }
}
