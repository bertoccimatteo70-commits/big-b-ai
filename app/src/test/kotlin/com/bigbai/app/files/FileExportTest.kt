package com.bigbai.app.files

import com.bigbai.core.model.ChatMessage
import com.bigbai.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileExportTest {

    @Test
    fun `slug is built from the first words`() {
        assertEquals("ciao-come-stai", FileExport.slugOf("Ciao, come stai?"))
    }

    @Test
    fun `slug falls back when the title is empty`() {
        assertEquals("chat", FileExport.slugOf(null))
        assertEquals("chat", FileExport.slugOf("   ---   "))
    }

    @Test
    fun `slug keeps at most five words`() {
        assertEquals(
            "uno-due-tre-quattro-cinque",
            FileExport.slugOf("Uno due tre quattro cinque sei sette"),
        )
    }

    @Test
    fun `suggested name has prefix timestamp and extension`() {
        val name = FileExport.suggestedFileName("Analisi del PDF", "md", 1_700_000_000_000L)
        assertTrue(name, name.startsWith("itagpt-analisi-del-pdf-"))
        assertTrue(name, name.endsWith(".md"))
    }

    @Test
    fun `unsafe extension is normalised`() {
        val name = FileExport.suggestedFileName("test", "../md", 0L)
        assertTrue(name, name.endsWith(".md"))
    }

    @Test
    fun `extension comes from the first code fence`() {
        assertEquals("kt", FileExport.extensionFor("Ecco il codice:\n```kotlin\nval x = 1\n```"))
        assertEquals("py", FileExport.extensionFor("```python\nprint(1)\n```"))
        assertEquals("md", FileExport.extensionFor("Solo testo, nessun blocco."))
    }

    @Test
    fun `language aliases map to real extensions`() {
        assertEquals("cpp", FileExport.extensionForLanguage("c++"))
        assertEquals("js", FileExport.extensionForLanguage("javascript"))
        assertEquals(null, FileExport.extensionForLanguage("linguaggio-inventato"))
    }

    @Test
    fun `mime type follows the extension`() {
        assertEquals("text/markdown", FileExport.mimeFor("md"))
        assertEquals("application/json", FileExport.mimeFor("json"))
        assertEquals("text/plain", FileExport.mimeFor("kt"))
    }

    @Test
    fun `markdown export keeps the title and both speakers`() {
        val messages = sampleConversation()
        val markdown = FileExport.conversationMarkdown("Saluto", messages)
        assertTrue(markdown, markdown.contains("# Saluto"))
        assertTrue(markdown, markdown.contains("## Utente"))
        assertTrue(markdown, markdown.contains("## ITAGPT"))
        assertTrue(markdown, markdown.contains("Ciao"))
        assertTrue(markdown, markdown.contains("Come stai?"))
    }

    @Test
    fun `plain text export contains every message in order`() {
        val text = FileExport.conversationPlainText(null, sampleConversation())
        assertTrue(text, text.startsWith("Conversazione"))
        assertTrue(text, text.indexOf("Ciao") < text.indexOf("Come stai?"))
        assertTrue(text, text.contains("UTENTE"))
        assertTrue(text, text.contains("ITAGPT"))
    }

    private fun sampleConversation(): List<ChatMessage> = listOf(
        ChatMessage(id = 1, conversationId = 7, role = Role.USER, content = "Ciao", createdAt = 1),
        ChatMessage(id = 2, conversationId = 7, role = Role.ASSISTANT, content = "Come stai?", createdAt = 2),
    )
}
