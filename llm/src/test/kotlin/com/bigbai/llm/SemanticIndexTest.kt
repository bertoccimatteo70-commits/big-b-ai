package com.bigbai.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SemanticIndexTest {

    private fun entry(id: String, text: String, vector: FloatArray, source: String = "doc:1:a.pdf") =
        IndexEntry(
            id = id,
            kind = IndexKind.DOCUMENT,
            sourceId = source,
            label = "a.pdf",
            text = text,
            vector = vector,
            createdAt = 1_000L,
        )

    @Test
    fun `search returns the closest passage`() {
        val index = SemanticIndex.empty()
        index.add(entry("1", "pomodori", floatArrayOf(1f, 0f)))
        index.add(entry("2", "carbonara", floatArrayOf(0f, 1f)))
        val results = index.search(floatArrayOf(1f, 0f), limit = 1, minScore = 0.5f)
        assertEquals(1, results.size)
        assertEquals("1", results.first().id)
    }

    @Test
    fun `replacing a source removes only its old passages`() {
        val index = SemanticIndex.empty()
        index.add(entry("1", "vecchio", floatArrayOf(1f, 0f)))
        index.add(entry("2", "altro", floatArrayOf(0f, 1f), source = "doc:2:b.pdf"))
        index.replaceSource("doc:1:a.pdf", listOf(entry("3", "nuovo", floatArrayOf(1f, 0f))))
        assertEquals(2, index.size)
        assertEquals(listOf("2", "3"), index.all().map { it.id }.sorted())
        assertEquals(1, index.removeSource("doc:2:b.pdf"))
        assertEquals(1, index.size)
    }

    @Test
    fun `the index survives a write and read roundtrip`() {
        val index = SemanticIndex.empty()
        index.add(entry("1", "testo con accenti è à ù", floatArrayOf(0.25f, -0.5f, 0.75f)))
        val bytes = ByteArrayOutputStream().also { index.writeTo(it) }.toByteArray()
        val restored = SemanticIndex.readFrom(ByteArrayInputStream(bytes))
        assertEquals(1, restored.size)
        val item = restored.all().first()
        assertEquals("testo con accenti è à ù", item.text)
        assertEquals(IndexKind.DOCUMENT, item.kind)
        assertEquals(3, item.vector.size)
        assertEquals(0.75f, item.vector[2], 1e-6f)
    }

    @Test
    fun `a corrupt file yields an empty index instead of throwing`() {
        val restored = SemanticIndex.readFrom(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)))
        assertEquals(0, restored.size)
        assertTrue(restored.search(floatArrayOf(1f), limit = 3).isEmpty())
        assertNull(SemanticIndex.readFromOrNull(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))))
    }

    @Test
    fun `a truncated file is reported as corrupt, not as a valid empty index`() {
        val index = SemanticIndex.empty()
        index.add(entry("1", "testo", floatArrayOf(0.5f, 0.5f)))
        val bytes = ByteArrayOutputStream().also { index.writeTo(it) }.toByteArray()
        val truncated = bytes.copyOf(bytes.size - 3)
        assertNull(SemanticIndex.readFromOrNull(ByteArrayInputStream(truncated)))
    }

    @Test
    fun `copy isolates later mutations from the original`() {
        val original = SemanticIndex.empty()
        original.add(entry("1", "primo", floatArrayOf(1f, 0f)))
        val copy = original.copy()
        copy.add(entry("2", "secondo", floatArrayOf(0f, 1f)))
        copy.removeSource("doc:1:a.pdf")
        assertEquals(1, original.size)
        assertEquals("1", original.all().first().id)
        assertEquals(0, copy.size)
    }
}
