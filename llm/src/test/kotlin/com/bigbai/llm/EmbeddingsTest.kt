package com.bigbai.llm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingsTest {

    @Test
    fun `normalize produces a unit vector`() {
        val normalized = Embeddings.normalize(floatArrayOf(3f, 4f))
        assertEquals(0.6f, normalized[0], 1e-5f)
        assertEquals(0.8f, normalized[1], 1e-5f)
        assertEquals(1.0, Embeddings.cosine(normalized, normalized).toDouble(), 1e-5)
    }

    @Test
    fun `a zero vector stays zero instead of producing NaN`() {
        assertArrayEquals(FloatArray(4), Embeddings.normalize(FloatArray(4)), 0f)
        assertEquals(0f, Embeddings.cosine(FloatArray(4), FloatArray(4)), 0f)
    }

    @Test
    fun `cosine is one for identical vectors and zero for orthogonal ones`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        assertEquals(1.0, Embeddings.cosine(a, a).toDouble(), 1e-6)
        assertEquals(0.0, Embeddings.cosine(a, b).toDouble(), 1e-6)
        assertEquals(0f, Embeddings.cosine(a, floatArrayOf(1f, 0f, 0f)), 0f)
    }

    @Test
    fun `rank orders by score and drops weak matches`() {
        val query = floatArrayOf(1f, 0f)
        val vectors = listOf(
            floatArrayOf(0f, 1f),
            floatArrayOf(0.9f, 0.1f),
            floatArrayOf(1f, 0f),
        )
        val ranked = Embeddings.rank(query, vectors, limit = 3, minScore = 0.5f)
        assertEquals(listOf(2, 1), ranked.map { it.index })
        assertTrue(ranked.all { it.score >= 0.5f })
    }

    @Test
    fun `chunks respect the character limit and keep the content`() {
        val text = (1..80).joinToString(" ") { "La frase numero $it contiene contenuto utile per il test." }
        val chunks = Embeddings.chunk(text, maxChars = 240, overlapChars = 60)
        assertTrue(chunks.size > 3)
        assertTrue(chunks.all { it.length <= 240 })
        assertTrue(chunks.first().contains("frase numero 1"))
        assertTrue(chunks.last().contains("numero 80"))
    }

    @Test
    fun `an enormous single word is hard split`() {
        val text = (1..400).joinToString("") { ('a' + (it % 20)).toString() }
        val chunks = Embeddings.chunk(text, maxChars = 100, overlapChars = 20)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.length <= 100 })
    }

    @Test
    fun `empty text produces no chunks`() {
        assertTrue(Embeddings.chunk("   \n\n  ").isEmpty())
    }
}
