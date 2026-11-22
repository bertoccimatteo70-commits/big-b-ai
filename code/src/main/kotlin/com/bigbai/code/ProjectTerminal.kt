package com.bigbai.code

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TerminalTone { COMMAND, OUTPUT, ERROR, INFO, SUCCESS }

data class TerminalLine(val text: String, val tone: TerminalTone = TerminalTone.OUTPUT)

data class TerminalResult(
    val lines: List<TerminalLine>,
    val exitCode: Int,
    val cwd: String,
    val clearScreen: Boolean = false,
)

data class TerminalLimits(
    val maxOutputChars: Int = 20_000,
    val maxReadBytes: Int = 200_000,
    val maxFilesScanned: Int = 300,
    val maxDepth: Int = 8,
    val maxHistory: Int = 50,
    val maxArgs: Int = 16,
    val maxCommandChars: Int = 400,
    val commandTimeoutMs: Long = 15_000,
    val runtimeTimeoutMs: Long = 60_000,
)

/**
 * Everything the terminal can delegate outside the project tree. All callbacks
 * are optional: without a runtime, `run` says the platform cannot execute the
 * language instead of pretending it did.
 */
data class TerminalHooks(
    val runtime: ExecutionRuntime? = null,
    val allowNetwork: () -> Boolean = { false },
    val verify: (suspend () -> String)? = null,
    val git: (suspend (List<String>) -> String)? = null,
)

/**
 * A real terminal over the project folder.
 *
 * It is not a system shell: Android apps have no shell access to the device, so
 * claiming otherwise would be a lie. Every command below performs a real
 * operation on the Storage Access Framework tree the user granted, or on the
 * in-memory workspace in tests. Paths cannot escape that tree, output is capped,
 * and each command has a timeout.
 */
class ProjectTerminal(
    private val workspace: ProjectWorkspace,
    private val hooks: TerminalHooks = TerminalHooks(),
    private val limits: TerminalLimits = TerminalLimits(),
) {
    var cwd: String = ""
        private set

    private val entries = mutableListOf<String>()
    val history: List<String> get() = entries.toList()

    fun reset() {
        cwd = ""
        entries.clear()
    }

    suspend fun execute(raw: String): TerminalResult {
        val line = raw.trim()
        if (line.isEmpty()) return TerminalResult(emptyList(), 0, cwd)
        if (line.length > limits.maxCommandChars) {
            return failure("Comando troppo lungo (massimo ${limits.maxCommandChars} caratteri).")
        }
        val args = splitArguments(line)
            ?: return failure("Virgolette non chiuse: correggi il comando.")
        if (args.isEmpty()) return TerminalResult(emptyList(), 0, cwd)
        if (args.size - 1 > limits.maxArgs) {
            return failure("Troppi argomenti (massimo ${limits.maxArgs}).")
        }
        entries += line
        if (entries.size > limits.maxHistory) entries.removeAt(0)

        val command = args.first().lowercase(Locale.US)
        val rest = args.drop(1)
        val timeout = when (command) {
            "run", "verify", "git" -> limits.runtimeTimeoutMs
            else -> limits.commandTimeoutMs
        }
        val outcome = try {
            withTimeoutOrNull(timeout) { dispatch(command, rest) }
        } catch (cancelled: CancellationException) {
            // A user interruption must cancel the coroutine, not become output.
            throw cancelled
        } catch (error: Exception) {
            Cmd().apply { err("Errore durante «$command»: ${error.message ?: error::class.simpleName}") }
        }
        if (outcome == null) {
            return TerminalResult(
                listOf(
                    TerminalLine("Comando interrotto: ha superato ${timeout / 1000} secondi.", TerminalTone.ERROR),
                    TerminalLine("Nessun risultato parziale viene mostrato come completato.", TerminalTone.INFO),
                ),
                1,
                cwd,
            )
        }
        return TerminalResult(cap(outcome.lines), outcome.code, cwd, outcome.clear)
    }

    private fun failure(message: String): TerminalResult =
        TerminalResult(listOf(TerminalLine(message, TerminalTone.ERROR)), 1, cwd)

    private suspend fun dispatch(command: String, args: List<String>): Cmd = when (command) {
        "help", "?" -> help()
        "pwd" -> Cmd().apply { out(WorkspacePaths.display(cwd)) }
        "ls", "dir" -> ls(args)
        "cd" -> cd(args)
        "cat" -> cat(args)
        "head" -> head(args)
        "tail" -> tail(args)
        "wc" -> wc(args)
        "grep" -> grep(args)
        "find" -> find(args)
        "tree" -> tree(args)
        "mkdir" -> mkdir(args)
        "touch" -> touch(args)
        "rm", "del" -> remove(args)
        "mv", "move" -> move(args)
        "cp", "copy" -> copy(args)
        "echo" -> echo(args)
        "stat" -> stat(args)
        "sha256", "hash" -> sha256(args)
        "run" -> runFile(args)
        "verify", "verifica" -> verify()
        "git" -> git(args)
        "support", "linguaggi" -> Cmd().apply {
            LanguageSupport.all().forEach { info ->
                out("${info.language}: ${info.level.label} — ${info.reason}")
            }
        }
        "history", "cronologia" -> Cmd().apply {
            entries.forEachIndexed { index, item -> out("${index + 1}  $item") }
        }
        "clear", "cls" -> Cmd().apply { clear = true }
        "date" -> Cmd().apply { out(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())) }
        else -> Cmd().apply {
            err("Comando sconosciuto: $command")
            info("Scrivi «help» per l'elenco dei comandi disponibili.")
        }
    }

    // ------------------------------------------------------------- navigation

    private suspend fun ls(args: List<String>): Cmd {
        val cmd = Cmd()
        val long = args.any { it == "-l" || it == "--long" }
        val targets = args.filterNot { it.startsWith("-") }.ifEmpty { listOf("") }
        for (target in targets) {
            val path = WorkspacePaths.resolve(cwd, target) ?: return cmd.apply { err("Percorso non valido: $target") }
            if (path.isNotEmpty() && !workspace.isDirectory(path)) {
                val content = workspace.read(path)
                if (content == null) {
                    cmd.err("Non trovato: $target")
                } else {
                    cmd.out(if (long) "${content.toByteArray().size}\t${WorkspacePaths.name(path)}" else WorkspacePaths.name(path))
                }
                continue
            }
            val children = workspace.list(path).sortedWith(compareBy({ !it.isDirectory }, { it.path.lowercase() }))
            if (children.isEmpty()) {
                cmd.info("(cartella vuota: ${WorkspacePaths.display(path)})")
                continue
            }
            for (child in children) {
                val name = WorkspacePaths.name(child.path) + if (child.isDirectory) "/" else ""
                cmd.out(if (long) "${if (child.isDirectory) "d" else "-"}\t${child.size}\t$name" else name)
            }
        }
        return cmd
    }

    private suspend fun cd(args: List<String>): Cmd {
        val cmd = Cmd()
        if (args.size > 1) return cmd.apply { err("Uso: cd [cartella]") }
        val target = args.firstOrNull() ?: ""
        val path = WorkspacePaths.resolve(cwd, target) ?: return cmd.apply { err("Percorso non valido: $target") }
        if (path.isNotEmpty() && !workspace.isDirectory(path)) {
            return cmd.apply { err("Non è una cartella: $target") }
        }
        cwd = path
        cmd.out(WorkspacePaths.display(cwd))
        return cmd
    }

    // -------------------------------------------------------------- file read

    private suspend fun cat(args: List<String>): Cmd {
        val cmd = Cmd()
        if (args.isEmpty()) return cmd.apply { err("Uso: cat <file> [file…]") }
        for (target in args) {
            val path = WorkspacePaths.resolve(cwd, target) ?: return cmd.apply { err("Percorso non valido: $target") }
            val content = workspace.read(path) ?: return cmd.apply { err("File non leggibile o troppo grande: $target") }
            cmd.out(content.trimEnd('\n'))
        }
        return cmd
    }

    private suspend fun head(args: List<String>): Cmd = headOrTail(args, head = true)

    private suspend fun tail(args: List<String>): Cmd = headOrTail(args, head = false)

    private suspend fun headOrTail(args: List<String>, head: Boolean): Cmd {
        val cmd = Cmd()
        val parsed = parseCount(args)
        val target = parsed.second.firstOrNull()
        if (target == null) return cmd.apply { err("Uso: ${if (head) "head" else "tail"} [-n N] <file>") }
        val path = WorkspacePaths.resolve(cwd, target) ?: return cmd.apply { err("Percorso non valido: $target") }
        val content = workspace.read(path) ?: return cmd.apply { err("File non leggibile: $target") }
        val lines = content.lines()
        val selected = if (head) lines.take(parsed.first) else lines.takeLast(parsed.first)
        selected.forEach(cmd::out)
        return cmd
    }

    private fun parseCount(args: List<String>): Pair<Int, List<String>> {
        val remaining = mutableListOf<String>()
        var count = 10
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            if (arg == "-n" && index + 1 < args.size) {
                count = args[index + 1].toIntOrNull()?.coerceIn(1, 500) ?: count
                index += 2
                continue
            }
            remaining += arg
            index++
        }
        return count to remaining
    }

    private suspend fun wc(args: List<String>): Cmd {
        val cmd = Cmd()
        val options = args.filter { it.startsWith("-") }.flatMap { it.drop(1).map { flag -> flag.lowercaseChar() } }
        val targets = args.filterNot { it.startsWith("-") }
        if (targets.isEmpty()) return cmd.apply { err("Uso: wc [-l] [-w] [-c] <file>") }
        for (target in targets) {
            val path = WorkspacePaths.resolve(cwd, target) ?: return cmd.apply { err("Percorso non valido: $target") }
            val content = workspace.read(path) ?: return cmd.apply { err("File non leggibile: $target") }
            val lines = content.count { it == '\n' } + if (content.isNotEmpty() && !content.endsWith('\n')) 1 else 0
            val words = content.split(Regex("\\s+")).count { it.isNotBlank() }
            val chars = content.length
            val parts = mutableListOf<String>()
            if (options.isEmpty() || 'l' in options) parts += lines.toString()
            if (options.isEmpty() || 'w' in options) parts += words.toString()
            if (options.isEmpty() || 'c' in options) parts += chars.toString()
            cmd.out("${parts.joinToString("\t")}\t$target")
        }
        return cmd
    }

    private suspend fun grep(args: List<String>): Cmd {
        val cmd = Cmd()
        var ignoreCase = false
        var withLine = false
        val positional = mutableListOf<String>()
        for (arg in args) {
            when (arg) {
                "-i", "--ignore-case" -> ignoreCase = true
                "-n", "--line-number" -> withLine = true
                else -> if (arg.startsWith("-") && arg.length > 1) {
                    return cmd.apply { err("Opzione non supportata: $arg") }
                } else {
                    positional += arg
                }
            }
        }
        if (positional.isEmpty()) return cmd.apply { err("Uso: grep [-i] [-n] <testo> [file…]") }
        val pattern = positional.first()
        val targets = positional.drop(1)
        val files = if (targets.isEmpty()) {
            val collected = mutableListOf<WorkspaceEntry>()
            walk(cwd, 0) { entry ->
                if (!entry.isDirectory && isText(entry.path)) collected += entry
            }
            collected.take(limits.maxFilesScanned)
        } else {
            targets.mapNotNull { target ->
                val path = WorkspacePaths.resolve(cwd, target) ?: return@mapNotNull null
                WorkspaceEntry(path, false, 0)
            }
        }
        var matches = 0
        for (file in files) {
            val content = workspace.read(file.path) ?: continue
            val lines = content.lines()
            for (index in lines.indices) {
                if (matches >= 200) break
                val text = lines[index]
                val found = if (ignoreCase) text.contains(pattern, ignoreCase = true) else text.contains(pattern)
                if (found) {
                    matches++
                    cmd.out(
                        buildString {
                            append(file.path)
                            if (withLine) append(':').append(index + 1)
                            append(": ").append(text.trim())
                        },
                    )
                }
            }
            if (matches >= 200) break
        }
        if (matches == 0) cmd.info("Nessuna corrispondenza per «$pattern».")
        return cmd
    }

    private suspend fun find(args: List<String>): Cmd {
        val cmd = Cmd()
        val pattern = args.firstOrNull()?.takeIf { !it.startsWith("-") }
        val results = mutableListOf<String>()
        walk(cwd, 0) { entry ->
            if (results.size < 300) {
                val name = WorkspacePaths.name(entry.path)
                if (pattern == null || name.contains(pattern, ignoreCase = true)) {
                    results += entry.path + (if (entry.isDirectory) "/" else "")
                }
            }
        }
        if (results.isEmpty()) cmd.info("Nessun elemento trovato.") else results.forEach(cmd::out)
        return cmd
    }

    private suspend fun tree(args: List<String>): Cmd {
        val cmd = Cmd()
        val maxDepth = args.firstOrNull()?.toIntOrNull()?.coerceIn(1, limits.maxDepth) ?: limits.maxDepth
        val start = if (args.firstOrNull()?.toIntOrNull() == null) {
            WorkspacePaths.resolve(cwd, args.firstOrNull() ?: "") ?: cwd
        } else {
            cwd
        }
        cmd.out(WorkspacePaths.display(start))
        suspend fun render(directory: String, prefix: String, depth: Int) {
            if (depth >= maxDepth) return
            val children = workspace.list(directory).sortedWith(compareBy({ !it.isDirectory }, { it.path.lowercase() }))
            children.forEachIndexed { index, child ->
                val last = index == children.lastIndex
                cmd.out("$prefix${if (last) "└── " else "├── "}${WorkspacePaths.name(child.path)}${if (child.isDirectory) "/" else ""}")
                if (child.isDirectory) render(child.path, prefix + if (last) "    " else "│   ", depth + 1)
            }
        }
        render(start, "", 0)
        return cmd
    }

    // ------------------------------------------------------------- file write

    private suspend fun mkdir(args: List<String>): Cmd {
        val cmd = Cmd()
        val targets = args.filterNot { it == "-p" }
        if (targets.isEmpty()) return cmd.apply { err("Uso: mkdir <cartella>") }
        for (target in targets) {
            val path = WorkspacePaths.resolve(cwd, target) ?: return cmd.apply { err("Percorso non valido: $target") }
            if (path.isEmpty()) return cmd.apply { err("Percorso non valido: $target") }
            if (workspace.mkdir(path)) cmd.out("Creata: $path") else cmd.err("Creazione non riuscita: $target")
        }
        return cmd
    }

    private suspend fun touch(args: List<String>): Cmd {
        val cmd = Cmd()
        if (args.isEmpty()) return cmd.apply { err("Uso: touch <file>") }
        for (target in args) {
            val path = WorkspacePaths.resolve(cwd, target) ?: return cmd.apply { err("Percorso non valido: $target") }
            if (workspace.exists(path)) {
                cmd.out("Esiste già: $path")
            } else if (workspace.write(path, "")) {
                cmd.out("Creato: $path")
            } else {
                cmd.err("Creazione non riuscita: $target")
            }
        }
        return cmd
    }

    private suspend fun remove(args: List<String>): Cmd {
        val cmd = Cmd()
        val recursive = args.any { it == "-r" || it == "-rf" || it == "-R" }
        val targets = args.filterNot { it.startsWith("-") }
        if (targets.isEmpty()) return cmd.apply { err("Uso: rm [-r] <percorso>") }
        for (target in targets) {
            val path = WorkspacePaths.resolve(cwd, target) ?: return cmd.apply { err("Percorso non valido: $target") }
            if (path.isEmpty()) return cmd.apply { err("Non posso eliminare la radice del progetto.") }
            if (!workspace.exists(path)) {
                cmd.err("Non trovato: $target")
                continue
            }
            if (workspace.isDirectory(path) && !recursive) {
                cmd.err("«$target» è una cartella: aggiungi -r per eliminarla con il contenuto.")
                continue
            }
            if (workspace.delete(path)) {
                cmd.out("Eliminato: $path")
                if (cwd == path || cwd.startsWith("$path/")) cwd = WorkspacePaths.parent(path)
            } else {
                cmd.err("Eliminazione non riuscita: $target")
            }
        }
        return cmd
    }

    private suspend fun move(args: List<String>): Cmd {
        val cmd = Cmd()
        if (args.size != 2) return cmd.apply { err("Uso: mv <origine> <destinazione>") }
        val from = WorkspacePaths.resolve(cwd, args[0]) ?: return cmd.apply { err("Origine non valida") }
        val to = WorkspacePaths.resolve(cwd, args[1]) ?: return cmd.apply { err("Destinazione non valida") }
        if (from.isEmpty() || to.isEmpty()) return cmd.apply { err("Percorso non valido") }
        if (!workspace.exists(from)) return cmd.apply { err("Non trovato: ${args[0]}") }
        if (workspace.exists(to)) return cmd.apply { err("La destinazione esiste già: ${args[1]}") }
        if (workspace.rename(from, to)) cmd.out("Spostato: $from → $to") else cmd.err("Spostamento non riuscito")
        return cmd
    }

    private suspend fun copy(args: List<String>): Cmd {
        val cmd = Cmd()
        if (args.size != 2) return cmd.apply { err("Uso: cp <origine> <destinazione>") }
        val from = WorkspacePaths.resolve(cwd, args[0]) ?: return cmd.apply { err("Origine non valida") }
        val to = WorkspacePaths.resolve(cwd, args[1]) ?: return cmd.apply { err("Destinazione non valida") }
        if (workspace.isDirectory(from)) {
            return cmd.apply { err("cp copia solo file: le cartelle non sono supportate.") }
        }
        val content = workspace.read(from) ?: return cmd.apply { err("File non leggibile: ${args[0]}") }
        if (workspace.exists(to)) return cmd.apply { err("La destinazione esiste già: ${args[1]}") }
        if (workspace.write(to, content)) cmd.out("Copiato: $from → $to") else cmd.err("Copia non riuscita")
        return cmd
    }

    private suspend fun echo(args: List<String>): Cmd {
        val cmd = Cmd()
        val redirect = args.indexOfFirst { it == ">" || it == ">>" }
        if (redirect < 0) {
            cmd.out(args.joinToString(" "))
            return cmd
        }
        if (redirect == args.size - 1) return cmd.apply { err("Manca il file dopo la redirezione.") }
        val append = args[redirect] == ">>"
        val text = args.take(redirect).joinToString(" ")
        val target = args[redirect + 1]
        val path = WorkspacePaths.resolve(cwd, target) ?: return cmd.apply { err("Percorso non valido: $target") }
        val previous = if (append) workspace.read(path).orEmpty() else ""
        val content = when {
            !append -> text + "\n"
            previous.isEmpty() -> text + "\n"
            previous.endsWith("\n") -> previous + text + "\n"
            else -> "$previous\n$text\n"
        }
        if (workspace.write(path, content)) {
            cmd.out(if (append) "Aggiunto a $path" else "Scritto $path (${content.length} caratteri)")
        } else {
            cmd.err("Scrittura non riuscita: $target")
        }
        return cmd
    }

    private suspend fun stat(args: List<String>): Cmd {
        val cmd = Cmd()
        val target = args.firstOrNull() ?: return cmd.apply { err("Uso: stat <file>") }
        val path = WorkspacePaths.resolve(cwd, target) ?: return cmd.apply { err("Percorso non valido: $target") }
        if (!workspace.exists(path)) return cmd.apply { err("Non trovato: $target") }
        if (workspace.isDirectory(path)) {
            cmd.out("$path · cartella")
            return cmd
        }
        val content = workspace.read(path) ?: return cmd.apply { err("File non leggibile: $target") }
        cmd.out("$path")
        cmd.out("Byte UTF-8: ${content.toByteArray().size}")
        cmd.out("Caratteri: ${content.length}")
        cmd.out("Righe: ${content.count { it == '\n' } + 1}")
        return cmd
    }

    private suspend fun sha256(args: List<String>): Cmd {
        val cmd = Cmd()
        val target = args.firstOrNull() ?: return cmd.apply { err("Uso: sha256 <file>") }
        val path = WorkspacePaths.resolve(cwd, target) ?: return cmd.apply { err("Percorso non valido: $target") }
        val content = workspace.read(path) ?: return cmd.apply { err("File non leggibile: $target") }
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        cmd.out(digest.joinToString("") { "%02x".format(it) } + "  $path")
        return cmd
    }

    // -------------------------------------------------------- external tools

    private suspend fun runFile(args: List<String>): Cmd {
        val cmd = Cmd()
        val target = args.firstOrNull() ?: return cmd.apply { err("Uso: run <file>") }
        val path = WorkspacePaths.resolve(cwd, target) ?: return cmd.apply { err("Percorso non valido: $target") }
        val content = workspace.read(path) ?: return cmd.apply { err("File non leggibile: $target") }
        when (path.substringAfterLast('.', "").lowercase()) {
            "json" -> {
                val result = JsonValidator.validate(content)
                if (result.valid) cmd.success("JSON valido: $path") else cmd.err(result.describe())
                return cmd
            }
            "xml", "svg", "plist" -> {
                val result = XmlValidator.validate(content)
                if (result.valid) cmd.success("XML formalmente valido: $path") else {
                    result.errors.forEach { cmd.err(it) }
                }
                return cmd
            }
            "csv", "tsv" -> {
                val result = CsvValidator.validate(
                    content,
                    delimiter = if (path.endsWith(".tsv")) '\t' else ',',
                )
                if (result.valid) {
                    cmd.success("CSV valido: ${result.rows} righe, ${result.columns} colonne")
                } else {
                    cmd.err("CSV non valido: ${result.errors.first()}")
                }
                return cmd
            }
        }
        val language = when (path.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "html"
            "js", "mjs" -> "javascript"
            else -> null
        }
        if (language == null) {
            val info = LanguageSupport.forPath(path)
            if (info == null) {
                cmd.err("Estensione non riconosciuta: $path")
            } else {
                cmd.err("${info.language}: ${info.reason}")
                cmd.info("Usa «verify» per l'analisi statica del progetto.")
            }
            return cmd
        }
        val runtime = hooks.runtime ?: run {
            cmd.err("Nessun runtime di esecuzione disponibile in questo contesto.")
            return cmd
        }
        val result = runtime.execute(
            ExecutionRequest(
                path = path,
                content = content,
                language = language,
                timeoutMs = 8_000,
                allowNetwork = hooks.allowNetwork(),
            ),
        )
        result.outputText().lines().forEach { line ->
            cmd.lines += TerminalLine(
                line,
                when {
                    result.success.not() || line.startsWith("ERRORE") -> TerminalTone.ERROR
                    else -> TerminalTone.OUTPUT
                },
            )
        }
        cmd.code = if (result.success) 0 else 1
        return cmd
    }

    private suspend fun verify(): Cmd {
        val cmd = Cmd()
        val handler = hooks.verify ?: return cmd.apply {
            err("Verifica non disponibile in questo contesto.")
        }
        cmd.out(handler())
        return cmd
    }

    private suspend fun git(args: List<String>): Cmd {
        val cmd = Cmd()
        val handler = hooks.git ?: return cmd.apply { err("Git non disponibile in questo contesto.") }
        cmd.out(handler(args))
        return cmd
    }

    // ------------------------------------------------------------------ help

    private fun help(): Cmd {
        val cmd = Cmd()
        cmd.info("Terminale ITAGPT Code · ambiente: cartella del progetto (SAF), non il sistema Android.")
        cmd.out("")
        cmd.out("Navigazione")
        cmd.out("  pwd · cd <cartella> · ls [-l] [cartella] · tree [profondità] · find [testo]")
        cmd.out("Lettura")
        cmd.out("  cat <file> · head [-n N] <file> · tail [-n N] <file> · wc [-l] [-w] [-c] <file>")
        cmd.out("  grep [-i] [-n] <testo> [file…] · stat <file> · sha256 <file>")
        cmd.out("Scrittura")
        cmd.out("  mkdir <cartella> · touch <file> · echo <testo> > <file> · echo <testo> >> <file>")
        cmd.out("  cp <origine> <destinazione> · mv <origine> <destinazione> · rm [-r] <percorso>")
        cmd.out("Strumenti")
        cmd.out("  run <file>: esegue HTML/JavaScript nella sandbox; valida JSON, XML e CSV")
        cmd.out("  verify: esegue il controllo reale del progetto (statica + validazione + runtime)")
        cmd.out("  git status|log|diff: interroga il repository locale del progetto")
        cmd.out("  support: elenca i linguaggi e cosa è realmente possibile")
        cmd.out("  history · clear · date · help")
        cmd.out("")
        cmd.info("Limiti: percorsi solo dentro il progetto, niente «..», niente shell di sistema.")
        cmd.info("Output massimo ${limits.maxOutputChars} caratteri, timeout ${limits.commandTimeoutMs / 1000} s per comando.")
        return cmd
    }

    // --------------------------------------------------------------- helpers

    private suspend fun walk(directory: String, depth: Int, action: suspend (WorkspaceEntry) -> Unit) {
        if (depth > limits.maxDepth) return
        val children = workspace.list(directory)
        for (child in children.take(limits.maxFilesScanned)) {
            action(child)
            if (child.isDirectory) walk(child.path, depth + 1, action)
        }
    }

    private fun isText(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS

    private fun cap(lines: List<TerminalLine>): List<TerminalLine> {
        val out = mutableListOf<TerminalLine>()
        var total = 0
        for (line in lines) {
            total += line.text.length + 1
            if (total > limits.maxOutputChars) {
                out += TerminalLine(
                    "… output troncato dopo ${limits.maxOutputChars} caratteri",
                    TerminalTone.INFO,
                )
                break
            }
            out += line
        }
        return out
    }

    /** Quote-aware argument split. Returns null on an unclosed quote. */
    internal fun splitArguments(line: String): List<String>? {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var started = false
        for (char in line) {
            if (escaped) {
                current.append(char)
                escaped = false
                started = true
                continue
            }
            when {
                char == '\\' -> {
                    escaped = true
                    started = true
                }
                quote != null -> if (char == quote) {
                    quote = null
                } else {
                    current.append(char)
                }
                char == '"' || char == '\'' -> {
                    quote = char
                    started = true
                }
                char.isWhitespace() -> {
                    if (started) {
                        result += current.toString()
                        current.clear()
                        started = false
                    }
                }
                else -> {
                    current.append(char)
                    started = true
                }
            }
        }
        if (quote != null) return null
        if (escaped) current.append('\\')
        if (started) result += current.toString()
        return result
    }

    private class Cmd {
        val lines = mutableListOf<TerminalLine>()
        var code = 0
        var clear = false

        fun out(text: String) {
            lines += TerminalLine(text)
        }

        fun info(text: String) {
            lines += TerminalLine(text, TerminalTone.INFO)
        }

        fun err(text: String) {
            lines += TerminalLine(text, TerminalTone.ERROR)
            code = 1
        }

        fun success(text: String) {
            lines += TerminalLine(text, TerminalTone.SUCCESS)
        }
    }

    private companion object {
        val TEXT_EXTENSIONS = setOf(
            "html", "htm", "css", "js", "mjs", "ts", "json", "xml", "md", "txt",
            "java", "kt", "kts", "c", "h", "cpp", "cc", "hpp", "cmake", "gradle",
            "properties", "yml", "yaml", "sql", "sh", "py", "csv", "tsv", "toml",
        )
    }
}
