package com.bigbai.app.ui.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeModelResolverTest {

    private val chat = CodeModelCandidate("chat", "Qwen Chat", isCode = false, rank = 1)
    private val code = CodeModelCandidate("code", "Qwen Coder", isCode = true, rank = 2)
    private val codeSmall = CodeModelCandidate("code-small", "Coder Small", isCode = true, rank = 0)

    @Test
    fun `an explicit code model is used exactly`() {
        val choice = CodeModelResolver.resolve(listOf(chat, code), "code", "chat")
        assertTrue(choice is CodeModelChoice.Selected)
        choice as CodeModelChoice.Selected
        assertEquals("code", choice.candidate.id)
        assertEquals(CodeModelSource.EXPLICIT_CODE, choice.source)
        assertEquals("Qwen Coder", CodeModelResolver.label(choice))
    }

    @Test
    fun `a missing explicit code model is an error, not a chat fallback`() {
        val choice = CodeModelResolver.resolve(listOf(chat), "deleted-code", "chat")
        assertTrue(choice is CodeModelChoice.Unavailable)
        assertTrue((choice as CodeModelChoice.Unavailable).message.contains("non è più disponibile"))
        assertEquals(null, CodeModelResolver.label(choice))
    }

    @Test
    fun `without an explicit choice the best code model wins over chat`() {
        val choice = CodeModelResolver.resolve(listOf(chat, codeSmall, code), null, "chat")
        choice as CodeModelChoice.Selected
        assertEquals("code", choice.candidate.id)
        assertEquals(CodeModelSource.AUTOMATIC_CODE, choice.source)
        assertTrue(CodeModelResolver.label(choice)!!.contains("Code automatico"))
    }

    @Test
    fun `chat is used only when no code model is installed, and it is labelled`() {
        val choice = CodeModelResolver.resolve(listOf(chat), null, "chat")
        choice as CodeModelChoice.Selected
        assertEquals("chat", choice.candidate.id)
        assertEquals(CodeModelSource.CHAT_FALLBACK, choice.source)
        assertTrue(CodeModelResolver.label(choice)!!.contains("modello chat"))
    }

    @Test
    fun `nothing installed is an explicit error`() {
        val choice = CodeModelResolver.resolve(emptyList(), null, null)
        assertTrue(choice is CodeModelChoice.Unavailable)
        assertTrue((choice as CodeModelChoice.Unavailable).message.contains("Nessun modello installato"))
    }

    @Test
    fun `missing chat id does not break the automatic selection`() {
        val choice = CodeModelResolver.resolve(listOf(code), null, "gone")
        choice as CodeModelChoice.Selected
        assertEquals("code", choice.candidate.id)
        assertEquals(CodeModelSource.AUTOMATIC_CODE, choice.source)
    }
}
