package com.bigbai.app.ui.chat

import com.bigbai.core.model.Attachment
import com.bigbai.core.model.ChatMessage
import com.bigbai.core.model.GenerationMetrics
import com.bigbai.core.model.Role
import com.bigbai.core.model.Source
import com.bigbai.core.repo.MemoryProposal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStateTransitionsTest {

    private val dirty = ChatUiState(
        conversationId = 42L,
        messages = listOf(
            ChatMessage(1L, 42L, Role.USER, "ciao", 1L),
            ChatMessage(2L, 42L, Role.ASSISTANT, "risposta", 2L),
        ),
        streamingText = "parziale",
        input = "bozza",
        attachments = listOf(
            Attachment(Attachment.Kind.PDF, "content://doc", "documento.pdf", "application/pdf"),
        ),
        isGenerating = true,
        isThinking = true,
        status = "Caricamento",
        error = "errore precedente",
        metrics = GenerationMetrics(tokensPerSecond = 3.0),
        sources = listOf(Source("Fonte", "https://example.org")),
        proposals = listOf(MemoryProposal("ricordo", "Personale", "l'utente lo ha chiesto")),
        modelLabel = "Qwen",
        modelReady = true,
        visionReady = true,
        lexiconSaved = 3,
    )

    @Test
    fun `new chat keeps only the global model state`() {
        assertEquals(
            ChatUiState(modelLabel = "Qwen", modelReady = true, visionReady = true),
            dirty.afterNewChat(),
        )
    }

    @Test
    fun `new chat keeps no message attachment or generation`() {
        val fresh = dirty.afterNewChat()
        assertTrue(fresh.messages.isEmpty())
        assertTrue(fresh.attachments.isEmpty())
        assertTrue(fresh.streamingText.isEmpty())
        assertTrue(fresh.input.isEmpty())
        assertFalse(fresh.isGenerating)
        assertFalse(fresh.isThinking)
        assertNull(fresh.metrics)
        assertNull(fresh.error)
        assertNull(fresh.status)
        assertEquals(0, fresh.lexiconSaved)
        assertEquals(0L, fresh.conversationId)
    }

    @Test
    fun `opening a conversation clears the previous conversation state`() {
        val opened = dirty.afterOpenConversation(7L)
        assertEquals(7L, opened.conversationId)
        assertTrue(opened.messages.isEmpty())
        assertTrue(opened.attachments.isEmpty())
        assertTrue(opened.input.isEmpty())
        assertNull(opened.metrics)
        assertNull(opened.error)
        assertTrue(opened.sources.isEmpty())
        assertTrue(opened.proposals.isEmpty())
        assertEquals(0, opened.lexiconSaved)
    }
}
