package com.bigbai.core.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTextTest {

    @Test
    fun `removes scripts and styles`() {
        val text = HtmlText.extract(
            "<html><head><style>body{color:red}</style><script>alert('x')</script></head>" +
                "<body><p>Contenuto reale</p></body></html>",
        )

        assertTrue(text.contains("Contenuto reale"))
        assertFalse(text.contains("alert"))
        assertFalse(text.contains("color:red"))
    }

    @Test
    fun `drops markup and keeps readable text`() {
        val text = HtmlText.extract("<h1>Titolo</h1><p>Prima <b>parte</b>.</p>")

        assertTrue(text.contains("Titolo"))
        assertTrue(text.contains("Prima parte"))
        assertFalse(text.contains("<"))
    }

    @Test
    fun `decodes common entities`() {
        val text = HtmlText.extract("<p>a &amp; b &lt; c</p>")

        assertTrue(text.contains("a & b"))
        assertTrue(text.contains("< c"))
    }

    @Test
    fun `collapses whitespace`() {
        val text = HtmlText.extract("<p>uno</p>\n\n\n<p>due</p>")

        assertFalse(text.contains("\n\n\n"))
    }
}
