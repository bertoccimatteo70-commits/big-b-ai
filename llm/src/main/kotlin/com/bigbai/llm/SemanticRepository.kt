package com.bigbai.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class RetrievedPassage(
    val text: String,
    val label: String,
    val sourceId: String,
    val score: Float,
    val kind: IndexKind,
)

/**
 * Local retrieval over documents and saved memories.
 *
 * The index lives in the app's private storage and is rebuilt from the
 * embeddings of the bundled model. Retrieval is used to put *relevant* passages
 * in front of the model instead of an arbitrary slice of a long PDF, which is
 * the difference between an answer grounded in the document and a guess.
 */
class SemanticRepository(
    private val context: Context,
    private val engine: EmbeddingEngine,
) {
    private val mutex = Mutex()
    private val indexFile = File(context.filesDir, "semantic/index.bin")
    private var index: SemanticIndex? = null

    data class Stats(
        val passages: Int,
        val sources: Int,
        val modelInstalled: Boolean,
        val modelLoaded: Boolean,
        val dimension: Int,
        val failure: String?,
    )

    /**
     * Indexes a document (a PDF's extracted text) under [sourceId]. Returns the
     * number of passages stored. A failure in the middle keeps the passages
     * already embedded instead of discarding everything.
     */
    suspend fun indexDocument(
        sourceId: String,
        label: String,
        text: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = mutex.withLock {
        val chunks = Embeddings.chunk(text, maxChars = 700, overlapChars = 120)
        if (chunks.isEmpty()) return@withLock 0
        val entries = mutableListOf<IndexEntry>()
        chunks.forEachIndexed { position, chunk ->
            val vector = engine.embed(chunk, query = false) ?: return@withLock finish(sourceId, entries)
            entries.add(
                IndexEntry(
                    id = "$sourceId#$position",
                    kind = IndexKind.DOCUMENT,
                    sourceId = sourceId,
                    label = label,
                    text = chunk,
                    vector = vector,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            onProgress(position + 1, chunks.size)
        }
        finish(sourceId, entries)
    }

    private suspend fun finish(sourceId: String, entries: List<IndexEntry>): Int {
        val target = load()
        target.replaceSource(sourceId, entries)
        persist(target)
        return entries.size
    }

    /** Indexes one saved memory so it can be retrieved semantically later. */
    suspend fun indexMemory(id: Long, text: String, label: String = "Memoria"): Boolean = mutex.withLock {
        if (text.isBlank()) return@withLock false
        val vector = engine.embed(text, query = false) ?: return@withLock false
        val target = load()
        target.add(
            IndexEntry(
                id = "memory:$id",
                kind = IndexKind.MEMORY,
                sourceId = "memory:$id",
                label = label,
                text = text.trim().take(1_500),
                vector = vector,
                createdAt = System.currentTimeMillis(),
            ),
        )
        persist(target)
        true
    }

    suspend fun removeSource(sourceId: String) = mutex.withLock {
        val target = load()
        if (target.removeSource(sourceId) > 0) persist(target)
    }

    suspend fun removeMemory(id: Long) = removeSource("memory:$id")

    /** Drops every memory passage but keeps indexed documents. */
    suspend fun clearMemories() = mutex.withLock {
        val target = load()
        val memorySources = target.all()
            .filter { it.kind == IndexKind.MEMORY }
            .map { it.sourceId }
            .distinct()
        if (memorySources.isEmpty()) return@withLock
        memorySources.forEach { target.removeSource(it) }
        persist(target)
    }

    /** Top passages for [query]; empty when the embedder is unavailable. */
    suspend fun retrieve(query: String, limit: Int = 4): List<RetrievedPassage> {
        if (query.isBlank()) return emptyList()
        val vector = engine.embed(query, query = true) ?: return emptyList()
        return mutex.withLock {
            val target = load()
            target.search(vector, limit = limit, minScore = MIN_SCORE).map { entry ->
                RetrievedPassage(
                    text = entry.text,
                    label = entry.label,
                    sourceId = entry.sourceId,
                    score = Embeddings.cosine(vector, entry.vector),
                    kind = entry.kind,
                )
            }
        }
    }

    suspend fun stats(): Stats = mutex.withLock {
        val target = load()
        Stats(
            passages = target.size,
            sources = target.sources().size,
            modelInstalled = engine.installed(),
            modelLoaded = engine.loaded(),
            dimension = engine.dim,
            failure = engine.failure(),
        )
    }

    suspend fun clear() = mutex.withLock {
        index = SemanticIndex.empty()
        indexFile.delete()
    }

    private suspend fun load(): SemanticIndex {
        index?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            if (!indexFile.isFile) return@withContext SemanticIndex.empty()
            runCatching { indexFile.inputStream().buffered().use { SemanticIndex.readFrom(it) } }
                .getOrDefault(SemanticIndex.empty())
        }
        index = loaded
        return loaded
    }

    private suspend fun persist(target: SemanticIndex) {
        withContext(Dispatchers.IO) {
            runCatching {
                indexFile.parentFile?.mkdirs()
                val temp = File(indexFile.parentFile, "${indexFile.name}.part")
                temp.outputStream().buffered().use { output ->
                    target.writeTo(output)
                    output.flush()
                }
                if (!temp.renameTo(indexFile)) {
                    temp.delete()
                }
            }
        }
    }

    private companion object {
        /**
         * E5 cosine scores for unrelated Italian sentences usually sit below
         * 0.7; 0.62 keeps recall on paraphrases without flooding the prompt with
         * irrelevant passages.
         */
        // Measured on the bundled model (host x86_64): related sentence pairs
        // score 0.90-0.91, unrelated pairs 0.71-0.77. A lower threshold would
        // inject irrelevant passages into the prompt, which is worse than
        // retrieving nothing: precision first.
        const val MIN_SCORE = 0.80f
    }
}
