package com.bigbai.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the 1.0.0 defect where sending a second message after an
 * AI answer silently created a new conversation and took the user out of the
 * open chat. The invariant under test is: an active conversation is never
 * replaced, regardless of generation flags or UI state.
 */
class ConversationSessionTest {

    @Test
    fun resolve_createsConversationOnlyOnce() {
        val session = ConversationSession()
        var creations = 0

        val first = session.resolve { creations++; 42L }
        val second = session.resolve { creations++; 43L }

        assertEquals(42L, first)
        assertEquals(42L, second)
        assertEquals(1, creations)
    }

    @Test
    fun resolve_afterAiAnswerKeepsTheSameConversation() {
        val session = ConversationSession()
        val id = session.resolve { 7L }

        // The UI may still be finishing the previous generation; that must not
        // influence identity resolution.
        val next = session.resolve { 8L }

        assertEquals(id, next)
        assertTrue(session.isBound)
    }

    @Test
    fun resolveDeferred_keepsIdentityAcrossConsecutiveMessages() = runBlocking {
        val session = ConversationSession()
        var creations = 0
        val first = session.resolveDeferred { creations++; 21L }
        val second = session.resolveDeferred { creations++; 22L }
        assertEquals(21L, first)
        assertEquals(21L, second)
        assertEquals(1, creations)
    }

    @Test
    fun resolve_rejectsInvalidCreatedId() {
        val session = ConversationSession()
        assertThrows(IllegalStateException::class.java) { session.resolve { 0L } }
        assertFalse(session.isBound)
    }

    @Test
    fun bind_ignoresInvalidAndDuplicateIds() {
        val session = ConversationSession()
        assertFalse(session.bind(0L))
        assertFalse(session.bind(-5L))
        assertTrue(session.bind(3L))
        assertFalse(session.bind(3L))
        assertEquals(3L, session.conversationId)
    }

    @Test
    fun reset_allowsAnExplicitNewChat() {
        val session = ConversationSession()
        session.bind(11L)
        session.reset()
        assertFalse(session.isBound)
        assertEquals(12L, session.resolve { 12L })
    }
}
