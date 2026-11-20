package com.bigbai.code

import kotlin.math.max
import kotlin.math.min

data class ProposedChange(
    val path: String,
    val content: String?,
    val delete: Boolean,
)

/**
 * Parses the explicit change protocol BIG B Code asks the model to use.
 *
 * The protocol is line-oriented on purpose: JSON-escaped source code is
 * unreliable for small local models, while a delimited block is trivial to
 * produce and to validate. Nothing is written to disk here — the UI shows the
 * diff and applies changes only after the user approves them.
 */
object PatchEngine {
    private const val START = "<<<<<<<"
    private const val END = ">>>>>>>"

    fun parse(response: String): List<ProposedChange> {
        val lines = response.lines()
        val changes = mutableListOf<ProposedChange>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trim()
            if (!line.startsWith(START)) {
                index++
                continue
            }
            val header = line.removePrefix(START).trim()
            val delete = header.startsWith("DELETE:", ignoreCase = true)
            val path = when {
                header.startsWith("FILE:", ignoreCase = true) -> header.substringAfter(':').trim()
                delete -> header.substringAfter(':').trim()
                else -> header.trim()
            }
            val body = StringBuilder()
            index++
            var closed = false
            while (index < lines.size) {
                val current = lines[index]
                if (current.trim().startsWith(END)) {
                    closed = true
                    break
                }
                body.append(current).append('\n')
                index++
            }
            if (path.isNotBlank() && closed) {
                changes.add(
                    ProposedChange(
                        path = path.replace('\\', '/').trim().removePrefix("/"),
                        content = if (delete) null else body.toString().trimEnd('\n'),
                        delete = delete,
                    ),
                )
            }
            index++
        }
        return changes
    }

    fun hasChanges(response: String): Boolean = parse(response).isNotEmpty()

    /**
     * Compact unified diff for the approval sheet. Files above the line cap get
     * a full-content preview instead of an O(n²) diff.
     */
    fun unifiedDiff(path: String, old: String?, new: String?): String {
        val oldLines = old?.lines() ?: emptyList()
        val newLines = new?.lines() ?: emptyList()
        val builder = StringBuilder()
        builder.append("--- a/").append(path).append('\n')
        builder.append("+++ b/").append(path).append('\n')

        if (new == null) {
            builder.append("@@ file eliminato (").append(oldLines.size).append(" righe) @@\n")
            oldLines.take(400).forEach { builder.append('-').append(it).append('\n') }
            return builder.toString()
        }
        if (old == null) {
            builder.append("@@ nuovo file (").append(newLines.size).append(" righe) @@\n")
            newLines.take(400).forEach { builder.append('+').append(it).append('\n') }
            return builder.toString()
        }

        if (oldLines.size > 900 || newLines.size > 900) {
            builder.append("@@ file troppo grande per un diff compatto: ").append(newLines.size)
                .append(" righe @@\n")
            newLines.take(200).forEach { builder.append(' ').append(it).append('\n') }
            return builder.toString()
        }

        val operations = diff(oldLines, newLines)
        var oldLine = 1
        var newLine = 1
        var index = 0
        while (index < operations.size) {
            // Group changes with three lines of context.
            if (operations[index].kind == ' ') {
                index++
                oldLine++
                newLine++
                continue
            }
            val hunkStart = max(0, index - 3)
            var hunkEnd = index
            var gap = 0
            var cursor = index
            while (cursor < operations.size) {
                if (operations[cursor].kind == ' ') {
                    gap++
                    if (gap > 6) break
                } else {
                    gap = 0
                    hunkEnd = cursor
                }
                cursor++
            }
            hunkEnd = min(operations.size - 1, max(hunkEnd, index) + 3)

            val contextBefore = operations.subList(hunkStart, index).count { it.kind == ' ' }
            val hunkOldStart = oldLine - contextBefore
            val hunkNewStart = newLine - contextBefore
            val oldCount = operations.subList(hunkStart, hunkEnd + 1).count { it.kind != '+' }
            val newCount = operations.subList(hunkStart, hunkEnd + 1).count { it.kind != '-' }
            builder.append("@@ -").append(hunkOldStart).append(',').append(oldCount)
                .append(" +").append(hunkNewStart).append(',').append(newCount).append(" @@\n")

            for (i in hunkStart..hunkEnd) {
                val op = operations[i]
                builder.append(op.kind).append(op.text).append('\n')
                if (op.kind != '+') oldLine++
                if (op.kind != '-') newLine++
            }
            index = hunkEnd + 1
        }
        return builder.toString()
    }

    fun stats(diff: String): Pair<Int, Int> {
        val added = diff.lineSequence().count { it.startsWith("+") && !it.startsWith("+++") }
        val removed = diff.lineSequence().count { it.startsWith("-") && !it.startsWith("---") }
        return added to removed
    }

    private data class Operation(val kind: Char, val text: String)

    private fun diff(old: List<String>, new: List<String>): List<Operation> {
        val n = old.size
        val m = new.size
        val table = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                table[i][j] = if (old[i] == new[j]) {
                    table[i + 1][j + 1] + 1
                } else {
                    max(table[i + 1][j], table[i][j + 1])
                }
            }
        }
        val result = ArrayList<Operation>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                old[i] == new[j] -> {
                    result.add(Operation(' ', old[i])); i++; j++
                }
                table[i + 1][j] >= table[i][j + 1] -> {
                    result.add(Operation('-', old[i])); i++
                }
                else -> {
                    result.add(Operation('+', new[j])); j++
                }
            }
        }
        while (i < n) {
            result.add(Operation('-', old[i])); i++
        }
        while (j < m) {
            result.add(Operation('+', new[j])); j++
        }
        return result
    }
}
