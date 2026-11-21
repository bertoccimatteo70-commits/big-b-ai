package com.bigbai.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectPathsTest {

    @Test
    fun `double dots inside a harmless name are accepted`() {
        assertEquals("notes..final.txt", ProjectPaths.normalize("notes..final.txt"))
        assertEquals("src/app..min.js", ProjectPaths.normalize("src/app..min.js"))
    }

    @Test
    fun `traversal and absolute paths are rejected`() {
        listOf(
            "../secret.txt",
            "a/../../secret.txt",
            "src/../secret.txt",
            "/etc/passwd",
            "\\\\server\\share\\file.txt",
            "C:/Windows/system.ini",
            "C:\\Windows\\system.ini",
        ).forEach { path ->
            assertNull("deve rifiutare $path", ProjectPaths.normalize(path))
        }
    }

    @Test
    fun `empty and dot segments are rejected`() {
        listOf("", "   ", "a//b", "a/./b", "./", "src/.git/config", ".env").forEach { path ->
            assertNull("deve rifiutare '$path'", ProjectPaths.normalize(path))
        }
    }

    @Test
    fun `a leading dot slash is a convenience, not an escape`() {
        assertEquals("src/app.js", ProjectPaths.normalize("./src/app.js"))
        assertNull(ProjectPaths.normalize("./../app.js"))
    }

    @Test
    fun `trailing and leading spaces are trimmed`() {
        assertEquals("src/app.js", ProjectPaths.normalize("  src/app.js  "))
    }

    @Test
    fun `rename keeps a plain file name only`() {
        assertEquals("notes..final.txt", ProjectPaths.safeFileName("notes..final.txt"))
        assertNull(ProjectPaths.safeFileName("../escape.txt"))
        assertNull(ProjectPaths.safeFileName("dir/file.txt"))
        assertNull(ProjectPaths.safeFileName("."))
        assertNull(ProjectPaths.safeFileName(".."))
        assertNull(ProjectPaths.safeFileName(""))
    }

    @Test
    fun `directory prefix check does not confuse similar names`() {
        assertTrue(ProjectPaths.isInside("src/app.js", "src"))
        assertTrue(ProjectPaths.isInside("src", "src"))
        assertFalse(ProjectPaths.isInside("src2/app.js", "src"))
    }

    @Test
    fun `agent and file explorer share the same policy`() {
        assertEquals(ProjectPaths.normalize("src/app.js"), CodeAgent.sanitizePath("src/app.js"))
        assertEquals(null, ProjectPaths.normalize("../x"))
        assertEquals(null, CodeAgent.sanitizePath("../x"))
    }
}
