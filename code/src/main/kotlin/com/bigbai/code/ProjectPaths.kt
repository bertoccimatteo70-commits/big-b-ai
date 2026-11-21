package com.bigbai.code

/**
 * Single path policy for manual edits and agent tools.
 *
 * Security comes from validating *segments*, not from rejecting substrings:
 * `notes..final.txt` is a perfectly valid file, while `a/../b`, `.git/config`
 * and `/etc/passwd` must never reach the project tree.
 */
object ProjectPaths {
    const val MAX_PATH_LENGTH = 240
    const val MAX_NAME_LENGTH = 120

    fun normalize(raw: String): String? {
        val slashed = raw.replace('\\', '/').trim()
        if (slashed.isEmpty() || slashed.length > MAX_PATH_LENGTH) return null
        // Absolute paths are refused instead of being silently made relative.
        if (slashed.startsWith("/")) return null
        if (Regex("^[A-Za-z]:").containsMatchIn(slashed)) return null
        val withoutDot = if (slashed.startsWith("./")) slashed.substring(2) else slashed
        if (withoutDot.isBlank()) return null
        val segments = withoutDot.split('/')
        if (segments.any { segment -> !validSegment(segment) }) return null
        return segments.joinToString("/")
    }

    /** A rename target is a name, never a path. */
    fun safeFileName(raw: String): String? {
        val clean = raw.trim()
        if (clean.isEmpty() || clean.length > MAX_NAME_LENGTH) return null
        if (clean == "." || clean == "..") return null
        if (clean.startsWith(".")) return null
        if (clean.contains('/') || clean.contains('\\')) return null
        if (clean.any { it.code < 0x20 || it == '\u007f' }) return null
        return clean
    }

    fun isInside(path: String, directory: String): Boolean =
        path == directory || path.startsWith("$directory/")

    private fun validSegment(segment: String): Boolean {
        if (segment.isEmpty() || segment == "." || segment == "..") return false
        // Hidden entries include .git, .env and provider metadata: not part of
        // the project surface the user granted to the agent.
        if (segment.startsWith(".")) return false
        if (segment.length > MAX_NAME_LENGTH) return false
        if (segment.any { it.code < 0x20 || it == '\u007f' }) return false
        return true
    }
}

/**
 * Hard budget for one agent task. A file counts once even when it is written
 * several times, so splitting a change over more steps cannot bypass the cap.
 */
class EditBudget(
    val maxFiles: Int = CodeAgent.MAX_FILES_PER_TASK,
    val maxOperations: Int = CodeAgent.MAX_OPERATIONS_PER_TASK,
) {
    private val changed = linkedSetOf<String>()
    var operations: Int = 0
        private set

    val fileCount: Int get() = changed.size
    val remainingFiles: Int get() = (maxFiles - changed.size).coerceAtLeast(0)
    val exhausted: Boolean get() = remainingFiles == 0 || operations >= maxOperations
    val changedPaths: Set<String> get() = changed.toSet()

    /** Counts one tool invocation, whatever its outcome. */
    fun beginOperation(): Boolean {
        if (operations >= maxOperations) return false
        operations += 1
        return true
    }

    fun canChange(path: String): Boolean = path in changed || changed.size < maxFiles

    /** Records a successful create/write/delete. False means it was not allowed. */
    fun recordChange(path: String): Boolean {
        if (!canChange(path)) return false
        changed += path
        return true
    }

    fun blockReason(): String = when {
        operations >= maxOperations ->
            "Limite di $maxOperations operazioni per attività raggiunto."
        else ->
            "Limite di $maxFiles file modificati per attività raggiunto."
    }
}
