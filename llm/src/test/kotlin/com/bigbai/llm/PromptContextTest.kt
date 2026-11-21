package com.bigbai.llm

import com.bigbai.core.lang.Calculator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptContextTest {

    @Test
    fun `retrieved passages and verified calculations are injected`() {
        val prompt = PromptTemplates.chatSystemPrompt(
            language = "it",
            memory = "",
            documentContext = null,
            sources = emptyList(),
            visionAvailable = false,
            hasImage = false,
            retrievedContext = "[a.pdf] I pomodori si conservano in un luogo fresco.",
            verifiedCalculations = Calculator.promptSection(
                listOf(Calculator.Verified("2+2", 4.0, "4")),
                italian = true,
            ),
        )
        assertTrue(prompt.contains("Passaggi pertinenti"))
        assertTrue(prompt.contains("[a.pdf]"))
        assertTrue(prompt.contains("2+2 = 4"))
    }

    @Test
    fun `the Italian rules forbid invented sources and bad grammar`() {
        val prompt = PromptTemplates.chatSystemPrompt(
            language = "it",
            memory = "",
            documentContext = null,
            sources = emptyList(),
            visionAvailable = false,
            hasImage = false,
        )
        assertTrue(prompt.contains("concordanze"))
        assertTrue(prompt.contains("Non citare fonti"))
        assertFalse(prompt.contains("Passaggi pertinenti"))
    }
}
