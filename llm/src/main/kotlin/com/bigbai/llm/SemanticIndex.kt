package com.bigbai.llm

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

enum class IndexKind { DOCUMENT, MEMORY, NOTE }

data class IndexEntry(
    val id: String,
    val kind: IndexKind,
    val sourceId: String,
    val label: String,
    val text: String,
    val vector: FloatArray,
    val createdAt: Long,
)

/**
 * On-device vector index over document passages and saved memories.
 *
 * It is intentionally small and dependency-free: a phone holds tens or hundreds
 * of passages, not millions, so a linear cosine scan is faster than maintaining
 * an approximate index and far easier to verify. The file format is versioned
 * and length-checked, so a corrupt or truncated index is discarded instead of
 * crashing the app.
 */
class SemanticIndex private constructor(
    private val entries: MutableList<IndexEntry>,
) {
    val size: Int get() = entries.size

    fun all(): List<IndexEntry> = entries.toList()

    fun add(entry: IndexEntry) {
        entries.removeAll { it.id == entry.id }
        entries.add(entry)
    }

    fun addAll(newEntries: List<IndexEntry>) {
        newEntries.forEach { add(it) }
    }

    /** Replaces every passage of one source (a document or a memory). */
    fun replaceSource(sourceId: String, newEntries: List<IndexEntry>) {
        entries.removeAll { it.sourceId == sourceId }
        entries.addAll(newEntries)
    }

    fun removeSource(sourceId: String): Int {
        val before = entries.size
        entries.removeAll { it.sourceId == sourceId }
        return before - entries.size
    }

    fun sources(): List<String> = entries.map { it.sourceId }.distinct()

    fun search(query: FloatArray, limit: Int = 4, minScore: Float = 0.55f): List<IndexEntry> {
        if (entries.isEmpty()) return emptyList()
        val ranked = Embeddings.rank(query, entries.map { it.vector }, limit, minScore)
        return ranked.map { entries[it.index] }
    }

    fun clear() = entries.clear()

    fun writeTo(output: OutputStream) {
        val data = DataOutputStream(output)
        data.writeInt(MAGIC)
        data.writeInt(VERSION)
        data.writeInt(entries.size)
        for (entry in entries) {
            data.writeUTF(entry.id.take(200))
            data.writeInt(entry.kind.ordinal)
            data.writeUTF(entry.sourceId.take(200))
            data.writeUTF(entry.label.take(200))
            data.writeUTF(entry.text.take(MAX_TEXT))
            data.writeLong(entry.createdAt)
            data.writeInt(entry.vector.size)
            for (value in entry.vector) data.writeFloat(value)
        }
        data.flush()
    }

    companion object {
        private const val MAGIC = 0x42425349 // "BBSI"
        private const val VERSION = 1
        private const val MAX_TEXT = 8_000
        private const val MAX_ENTRIES = 20_000

        fun empty(): SemanticIndex = SemanticIndex(mutableListOf())

        /**
         * Reads an index written by [writeTo]. Returns an empty index when the
         * stream is not a BIG B index, has an unknown version or is truncated:
         * a damaged cache must never prevent the app from starting.
         */
        fun readFrom(input: InputStream): SemanticIndex {
            val index = empty()
            runCatching {
                val data = DataInputStream(input)
                if (data.readInt() != MAGIC) return empty()
                if (data.readInt() != VERSION) return empty()
                val count = data.readInt()
                if (count < 0 || count > MAX_ENTRIES) return empty()
                repeat(count) {
                    val id = data.readUTF()
                    val kindOrdinal = data.readInt()
                    val kind = IndexKind.entries.getOrNull(kindOrdinal) ?: IndexKind.NOTE
                    val sourceId = data.readUTF()
                    val label = data.readUTF()
                    val text = data.readUTF()
                    val createdAt = data.readLong()
                    val dim = data.readInt()
                    if (dim <= 0 || dim > 4096) return empty()
                    val vector = FloatArray(dim) { data.readFloat() }
                    index.add(
                        IndexEntry(
                            id = id,
                            kind = kind,
                            sourceId = sourceId,
                            label = label,
                            text = text,
                            vector = vector,
                            createdAt = createdAt,
                        ),
                    )
                }
            }.onFailure { return empty() }
            return index
        }
    }
}
