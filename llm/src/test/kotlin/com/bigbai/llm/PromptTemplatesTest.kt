package com.bigbai.llm

import com.bigbai.core.model.Source
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTemplatesTest {

    @Test
    fun `italian prompt asks for italian and forbids invented actions`() {
        val prompt = PromptTemplates.chatSystemPrompt(
            language = "it",
            memory = "",
            documentContext = null,
            sources = emptyList(),
            visionAvailable = false,
            hasImage = false,
        )
        assertTrue(prompt.contains("Rispondi in italiano"))
        assertTrue(prompt.contains("Non affermare di aver eseguito azioni"))
    }

    @Test
    fun `spanish prompt instructs the model to answer in spanish`() {
        val prompt = PromptTemplates.chatSystemPrompt(
            language = "es",
            memory = "",
            documentContext = null,
            sources = emptyList(),
            visionAvailable = false,
            hasImage = false,
        )
        assertTrue(prompt.contains("Answer in español"))
        assertFalse(prompt.contains("Answer in English"))
    }

    @Test
    fun `image without a vision model is described as unsupported`() {
        val prompt = PromptTemplates.chatSystemPrompt(
            language = "it",
            memory = "",
            documentContext = null,
            sources = emptyList(),
            visionAvailable = false,
            hasImage = true,
        )
        assertTrue(prompt.contains("non è multimodale"))
    }

    @Test
    fun `image with a vision model allows describing only visible content`() {
        val prompt = PromptTemplates.chatSystemPrompt(
            language = "it",
            memory = "",
            documentContext = null,
            sources = emptyList(),
            visionAvailable = true,
            hasImage = true,
        )
        assertTrue(prompt.contains("Descrivi solo ciò che è effettivamente visibile"))
        assertFalse(prompt.contains("non è multimodale"))
    }

    @Test
    fun `sources and document context are included with citation instructions`() {
        val prompt = PromptTemplates.chatSystemPrompt(
            language = "it",
            memory = "Nome: Matteo",
            documentContext = "[Pagina 1] Contratto",
            sources = listOf(Source("Titolo", "https://example.com", "snippet")),
            visionAvailable = false,
            hasImage = false,
        )
        assertTrue(prompt.contains("Nome: Matteo"))
        assertTrue(prompt.contains("[Pagina 1] Contratto"))
        assertTrue(prompt.contains("https://example.com"))
        assertTrue(prompt.contains("Cita con [n]"))
    }

    @Test
    fun `tutor section is appended when present`() {
        val prompt = PromptTemplates.chatSystemPrompt(
            language = "it",
            memory = "",
            documentContext = null,
            sources = emptyList(),
            visionAvailable = false,
            hasImage = false,
            tutor = "Tutor di spagnolo: livello base.",
        )
        assertTrue(prompt.contains("Tutor di spagnolo"))
    }

    @Test
    fun `code prompt never claims to have modified files`() {
        val prompt = PromptTemplates.codeSystemPrompt(
            language = "it",
            projectSummary = "Progetto di prova",
            fileTree = "index.html",
            activeFile = "<html></html>",
        )
        assertTrue(prompt.contains("Non affermare di aver modificato file"))
        assertTrue(prompt.contains("<<<<<<< FILE:"))
    }
}
