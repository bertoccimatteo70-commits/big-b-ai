package com.bigbai.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatorsTest {

    @Test
    fun `json accepts a complete document`() {
        val result = JsonValidator.validate(
            """{"nome": "città", "valori": [1, -2.5, 3e10, true, false, null], "nid": {"a": 1}}""",
        )

        assertTrue(result.describe(), result.valid)
    }

    @Test
    fun `json rejects a trailing comma`() {
        val result = JsonValidator.validate("{\"a\": 1,}")

        assertFalse(result.valid)
        assertTrue(result.describe(), result.line >= 1)
    }

    @Test
    fun `json rejects an unquoted key`() {
        assertFalse(JsonValidator.validate("{a: 1}").valid)
    }

    @Test
    fun `json rejects an unterminated string`() {
        assertFalse(JsonValidator.validate("{\"a\": \"testo}").valid)
    }

    @Test
    fun `json rejects a raw control character in a string`() {
        assertFalse(JsonValidator.validate("{\"a\": \"riga\nriga\"}").valid)
    }

    @Test
    fun `json rejects missing separators`() {
        assertFalse(JsonValidator.validate("[1 2]").valid)
    }

    @Test
    fun `json rejects empty input`() {
        assertFalse(JsonValidator.validate("   ").valid)
    }

    @Test
    fun `json accepts escaped unicode`() {
        assertTrue(JsonValidator.validate("{\"a\": \"citt\\u00e0\"}").valid)
    }

    @Test
    fun `xml accepts a well formed document`() {
        val result = XmlValidator.validate("<?xml version=\"1.0\"?><root a=\"1\"><child/>testo</root>")

        assertTrue(result.errors.toString(), result.valid)
    }

    @Test
    fun `xml rejects a mismatched closing tag`() {
        val result = XmlValidator.validate("<root><child></root>")

        assertFalse(result.valid)
        assertTrue(result.errors.first().contains("child"))
    }

    @Test
    fun `xml rejects multiple roots`() {
        assertFalse(XmlValidator.validate("<a/><b/>").valid)
    }

    @Test
    fun `xml rejects unquoted attributes`() {
        assertFalse(XmlValidator.validate("<root a=1/>").valid)
    }

    @Test
    fun `xml rejects an unclosed element`() {
        assertFalse(XmlValidator.validate("<root><child>").valid)
    }

    @Test
    fun `csv accepts consistent rows`() {
        val result = CsvValidator.validate("a,b\n1,2\n3,4\n")

        assertTrue(result.valid)
        assertEquals(3, result.rows)
        assertEquals(2, result.columns)
    }

    @Test
    fun `csv rejects inconsistent columns`() {
        val result = CsvValidator.validate("a,b\n1\n")

        assertFalse(result.valid)
        assertTrue(result.errors.first().contains("1 colonne"))
    }

    @Test
    fun `csv handles quoted commas and escaped quotes`() {
        val result = CsvValidator.validate("\"a,b\",c\n\"d\"\"e\",f\n")

        assertTrue(result.errors.toString(), result.valid)
        assertEquals(2, result.columns)
    }

    @Test
    fun `csv reports unterminated quotes`() {
        val result = CsvValidator.validate("a,b\n\"non chiuso,2\n")

        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("virgolette") })
    }

    @Test
    fun `tsv uses tabs`() {
        val result = CsvValidator.validate("a\tb\n1\t2\n", delimiter = '\t')

        assertTrue(result.valid)
    }
}
