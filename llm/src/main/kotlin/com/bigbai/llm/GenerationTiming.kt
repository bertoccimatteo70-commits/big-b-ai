package com.bigbai.llm

/**
 * Timing values derived from one generation.
 *
 * [promptMs] is the time llama.cpp spent evaluating the prompt, [generationMs]
 * the time spent decoding the answer, [firstTokenMs] the wall-clock time to the
 * first streamed token (TTFT). They are distinct measurements: conflating TTFT
 * with prompt evaluation made the reported numbers wrong.
 */
internal data class Timing(
    val promptTokens: Int,
    val completionTokens: Int,
    val promptMs: Long,
    val generationMs: Long,
    val firstTokenMs: Long,
    val tokensPerSecond: Double,
)

/**
 * Converts the raw counters returned by `nativePerf` plus wall-clock samples
 * into [Timing].
 *
 * llama.cpp's perf struct is authoritative when present:
 * `[0] = t_p_eval_ms`, `[1] = t_eval_ms`, `[2] = n_p_eval`, `[3] = n_eval`.
 * The wall clock is only a fallback, used when the native counters are missing
 * or zero (for example a generation stopped before the first token). Keeping
 * this arithmetic out of [LlamaEngine] makes it verifiable with JVM tests.
 */
internal object GenerationTiming {

    fun compute(
        promptStartedMs: Long,
        firstTokenAtMs: Long,
        finishedMs: Long,
        streamedTokens: Int,
        perf: LongArray?,
    ): Timing {
        val hasFirstToken = firstTokenAtMs > 0L && firstTokenAtMs >= promptStartedMs
        val firstTokenMs = if (hasFirstToken) firstTokenAtMs - promptStartedMs else 0L
        val decodeWallMs = if (hasFirstToken) {
            (finishedMs - firstTokenAtMs).coerceAtLeast(0L)
        } else {
            (finishedMs - promptStartedMs).coerceAtLeast(0L)
        }

        val perfPromptTokens = perf?.getOrNull(2)?.toInt()?.takeIf { it > 0 }
        val perfCompletionTokens = perf?.getOrNull(3)?.toInt()?.takeIf { it > 0 }
        val perfPromptMs = perf?.getOrNull(0)?.takeIf { it > 0 }
        val perfGenerationMs = perf?.getOrNull(1)?.takeIf { it > 0 }

        val promptTokens = perfPromptTokens ?: 0
        val completionTokens = perfCompletionTokens ?: streamedTokens
        val promptMs = perfPromptMs ?: firstTokenMs
        val generationMs = perfGenerationMs ?: decodeWallMs

        val tokensPerSecond = when {
            // Decode speed excludes prompt evaluation and the first token.
            perfGenerationMs != null && perfCompletionTokens != null ->
                perfCompletionTokens.toDouble() * 1000.0 / perfGenerationMs
            streamedTokens > 0 && decodeWallMs > 0 ->
                streamedTokens.toDouble() * 1000.0 / decodeWallMs
            else -> 0.0
        }

        return Timing(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            promptMs = promptMs,
            generationMs = generationMs,
            firstTokenMs = firstTokenMs,
            tokensPerSecond = tokensPerSecond,
        )
    }
}
