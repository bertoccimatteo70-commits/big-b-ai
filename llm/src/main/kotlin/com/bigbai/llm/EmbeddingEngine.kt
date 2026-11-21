package com.bigbai.llm

import android.content.Context
import android.os.SystemClock
import com.bigbai.core.device.MemorySampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs the bundled embedding model.
 *
 * It is a separate native session from the chat model on purpose: embeddings
 * must not evict a multi-gigabyte chat model, and a failed embedding must never
 * break generation. All calls are serialised on one dispatcher because a
 * llama_context is not thread-safe.
 */
class EmbeddingEngine(
    private val context: Context,
    private val installer: BundledModelInstaller,
    private val memorySampler: MemorySampler,
    private val spec: BundledModelSpec = BundledModels.EMBEDDING,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val mutex = Mutex()

    @Volatile private var handle: Long = 0L
    @Volatile private var dimension: Int = 0
    @Volatile private var loadFailure: String? = null
    @Volatile private var loadTimeMs: Long = 0L

    val dim: Int get() = dimension

    fun installed(): Boolean = installer.isInstalled(spec)

    fun failure(): String? = loadFailure

    fun loaded(): Boolean = handle != 0L

    fun loadTime(): Long = loadTimeMs

    fun label(): String = spec.label

    /**
     * Loads the model if needed. Returns false when the device does not have
     * enough free memory right now or the native load fails; callers then fall
     * back to lexical context instead of blocking the answer.
     */
    suspend fun ensureLoaded(): Boolean = mutex.withLock {
        if (handle != 0L) return@withLock true
        if (memorySampler.available() < MIN_FREE_BYTES) {
            loadFailure = "RAM libera insufficiente per il modello di embedding"
            return@withLock false
        }
        val file = installer.ensure(spec).getOrElse { error ->
            loadFailure = error.message ?: "Modello di embedding non disponibile"
            return@withLock false
        }
        val threads = Runtime.getRuntime().availableProcessors()
            .coerceAtLeast(1)
            .let { cores -> if (cores <= 4) cores else 4 }
        val started = SystemClock.elapsedRealtime()
        val newHandle = withContext(dispatcher) {
            NativeLlama.nativeLoadEmbedder(file.absolutePath, EMBED_CONTEXT, threads)
        }
        if (newHandle == 0L) {
            loadFailure = NativeLlama.nativeLastError().ifBlank { "Caricamento del modello di embedding non riuscito" }
            return@withLock false
        }
        handle = newHandle
        dimension = withContext(dispatcher) { NativeLlama.nativeEmbedderDim(newHandle) }
        loadTimeMs = SystemClock.elapsedRealtime() - started
        loadFailure = null
        true
    }

    /**
     * Embeds one text. [query] selects the E5 query prefix; passages use the
     * passage prefix. Returns an L2-normalised vector, or null on failure.
     */
    suspend fun embed(text: String, query: Boolean): FloatArray? {
        val clean = text.trim().replace(Regex("""\s+"""), " ").take(MAX_CHARS)
        if (clean.isEmpty()) return null
        if (!ensureLoaded()) return null
        val current = handle
        if (current == 0L) return null
        val prefixed = (if (query) Embeddings.QUERY_PREFIX else Embeddings.PASSAGE_PREFIX) + clean
        val vector = withContext(dispatcher) { NativeLlama.nativeEmbed(current, prefixed) }
        if (vector == null || vector.isEmpty()) {
            loadFailure = NativeLlama.nativeLastError().ifBlank { "Embedding non riuscito" }
            return null
        }
        dimension = vector.size
        return Embeddings.normalize(vector)
    }

    suspend fun close() = mutex.withLock {
        val current = handle
        handle = 0L
        if (current != 0L) withContext(dispatcher) { NativeLlama.nativeFreeEmbedder(current) }
    }

    private companion object {
        /** e5-small is trained with a 512-token window. */
        const val EMBED_CONTEXT = 512
        const val MAX_CHARS = 1_800
        const val MIN_FREE_BYTES = 550L * 1024 * 1024
    }
}
