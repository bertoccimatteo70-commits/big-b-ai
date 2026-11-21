package com.bigbai.llm

/**
 * Decides when a finished answer is broken enough to justify one automatic
 * rewrite. It is intentionally narrow: only the failure modes that a second
 * pass actually fixes (loops, unclosed code) trigger it, and never after the
 * answer has already taken most of the user's patience or the timeout budget.
 */
object QualityGate {

    /** A rewrite is pointless after this much wall-clock time. */
    const val MAX_ELAPSED_MS = 90_000L

    fun shouldRetry(report: ResponseQuality.Report, elapsedMs: Long): Boolean {
        if (report.empty) return false
        if (elapsedMs < 0L || elapsedMs > MAX_ELAPSED_MS) return false
        return report.repetitionRatio > 0.45 || report.unclosedFence
    }

    /**
     * One-line description of what is wrong, used in the UI. Null when the
     * answer passed every check.
     */
    fun describe(report: ResponseQuality.Report): String? {
        if (report.empty) return "La risposta è vuota"
        if (report.issues.isEmpty()) return null
        return report.issues.joinToString(" · ")
    }
}
