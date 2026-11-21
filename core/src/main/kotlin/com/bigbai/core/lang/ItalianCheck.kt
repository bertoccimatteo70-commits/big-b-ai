package com.bigbai.core.lang

/**
 * Deterministic Italian writing checks on a finished answer.
 *
 * These are mechanical rules — spacing, duplicated words, unbalanced quotes —
 * not a grammar model. They exist because small local models fail in these
 * specific, visible ways, and a fixed rule can catch them without a second
 * inference pass and without inventing corrections.
 */
object ItalianCheck {

    enum class Kind { SPACING, DUPLICATE_WORD, PUNCTUATION, UNBALANCED, TOO_LONG_SENTENCE }

    data class Issue(
        val kind: Kind,
        val message: String,
        val snippet: String,
    )

    /**
     * Inspects [text] and returns at most [limit] issues, ordered by severity.
     * Code fences and inline code are ignored: spacing inside code is the
     * author's choice, not an Italian grammar error.
     */
    fun inspect(text: String, limit: Int = 6): List<Issue> {
        if (text.isBlank()) return emptyList()
        val prose = stripCode(text)
        val issues = mutableListOf<Issue>()

        if (Regex("""[ \t]{2,}""").containsMatchIn(prose)) {
            issues.add(Issue(Kind.SPACING, "Spazi multipli nel testo", snippetAround(prose, Regex("""[ \t]{2,}"""))))
        }
        val spaceBeforePunctuation = Regex("""\s+[,.;:!?]""")
        if (spaceBeforePunctuation.containsMatchIn(prose)) {
            issues.add(
                Issue(
                    Kind.PUNCTUATION,
                    "Spazio prima della punteggiatura",
                    snippetAround(prose, spaceBeforePunctuation),
                ),
            )
        }
        // "parola,parola" is wrong, but "3,14" and "12:30" are numbers, not prose.
        val missingSpace = Regex("""[,;:](?=[A-Za-zÀ-ÿ])""")
        if (missingSpace.containsMatchIn(prose)) {
            issues.add(
                Issue(
                    Kind.SPACING,
                    "Manca lo spazio dopo la punteggiatura",
                    snippetAround(prose, missingSpace),
                ),
            )
        }
        val duplicated = Regex("""(?i)\b([\p{L}]{2,})\s+\1\b""")
        duplicated.find(prose)?.let { match ->
            issues.add(
                Issue(
                    Kind.DUPLICATE_WORD,
                    "Parola ripetuta: «${match.groupValues[1]}»",
                    match.value,
                ),
            )
        }
        val repeatedPunctuation = Regex("""([!?])\1+|,,|;;""")
        repeatedPunctuation.find(prose)?.let { match ->
            issues.add(Issue(Kind.PUNCTUATION, "Punteggiatura ripetuta", match.value))
        }
        if (prose.count { it == '(' } != prose.count { it == ')' }) {
            issues.add(Issue(Kind.UNBALANCED, "Parentesi non bilanciate", ""))
        }
        if (prose.count { it == '«' } != prose.count { it == '»' }) {
            issues.add(Issue(Kind.UNBALANCED, "Virgolette caporali non bilanciate", ""))
        }
        val longest = prose.split(Regex("""[.!?]\s+""")).maxByOrNull { it.length }
        if (longest != null && longest.length > 420) {
            issues.add(
                Issue(
                    Kind.TOO_LONG_SENTENCE,
                    "Frase molto lunga (${longest.length} caratteri)",
                    longest.take(80).trim() + "…",
                ),
            )
        }
        return issues.take(limit)
    }

    /** One-line summary for the UI, or null when the text passes every rule. */
    fun summary(issues: List<Issue>): String? {
        if (issues.isEmpty()) return null
        return issues.joinToString(" · ") { it.message }
    }

    /** Text with fenced blocks and inline code removed, newlines preserved. */
    internal fun stripCode(text: String): String {
        val withoutFences = text.replace(Regex("""```[\s\S]*?```"""), " ")
        val withoutInline = withoutFences.replace(Regex("""`[^`\n]{1,200}`"""), " ")
        return withoutInline
    }

    private fun snippetAround(text: String, pattern: Regex): String {
        val match = pattern.find(text) ?: return ""
        val start = (match.range.first - 20).coerceAtLeast(0)
        val end = (match.range.last + 20).coerceAtMost(text.length)
        return text.substring(start, end).replace('\n', ' ').trim()
    }
}
