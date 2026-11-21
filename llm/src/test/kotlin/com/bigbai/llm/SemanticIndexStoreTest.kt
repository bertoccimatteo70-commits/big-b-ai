package com.bigbai.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SemanticIndexStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun entry(id: String, text: String, source: String = "memory:$id") = IndexEntry(
        id = id,
        kind = IndexKind.MEMORY,
        sourceId = source,
        label = "Memoria",
        text = text,
        vector = floatArrayOf(1f, 0f),
        createdAt = 1_000L,
    )

    private fun index(vararg entries: IndexEntry): SemanticIndex =
        SemanticIndex.empty().also { it.addAll(entries.toList()) }

    @Test
    fun `a saved index is readable and leaves no part file`() {
        val dir = temp.newFolder("semantic")
        val store = SemanticIndexStore(dir)
        val result = store.save(index(entry("1", "pomodori")))
        assertTrue(result.isSuccess)
        assertFalse(File(dir, "index.bin.part").exists())
        val loaded = store.load()
        assertEquals(1, loaded.index.size)
        assertEquals("pomodori", loaded.index.all().first().text)
        assertFalse(loaded.corrupt)
        assertTrue(loaded.error == null)
    }

    @Test
    fun `a corrupt main file is restored from the backup`() {
        val dir = temp.newFolder("semantic")
        val store = SemanticIndexStore(dir)
        assertTrue(store.save(index(entry("1", "primo"))).isSuccess)
        // The second save copies the first index to index.bin.bak.
        assertTrue(store.save(index(entry("1", "primo"), entry("2", "secondo"))).isSuccess)
        assertTrue(File(dir, "index.bin.bak").isFile)
        File(dir, "index.bin").writeBytes(byteArrayOf(9, 9, 9, 9, 9))

        val loaded = store.load()
        assertTrue(loaded.corrupt)
        assertTrue(loaded.recovered)
        assertEquals(1, loaded.index.size)
        assertEquals("primo", loaded.index.all().first().text)
        assertTrue(File(dir, "index.bin.corrupt").isFile)
        // The recovered index has been written back to the main file.
        assertEquals(1, store.load().index.size)
    }

    @Test
    fun `a corrupt main file without a backup yields an empty index and a quarantine`() {
        val dir = temp.newFolder("semantic")
        File(dir, "index.bin").writeText("not an index")
        val store = SemanticIndexStore(dir)
        val loaded = store.load()
        assertTrue(loaded.corrupt)
        assertFalse(loaded.recovered)
        assertEquals(0, loaded.index.size)
        assertTrue(File(dir, "index.bin.corrupt").isFile)
    }

    @Test
    fun `a failed save reports failure and keeps the previous index`() {
        val dir = temp.newFolder("semantic")
        val store = SemanticIndexStore(dir)
        assertTrue(store.save(index(entry("1", "buono"))).isSuccess)

        // A file where the directory should be makes the save impossible.
        val blocked = temp.newFile("blocked")
        val failing = SemanticIndexStore(blocked)
        val result = failing.save(index(entry("2", "perso")))
        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
        assertFalse(File(blocked, "index.bin").exists())

        assertEquals(1, store.load().index.size)
    }

    @Test
    fun `an empty index is valid and distinguishable from a corrupt one`() {
        val dir = temp.newFolder("semantic")
        val store = SemanticIndexStore(dir)
        assertTrue(store.save(SemanticIndex.empty()).isSuccess)
        val loaded = store.load()
        assertFalse(loaded.corrupt)
        assertEquals(0, loaded.index.size)
        assertNull(loaded.error)
    }

    @Test
    fun `clear removes every index file`() {
        val dir = temp.newFolder("semantic")
        val store = SemanticIndexStore(dir)
        assertTrue(store.save(index(entry("1", "x"))).isSuccess)
        assertTrue(store.clear().isSuccess)
        assertFalse(File(dir, "index.bin").exists())
        assertFalse(File(dir, "index.bin.bak").exists())
        assertEquals(0L, store.sizeBytes())
    }
}
