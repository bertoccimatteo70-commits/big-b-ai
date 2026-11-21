package com.bigbai.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InstallMarkerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val spec = EmbeddingModels.EMBEDDING

    @Test
    fun `the marker roundtrips with its format version`() {
        val marker = InstallMarker("a".repeat(64), 132_439_008L, 1_700_000_000_000L)
        val parsed = InstallMarker.parse(marker.encode())
        assertNotNull(parsed)
        assertEquals(marker, parsed)
    }

    @Test
    fun `a legacy sha-only marker is not trusted`() {
        assertNull(InstallMarker.parse(spec.sha256))
    }

    @Test
    fun `garbage markers are rejected`() {
        assertNull(InstallMarker.parse(""))
        assertNull(InstallMarker.parse("2|abc|1|2"))
        assertNull(InstallMarker.parse("3|${"a".repeat(64)}|1|2"))
        assertNull(InstallMarker.parse("2|${"z".repeat(64)}|1|2"))
        assertNull(InstallMarker.parse("2|${"a".repeat(64)}|0|2"))
    }

    @Test
    fun `a changed modification time invalidates the marker`() {
        val file = temp.newFile("model.gguf")
        file.writeBytes(ByteArray(16))
        val marker = InstallMarker.of(spec, file)
        assertTrue(marker.matches(file))
        file.setLastModified(file.lastModified() + 5_000)
        assertFalse(marker.matches(file))
    }

    @Test
    fun `a changed size invalidates the marker`() {
        val file = temp.newFile("model.gguf")
        file.writeBytes(ByteArray(16))
        val marker = InstallMarker.of(spec, file)
        file.writeBytes(ByteArray(8))
        assertFalse(marker.matches(file))
    }

    @Test
    fun `the optional embedding model declares the verified MIT license and digest`() {
        assertTrue(spec.license.startsWith("MIT"))
        assertEquals(132_439_008L, spec.sizeBytes)
        assertEquals(64, spec.sha256.length)
        assertTrue(spec.fileName.endsWith(".gguf"))
        assertTrue(spec.sourceUrl.startsWith("https://"))
    }
}
