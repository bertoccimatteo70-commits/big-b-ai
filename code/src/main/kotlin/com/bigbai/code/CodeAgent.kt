package com.bigbai.code

/**
 * A single operation the model asked ITAGPT Code to perform on the project.
 *
 * The protocol is intentionally textual instead of JSON: small local models
 * (1–3 B parameters) emit malformed JSON far more often than a tagged block,
 * and a tagged block can be parsed even when it is wrapped in a Markdown fence.
 */
sealed interface AgentAction {
    data class WriteFile(val path: String, val content: String) : AgentAction
    data class ReadFile(val path: String) : AgentAction
    data class DeleteFile(val path: String) : AgentAction
    data object ListFiles : AgentAction
    data class Finish(val summary: String) : AgentAction
    data class Invalid(val reason: String) : AgentAction
}

enum class AgentStepStatus { RUNNING, DONE, FAILED, NEEDS_USER }

/** One line of the activity log shown while the agent works. */
data class AgentStep(
    val label: String,
    val detail: String? = null,
    val status: AgentStepStatus = AgentStepStatus.DONE,
)

/**
 * Parses and validates the model output. Everything here is pure so the rules
 * that protect the project can be unit tested without Android or a model.
 */
object CodeAgent {

    /**
     * Hard limits: a single user request cannot turn into unbounded work, but
     * five steps were too few for a real multi-file task. Twelve steps let the
     * agent read, write, verify and finish; the user can still write "continua"
     * for a new budget, and the per-file/action caps stay in force.
     */
    const val MAX_STEPS = 12
    const val MAX_ACTIONS_PER_STEP = 6
    const val MAX_FILES_PER_TASK = 40
    const val MAX_FILE_BYTES = 200_000
    const val MAX_CONTEXT_CHARS = 24_000
    const val MAX_READ_CHARS = 12_000

    private val TOOL_BLOCK = Regex(
        """<tool\s+name\s*=\s*"([a-zA-Z_]+)"(?:\s+path\s*=\s*"([^"]*)")?\s*>""",
    )
    private val TOOL_CLOSE = Regex("""</tool>""")
    private val SELF_CLOSING = Regex(
        """<tool\s+name\s*=\s*"([a-zA-Z_]+)"(?:\s+path\s*=\s*"([^"]*)")?\s*/>""",
    )
    private val LEGACY_FILE = Regex("""<<<<<<< FILE:\s*([^\n]+)\n""")
    private val LEGACY_DELETE = Regex("""<<<<<<< DELETE:\s*([^\n]+)""")
    private val LEGACY_END = Regex(""">>>>>>> END""")

    /**
     * Normalises a model-supplied path. Returns null for anything that could
     * escape the authorised project folder or hide files from the user.
     */
    fun sanitizePath(raw: String): String? {
        val trimmed = raw.replace('\\', '/').trim()
        // Absolute paths are rejected, not silently made relative.
        if (trimmed.startsWith("/")) return null
        if (Regex("^[A-Za-z]:/").containsMatchIn(trimmed)) return null
        val normalised = trimmed.removePrefix("./").trim()
        if (normalised.isBlank()) return null
        if (normalised.length > 180) return null
        if (normalised.contains("..")) return null
        if (normalised.startsWith(".")) return null
        val segments = normalised.split('/')
        if (segments.any { it.isBlank() || it == "." }) return null
        if (segments.any { it.startsWith(".") }) return null
        return normalised
    }

    fun isDestructive(action: AgentAction): Boolean = action is AgentAction.DeleteFile

    fun parse(response: String): List<AgentAction> {
        val actions = mutableListOf<AgentAction>()
        var cursor = 0
        while (actions.size < MAX_ACTIONS_PER_STEP) {
            val match = TOOL_BLOCK.find(response, cursor)
            val selfClosing = SELF_CLOSING.find(response, cursor)
            val useSelfClosing = selfClosing != null &&
                (match == null || selfClosing.range.first < match.range.first)
            if (useSelfClosing) {
                selfClosing?.let { found ->
                    actionFor(found.groupValues[1], found.groupValues[2], "")?.let(actions::add)
                    cursor = found.range.last + 1
                }
                continue
            }
            if (match == null) break
            val name = match.groupValues[1]
            val path = match.groupValues[2]
            val contentStart = match.range.last + 1
            val close = TOOL_CLOSE.find(response, contentStart)
            if (close == null) {
                // A truncated block is an error, not permission to write half a file.
                actions.add(AgentAction.Invalid("Blocco «$name» non chiuso"))
                break
            }
            val body = response.substring(contentStart, close.range.first)
            actionFor(name, path, body)?.let(actions::add)
            cursor = close.range.last + 1
        }

        if (actions.isEmpty()) {
            actions.addAll(parseLegacy(response))
        }
        return actions
    }

    private fun actionFor(name: String, rawPath: String, body: String): AgentAction? {
        val path = sanitizePath(rawPath)
        return when (name.lowercase()) {
            "write_file", "create_file", "write", "create" -> {
                if (path == null) return AgentAction.Invalid("Percorso non valido: «$rawPath»")
                val content = stripFence(body)
                if (content.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
                    AgentAction.Invalid("File troppo grande: $path")
                } else {
                    AgentAction.WriteFile(path, content)
                }
            }
            "read_file", "read", "open_file" ->
                if (path == null) AgentAction.Invalid("Percorso non valido: «$rawPath»")
                else AgentAction.ReadFile(path)
            "delete_file", "delete", "remove_file" ->
                if (path == null) AgentAction.Invalid("Percorso non valido: «$rawPath»")
                else AgentAction.DeleteFile(path)
            "list_files", "list", "ls" -> AgentAction.ListFiles
            "finish", "done", "complete" -> AgentAction.Finish(body.trim().ifBlank { "Attività completata." })
            else -> AgentAction.Invalid("Strumento sconosciuto: $name")
        }
    }

    /** Removes a single Markdown fence that some models wrap around file content. */
    internal fun stripFence(body: String): String {
        var text = body
        if (text.startsWith("\n")) text = text.substring(1)
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return text.trimEnd('\n')
        val firstLineEnd = trimmed.indexOf('\n')
        if (firstLineEnd < 0) return text.trimEnd('\n')
        val withoutHeader = trimmed.substring(firstLineEnd + 1)
        val closing = withoutHeader.lastIndexOf("```")
        if (closing < 0) return withoutHeader.trimEnd('\n')
        return withoutHeader.substring(0, closing).trimEnd('\n')
    }

    private fun parseLegacy(response: String): List<AgentAction> {
        val actions = mutableListOf<AgentAction>()
        val delete = LEGACY_DELETE.find(response)
        if (delete != null) {
            val path = sanitizePath(delete.groupValues[1].trim())
            if (path != null) actions.add(AgentAction.DeleteFile(path))
        }
        var cursor = 0
        while (actions.size < MAX_ACTIONS_PER_STEP) {
            val header = LEGACY_FILE.find(response, cursor) ?: break
            val path = sanitizePath(header.groupValues[1].trim())
            val contentStart = header.range.last + 1
            val end = LEGACY_END.find(response, contentStart) ?: break
            val content = response.substring(contentStart, end.range.first).trimEnd('\n')
            if (path != null && content.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) {
                actions.add(AgentAction.WriteFile(path, content))
            } else if (path == null) {
                actions.add(AgentAction.Invalid("Percorso non valido: «${header.groupValues[1].trim()}»"))
            }
            cursor = end.range.last + 1
        }
        return actions
    }

    /**
     * System prompt for the agent. It documents the exact protocol, the limits
     * and the honesty rules: no invented builds, no silent deletions.
     */
    fun systemPrompt(
        language: String,
        projectName: String,
        tree: String,
        projectContext: String,
        step: Int,
        maxSteps: Int,
    ): String {
        val italian = language.startsWith("it")
        val rules = if (italian) {
            """
            Sei ITAGPT Code, l'assistente di programmazione locale di ITAGPT.
            Lavori da solo sul progetto «$projectName», in una cartella autorizzata.

            COME LAVORI
            - Hai strumenti reali: scrivi, leggi, elenchi ed elimini file del progetto.
            - Usa uno strumento per volta con questo formato esatto:
              <tool name="write_file" path="index.html">
              contenuto completo del file
              </tool>
              <tool name="read_file" path="src/app.js"/>
              <tool name="list_files"/>
              <tool name="delete_file" path="vecchio.txt"/>
              <tool name="finish">riassunto di ciò che hai fatto</tool>
            - Scrivi sempre il contenuto COMPLETO del file, mai frammenti o "...".
            - Prima di modificare un file che non hai creato in questa attività, leggilo.
            - Quando l'obiettivo è raggiunto usa <tool name="finish">…</tool>.
            - Se manca un'informazione davvero indispensabile, chiedila con una sola domanda e fermati.

            REGOLE
            - Non inventare file, API, compilazioni o test superati.
            - Non dire di aver fatto qualcosa che non hai fatto: l'app esegue solo gli strumenti.
            - Non eliminare file senza che sia necessario; l'eliminazione richiede conferma.
            - Il progetto è tuo: niente percorsi assoluti, niente "..".
            - Passo $step di $maxSteps. Sei autonomo: non chiedere conferma per creare o modificare file.
            """.trimIndent()
        } else {
            """
            You are ITAGPT Code, the local programming assistant of ITAGPT.
            You work alone on the project "$projectName", inside an authorised folder.

            HOW YOU WORK
            - You have real tools: write, read, list and delete project files.
            - Use one tool at a time with exactly this format:
              <tool name="write_file" path="index.html">
              full file content
              </tool>
              <tool name="read_file" path="src/app.js"/>
              <tool name="list_files"/>
              <tool name="delete_file" path="old.txt"/>
              <tool name="finish">summary of what you did</tool>
            - Always write the COMPLETE file content, never fragments or "...".
            - Read a file before changing one you did not create in this task.
            - When the goal is reached use <tool name="finish">…</tool>.
            - If a truly essential detail is missing, ask one question and stop.

            RULES
            - Never invent files, APIs, builds or passing tests.
            - Never claim to have done something the app did not execute.
            - Do not delete files unless necessary; deletion needs confirmation.
            - Stay inside the project: no absolute paths, no "..".
            - Step $step of $maxSteps. Be autonomous: do not ask permission to create or edit files.
            """.trimIndent()
        }
        return buildString {
            appendLine(rules)
            if (tree.isNotBlank()) {
                appendLine()
                appendLine(if (italian) "STRUTTURA ATTUALE" else "CURRENT TREE")
                appendLine(tree.take(2500))
            }
            if (projectContext.isNotBlank()) {
                appendLine()
                appendLine(if (italian) "FILE DEL PROGETTO (estratto)" else "PROJECT FILES (excerpt)")
                appendLine(projectContext.take(MAX_CONTEXT_CHARS))
            }
        }.trim()
    }
}
