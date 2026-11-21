package com.bigbai.core.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentTextExtractorTest {

    @Test
    fun `docx paragraphs are extracted in order`() {
        val xml = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>Prima riga</w:t></w:r></w:p>
                <w:p><w:r><w:t>Seconda riga</w:t></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()
        val bytes = zip("word/document.xml" to xml.toByteArray())
        val result = DocumentTextExtractor.extract("contratto.docx", ByteArrayInputStream(bytes)).getOrThrow()
        assertEquals("docx", result.format)
        assertTrue(result.text.contains("Prima riga"))
        assertTrue(result.text.contains("Seconda riga"))
        assertTrue(result.text.indexOf("Prima") < result.text.indexOf("Seconda"))
    }

    @Test
    fun `xlsx shared strings and numeric cells are extracted`() {
        val shared = """
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <si><t>Ciao</t></si>
            </sst>
        """.trimIndent()
        val sheet = """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1">
                  <c r="A1" t="s"><v>0</v></c>
                  <c r="B1"><v>42</v></c>
                </row>
              </sheetData>
            </worksheet>
        """.trimIndent()
        val bytes = zip(
            "xl/sharedStrings.xml" to shared.toByteArray(),
            "xl/worksheets/sheet1.xml" to sheet.toByteArray(),
        )
        val result = DocumentTextExtractor.extract("budget.xlsx", ByteArrayInputStream(bytes)).getOrThrow()
        assertTrue(result.text.contains("A1: Ciao"))
        assertTrue(result.text.contains("B1: 42"))
        assertTrue(result.text.contains("[Foglio 1]"))
    }

    @Test
    fun `csv and txt are read as utf8`() {
        val csv = DocumentTextExtractor.extract(
            "vendite.csv",
            ByteArrayInputStream("mese,valore\ngennaio,10".toByteArray()),
        ).getOrThrow()
        assertTrue(csv.text.contains("gennaio,10"))

        val txt = DocumentTextExtractor.extract(
            "note.txt",
            ByteArrayInputStream("\uFEFFNota di prova".toByteArray()),
        ).getOrThrow()
        assertEquals("Nota di prova", txt.text)
    }

    @Test
    fun `legacy binary office files are refused with a real explanation`() {
        val result = DocumentTextExtractor.extract("vecchio.doc", ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains(".docx"))
    }

    @Test
    fun `long text is bounded and marked as truncated`() {
        val text = "a".repeat(DocumentTextExtractor.MAX_CHARS + 50)
        val result = DocumentTextExtractor.extract("lungo.txt", ByteArrayInputStream(text.toByteArray())).getOrThrow()
        assertEquals(DocumentTextExtractor.MAX_CHARS, result.text.length)
        assertTrue(result.truncated)
        assertFalse(result.text.length > DocumentTextExtractor.MAX_CHARS)
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
