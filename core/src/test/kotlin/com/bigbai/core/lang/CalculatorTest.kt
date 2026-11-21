package com.bigbai.core.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorTest {

    @Test
    fun `respects operator precedence and parentheses`() {
        assertEquals(14.0, Calculator.evaluate("2 + 3 * 4")!!, 1e-9)
        assertEquals(20.0, Calculator.evaluate("(2 + 3) * 4")!!, 1e-9)
        assertEquals(2.0, Calculator.evaluate("10 / 5")!!, 1e-9)
        assertEquals(8.0, Calculator.evaluate("2 ^ 3")!!, 1e-9)
    }

    @Test
    fun `handles unary minus`() {
        assertEquals(-6.0, Calculator.evaluate("-2 * 3")!!, 1e-9)
        assertEquals(1.0, Calculator.evaluate("3 + -2")!!, 1e-9)
    }

    @Test
    fun `rejects division by zero and non arithmetic text`() {
        assertNull(Calculator.evaluate("10 / 0"))
        assertNull(Calculator.evaluate("ciao mondo"))
        assertNull(Calculator.evaluate("2 +"))
        assertNull(Calculator.evaluate("sqrt(4)"))
    }

    @Test
    fun `detects arithmetic inside a sentence`() {
        val found = Calculator.detect("Quanto fa 12 * 12? Secondo me 144.")
        assertEquals(1, found.size)
        assertEquals("12 * 12", found.first().expression)
        assertEquals("144", found.first().formatted)
    }

    @Test
    fun `detects percentages written in Italian`() {
        val found = Calculator.detect("Il 15% di 240 è un valore utile")
        assertEquals(1, found.size)
        assertEquals(36.0, found.first().value, 1e-9)
    }

    @Test
    fun `does not mistake a year range for a subtraction`() {
        assertTrue(Calculator.detect("Il periodo 2024-2025 è stato intenso").isEmpty())
    }

    @Test
    fun `format trims useless decimals`() {
        assertEquals("36", Calculator.format(36.0))
        assertEquals("3.5", Calculator.format(3.5))
    }

    @Test
    fun `prompt section lists the verified results`() {
        val section = Calculator.promptSection(
            listOf(Calculator.Verified("2+2", 4.0, "4")),
            italian = true,
        )
        assertTrue(section.contains("CALCOLI VERIFICATI"))
        assertTrue(section.contains("2+2 = 4"))
    }
}
