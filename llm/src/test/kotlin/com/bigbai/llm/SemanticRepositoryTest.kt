package com.bigbai.llm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SemanticRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class FakeEmbedder(
        var vector: (String) -> FloatArray? = { floatArrayOf(1f, 0f) },
    ) : TextEmbedder {
        var failure: String? = null
        var calls = 0
        override val dim: Int = 2

        override suspend fun embed(text: String, query: Boolean): FloatArray? {
            calls += 1
            return vector(text)
        }

        override fun installed(): Boolean = true
        override fun loaded(): Boolean = true
        override fun failure(): String? = failure
    }

    private fun repository(embedder: FakeEmbedder, dir: File = temp.newFolder("semantic")) =
        SemanticRepository(embedder, SemanticIndexStore(dir))

    @Test
    fun `an indexed document is persisted and visible after a restart`() = runBlocking {
        val embedder = FakeEmbedder()
        val dir = temp.newFolder("semantic")
        val repo = repository(embedder, dir)
        val outcome = repo.indexDocument("doc:1:a.pdf", "a.pdf", "I pomodori si conservano al fresco.")
        assertTrue(outcome.persisted)
        assertEquals(1, outcome.passages)
        assertTrue(repo.stats().persisted)

        val restarted = repository(FakeEmbedder(), dir)
        assertEquals(1, restarted.stats().passages)
    }

    @Test
    fun `a failed save is reported and does not replace the previous index`() = runBlocking {
        val dir = temp.newFolder("semantic")
        val repo = repository(FakeEmbedder(), dir)
        assertTrue(repo.indexDocument("doc:1:a", "a", "testo valido").persisted)
        assertEquals(1, repo.stats().passages)

        val blocked = temp.newFile("blocked")
        val failing = SemanticRepository(FakeEmbedder(), SemanticIndexStore(blocked))
        val outcome = failing.indexDocument("doc:2:b", "b", "altro testo")
        assertFalse(outcome.persisted)
        assertNotNull(outcome.error)

        // The original repository still has exactly its previous entry.
        assertEquals(1, repo.stats().passages)
    }

    @Test
    fun `retrieval returns only passages above the threshold`() = runBlocking {
        val embedder = FakeEmbedder { text ->
            if (text.contains("pomodori")) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f)
        }
        val repo = repository(embedder)
        repo.indexDocument("doc:1:a", "a", "I pomodori maturano al sole.")
        repo.indexDocument("doc:2:b", "b", "La chitarra ha sei corde.")
        // The query uses the same fake vector as the pomodori passage.
        val results = repo.retrieve("pomodori", limit = 4)
        assertEquals(1, results.size)
        assertTrue(results.first().text.contains("pomodori"))
        assertTrue(results.first().score >= 0.83f)
    }

    @Test
    fun `memories are indexed, removed and cleared without touching documents`() = runBlocking {
        val repo = repository(FakeEmbedder())
        repo.indexDocument("doc:1:a", "a", "documento")
        assertTrue(repo.indexMemory(7, "ricordo personale").persisted)
        assertEquals(2, repo.stats().passages)

        assertTrue(repo.removeMemory(7))
        assertEquals(1, repo.stats().passages)

        assertTrue(repo.indexMemory(8, "altro ricordo").persisted)
        val cleared = repo.clearMemories()
        assertTrue(cleared.persisted)
        assertEquals(1, cleared.passages)
        assertEquals(1, repo.stats().passages)
    }

    @Test
    fun `rebuilding memories replaces the old memory vectors and keeps documents`() = runBlocking {
        val repo = repository(FakeEmbedder())
        repo.indexDocument("doc:1:a", "a", "documento")
        repo.indexMemory(1, "vecchio")
        val outcome = repo.rebuildMemories(
            listOf(
                SemanticRepository.MemorySeed(1, "nuovo contenuto", "Personale"),
                SemanticRepository.MemorySeed(2, "secondo ricordo", "Lavoro"),
            ),
        )
        assertTrue(outcome.persisted)
        assertEquals(2, outcome.passages)
        assertEquals(3, repo.stats().passages)
    }

    @Test
    fun `an unavailable embedder reports failure instead of saving an empty index`() = runBlocking {
        val embedder = FakeEmbedder { null }
        embedder.failure = "modello non caricato"
        val repo = repository(embedder)
        val outcome = repo.indexDocument("doc:1:a", "a", "testo")
        assertFalse(outcome.persisted)
        assertEquals(0, outcome.passages)
        assertEquals("modello non caricato", outcome.error)
    }

    @Test
    fun `a corrupt index is recovered and reported in the stats`() = runBlocking {
        val dir = temp.newFolder("semantic")
        val seed = repository(FakeEmbedder(), dir)
        seed.indexDocument("doc:1:a", "a", "primo")
        seed.indexDocument("doc:1:a", "a", "primo aggiornato")
        File(dir, "index.bin").writeBytes(byteArrayOf(7, 7, 7))

        val repo = repository(FakeEmbedder(), dir)
        val stats = repo.stats()
        assertTrue(stats.corrupt)
        assertTrue(stats.recovered)
        assertEquals(1, stats.passages)
    }

    @Test
    fun `clearing the index removes the file and empties the stats`() = runBlocking {
        val repo = repository(FakeEmbedder())
        repo.indexDocument("doc:1:a", "a", "testo")
        assertTrue(repo.clear())
        assertEquals(0, repo.stats().passages)
        assertEquals(0L, repo.stats().indexBytes)
    }
}
