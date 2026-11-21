package com.bigbai.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingMemoryPolicyTest {

    private val mib = 1024L * 1024L

    @Test
    fun `the default requirement no longer refuses capable devices`() {
        val required = EmbeddingMemoryPolicy.requiredBytes()
        assertTrue(required < 300 * mib)
        // 1.5.0 required 550 MiB; 400 MiB is plenty for a 132 MB model.
        assertTrue(EmbeddingMemoryPolicy.evaluate(400 * mib, pressureLevel = 0).allowed)
    }

    @Test
    fun `the requirement is the model plus runtime and safety margin`() {
        val expected = EmbeddingMemoryPolicy.MODEL_BYTES +
            EmbeddingMemoryPolicy.RUNTIME_BYTES +
            EmbeddingMemoryPolicy.SAFETY_BYTES
        assertEquals(expected, EmbeddingMemoryPolicy.requiredBytes())
    }

    @Test
    fun `low-RAM devices and memory pressure raise the bar`() {
        val base = EmbeddingMemoryPolicy.requiredBytes()
        assertEquals(base + EmbeddingMemoryPolicy.LOW_RAM_EXTRA_BYTES, EmbeddingMemoryPolicy.requiredBytes(lowRamDevice = true))
        assertEquals(base + EmbeddingMemoryPolicy.SAFETY_BYTES, EmbeddingMemoryPolicy.requiredBytes(pressureLevel = 2))
        assertTrue(EmbeddingMemoryPolicy.requiredBytes(lowRamDevice = true, pressureLevel = 2) > base)
    }

    @Test
    fun `one byte below the requirement is refused with a readable reason`() {
        val required = EmbeddingMemoryPolicy.requiredBytes()
        val decision = EmbeddingMemoryPolicy.evaluate(required - 1, pressureLevel = 0)
        assertFalse(decision.allowed)
        assertTrue(decision.reason!!.contains("insufficiente"))
        assertTrue(EmbeddingMemoryPolicy.evaluate(required, pressureLevel = 0).allowed)
    }

    @Test
    fun `critical pressure refuses the load even with plenty of RAM`() {
        val decision = EmbeddingMemoryPolicy.evaluate(8_000 * mib, pressureLevel = 3)
        assertFalse(decision.allowed)
        assertTrue(decision.reason!!.contains("critica"))
    }

    @Test
    fun `moderate pressure is still allowed when there is room`() {
        val required = EmbeddingMemoryPolicy.requiredBytes(pressureLevel = 1)
        assertTrue(EmbeddingMemoryPolicy.evaluate(required + 10 * mib, pressureLevel = 1).allowed)
    }

    @Test
    fun `the reported size is human readable`() {
        assertEquals("126 MiB", EmbeddingMemoryPolicy.mib(132_439_008L))
    }
}
