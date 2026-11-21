package com.bigbai.code

/**
 * Chooses which project files deserve the limited context window.
 *
 * Passing the first N files alphabetically hides the file the user is talking
 * about and fills the prompt with unrelated code. This ranks by the request,
 * by entry points and by the imports of the most relevant files, then stops at
 * a hard character budget so low-memory devices are not flooded.
 */
object ProjectContextBuilder {

    private val STOP_WORDS = setOf(
        "il", "lo", "la", "i", "gli", "le", "un", "una", "uno", "di", "da", "in", "con",
        "su", "per", "tra", "fra", "che", "chi", "cui", "non", "del", "della", "dei",
        "delle", "degli", "al", "alla", "ai", "alle", "agli", "e", "ed", "o", "ma",
        "the", "and", "for", "with", "this", "that", "from", "into", "please", "fix",
        "crea", "creare", "modifica", "modificare", "correggi", "correggere", "aggiungi",
        "sistema", "sistemare", "file", "progetto", "codice", "deve", "devi", "sono",
    )

    private val ENTRY_POINTS = setOf(
        "index.html", "index.htm", "main.js", "main.ts", "app.js", "app.ts",
        "main.py", "main.kt", "main.java", "main.cpp", "main.c", "readme.md",
        "package.json", "build.gradle.kts", "build.gradle", "settings.gradle.kts",
        "cmakelists.txt", "androidmanifest.xml",
    )

    private val IMPORT = Regex(
        """(?m)^\s*(?:import|require|include|from)\s*[^\n"']*["']([^"']+)["']""",
    )

    fun build(
        files: Map<String, String>,
        request: String,
        maxChars: Int = CodeAgent.MAX_CONTEXT_CHARS,
        perFileChars: Int = 6_000,
    ): String {
        if (files.isEmpty() || maxChars <= 0) return ""
        val tokens = tokens(request)
        val scores = LinkedHashMap<String, Int>()

        for ((path, content) in files) {
            val lowerPath = path.lowercase()
            val lowerContent = content.lowercase()
            var score = 0
            for (token in tokens) {
                if (lowerPath.contains(token)) score += 8
                val occurrences = countOccurrences(lowerContent, token)
                score += minOf(occurrences, 6) * 2
            }
            val name = path.substringAfterLast('/').lowercase()
            if (name in ENTRY_POINTS) score += 4
            score += (4 - path.count { it == '/' }).coerceAtLeast(0)
            scores[path] = score
        }

        // A file imported by a highly relevant file is itself relevant.
        val seeds = scores.entries.sortedByDescending { it.value }.take(6).map { it.key }
        for (seed in seeds) {
            val content = files[seed] ?: continue
            IMPORT.findAll(content).forEach { match ->
                val imported = match.groupValues[1]
                    .trim()
                    .removePrefix("./")
                    .replace('\\', '/')
                if (imported.isBlank()) return@forEach
                val base = imported.substringAfterLast('/').substringBeforeLast('.')
                if (base.isBlank()) return@forEach
                for (candidate in files.keys) {
                    if (candidate == seed) continue
                    val candidateName = candidate.substringAfterLast('/').substringBeforeLast('.')
                    if (candidateName == base || imported.endsWith("/${candidate.substringAfterLast('/')}")) {
                        scores[candidate] = (scores[candidate] ?: 0) + 6
                    }
                }
            }
        }

        val ranked = scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

        val builder = StringBuilder()
        for (path in ranked) {
            if (builder.length >= maxChars) break
            val content = files[path] ?: continue
            val header = "FILE $path\n"
            val remaining = maxChars - builder.length - header.length
            if (remaining <= 0) break
            val excerpt = content.take(minOf(perFileChars, remaining))
            builder.append(header)
            builder.append(excerpt)
            if (excerpt.length < content.length && builder.length + 16 < maxChars) {
                builder.append("\n… (troncato)")
            }
            builder.append("\n")
        }
        return builder.toString().take(maxChars).trim()
    }

    fun tokens(request: String): List<String> = request
        .lowercase()
        .split(Regex("[^a-z0-9_./-]+"))
        .map { it.trim('.', '/', '-', '_') }
        .filter { it.length >= 3 && it !in STOP_WORDS }
        .distinct()
        .take(24)

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count += 1
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }
}
