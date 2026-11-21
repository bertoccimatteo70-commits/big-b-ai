package com.bigbai.llm

import com.bigbai.core.model.FinishReason

/**
 * Decides when a finished answer is broken enough to justify one automatic
 * rewrite. It is intentionally narrow: only the failure modes that a second
 * pass actually fixes (loops, unclosed code, a real token-limit cut) trigger it.
 *
 * The distinction that 1.5.0 missed: a heuristic "looks truncated" answer that
 * ended with EOS is a finished answer, not a failure. Only `LENGTH` (the model
 * hit `max_tokens`) or a cancelled/timed-out generation is treated as a real
 * cut, and a cancelled/timed-out answer is never retried automatically.
 */
object QualityGate {

    /** A rewrite is pointless after this much wall-clock time. */
    const val MAX_ELAPSED_MS = 90_000L

    /** One rewrite: a second one would double the wait without new evidence. */
    const val MAX_ATTEMPTS = 1

    fun shouldRetry(
        report: ResponseQuality.Report,
        elapsedMs: Long,
        finishReason: FinishReason = FinishReason.UNKNOWN,
        attempt: Int = 0,
    ): Boolean {
        if (attempt >= MAX_ATTEMPTS) return false
        if (elapsedMs < 0L || elapsedMs > MAX_ELAPSED_MS) return false
        when (finishReason) {
            FinishReason.CANCELLED, FinishReason.TIMEOUT, FinishReason.ERROR -> return false
            else -> Unit
        }
        if (report.empty) return false
        // A real token-limit cut is worth one longer retry, regardless of how
        // the text happens to end.
        if (finishReason == FinishReason.LENGTH) return true
        // With EOS (or an unknown reason) only the failure modes a rewrite can
        // actually fix are retried. "Ends without a full stop" is not one.
        return report.repetitionRatio > 0.45 || report.unclosedFence
    }

    /** Extra tokens granted to the single retry after a LENGTH cut. */
    fun retryMaxTokens(original: Int): Int = (original + 512).coerceAtMost(4_096)

    /**
     * Decides whether the single rewrite may replace the first answer.
     *
     * A longer answer is not automatically better: a model that loops can burn
     * the larger LENGTH budget and return a truncated, more repetitive text.
     * In that case the complete first answer is kept and only annotated.
     */
    fun isAcceptableRetry(first: ResponseQuality.Report, retry: ResponseQuality.Report): Boolean {
        if (first.truncated && !retry.truncated) return true
        if (!first.truncated && retry.truncated) return false
        if (retry.repetitionRatio > first.repetitionRatio + 0.15) return false
        if (!first.unclosedFence && retry.unclosedFence) return false
        return true
    }

    /**
     * One-line description of what is wrong, used in the UI. Null when the
     * answer passed every check.
     */
    fun describe(
        report: ResponseQuality.Report,
        finishReason: FinishReason = FinishReason.UNKNOWN,
    ): String? {
        if (report.empty) return "La risposta è vuota"
        if (finishReason == FinishReason.LENGTH) {
            return "La risposta ha raggiunto il limite di token ed è stata tagliata"
        }
        if (report.issues.isEmpty()) return null
        return report.issues.joinToString(" · ")
    }
}
