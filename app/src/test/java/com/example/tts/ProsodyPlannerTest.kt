package com.example.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProsodyPlannerTest {
    @Test
    fun plainSentenceIsSingleNeutralSegment() {
        val plan = ProsodyPlanner.plan("Una frase normal.")
        assertEquals(1, plan.size)
        assertTrue(plan[0].isNeutral)
    }

    @Test
    fun quotedTextGetsMoreWeight() {
        val plan = ProsodyPlanner.plan("Dijo \"vamos a ganar\" y se fue.")
        val quote = plan.first { it.text.startsWith("vamos") }
        assertTrue(quote.gain > 1f && quote.speedFactor < 1f && quote.pauseBeforeMs > 0)
    }

    @Test
    fun parenthesisDependsOnContent() {
        val soft = ProsodyPlanner.plan("(suspira un poco) vale.").first { it.text.startsWith("suspira") }
        val loud = ProsodyPlanner.plan("(¡CUIDADO!) corre.").first { it.text.contains("CUIDADO") }
        assertTrue(soft.gain < 1f)
        assertTrue(loud.gain > 1f)
    }

    @Test
    fun questionIsSlowerWithPause() {
        val q = ProsodyPlanner.plan("¿Quién eres?").single()
        assertTrue(q.speedFactor < 1f && q.pauseAfterMs > 0)
    }

    @Test
    fun unbalancedQuoteIsDropped() {
        val plan = ProsodyPlanner.plan("Comilla \" suelta.")
        assertTrue(plan.none { it.text.contains('"') })
    }
}
