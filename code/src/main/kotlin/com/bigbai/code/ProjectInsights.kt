package com.bigbai.code

import com.bigbai.code.DiagnosticSeverity.ERROR
import com.bigbai.code.DiagnosticSeverity.INFO
import com.bigbai.code.DiagnosticSeverity.WARNING

enum class InsightKind { DUPLICATE_CODE, MISSING_DEPENDENCY, TODO, LARGE_FILE, STRUCTURE }

data class ProjectInsight(
    val kind: InsightKind,
    val path: String,
    val line: Int,
    val message: String,
    val severity: DiagnosticSeverity,
)

/**
 * Project-wide analysis that a single-file analyzer cannot do.
 *
 * It looks for duplicated blocks across files, imports that no dependency
 * declares, unfinished work markers, oversized files and the overall shape of
 * the project. Everything is deterministic and local: the report states what it
 * actually checked, and never claims to have compiled anything.
 */
object ProjectInsights {

    private val CODE_EXTENSIONS = setOf(
        "kt", "kts", "java", "js", "jsx", "ts", "tsx", "py", "c", "h", "cpp", "cc", "hpp",
        "css", "html", "htm", "xml", "gradle", "sh",
    )

    private val NODE_BUILTINS = setOf(
        "assert", "buffer", "child_process", "cluster", "crypto", "dgram", "dns", "events",
        "fs", "http", "http2", "https", "net", "os", "path", "perf_hooks", "process",
        "querystring", "readline", "stream", "string_decoder", "timers", "tls", "tty",
        "url", "util", "v8", "vm", "worker_threads", "zlib",
    )

    private val PYTHON_STDLIB = setOf(
        "abc", "argparse", "asyncio", "base64", "collections", "contextlib", "copy", "csv",
        "dataclasses", "datetime", "decimal", "enum", "functools", "glob", "hashlib", "heapq",
        "html", "http", "importlib", "io", "itertools", "json", "logging", "math", "os",
        "pathlib", "pickle", "random", "re", "shutil", "signal", "socket", "sqlite3", "ssl",
        "statistics", "string", "struct", "subprocess", "sys", "tempfile", "threading", "time",
        "tkinter", "typing", "unittest", "urllib", "uuid", "venv", "warnings", "xml", "zipfile",
    )

    fun analyze(files: Map<String, String>, maxFiles: Int = 200): List<ProjectInsight> {
        // Dependency declarations are not source files, but they must be in the
        // map for the import analysis to know what is declared.
        val selected = files.entries
            .filter { (path, content) ->
                content.isNotBlank() && (isCode(path) || path in MANIFESTS)
            }
            .take(maxFiles)
        if (selected.isEmpty()) return emptyList()
        val map = selected.associate { it.key to it.value }
        val insights = mutableListOf<ProjectInsight>()
        insights += duplicates(map)
        insights += missingDependencies(map)
        insights += markers(map)
        insights += largeFiles(map)
        return insights.sortedWith(
            compareBy<ProjectInsight> { it.severity.ordinal }.thenBy { it.path }.thenBy { it.line },
        ).take(40)
    }

    /** Human-readable project shape: file count by language and entry points. */
    fun summary(files: Map<String, String>): String {
        if (files.isEmpty()) return "Nessun file di testo nel progetto."
        val byExtension = files.keys
            .groupingBy { it.substringAfterLast('.', "senza estensione").lowercase() }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(8)
        val builder = StringBuilder()
        builder.append("File di testo: ").append(files.size).append('\n')
        builder.append("Per tipo: ")
        builder.append(byExtension.joinToString(", ") { "${it.key} ${it.value}" })
        builder.append('\n')
        val entryPoints = ENTRY_POINTS.filter { files.containsKey(it) }
        if (entryPoints.isNotEmpty()) {
            builder.append("Punti di ingresso: ").append(entryPoints.joinToString(", ")).append('\n')
        }
        val tests = files.keys.filter { path ->
            val lower = path.lowercase()
            lower.contains("test") || lower.contains("spec") || lower.endsWith("_test.py")
        }
        builder.append("File di test riconosciuti: ").append(tests.size).append('\n')
        builder.append("Righe totali: ").append(files.values.sumOf { it.lineSequence().count() })
        return builder.toString().trim()
    }

    /**
     * Markdown report of what an agent task changed and which checks ran.
     * It is written from real data: the paths that were written and the
     * diagnostics that were actually produced.
     */
    fun changeReport(
        written: List<String>,
        diagnostics: Map<String, List<Diagnostic>>,
        checks: List<String>,
    ): String {
        val builder = StringBuilder()
        builder.append("### Report delle modifiche\n")
        builder.append("File scritti: ").append(written.distinct().size).append('\n')
        written.distinct().forEach { path ->
            val found = diagnostics[path].orEmpty()
            val errors = found.count { it.severity == ERROR }
            val warnings = found.count { it.severity == WARNING }
            builder.append("- ").append(path)
            if (found.isNotEmpty()) builder.append(" — $errors errori, $warnings avvisi")
            builder.append('\n')
        }
        val totalErrors = diagnostics.values.flatten().count { it.severity == ERROR }
        builder.append("Errori statici totali: ").append(totalErrors).append('\n')
        if (checks.isNotEmpty()) {
            builder.append("Controlli eseguiti:\n")
            checks.forEach { builder.append("- ").append(it).append('\n') }
        }
        builder.append(
            "Nota: analisi statica e, quando il runtime è disponibile, esecuzione in sandbox. " +
                "Nessuna compilazione è stata eseguita: Android non include javac, kotlinc o clang.",
        )
        return builder.toString().trim()
    }

    // -------------------------------------------------------------- duplicates

    private const val BLOCK_LINES = 8
    private const val MAX_DUPLICATES = 8

    private fun duplicates(files: Map<String, String>): List<ProjectInsight> {
        val blocks = HashMap<Int, MutableList<Pair<String, Int>>>()
        for ((path, content) in files) {
            if (!isCode(path)) continue
            val lines = content.lines()
            val normalized = lines.map { normalizeLine(it) }
            for (start in 0..(lines.size - BLOCK_LINES)) {
                val window = normalized.subList(start, start + BLOCK_LINES)
                if (window.any { it.isEmpty() }) continue
                val joined = window.joinToString("\n")
                if (joined.length < 160) continue
                blocks.getOrPut(joined.hashCode()) { mutableListOf() }.add(path to start + 1)
            }
        }
        val insights = mutableListOf<ProjectInsight>()
        val seenPairs = HashSet<String>()
        for ((_, locations) in blocks) {
            if (locations.size < 2) continue
            val first = locations.first()
            val other = locations.firstOrNull { it.first != first.first } ?: continue
            val key = listOf(first.first, other.first).sorted().joinToString("|")
            if (!seenPairs.add(key)) continue
            insights.add(
                ProjectInsight(
                    kind = InsightKind.DUPLICATE_CODE,
                    path = other.first,
                    line = other.second,
                    message = "Blocco di $BLOCK_LINES righe duplicato da ${first.first}:${first.second}",
                    severity = WARNING,
                ),
            )
            if (insights.size >= MAX_DUPLICATES) break
        }
        return insights
    }

    private fun normalizeLine(line: String): String {
        val trimmed = line.trim().replace(Regex("""\s+"""), " ")
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("*")) return ""
        // Braces are structural, not noise: keeping them lets blocks with a
        // closing brace still be compared, while blank lines still break a
        // window (they separate unrelated code).
        return trimmed
    }

    // ---------------------------------------------------------- dependencies

    private fun missingDependencies(files: Map<String, String>): List<ProjectInsight> {
        val insights = mutableListOf<ProjectInsight>()
        val packageJson = files["package.json"]
        val declared = packageJson?.let { json ->
            Regex("""["'](@?[\w./-]+)["']\s*:\s*["'][~^]?\d""").findAll(json)
                .map { match ->
                    val name = match.groupValues[1]
                    if (name.startsWith("@")) {
                        name.split('/').take(2).joinToString("/")
                    } else {
                        name.substringBefore('/')
                    }
                }
                .toSet()
        }.orEmpty()
        val jsFiles = files.filterKeys { it.endsWith(".js") || it.endsWith(".jsx") || it.endsWith(".ts") || it.endsWith(".tsx") }
        if (jsFiles.isNotEmpty()) {
            val imports = LinkedHashMap<String, String>()
            val patterns = listOf(
                Regex("""\bfrom\s+["']([^"']+)["']"""),
                Regex("""\brequire\(\s*["']([^"']+)["']\s*\)"""),
                Regex("""\bimport\s+["']([^"']+)["']"""),
            )
            for ((path, content) in jsFiles) {
                for (pattern in patterns) {
                    pattern.findAll(content).forEach { match ->
                        val specifier = match.groupValues[1]
                        if (specifier.startsWith(".") || specifier.startsWith("/")) return@forEach
                        val root = if (specifier.startsWith("@")) {
                            specifier.split('/').take(2).joinToString("/")
                        } else {
                            specifier.substringBefore('/')
                        }
                        if (root in NODE_BUILTINS || root.startsWith("node:")) return@forEach
                        imports.putIfAbsent(root, path)
                    }
                }
            }
            if (packageJson == null && imports.isNotEmpty()) {
                insights.add(
                    ProjectInsight(
                        InsightKind.MISSING_DEPENDENCY,
                        imports.values.first(),
                        1,
                        "package.json assente: le dipendenze importate non sono dichiarate",
                        INFO,
                    ),
                )
            } else {
                imports.filterKeys { it !in declared }.entries.take(6).forEach { entry ->
                    insights.add(
                        ProjectInsight(
                            InsightKind.MISSING_DEPENDENCY,
                            entry.value,
                            1,
                            "Dipendenza «${entry.key}» importata ma non dichiarata in package.json",
                            WARNING,
                        ),
                    )
                }
            }
        }

        val requirements = files["requirements.txt"] ?: files["pyproject.toml"]
        val pyFiles = files.filterKeys { it.endsWith(".py") }
        if (pyFiles.isNotEmpty()) {
            val imported = LinkedHashMap<String, String>()
            val importPattern = Regex("""(?m)^\s*(?:from|import)\s+([a-zA-Z_][\w.]*)""")
            for ((path, content) in pyFiles) {
                importPattern.findAll(content).forEach { match ->
                    val root = match.groupValues[1].substringBefore('.')
                    if (root !in PYTHON_STDLIB) imported.putIfAbsent(root, path)
                }
            }
            if (requirements == null && imported.isNotEmpty()) {
                insights.add(
                    ProjectInsight(
                        InsightKind.MISSING_DEPENDENCY,
                        imported.values.first(),
                        1,
                        "Nessun requirements.txt/pyproject.toml: le dipendenze Python non sono dichiarate",
                        INFO,
                    ),
                )
            } else if (requirements != null) {
                imported.filterKeys { !requirements.contains(it, ignoreCase = true) }.entries.take(4)
                    .forEach { entry ->
                        insights.add(
                            ProjectInsight(
                                InsightKind.MISSING_DEPENDENCY,
                                entry.value,
                                1,
                                "Modulo Python «${entry.key}» importato ma non elencato nelle dipendenze",
                                WARNING,
                            ),
                        )
                    }
            }
        }
        return insights
    }

    // --------------------------------------------------------------- markers

    private fun markers(files: Map<String, String>): List<ProjectInsight> {
        val insights = mutableListOf<ProjectInsight>()
        val pattern = Regex("""(?m)^\s*(?://|#|/\*|\*)?\s*(TODO|FIXME|HACK|XXX)\b[:\s]*(.*)$""")
        for ((path, content) in files) {
            if (!isCode(path)) continue
            val matches = pattern.findAll(content).toList()
            if (matches.isEmpty()) continue
            val first = matches.first()
            val line = content.take(first.range.first).count { it == '\n' } + 1
            insights.add(
                ProjectInsight(
                    InsightKind.TODO,
                    path,
                    line,
                    "${matches.size} attività incompleta/e (TODO/FIXME) nel file",
                    INFO,
                ),
            )
            if (insights.size >= 6) break
        }
        return insights
    }

    private fun largeFiles(files: Map<String, String>): List<ProjectInsight> = files.mapNotNull { (path, content) ->
        val lines = content.lineSequence().count()
        if (lines > 1500) {
            ProjectInsight(
                InsightKind.LARGE_FILE,
                path,
                1,
                "File molto grande ($lines righe): valuta di dividerlo",
                WARNING,
            )
        } else {
            null
        }
    }.take(4)

    private fun isCode(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in CODE_EXTENSIONS

    private val MANIFESTS = setOf("package.json", "requirements.txt", "pyproject.toml")

    private val ENTRY_POINTS = listOf(
        "index.html", "main.py", "app.py", "Main.java", "Main.kt", "App.kt",
        "package.json", "build.gradle.kts", "CMakeLists.txt", "README.md",
    )
}
