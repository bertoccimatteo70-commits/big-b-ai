package com.bigbai.code

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectFilesReadTest {

    @Test
    fun `reads a stream below the limit`() {
        val text = ProjectFiles.readBounded(ByteArrayInputStream("hello".toByteArray()), 16)

        assertEquals("hello", text)
    }

    @Test
    fun `accepts a stream exactly at the limit`() {
        val payload = "x".repeat(16)

        val text = ProjectFiles.readBounded(ByteArrayInputStream(payload.toByteArray()), 16)

        assertEquals(payload, text)
    }

    @Test
    fun `rejects a stream that exceeds the limit`() {
        val payload = "x".repeat(17)

        val text = ProjectFiles.readBounded(ByteArrayInputStream(payload.toByteArray()), 16)

        assertNull(text)
    }

    @Test
    fun `decodes multi-byte utf8 across chunk boundaries`() {
        val payload = "città è perché 🚀".repeat(1_000)

        val text = ProjectFiles.readBounded(ByteArrayInputStream(payload.toByteArray()), 100_000)

        assertEquals(payload, text)
    }
}
