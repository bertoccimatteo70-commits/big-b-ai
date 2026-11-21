package com.bigbai.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class Utf8StreamTest {

    @Test
    fun `decodes ascii unchanged`() {
        val stream = Utf8Stream()
        assertEquals("hello", stream.feed("hello".toByteArray()))
        assertEquals("", stream.flush())
    }

    @Test
    fun `joins a two byte character split across tokens`() {
        val bytes = "è".toByteArray(Charsets.UTF_8)
        assertEquals(2, bytes.size)
        val stream = Utf8Stream()
        assertEquals("", stream.feed(byteArrayOf(bytes[0])))
        assertEquals("è", stream.feed(byteArrayOf(bytes[1])))
        assertEquals("", stream.flush())
    }

    @Test
    fun `joins a three byte character split across three tokens`() {
        val bytes = "€".toByteArray(Charsets.UTF_8)
        assertEquals(3, bytes.size)
        val stream = Utf8Stream()
        assertEquals("", stream.feed(byteArrayOf(bytes[0])))
        assertEquals("", stream.feed(byteArrayOf(bytes[1])))
        assertEquals("€", stream.feed(byteArrayOf(bytes[2])))
    }

    @Test
    fun `joins a four byte emoji split across tokens`() {
        val text = "😀"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(4, bytes.size)
        val stream = Utf8Stream()
        val first = stream.feed(bytes.copyOfRange(0, 2))
        val second = stream.feed(bytes.copyOfRange(2, 4))
        assertEquals(text, first + second)
    }

    @Test
    fun `flush returns an incomplete trailing sequence without crashing`() {
        val bytes = "à".toByteArray(Charsets.UTF_8)
        val stream = Utf8Stream()
        assertEquals("", stream.feed(byteArrayOf(bytes[0])))
        // A truncated sequence at the end of generation must not be lost silently
        // in a way that breaks the following turn.
        assertEquals("�", stream.flush())
    }

    @Test
    fun `keeps mixed text and multibyte characters in order`() {
        val stream = Utf8Stream()
        val output = StringBuilder()
        val text = "Ciao, è già €😀."
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            output.append(stream.feed(byteArrayOf(byte)))
        }
        output.append(stream.flush())
        assertEquals(text, output.toString())
    }
}
