package com.bigbai.llm

import android.content.Context
import android.os.SystemClock
import com.bigbai.core.device.MemorySampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

/**
 * Runs the bundled embedding model.
 *
 * It is a separate native session from the chat model on purpose: embeddings
 * must not evict a multi-gigabyte chat model, and a failed embedding must never
 * break generation. All native calls are serialised by [EmbedderSession], which
 * also guarantees that `close()` cannot free the handle during an embedding.
 */
class EmbeddingEngine(
    context: Context,
    private val installer: BundledModelInstaller,
    private val memorySampler: MemorySampler,
    private val spec: BundledModelSpec = BundledModels.EMBEDDING,
    private val lowRamDevice: Boolean = false,
) : TextEmbedder {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)

    private val session = EmbedderSession(
        loadModel = {
            val fileResult = installer.ensure(spec)
            val file = fileResult.getOrNull()
            if (file == null) {
                Result.failure(
                    fileResult.exceptionOrNull()
                        ?: IllegalStateException("Modello di embedding non disponibile"),
                )
            } else {
                val threads = Runtime.getRuntime().availableProcessors()
                    .coerceAtLeast(1)
                    .let { cores -> if (cores <= 4) cores else 4 }
                val handle = withContext(dispatcher) {
                    NativeLlama.nativeLoadEmbedder(file.absolutePath, EMBED_CONTEXT, threads)
                }
                if (handle == 0L) {
                    Result.failure(
                        IllegalStateException(
                            NativeLlama.nativeLastError()
                                .ifBlank { "Caricamento del modello di embedding non riuscito" },
                        ),
                    )
                } else {
                    Result.success(handle)
                }
            }
        },
        freeModel = { handle -> withContext(dispatcher) { NativeLlama.nativeFreeEmbedder(handle) } },
        embedText = { handle, text -> withContext(dispatcher) { NativeLlama.nativeEmbed(handle, text) } },
        dimensionOf = { handle -> withContext(dispatcher) { NativeLlama.nativeEmbedderDim(handle) } },
        canLoad = { decision().allowed },
        loadRefusal = { decision().reason ?: "Modello di embedding non caricabile in questo momento" },
        lastError = { NativeLlama.nativeLastError() },
        clock = { SystemClock.elapsedRealtime() },
    )

    override val dim: Int get() = session.dimension()

    override fun installed(): Boolean = installer.isInstalled(spec)

    override fun failure(): String? = session.failure()

    override fun loaded(): Boolean = session.loaded()

    fun loadTime(): Long = session.loadTime()

    fun label(): String = spec.label

    fun snapshot(): EmbedderSession.Snapshot = session.snapshot()

    fun decision(): EmbeddingMemoryPolicy.Decision = EmbeddingMemoryPolicy.evaluate(
        availableBytes = memorySampler.available(),
        pressureLevel = memorySampler.pressureLevel(),
        lowRamDevice = lowRamDevice,
    )

    suspend fun ensureLoaded(): Boolean = session.ensureLoaded()

    /** Reloads after a failure or after the system released memory. */
    suspend fun reload(): Boolean = session.reload()

    /**
     * Embeds one text. [query] selects the E5 query prefix; passages use the
     * passage prefix. Returns an L2-normalised vector, or null on failure.
     */
    override suspend fun embed(text: String, query: Boolean): FloatArray? {
        val clean = text.trim().replace(Regex("""\s+"""), " ").take(MAX_CHARS)
        if (clean.isEmpty()) return null
        return session.embed(clean, if (query) Embeddings.QUERY_PREFIX else Embeddings.PASSAGE_PREFIX)
    }

    suspend fun close() = session.close()

    private companion object {
        /** e5-small is trained with a 512-token window. */
        const val EMBED_CONTEXT = 512
        const val MAX_CHARS = 1_800
    }
}
