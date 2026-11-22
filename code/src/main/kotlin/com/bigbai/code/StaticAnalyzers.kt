package com.bigbai.code

enum class DiagnosticSeverity { ERROR, WARNING, INFO }

data class Diagnostic(
    val severity: DiagnosticSeverity,
    val line: Int,
    val message: String,
    val rule: String,
)

/**
 * Local, deterministic static analysis. It is deliberately rule-based and
 * transparent: it never pretends to be a compiler and never invents errors.
 * The UI labels these results as "analisi statica", not "compilazione".
 */
object StaticAnalyzers {

    fun analyze(path: String, content: String): List<Diagnostic> = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> analyzeHtml(content)
        "java" -> analyzeJava(path, content)
        "kt", "kts" -> analyzeKotlin(path, content)
        "py" -> analyzePython(content)
        "c", "h", "cpp", "cc", "hpp" -> analyzeCpp(path, content)
        "css" -> analyzeCss(content)
        "json" -> analyzeJson(content)
        "xml", "svg", "plist" -> analyzeXml(content)
        else -> emptyList()
    }

    // ---------------------------------------------------------------- HTML ----

    private val voidTags = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link",
        "meta", "param", "source", "track", "wbr", "!doctype",
    )

    private fun analyzeHtml(content: String): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val stack = ArrayDeque<Pair<String, Int>>()
        val tagRegex = Regex("<(/?)([a-zA-Z!][a-zA-Z0-9:-]*)([^>]*)>")
        var line = 1
        var lastIndex = 0

        for (match in tagRegex.findAll(content)) {
            line += content.substring(lastIndex, match.range.first).count { it == '\n' }
            lastIndex = match.range.first
            val closing = match.groupValues[1] == "/"
            val name = match.groupValues[2].lowercase()
            val selfClosing = match.groupValues[3].trimEnd().endsWith("/")
            if (name in voidTags || selfClosing) continue
            if (!closing) {
                stack.addLast(name to line)
            } else {
                val index = stack.indexOfLast { it.first == name }
                if (index < 0) {
                    diagnostics.add(Diagnostic(DiagnosticSeverity.ERROR, line, "Tag di chiusura </$name> senza apertura", "html.unmatched-close"))
                } else {
                    for (i in stack.lastIndex downTo index + 1) {
                        val unclosed = stack[i]
                        diagnostics.add(
                            Diagnostic(
                                DiagnosticSeverity.ERROR,
                                unclosed.second,
                                "Tag <${unclosed.first}> non chiuso",
                                "html.unclosed-tag",
                            ),
                        )
                    }
                    while (stack.size > index) stack.removeLast()
                }
            }
        }
        for (entry in stack) {
            diagnostics.add(Diagnostic(DiagnosticSeverity.ERROR, entry.second, "Tag <${entry.first}> non chiuso", "html.unclosed-tag"))
        }
        if (!content.trimStart().lowercase().startsWith("<!doctype")) {
            diagnostics.add(Diagnostic(DiagnosticSeverity.WARNING, 1, "Manca <!DOCTYPE html>", "html.doctype"))
        }
        if (content.contains("<script", ignoreCase = true) && !content.contains("</script>", ignoreCase = true)) {
            diagnostics.add(Diagnostic(DiagnosticSeverity.ERROR, 1, "Blocco <script> non chiuso", "html.unclosed-script"))
        }
        if (content.contains("<img", ignoreCase = true) && Regex("<img[^>]*>", RegexOption.IGNORE_CASE)
                .findAll(content).any { !it.value.contains("alt=", ignoreCase = true) }
        ) {
            diagnostics.add(Diagnostic(DiagnosticSeverity.INFO, 1, "Alcune immagini non hanno l'attributo alt", "html.img-alt"))
        }
        return diagnostics.sortedBy { it.line }
    }

    // ---------------------------------------------------------------- Java ----

    private fun analyzeJava(path: String, content: String): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        // One pass over every bracket type: calling balance twice would report
        // the same unmatched parenthesis under two different rules.
        diagnostics += balance(content, "java.braces", "graffe e parentesi")

        val fileName = path.substringAfterLast('/').removeSuffix(".java")
        val publicClass = Regex("public\\s+(?:final\\s+|abstract\\s+)?class\\s+(\\w+)").find(content)
        if (publicClass != null && publicClass.groupValues[1] != fileName) {
            diagnostics.add(
                Diagnostic(
                    DiagnosticSeverity.ERROR,
                    lineOf(content, publicClass.range.first),
                    "La classe pubblica ${publicClass.groupValues[1]} deve essere nel file $fileName.java",
                    "java.public-class-name",
                ),
            )
        }
        if (!Regex("\\b(class|interface|enum|record)\\b").containsMatchIn(content)) {
            diagnostics.add(Diagnostic(DiagnosticSeverity.WARNING, 1, "Nessuna dichiarazione di tipo trovata", "java.no-type"))
        }
        Regex("catch\\s*\\([^)]*\\)\\s*\\{\\s*}").findAll(content).forEach {
            diagnostics.add(
                Diagnostic(
                    DiagnosticSeverity.WARNING,
                    lineOf(content, it.range.first),
                    "Blocco catch vuoto: l'errore viene ignorato",
                    "java.empty-catch",
                ),
            )
        }
        Regex("System\\.out\\.print").findAll(content).take(3).forEach {
            diagnostics.add(
                Diagnostic(
                    DiagnosticSeverity.INFO,
                    lineOf(content, it.range.first),
                    "Output su console: valuta un logger",
                    "java.system-out",
                ),
            )
        }
        return diagnostics.sortedBy { it.line }
    }

    // ----------------------------------------------------------------- C++ ----

    private fun analyzeCpp(path: String, content: String): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        diagnostics += balance(content, "cpp.braces", "graffe e parentesi")
        val isHeader = path.endsWith(".h") || path.endsWith(".hpp")
        if (isHeader && !content.contains("#pragma once") && !Regex("#ifndef\\s+\\w+").containsMatchIn(content)) {
            diagnostics.add(Diagnostic(DiagnosticSeverity.WARNING, 1, "Header senza include guard (#pragma once)", "cpp.include-guard"))
        }
        if (isHeader && content.contains("using namespace std")) {
            diagnostics.add(Diagnostic(DiagnosticSeverity.WARNING, 1, "using namespace std in un header inquinia lo scope globale", "cpp.using-namespace-header"))
        }
        if (content.contains("malloc(") && !content.contains("free(")) {
            diagnostics.add(Diagnostic(DiagnosticSeverity.INFO, 1, "malloc senza free visibile nel file", "cpp.malloc-free"))
        }
        if (Regex("#include\\s*<iostream>").containsMatchIn(content) && !content.contains("std::")) {
            diagnostics.add(Diagnostic(DiagnosticSeverity.INFO, 1, "iostream incluso ma nessun uso di std::", "cpp.unused-include"))
        }
        return diagnostics.sortedBy { it.line }
    }

    // -------------------------------------------------------------- Kotlin ----

    private fun analyzeKotlin(path: String, content: String): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        diagnostics += balance(content, "kotlin.braces", "graffe e parentesi")
        Regex("\\bTODO\\s*\\(").findAll(content).take(3).forEach {
            diagnostics.add(
                Diagnostic(
                    DiagnosticSeverity.INFO,
                    lineOf(content, it.range.first),
                    "TODO() lancia un'eccezione a runtime: completare prima della consegna",
                    "kotlin.todo",
                ),
            )
        }
        Regex("catch\\s*\\([^)]*\\)\\s*\\{\\s*}").findAll(content).take(3).forEach {
            diagnostics.add(
                Diagnostic(
                    DiagnosticSeverity.WARNING,
                    lineOf(content, it.range.first),
                    "Blocco catch vuoto: l'errore viene ignorato",
                    "kotlin.empty-catch",
                ),
            )
        }
        if (!Regex("\\b(fun|class|object|interface|val|var)\\b").containsMatchIn(content)) {
            diagnostics.add(
                Diagnostic(
                    DiagnosticSeverity.WARNING,
                    1,
                    "Nessuna dichiarazione Kotlin riconosciuta nel file",
                    "kotlin.no-declaration",
                ),
            )
        }
        if (path.endsWith(".kts") && !content.contains("plugins") && !content.contains("dependencies")) {
            diagnostics.add(
                Diagnostic(
                    DiagnosticSeverity.INFO,
                    1,
                    "Script Gradle senza plugins né dependencies visibili",
                    "kotlin.gradle-script",
                ),
            )
        }
        return diagnostics.sortedBy { it.line }
    }

    // -------------------------------------------------------------- Python ----

    private fun analyzePython(content: String): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        diagnostics += pythonBalance(content)
        Regex("(?m)^\\s*except\\s*:").findAll(content).take(3).forEach {
            diagnostics.add(
                Diagnostic(
                    DiagnosticSeverity.WARNING,
                    lineOf(content, it.range.first),
                    "«except:» cattura anche KeyboardInterrupt e SystemExit: indica il tipo di eccezione",
                    "python.bare-except",
                ),
            )
        }
        val tabs = content.lines().count { it.startsWith("\t") }
        val spaces = content.lines().count { it.startsWith("    ") }
        if (tabs > 0 && spaces > 0) {
            diagnostics.add(
                Diagnostic(
                    DiagnosticSeverity.WARNING,
                    1,
                    "Indentazione mista con tab e spazi: Python può rifiutare il file",
                    "python.mixed-indent",
                ),
            )
        }
        return diagnostics.sortedBy { it.line }
    }

    /** Bracket balance for Python, skipping comments and single/triple strings. */
    private fun pythonBalance(content: String): List<Diagnostic> {
        val pairs = mapOf('(' to ')', '[' to ']', '{' to '}')
        val closers = pairs.values.toSet()
        val stack = ArrayDeque<Pair<Char, Int>>()
        val diagnostics = mutableListOf<Diagnostic>()
        var line = 1
        var index = 0
        var quote: String? = null
        var comment = false
        while (index < content.length) {
            val char = content[index]
            if (char == '\n') {
                line++
                comment = false
                index++
                continue
            }
            if (comment) {
                index++
                continue
            }
            if (quote != null) {
                if (char == '\\' && quote.length == 1) {
                    index += 2
                    continue
                }
                if (content.startsWith(quote, index)) {
                    index += quote.length
                    quote = null
                    continue
                }
                index++
                continue
            }
            if (char == '#') {
                comment = true
                index++
                continue
            }
            if (char == '"' || char == '\'') {
                quote = if (content.startsWith("$char$char$char", index)) "$char$char$char" else char.toString()
                index += quote.length
                continue
            }
            when (char) {
                in pairs.keys -> stack.addLast(char to line)
                in closers -> {
                    val expected = pairs.entries.firstOrNull { it.value == char }?.key
                    val last = stack.lastOrNull()
                    if (last == null || last.first != expected) {
                        diagnostics.add(
                            Diagnostic(
                                DiagnosticSeverity.ERROR,
                                line,
                                "Chiusura '$char' inattesa",
                                "python.brackets.unexpected-close",
                            ),
                        )
                    } else {
                        stack.removeLast()
                    }
                }
            }
            index++
        }
        for ((open, startLine) in stack) {
            diagnostics.add(
                Diagnostic(
                    DiagnosticSeverity.ERROR,
                    startLine,
                    "Apertura '$open' non chiusa",
                    "python.brackets.unclosed",
                ),
            )
        }
        return diagnostics
    }

    // ---------------------------------------------------------- data formats ----

    private fun analyzeJson(content: String): List<Diagnostic> {
        val result = JsonValidator.validate(content)
        if (result.valid) return emptyList()
        return listOf(
            Diagnostic(
                DiagnosticSeverity.ERROR,
                result.line,
                result.error ?: "JSON non valido",
                "json.syntax",
            ),
        )
    }

    private fun analyzeXml(content: String): List<Diagnostic> = XmlValidator.validate(content)
        .errors
        .mapIndexed { index, error ->
            val line = Regex("riga (\\d+)").find(error)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            Diagnostic(
                DiagnosticSeverity.ERROR,
                line,
                error,
                if (index == 0) "xml.wellformed" else "xml.wellformed.more",
            )
        }

    private fun analyzeCss(content: String): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val open = content.count { it == '{' }
        val close = content.count { it == '}' }
        if (open != close) {
            diagnostics.add(
                Diagnostic(
                    DiagnosticSeverity.ERROR,
                    lineOf(content, content.length - 1),
                    "Blocchi CSS non bilanciati ($open aperte, $close chiuse)",
                    "css.braces",
                ),
            )
        }
        return diagnostics
    }

    // -------------------------------------------------------------- helpers ----

    private fun balance(content: String, rule: String, label: String): List<Diagnostic> {
        val pairs = mapOf('{' to '}', '(' to ')', '[' to ']')
        val closers = pairs.values.toSet()
        val stack = ArrayDeque<Pair<Char, Int>>()
        val diagnostics = mutableListOf<Diagnostic>()
        var line = 1
        var inString = false
        var inChar = false
        var inLineComment = false
        var inBlockComment = false
        var escaped = false
        var previous = ' '

        for (char in content) {
            if (char == '\n') {
                line++
                inLineComment = false
                previous = char
                continue
            }
            if (inLineComment) continue
            if (inBlockComment) {
                if (previous == '*' && char == '/') inBlockComment = false
                previous = char
                continue
            }
            if (inString) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') inString = false
                previous = char
                continue
            }
            if (inChar) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '\'') inChar = false
                previous = char
                continue
            }
            if (previous == '/' && char == '/') {
                inLineComment = true
                previous = char
                continue
            }
            if (previous == '/' && char == '*') {
                inBlockComment = true
                previous = char
                continue
            }
            when (char) {
                '"' -> inString = true
                '\'' -> inChar = true
                in pairs.keys -> stack.addLast(char to line)
                in closers -> {
                    val expectedOpen = pairs.entries.firstOrNull { it.value == char }?.key
                    val last = stack.lastOrNull()
                    if (last == null || last.first != expectedOpen) {
                        diagnostics.add(
                            Diagnostic(
                                DiagnosticSeverity.ERROR,
                                line,
                                "Chiusura '$char' inattesa",
                                "$rule.unexpected-close",
                            ),
                        )
                    } else {
                        stack.removeLast()
                    }
                }
            }
            previous = char
        }
        for (entry in stack) {
            diagnostics.add(
                Diagnostic(
                    DiagnosticSeverity.ERROR,
                    entry.second,
                    "Apertura '${entry.first}' non chiusa ($label)",
                    "$rule.unclosed",
                ),
            )
        }
        return diagnostics
    }

    private fun lineOf(content: String, index: Int): Int =
        content.take(index.coerceIn(0, content.length)).count { it == '\n' } + 1
}
