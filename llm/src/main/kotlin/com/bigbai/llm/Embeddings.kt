package com.bigbai.llm

import kotlin.math.sqrt

/**
 * Vector math and text chunking for the local semantic index.
 *
 * This file is deliberately pure Kotlin: the ranking and chunking rules are the
 * part that can be wrong, so they are unit-tested on the JVM instead of being
 * verified only on a phone. The native engine only produces the raw vectors.
 */
object Embeddings {

    /** multilingual-e5-small outputs 384 floats per text. */
    const val E5_DIM = 384

    /**
     * E5 models are trained with asymmetric prefixes: the query and the passage
     * must not be embedded with the same string, otherwise retrieval quality
     * drops noticeably. These constants are part of the model contract, not a
     * cosmetic choice.
     */
    const val QUERY_PREFIX = "query: "
    const val PASSAGE_PREFIX = "passage: "

    /** L2-normalises a vector so cosine similarity becomes a dot product. */
    fun normalize(vector: FloatArray): FloatArray {
        var sum = 0.0
        for (value in vector) {
            val v = value.toDouble()
            sum += v * v
        }
        val norm = sqrt(sum)
        if (norm <= 1e-12) return FloatArray(vector.size)
        return FloatArray(vector.size) { (vector[it] / norm).toFloat() }
    }

    /** Cosine similarity in [-1, 1]; 0 when the vectors are not comparable. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (index in a.indices) {
            val x = a[index].toDouble()
            val y = b[index].toDouble()
            dot += x * y
            normA += x * x
            normB += y * y
        }
        if (normA <= 1e-12 || normB <= 1e-12) return 0f
        return (dot / (sqrt(normA) * sqrt(normB))).toFloat().coerceIn(-1f, 1f)
    }

    data class Scored(val index: Int, val score: Float)

    /**
     * Ranks [vectors] against [query] and returns at most [limit] entries above
     * [minScore], best first. Ties keep the original order, so results are
     * deterministic.
     */
    fun rank(
        query: FloatArray,
        vectors: List<FloatArray>,
        limit: Int = 4,
        minScore: Float = 0.55f,
    ): List<Scored> {
        if (limit <= 0) return emptyList()
        return vectors.indices
            .map { Scored(it, cosine(query, vectors[it])) }
            .filter { it.score >= minScore }
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.index })
            .take(limit)
    }

    /**
     * Splits a document into overlapping passages of at most [maxChars]
     * characters, preferring sentence boundaries.
     *
     * Overlap matters for retrieval: a fact that straddles a boundary must still
     * appear whole in at least one chunk. The hard cap is respected even for a
     * single enormous sentence, and the function always makes progress, so a
     * malformed document cannot hang indexing.
     */
    fun chunk(text: String, maxChars: Int = 700, overlapChars: Int = 120): List<String> {
        if (maxChars < 80) return listOf(text.trim()).filter { it.isNotEmpty() }
        val overlap = overlapChars.coerceIn(0, maxChars / 2)
        val clean = text.replace("\r\n", "\n").replace('\r', '\n')
            .replace(Regex("""[ \t]+"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        if (clean.isEmpty()) return emptyList()

        // splitUnits already guarantees that every unit fits the cap, so the
        // loop only has to decide where to break. The overlap tail is dropped
        // when it would push the next chunk over the hard limit: the cap is a
        // promise to the caller, the overlap is an optimisation.
        val units = splitUnits(clean, maxChars)
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (unit in units) {
            if (current.isNotEmpty() && current.length + 1 + unit.length > maxChars) {
                chunks.add(current.toString().trim())
                val tail = overlapTail(current.toString(), overlap)
                current.setLength(0)
                if (tail.isNotEmpty() && tail.length + 1 + unit.length <= maxChars) {
                    current.append(tail)
                }
            }
            if (current.isNotEmpty() && !current.endsWith(" ")) current.append(' ')
            current.append(unit)
        }
        if (current.isNotBlank()) chunks.add(current.toString().trim())
        return chunks.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /** Sentence-ish units, hard-split when a sentence alone exceeds [maxChars]. */
    private fun splitUnits(text: String, maxChars: Int): List<String> {
        val raw = text.split(Regex("""(?<=[.!?;:])\s+|\n{2,}"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val units = mutableListOf<String>()
        for (sentence in raw) {
            if (sentence.length <= maxChars) {
                units.add(sentence)
            } else {
                units.addAll(hardSplit(sentence, maxChars))
            }
        }
        return units
    }

    private fun hardSplit(text: String, maxChars: Int): List<String> {
        val pieces = mutableListOf<String>()
        val words = text.split(' ')
        val current = StringBuilder()
        for (word in words) {
            if (word.length > maxChars) {
                if (current.isNotEmpty()) {
                    pieces.add(current.toString())
                    current.setLength(0)
                }
                var start = 0
                while (start < word.length) {
                    val end = minOf(word.length, start + maxChars)
                    pieces.add(word.substring(start, end))
                    start = end
                }
                continue
            }
            if (current.isNotEmpty() && current.length + 1 + word.length > maxChars) {
                pieces.add(current.toString())
                current.setLength(0)
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }
        if (current.isNotEmpty()) pieces.add(current.toString())
        return pieces.filter { it.isNotBlank() }
    }

    /** Last [overlap] characters of [text], trimmed to a word boundary. */
    private fun overlapTail(text: String, overlap: Int): String {
        if (overlap <= 0 || text.isEmpty()) return ""
        val start = (text.length - overlap).coerceAtLeast(0)
        val slice = text.substring(start)
        val firstSpace = slice.indexOf(' ')
        val trimmed = if (start > 0 && firstSpace in 0 until slice.length - 1) {
            slice.substring(firstSpace + 1)
        } else {
            slice
        }
        return trimmed.trim()
    }
}
