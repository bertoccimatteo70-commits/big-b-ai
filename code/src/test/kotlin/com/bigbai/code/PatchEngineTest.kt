package com.bigbai.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchEngineTest {

    @Test
    fun `parses a file block with path and body`() {
        val response = """
            Ecco la modifica:

            <<<<<<< FILE: src/index.html
            <!doctype html>
            <html><body>ciao</body></html>
            >>>>>>>

            Fatto.
        """.trimIndent()

        val changes = PatchEngine.parse(response)

        assertEquals(1, changes.size)
        assertEquals("src/index.html", changes[0].path)
        assertFalse(changes[0].delete)
        assertEquals("<!doctype html>\n<html><body>ciao</body></html>", changes[0].content)
    }

    @Test
    fun `parses delete blocks`() {
        val response = "<<<<<<< DELETE: old/file.txt\n>>>>>>>"
        val changes = PatchEngine.parse(response)

        assertEquals(1, changes.size)
        assertEquals("old/file.txt", changes[0].path)
        assertTrue(changes[0].delete)
        assertNull(changes[0].content)
    }

    @Test
    fun `normalises windows separators and leading slash`() {
        val response = "<<<<<<< FILE: \\src\\main\\App.java\nclass App {}\n>>>>>>>"
        val changes = PatchEngine.parse(response)

        assertEquals("src/main/App.java", changes[0].path)
    }

    @Test
    fun `ignores unclosed blocks and plain prose`() {
        assertTrue(PatchEngine.parse("Nessuna modifica, solo una spiegazione.").isEmpty())
        assertTrue(PatchEngine.parse("<<<<<<< FILE: a.txt\nsenza chiusura").isEmpty())
        assertFalse(PatchEngine.hasChanges("testo normale"))
    }

    @Test
    fun `parses multiple blocks`() {
        val response = """
            <<<<<<< FILE: a.txt
            uno
            >>>>>>>
            <<<<<<< FILE: b.txt
            due
            >>>>>>>
        """.trimIndent()

        val changes = PatchEngine.parse(response)

        assertEquals(listOf("a.txt", "b.txt"), changes.map { it.path })
    }

    @Test
    fun `new file diff counts every line as added`() {
        val diff = PatchEngine.unifiedDiff("a.txt", null, "uno\ndue")
        val (added, removed) = PatchEngine.stats(diff)

        assertEquals(2, added)
        assertEquals(0, removed)
        assertTrue(diff.contains("+++ b/a.txt"))
    }

    @Test
    fun `changed line produces one addition and one removal`() {
        val diff = PatchEngine.unifiedDiff("a.txt", "uno\ndue\ntre", "uno\nDUE\ntre")
        val (added, removed) = PatchEngine.stats(diff)

        assertEquals(1, added)
        assertEquals(1, removed)
        assertTrue(diff.contains("-due"))
        assertTrue(diff.contains("+DUE"))
    }

    @Test
    fun `deleted file diff marks all lines as removed`() {
        val diff = PatchEngine.unifiedDiff("a.txt", "uno\ndue", null)
        val (added, removed) = PatchEngine.stats(diff)

        assertEquals(0, added)
        assertEquals(2, removed)
        assertTrue(diff.contains("file eliminato"))
    }
}
