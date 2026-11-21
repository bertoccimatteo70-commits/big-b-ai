package com.bigbai.llm

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *
 * Persistence is verified: a mutation is applied to a copy of the index, saved
 * atomically, read back, and only then becomes the in-memory index. A failed
 * save leaves the previous state intact and is reported to the caller.
 */
class SemanticRepository(
    private val engine: TextEmbedder,
    private val store: SemanticIndexStore,
) {
    constructor(context: Context, engine: EmbeddingEngine) : this(
        engine,
        SemanticIndexStore(File(context.filesDir, "semantic")),
    )
    data class IndexOutcome(
        val passages: Int,
        val persisted: Boolean,
        val error: String? = null,
    )

    data class MemorySeed(
        val id: Long,
        val text: String,
        val label: String = "Memoria",
    )

    data class Stats(
        val passages: Int,
        val sources: Int,
        val modelInstalled: Boolean,
        val modelLoaded: Boolean,
        val dimension: Int,
        val failure: String?,
        val persisted: Boolean,
        val corrupt: Boolean,
        val recovered: Boolean,
        val indexBytes: Long,
        val lastError: String?,
    )

    private val mutex = Mutex()
    private var index: SemanticIndex? = null
    private var loaded: SemanticIndexStore.Loaded? = null
    private var lastError: String? = null

    /**
     * Indexes a document (a PDF's extracted text) under [sourceId]. Returns how
     * many passages were embedded and whether they were actually persisted. A
     * failure in the middle keeps the passages already embedded instead of
     * discarding everything, but never reports a save that did not happen.
     */
    suspend fun indexDocument(
        sourceId: String,
        label: String,
        text: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): IndexOutcome {
        val chunks = Embeddings.chunk(text, maxChars = 700, overlapChars = 120)
        if (chunks.isEmpty()) return IndexOutcome(0, persisted = true)
        val entries = mutableListOf<IndexEntry>()
        for ((position, chunk) in chunks.withIndex()) {
            // Stop at the first failure instead of hammering a broken embedder
            // for every remaining chunk.
            val vector = engine.embed(chunk, query = false) ?: break
            if (vector.isEmpty()) break
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
        if (entries.isEmpty()) {
            return IndexOutcome(
                passages = 0,
                persisted = false,
                error = engine.failure() ?: "Embedding non disponibile",
            )
        }
        return commit(entries.size) { target -> target.replaceSource(sourceId, entries) }
    }

    /** Indexes one saved memory so it can be retrieved semantically later. */
    suspend fun indexMemory(id: Long, text: String, label: String = "Memoria"): IndexOutcome {
        if (text.isBlank()) return IndexOutcome(0, persisted = false, error = "Ricordo vuoto")
        val vector = engine.embed(text, query = false)
            ?: return IndexOutcome(0, persisted = false, error = engine.failure() ?: "Embedding non disponibile")
        val entry = IndexEntry(
            id = "memory:$id",
            kind = IndexKind.MEMORY,
            sourceId = "memory:$id",
            label = label,
            text = text.trim().take(1_500),
            vector = vector,
            createdAt = System.currentTimeMillis(),
        )
        return commit(1) { target -> target.add(entry) }
    }

    suspend fun removeSource(sourceId: String): Boolean = mutex.withLock {
        val next = loadLocked().copy()
        if (next.removeSource(sourceId) == 0) return@withLock true
        saveLocked(next)
    }

    suspend fun removeMemory(id: Long): Boolean = removeSource("memory:$id")

    /** Drops every memory passage but keeps indexed documents. */
    suspend fun clearMemories(): IndexOutcome = mutex.withLock {
        val next = loadLocked().copy()
        val before = next.size
        val memorySources = next.all()
            .filter { it.kind == IndexKind.MEMORY }
            .map { it.sourceId }
            .distinct()
        if (memorySources.isEmpty()) return@withLock IndexOutcome(0, persisted = true)
        memorySources.forEach { next.removeSource(it) }
        val removed = before - next.size
        if (saveLocked(next)) {
            IndexOutcome(removed, persisted = true)
        } else {
            IndexOutcome(removed, persisted = false, error = lastError)
        }
    }

    /**
     * Rebuilds every memory vector from the database. Used by diagnostics after
     * a corrupt index or after the embedding model changed; documents cannot be
     * rebuilt because their extracted text is not kept in the database.
     */
    suspend fun rebuildMemories(seeds: List<MemorySeed>): IndexOutcome {
        val entries = seeds.mapNotNull { seed ->
            if (seed.text.isBlank()) return@mapNotNull null
            val vector = engine.embed(seed.text, query = false) ?: return@mapNotNull null
            IndexEntry(
                id = "memory:${seed.id}",
                kind = IndexKind.MEMORY,
                sourceId = "memory:${seed.id}",
                label = seed.label,
                text = seed.text.trim().take(1_500),
                vector = vector,
                createdAt = System.currentTimeMillis(),
            )
        }
        if (seeds.isNotEmpty() && entries.isEmpty()) {
            return IndexOutcome(0, persisted = false, error = engine.failure() ?: "Embedding non disponibile")
        }
        return commit(entries.size) { target ->
            target.all()
                .filter { it.kind == IndexKind.MEMORY }
                .map { it.sourceId }
                .distinct()
                .forEach { target.removeSource(it) }
            entries.forEach { target.add(it) }
        }
    }

    /** Top passages for [query]; empty when the embedder is unavailable. */
    suspend fun retrieve(query: String, limit: Int = 4): List<RetrievedPassage> {
        if (query.isBlank()) return emptyList()
        val vector = engine.embed(query, query = true) ?: return emptyList()
        return mutex.withLock {
            val target = loadLocked()
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
        val target = loadLocked()
        Stats(
            passages = target.size,
            sources = target.sources().size,
            modelInstalled = engine.installed(),
            modelLoaded = engine.loaded(),
            dimension = engine.dim,
            failure = engine.failure(),
            persisted = lastError == null,
            corrupt = loaded?.corrupt == true,
            recovered = loaded?.recovered == true,
            indexBytes = store.sizeBytes(),
            lastError = lastError,
        )
    }

    suspend fun clear(): Boolean = mutex.withLock {
        val result = store.clear()
        index = SemanticIndex.empty()
        loaded = null
        lastError = result.exceptionOrNull()?.message
        result.isSuccess
    }

    private suspend fun commit(passages: Int, transform: (SemanticIndex) -> Unit): IndexOutcome =
        mutex.withLock {
            val next = loadLocked().copy()
            transform(next)
            if (saveLocked(next)) {
                IndexOutcome(passages, persisted = true)
            } else {
                IndexOutcome(passages, persisted = false, error = lastError)
            }
        }

    /** Saves [next] and swaps it in only if the write was verified. */
    private fun saveLocked(next: SemanticIndex): Boolean {
        val result = store.save(next)
        return if (result.isSuccess) {
            index = next
            lastError = null
            true
        } else {
            lastError = result.exceptionOrNull()?.message ?: "Salvataggio dell'indice non riuscito"
            false
        }
    }

    private fun loadLocked(): SemanticIndex {
        index?.let { return it }
        val state = store.load()
        loaded = state
        index = state.index
        if (state.error != null) lastError = state.error
        return state.index
    }

    private companion object {
        /**
         * Measured with scripts/eval-retrieval.py on the bundled model and an
         * Italian dataset (10 documents, 18 queries): 0.80 gives precision
         * 0.769 with 3 false positives, 0.82-0.83 gives precision 1.000 and
         * recall 1.000, 0.84 starts losing relevant passages. 0.83 sits in the
         * middle of the valid window. See docs/RETRIEVAL-1.5.1.md.
         */
        const val MIN_SCORE = 0.83f
    }
}
