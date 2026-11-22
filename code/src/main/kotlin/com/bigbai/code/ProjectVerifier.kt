package com.bigbai.code

enum class CheckCategory(val label: String) {
    STATIC_ANALYSIS("Analisi statica"),
    DATA_VALIDATION("Validazione dati"),
    EXECUTION("Esecuzione"),
    COMPILATION("Compilazione"),
    TESTS("Test"),
}

enum class CheckStatus(val label: String) {
    PASSED("superato"),
    WARNING("avviso"),
    FAILED("fallito"),
    UNSUPPORTED("non disponibile"),
    INFO("informativo"),
}

data class VerificationCheck(
    val name: String,
    val category: CheckCategory,
    val status: CheckStatus,
    val detail: String,
    val issues: List<String> = emptyList(),
    val durationMs: Long = 0,
)

data class VerificationReport(
    val checks: List<VerificationCheck>,
    val files: Int,
    val generatedAt: Long = System.currentTimeMillis(),
    /** Raw result of the real execution, when a runtime ran a file. */
    val execution: ExecutionResult? = null,
) {
    val failed: List<VerificationCheck> get() = checks.filter { it.status == CheckStatus.FAILED }
    val warnings: List<VerificationCheck> get() = checks.filter { it.status == CheckStatus.WARNING }
    val unsupported: List<VerificationCheck> get() = checks.filter { it.status == CheckStatus.UNSUPPORTED }
    val passed: Boolean get() = failed.isEmpty()

    fun summary(): String {
        if (checks.isEmpty()) return "Nessun file da verificare."
        return buildString {
            append("${checks.size} controlli su $files file: ")
            append("${checks.count { it.status == CheckStatus.PASSED }} superati, ")
            append("${warnings.size} avvisi, ")
            append("${failed.size} falliti, ")
            append("${unsupported.size} non disponibili.")
        }
    }

    /** Plain-text report for the terminal and the UI. */
    fun toText(): String = buildString {
        appendLine("VERIFICA DEL PROGETTO — ${checks.size} controlli su $files file")
        appendLine(summary())
        for (category in CheckCategory.entries) {
            val group = checks.filter { it.category == category }
            if (group.isEmpty()) continue
            appendLine()
            appendLine("[${category.label}]")
            for (check in group) {
                appendLine("${marker(check.status)} ${check.name}: ${check.detail}")
                check.issues.take(8).forEach { appendLine("    • $it") }
                if (check.issues.size > 8) appendLine("    • … altri ${check.issues.size - 8}")
            }
        }
        appendLine()
        append("Nota: «non disponibile» significa che il controllo non è stato eseguito, non che sia passato.")
    }.trim()

    private fun marker(status: CheckStatus): String = when (status) {
        CheckStatus.PASSED -> "[OK]"
        CheckStatus.WARNING -> "[!]"
        CheckStatus.FAILED -> "[X]"
        CheckStatus.UNSUPPORTED -> "[—]"
        CheckStatus.INFO -> "[i]"
    }
}

data class VerifyOptions(
    val maxFiles: Int = 200,
    val maxFileChars: Int = 200_000,
    val runHtml: Boolean = true,
    val runJavaScript: Boolean = true,
)

/**
 * Real project verification.
 *
 * Each check either runs a real operation (static analysis, a real JSON/XML/CSV
 * parser, the isolated WebView runtime) or is reported as unavailable. Nothing
 * here calls a compiler: Android does not ship javac, kotlinc or clang for apps,
 * and the report says so instead of inventing a build.
 */
class ProjectVerifier(private val runtime: ExecutionRuntime? = null) {

    suspend fun verify(files: Map<String, String>, options: VerifyOptions = VerifyOptions()): VerificationReport {
        val selected = files.entries
            .filter { (path, content) -> content.isNotBlank() && content.length <= options.maxFileChars }
            .take(options.maxFiles)
        val checks = mutableListOf<VerificationCheck>()
        if (selected.isEmpty()) {
            return VerificationReport(emptyList(), 0)
        }
        val map = selected.associate { it.key to it.value }
        checks += staticAnalysis(map)
        checks += jsonCheck(map)
        checks += xmlCheck(map)
        checks += csvCheck(map)
        checks += localIncludes(map)
        checks += dependencies(map)
        checks += compilation(map)
        val executionResult = execution(map, options)
        checks += executionResult.first
        checks += testPresence(map)
        return VerificationReport(checks, map.size, execution = executionResult.second)
    }

    // ------------------------------------------------------- static analysis

    private fun staticAnalysis(files: Map<String, String>): VerificationCheck {
        val started = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var analyzed = 0
        for ((path, content) in files) {
            val diagnostics = StaticAnalyzers.analyze(path, content)
            if (diagnostics.isEmpty() && !isAnalyzable(path)) continue
            analyzed++
            diagnostics.forEach { diagnostic ->
                val line = "${path}:${diagnostic.line} — ${diagnostic.message}"
                when (diagnostic.severity) {
                    DiagnosticSeverity.ERROR -> errors += line
                    DiagnosticSeverity.WARNING -> warnings += line
                    DiagnosticSeverity.INFO -> Unit
                }
            }
        }
        return VerificationCheck(
            name = "Analisi statica",
            category = CheckCategory.STATIC_ANALYSIS,
            status = when {
                errors.isNotEmpty() -> CheckStatus.FAILED
                warnings.isNotEmpty() -> CheckStatus.WARNING
                analyzed == 0 -> CheckStatus.INFO
                else -> CheckStatus.PASSED
            },
            detail = when {
                analyzed == 0 -> "Nessun file analizzabile tra quelli selezionati."
                errors.isEmpty() && warnings.isEmpty() -> "$analyzed file analizzati, nessun errore o avviso."
                else -> "$analyzed file analizzati: ${errors.size} errori, ${warnings.size} avvisi."
            },
            issues = (errors + warnings).take(12),
            durationMs = System.currentTimeMillis() - started,
        )
    }

    // ------------------------------------------------------ data validation

    private fun jsonCheck(files: Map<String, String>): List<VerificationCheck> {
        val json = files.filterKeys { it.endsWith(".json", ignoreCase = true) }
        if (json.isEmpty()) return emptyList()
        val issues = mutableListOf<String>()
        var valid = 0
        for ((path, content) in json) {
            val result = JsonValidator.validate(content)
            if (result.valid) valid++ else issues += "$path: ${result.describe()}"
        }
        return listOf(
            VerificationCheck(
                name = "Documenti JSON",
                category = CheckCategory.DATA_VALIDATION,
                status = if (issues.isEmpty()) CheckStatus.PASSED else CheckStatus.FAILED,
                detail = "$valid/${json.size} documenti JSON validi.",
                issues = issues,
            ),
        )
    }

    private fun xmlCheck(files: Map<String, String>): List<VerificationCheck> {
        val xml = files.filterKeys {
            val extension = it.substringAfterLast('.', "").lowercase()
            extension == "xml" || extension == "svg" || extension == "plist"
        }
        if (xml.isEmpty()) return emptyList()
        val issues = mutableListOf<String>()
        var valid = 0
        for ((path, content) in xml) {
            val result = XmlValidator.validate(content)
            if (result.valid) valid++ else issues += "$path: ${result.errors.first()}"
        }
        return listOf(
            VerificationCheck(
                name = "Documenti XML",
                category = CheckCategory.DATA_VALIDATION,
                status = if (issues.isEmpty()) CheckStatus.PASSED else CheckStatus.FAILED,
                detail = "$valid/${xml.size} documenti formalmente validi.",
                issues = issues,
            ),
        )
    }

    private fun csvCheck(files: Map<String, String>): List<VerificationCheck> {
        val csv = files.filterKeys {
            it.endsWith(".csv", ignoreCase = true) || it.endsWith(".tsv", ignoreCase = true)
        }
        if (csv.isEmpty()) return emptyList()
        val issues = mutableListOf<String>()
        var valid = 0
        for ((path, content) in csv) {
            val result = CsvValidator.validate(content, if (path.endsWith(".tsv", true)) '\t' else ',')
            if (result.valid) valid++ else issues += "$path: ${result.errors.first()}"
        }
        return listOf(
            VerificationCheck(
                name = "File CSV/TSV",
                category = CheckCategory.DATA_VALIDATION,
                status = if (issues.isEmpty()) CheckStatus.PASSED else CheckStatus.FAILED,
                detail = "$valid/${csv.size} file con righe e colonne coerenti.",
                issues = issues,
            ),
        )
    }

    // ------------------------------------------------------ C/C++ includes

    private fun localIncludes(files: Map<String, String>): List<VerificationCheck> {
        val sources = files.filterKeys {
            it.substringAfterLast('.', "").lowercase() in setOf("c", "h", "cpp", "cc", "hpp")
        }
        if (sources.isEmpty()) return emptyList()
        val issues = mutableListOf<String>()
        val pattern = Regex("#\\s*include\\s+\"([^\"]+)\"")
        for ((path, content) in sources) {
            val directory = path.substringBeforeLast('/', "")
            for (match in pattern.findAll(content)) {
                val include = match.groupValues[1]
                val relative = if (directory.isEmpty()) include else "$directory/$include"
                val fromRoot = include
                if (!files.containsKey(relative) && !files.containsKey(fromRoot)) {
                    issues += "$path: include locale non trovato «$include»"
                }
            }
        }
        return listOf(
            VerificationCheck(
                name = "Include locali C/C++",
                category = CheckCategory.STATIC_ANALYSIS,
                status = if (issues.isEmpty()) CheckStatus.PASSED else CheckStatus.FAILED,
                detail = if (issues.isEmpty()) {
                    "Tutti gli header locali inclusi sono presenti nel progetto."
                } else {
                    "${issues.size} include locali non risolti."
                },
                issues = issues,
            ),
        )
    }

    // -------------------------------------------------------- dependencies

    private fun dependencies(files: Map<String, String>): List<VerificationCheck> {
        val insights = ProjectInsights.analyze(files).filter {
            it.kind == InsightKind.MISSING_DEPENDENCY
        }
        if (insights.isEmpty()) return emptyList()
        return listOf(
            VerificationCheck(
                name = "Dipendenze dichiarate",
                category = CheckCategory.STATIC_ANALYSIS,
                status = CheckStatus.WARNING,
                detail = "${insights.size} import senza dichiarazione corrispondente.",
                issues = insights.map { "${it.path}: ${it.message}" },
            ),
        )
    }

    // ---------------------------------------------------------- compilation

    private fun compilation(files: Map<String, String>): List<VerificationCheck> {
        val checks = mutableListOf<VerificationCheck>()
        val jvm = files.keys.filter {
            it.endsWith(".java", true) || it.endsWith(".kt", true) || it.endsWith(".kts", true)
        }
        if (jvm.isNotEmpty()) {
            checks += VerificationCheck(
                name = "Compilazione Java/Kotlin",
                category = CheckCategory.COMPILATION,
                status = CheckStatus.UNSUPPORTED,
                detail = "${jvm.size} sorgenti trovati, ma Android non include javac o kotlinc: " +
                    "nessuna compilazione è stata eseguita.",
                issues = listOf(
                    "Sul dispositivo restano disponibili l'analisi statica e i controlli strutturali.",
                    "Una verifica di compilazione richiede una toolchain esterna (Gradle/JDK).",
                ),
            )
        }
        val native = files.keys.filter {
            it.substringAfterLast('.', "").lowercase() in setOf("c", "h", "cpp", "cc", "hpp")
        }
        if (native.isNotEmpty()) {
            checks += VerificationCheck(
                name = "Compilazione C/C++",
                category = CheckCategory.COMPILATION,
                status = CheckStatus.UNSUPPORTED,
                detail = "${native.size} sorgenti trovati, ma Android non include clang per le app: " +
                    "nessuna compilazione è stata eseguita.",
                issues = listOf("Sono stati controllati parentesi, include locali e guardie degli header."),
            )
        }
        return checks
    }

    // ------------------------------------------------------------ execution

    private suspend fun execution(
        files: Map<String, String>,
        options: VerifyOptions,
    ): Pair<List<VerificationCheck>, ExecutionResult?> {
        val html = selectEntry(files, listOf("index.html", "main.html"), "html", "htm")
        val javascript = selectEntry(files, listOf("index.js", "main.js", "app.js"), "js", "mjs")
        val target = when {
            options.runHtml && html != null -> html
            options.runJavaScript && javascript != null -> javascript
            else -> null
        }
        if (target == null) return emptyList<VerificationCheck>() to null
        val language = if (target.substringAfterLast('.', "").lowercase() in setOf("html", "htm")) "html" else "javascript"
        val runtime = runtime ?: return listOf(
            VerificationCheck(
                name = "Esecuzione $language",
                category = CheckCategory.EXECUTION,
                status = CheckStatus.UNSUPPORTED,
                detail = "Runtime non disponibile in questo contesto: $target non è stato eseguito.",
            ),
        ) to null
        val started = System.currentTimeMillis()
        val result = runtime.execute(
            ExecutionRequest(
                path = target,
                content = files.getValue(target),
                language = language,
                timeoutMs = 8_000,
            ),
        )
        val check = VerificationCheck(
            name = "Esecuzione $language",
            category = CheckCategory.EXECUTION,
            status = when {
                result.timedOut -> CheckStatus.FAILED
                result.errors.isNotEmpty() -> CheckStatus.FAILED
                result.success -> CheckStatus.PASSED
                else -> CheckStatus.WARNING
            },
            detail = when {
                result.timedOut -> "$target interrotto per timeout dopo ${result.durationMs} ms."
                result.success -> "$target eseguito in ${result.durationMs} ms, " +
                    "${result.logs.size} righe di log, ${result.blockedRequests} richieste bloccate."
                else -> "$target ha prodotto ${result.errors.size} errori."
            },
            issues = result.errors,
            durationMs = System.currentTimeMillis() - started,
        )
        return listOf(check) to result
    }

    private fun selectEntry(
        files: Map<String, String>,
        preferred: List<String>,
        vararg extensions: String,
    ): String? {
        preferred.firstOrNull { files.containsKey(it) }?.let { return it }
        return files.keys.firstOrNull { path ->
            path.substringAfterLast('.', "").lowercase() in extensions &&
                !path.contains("test", ignoreCase = true) &&
                !path.contains("spec", ignoreCase = true)
        }
    }

    // ---------------------------------------------------------------- tests

    private fun testPresence(files: Map<String, String>): List<VerificationCheck> {
        val tests = files.keys.filter { path ->
            val lower = path.lowercase()
            lower.contains("test") || lower.contains("spec")
        }
        if (tests.isEmpty()) return emptyList()
        return listOf(
            VerificationCheck(
                name = "File di test riconosciuti",
                category = CheckCategory.TESTS,
                status = CheckStatus.INFO,
                detail = "${tests.size} file di test nel progetto. Non è stato eseguito alcun runner: " +
                    "Android non include un esecutore universale per questi linguaggi.",
                issues = tests.take(8),
            ),
        )
    }

    private fun isAnalyzable(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in setOf(
            "html", "htm", "java", "kt", "kts", "c", "h", "cpp", "cc", "hpp", "css", "py", "json", "xml",
        )
}
