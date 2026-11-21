package com.bigbai.llm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class EmbedderSessionTest {

    private fun session(
        load: suspend () -> Result<Long> = { Result.success(1L) },
        free: suspend (Long) -> Unit = {},
        embed: suspend (Long, String) -> FloatArray? = { _, _ -> floatArrayOf(1f, 0f) },
        dimension: suspend (Long) -> Int = { 2 },
        canLoad: () -> Boolean = { true },
        refusal: () -> String = { "RAM insufficiente" },
        error: () -> String = { "" },
        clock: () -> Long = { 0L },
    ) = EmbedderSession(load, free, embed, dimension, canLoad, refusal, error, clock)

    @Test
    fun `concurrent embeddings load the model exactly once`() = runBlocking {
        val loads = AtomicInteger()
        val session = session(load = {
            loads.incrementAndGet()
            Result.success(7L)
        })
        val results = (1..8).map { index ->
            async(Dispatchers.Default) { session.embed("testo $index", "passage: ") }
        }.awaitAll()
        assertTrue(results.all { it != null })
        assertEquals(1, loads.get())
        assertEquals(8, session.snapshot().embeds)
    }

    @Test
    fun `close waits for an in-flight embedding and never frees under it`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val frees = AtomicInteger()
        val session = session(
            load = { Result.success(42L) },
            free = { frees.incrementAndGet() },
            embed = { _, _ ->
                started.complete(Unit)
                gate.await()
                floatArrayOf(1f, 0f)
            },
        )
        val embedJob = launch(Dispatchers.Default) { session.embed("testo", "query: ") }
        started.await()
        val closeJob = launch(Dispatchers.Default) { session.close() }
        delay(80)
        // The close is blocked on the same mutex: the native handle is still
        // alive while the embedding is running.
        assertEquals(0, frees.get())
        gate.complete(Unit)
        embedJob.join()
        closeJob.join()
        assertEquals(1, frees.get())
        assertFalse(session.loaded())
    }

    @Test
    fun `an embedding after close reloads the model`() = runBlocking {
        val loads = AtomicInteger()
        val session = session(load = {
            loads.incrementAndGet()
            Result.success(5L)
        })
        assertNotNull(session.embed("primo", "passage: "))
        session.close()
        assertNotNull(session.embed("secondo", "passage: "))
        assertEquals(2, loads.get())
        assertEquals(1, session.snapshot().frees)
    }

    @Test
    fun `a double close frees the native handle once`() = runBlocking {
        val frees = AtomicInteger()
        val session = session(free = { frees.incrementAndGet() })
        session.ensureLoaded()
        session.close()
        session.close()
        assertEquals(1, frees.get())
    }

    @Test
    fun `a refused load does not call the native loader`() = runBlocking {
        val session = session(
            load = { error("the native loader must not be called") },
            canLoad = { false },
            refusal = { "RAM libera insufficiente" },
        )
        assertFalse(session.ensureLoaded())
        assertEquals("RAM libera insufficiente", session.failure())
        assertFalse(session.loaded())
    }

    @Test
    fun `a native load failure is reported and can be retried`() = runBlocking {
        var attempt = 0
        val session = session(load = {
            attempt += 1
            if (attempt == 1) Result.failure(IllegalStateException("modello mancante")) else Result.success(9L)
        })
        assertFalse(session.ensureLoaded())
        assertEquals("modello mancante", session.failure())
        assertTrue(session.ensureLoaded())
        assertNull(session.failure())
        assertEquals(2, attempt)
    }

    @Test
    fun `an embedding failure is reported and cleared by the next success`() = runBlocking {
        var fail = true
        val session = session(
            embed = { _, _ -> if (fail) null else floatArrayOf(3f, 4f) },
            error = { "errore nativo" },
        )
        assertNull(session.embed("testo", "passage: "))
        assertEquals("errore nativo", session.failure())
        fail = false
        val vector = session.embed("testo", "passage: ")
        assertNotNull(vector)
        assertEquals(0.6f, vector!![0], 1e-5f)
        assertNull(session.failure())
    }

    @Test
    fun `reload frees and loads in one atomic step`() = runBlocking {
        val loads = AtomicInteger()
        val frees = AtomicInteger()
        val session = session(
            load = {
                loads.incrementAndGet()
                Result.success(11L)
            },
            free = { frees.incrementAndGet() },
        )
        session.ensureLoaded()
        assertTrue(session.reload())
        assertEquals(2, loads.get())
        assertEquals(1, frees.get())
        assertTrue(session.loaded())
    }
}
