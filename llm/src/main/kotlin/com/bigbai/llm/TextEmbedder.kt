package com.bigbai.llm

/**
 * The small surface the semantic repository needs from an embedder.
 *
 * Keeping it an interface lets the persistence, retrieval and failure paths be
 * tested on the JVM with a deterministic fake, without loading the native model
 * or an Android Context. [EmbeddingEngine] is the production implementation.
 */
interface TextEmbedder {
    val dim: Int

    suspend fun embed(text: String, query: Boolean): FloatArray?

    fun installed(): Boolean

    fun loaded(): Boolean

    fun failure(): String?
}
