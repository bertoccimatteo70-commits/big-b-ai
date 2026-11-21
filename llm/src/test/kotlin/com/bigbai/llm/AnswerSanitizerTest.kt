package com.bigbai.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerSanitizerTest {

    @Test
    fun `clean removes an end of turn marker and everything after it`() {
        val raw = "Risposta completa.<|im_end|>\nUtente: inventato"
        assertEquals("Risposta completa.", AnswerSanitizer.clean(raw))
    }

    @Test
    fun `clean keeps normal text untouched`() {
        val raw = "Un testo normale, con <parentesi> e simboli & vari."
        assertEquals(raw, AnswerSanitizer.clean(raw))
    }

    @Test
    fun `streaming never leaks a partial marker`() {
        val sanitizer = AnswerSanitizer()
        val first = sanitizer.feed("Ciao <|im")
        val second = sanitizer.feed("_end|>turno successivo")
        assertEquals("Ciao ", first)
        assertEquals("", second)
        assertTrue(sanitizer.finished)
    }

    @Test
    fun `held back text is flushed when it is not a marker`() {
        val sanitizer = AnswerSanitizer()
        val first = sanitizer.feed("1 <")
        val rest = sanitizer.feed(" 2")
        val tail = sanitizer.flush()
        assertEquals("1 ", first)
        assertEquals("< 2", rest)
        assertEquals("", tail)
        assertFalse(sanitizer.finished)
    }

    @Test
    fun `empty chunks are ignored`() {
        val sanitizer = AnswerSanitizer()
        assertEquals("", sanitizer.feed(""))
        assertEquals("", sanitizer.flush())
    }
}
