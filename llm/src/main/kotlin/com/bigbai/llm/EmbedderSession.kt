package com.bigbai.llm

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns one native embedding handle and serialises every operation on it.
 *
 * The critical invariant: **the handle is never freed while an embedding is in
 * flight**. Loading, embedding and freeing all happen under the same mutex, so
 * a `close()` triggered by `onTrimMemory` waits for the current vector instead
 * of freeing the native session under it. In 1.5.0 `embed()` read the handle
 * outside the lock and called into native code after `close()` could have freed
 * it — a use-after-free that crashed the process.
 *
 * The class is pure Kotlin with injected native operations, so the races and
 * the lifecycle are tested on the JVM instead of only on a phone.
 */
class EmbedderSession(
    private val loadModel: suspend () -> Result<Long>,
    private val freeModel: suspend (Long) -> Unit,
    private val embedText: suspend (Long, String) -> FloatArray?,
    private val dimensionOf: suspend (Long) -> Int,
    private val canLoad: () -> Boolean,
    private val loadRefusal: () -> String,
    private val lastError: () -> String,
    private val clock: () -> Long,
) {
    data class Snapshot(
        val loaded: Boolean,
        val dimension: Int,
        val failure: String?,
        val loadTimeMs: Long,
        val loads: Int,
        val embeds: Int,
        val frees: Int,
    )

    private val mutex = Mutex()
    private var handle: Long = 0L
    private var dimension: Int = 0
    private var failure: String? = null
    private var loadTimeMs: Long = 0L
    private var loads = 0
    private var embeds = 0
    private var frees = 0

    fun loaded(): Boolean = handle != 0L

    fun dimension(): Int = dimension

    fun failure(): String? = failure

    fun loadTime(): Long = loadTimeMs

    fun snapshot(): Snapshot = Snapshot(loaded(), dimension, failure, loadTimeMs, loads, embeds, frees)

    suspend fun ensureLoaded(): Boolean = mutex.withLock { loadLocked() }

    /**
     * Embeds [text] with [prefix] (the E5 query/passage prefix). The whole
     * operation, including a possible load, is under the mutex: a concurrent
     * `close()` cannot free the handle between the load and the native call.
     */
    suspend fun embed(text: String, prefix: String): FloatArray? = mutex.withLock {
        if (!loadLocked()) return@withLock null
        val current = handle
        if (current == 0L) return@withLock null
        val vector = runCatching { embedText(current, prefix + text) }.getOrNull()
        if (vector == null || vector.isEmpty()) {
            failure = lastError().ifBlank { "Embedding non riuscito" }
            return@withLock null
        }
        embeds += 1
        dimension = vector.size
        failure = null
        Embeddings.normalize(vector)
    }

    suspend fun close() = mutex.withLock { freeLocked() }

    /** Frees and reloads atomically: no embedding can slip between the two. */
    suspend fun reload(): Boolean = mutex.withLock {
        freeLocked()
        loadLocked()
    }

    private suspend fun loadLocked(): Boolean {
        if (handle != 0L) return true
        if (!canLoad()) {
            failure = loadRefusal()
            return false
        }
        val started = clock()
        val loaded = runCatching { loadModel() }.getOrElse { Result.failure(it) }
        val newHandle = loaded.getOrElse { error ->
            failure = error.message ?: "Caricamento del modello di embedding non riuscito"
            return false
        }
        if (newHandle == 0L) {
            failure = lastError().ifBlank { "Caricamento del modello di embedding non riuscito" }
            return false
        }
        handle = newHandle
        loads += 1
        dimension = runCatching { dimensionOf(newHandle) }.getOrDefault(0)
        loadTimeMs = clock() - started
        failure = null
        return true
    }

    private suspend fun freeLocked() {
        val current = handle
        handle = 0L
        dimension = 0
        if (current != 0L) {
            frees += 1
            runCatching { freeModel(current) }
        }
    }
}
