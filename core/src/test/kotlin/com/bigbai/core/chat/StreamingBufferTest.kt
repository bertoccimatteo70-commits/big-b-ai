package com.bigbai.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingBufferTest {

    @Test
    fun firstTokenIsPublishedImmediately() {
        val buffer = StreamingBuffer(intervalMs = 40)
        assertEquals("Ciao", buffer.append("Ciao", nowMs = 0))
    }

    @Test
    fun tokensInsideTheWindowAreBuffered() {
        val buffer = StreamingBuffer(intervalMs = 40)
        buffer.append("C", 0)
        assertNull(buffer.append("i", 10))
        assertNull(buffer.append("a", 20))
        assertEquals("Ciao", buffer.append("o", 45))
    }

    @Test
    fun flushReturnsEveryToken() {
        val buffer = StreamingBuffer(intervalMs = 40)
        buffer.append("un ", 0)
        buffer.append("test", 5)
        assertEquals("un test", buffer.flush())
        assertEquals("un test", buffer.snapshot())
    }

    @Test
    fun emptyTokensDoNotPublish() {
        val buffer = StreamingBuffer(intervalMs = 40)
        assertNull(buffer.append("", 0))
        assertTrue(buffer.isEmpty())
    }
}
