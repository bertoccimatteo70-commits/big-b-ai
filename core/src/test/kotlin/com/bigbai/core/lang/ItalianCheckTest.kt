package com.bigbai.core.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItalianCheckTest {

    @Test
    fun `clean Italian text has no issues`() {
        val text = "La fotosintesi è il processo con cui le piante trasformano la luce in energia. " +
            "Avviene nelle foglie e produce zuccheri e ossigeno."
        assertTrue(ItalianCheck.inspect(text).isEmpty())
        assertNull(ItalianCheck.summary(ItalianCheck.inspect(text)))
    }

    @Test
    fun `double spaces are reported`() {
        val issues = ItalianCheck.inspect("Questa  frase ha uno spazio doppio.")
        assertTrue(issues.any { it.kind == ItalianCheck.Kind.SPACING })
    }

    @Test
    fun `space before punctuation is reported`() {
        val issues = ItalianCheck.inspect("Questa frase ha uno spazio , prima della virgola.")
        assertTrue(issues.any { it.kind == ItalianCheck.Kind.PUNCTUATION })
    }

    @Test
    fun `missing space after a comma is reported but decimals are not`() {
        assertTrue(ItalianCheck.inspect("Prima,poi dopo.").any { it.kind == ItalianCheck.Kind.SPACING })
        assertTrue(ItalianCheck.inspect("Il valore è 3,14 circa.").none { it.kind == ItalianCheck.Kind.SPACING })
    }

    @Test
    fun `duplicated words are reported`() {
        val issues = ItalianCheck.inspect("Questa questa frase ripete una parola.")
        assertTrue(issues.any { it.kind == ItalianCheck.Kind.DUPLICATE_WORD })
    }

    @Test
    fun `unbalanced parentheses are reported`() {
        val issues = ItalianCheck.inspect("Una frase (con una parentesi aperta.")
        assertTrue(issues.any { it.kind == ItalianCheck.Kind.UNBALANCED })
    }

    @Test
    fun `code blocks are ignored`() {
        val text = "Ecco il codice:\n```kotlin\nval  x  =  1  ,  y\n```\nFine."
        assertTrue(ItalianCheck.inspect(text).isEmpty())
    }

    @Test
    fun `the summary joins the issues`() {
        val issues = ItalianCheck.inspect("Doppio  spazio e parola parola ripetuta.")
        assertEquals(issues.size, ItalianCheck.summary(issues)!!.split(" · ").size)
    }
}
