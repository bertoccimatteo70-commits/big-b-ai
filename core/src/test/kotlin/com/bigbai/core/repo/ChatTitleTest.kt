package com.bigbai.core.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTitleTest {

    @Test
    fun `keeps short messages unchanged`() {
        assertEquals("Come funziona Room?", ChatRepository.deriveTitle("Come funziona Room?"))
    }

    @Test
    fun `strips markdown noise and collapses whitespace`() {
        assertEquals(
            "Titolo pulito",
            ChatRepository.deriveTitle("## Titolo   **pulito**"),
        )
    }

    @Test
    fun `removes fenced code blocks`() {
        val title = ChatRepository.deriveTitle("Spiega questo:\n```kotlin\nfun main() {}\n```\nGrazie")
        assertTrue(title.startsWith("Spiega questo:"))
        assertTrue(!title.contains("fun main"))
    }

    @Test
    fun `truncates long text at a word boundary`() {
        val text = "Questa è una richiesta molto lunga che deve essere riassunta senza spezzare una parola"
        val title = ChatRepository.deriveTitle(text, maxChars = 40)
        assertTrue(title.length <= 41)
        assertTrue(title.endsWith("…"))
    }

    @Test
    fun `blank input produces a blank title`() {
        assertEquals("", ChatRepository.deriveTitle("   \n  "))
    }
}
