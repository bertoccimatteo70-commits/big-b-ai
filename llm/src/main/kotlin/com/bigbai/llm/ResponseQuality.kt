package com.bigbai.llm

/**
 * Cheap, deterministic quality checks on a finished answer.
 *
 * These are heuristics, not a second model: they detect the failure modes that
 * small local models actually show (loops, cut-off answers, empty replies,
 * unclosed code fences) so the app can retry, warn or mark the answer instead
 * of presenting a broken reply as if it were complete.
 */
object ResponseQuality {

    data class Report(
        val empty: Boolean,
        val repetitionRatio: Double,
        val truncated: Boolean,
        val unclosedFence: Boolean,
        val words: Int,
        val issues: List<String>,
    ) {
        val acceptable: Boolean get() = issues.isEmpty()
    }

    fun inspect(text: String): Report {
        val clean = text.trim()
        if (clean.isEmpty()) {
            return Report(true, 0.0, false, false, 0, listOf("Risposta vuota"))
        }
        val words = clean.split(Regex("""\s+""")).filter { it.isNotBlank() }
        val ratio = repetitionRatio(words)
        val fences = Regex("```").findAll(clean).count()
        val unclosedFence = fences % 2 == 1
        val last = clean.last()
        val truncated = last.isLetterOrDigit() && !clean.endsWith(".") && !clean.endsWith(":") &&
            !clean.endsWith("!") && !clean.endsWith("?") && !clean.endsWith(")") &&
            !clean.endsWith("»") && !clean.endsWith("\"") && clean.length > 40
        val issues = buildList {
            if (ratio > 0.45) add("Ripetizioni eccessive")
            if (unclosedFence) add("Blocco di codice non chiuso")
            if (truncated && !unclosedFence) add("Risposta probabilmente troncata")
        }
        return Report(false, ratio, truncated, unclosedFence, words.size, issues)
    }

    /**
     * Fraction of 6-word shingles that occur more than once. Normal prose stays
     * well below 0.45; a looping model goes far above it.
     */
    internal fun repetitionRatio(words: List<String>): Double {
        if (words.size < 12) return 0.0
        val shingles = mutableMapOf<String, Int>()
        for (index in 0..words.size - 6) {
            val key = words.subList(index, index + 6).joinToString(" ").lowercase()
            shingles[key] = (shingles[key] ?: 0) + 1
        }
        if (shingles.isEmpty()) return 0.0
        val repeated = shingles.values.count { it > 1 }
        return repeated.toDouble() / shingles.size
    }

    /** Removes an answer that only repeats the question. */
    fun isEcho(question: String, answer: String): Boolean {
        val q = question.trim().lowercase().trimEnd('?', '.', '!')
        val a = answer.trim().lowercase().trimEnd('?', '.', '!')
        if (q.length < 8 || a.length < 8) return false
        return a == q || (a.startsWith(q) && a.length < q.length + 40)
    }
}
