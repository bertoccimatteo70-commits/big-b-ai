package com.bigbai.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageAnalyzersTest {

    @Test
    fun `kotlin detects unbalanced braces`() {
        val diagnostics = StaticAnalyzers.analyze("App.kt", "class App { fun run() { }")

        assertTrue(diagnostics.any { it.rule.startsWith("kotlin.braces") && it.severity == DiagnosticSeverity.ERROR })
    }

    @Test
    fun `kotlin reports TODO calls`() {
        val diagnostics = StaticAnalyzers.analyze("App.kt", "class App { fun run() { TODO() } }")

        assertTrue(diagnostics.any { it.rule == "kotlin.todo" })
    }

    @Test
    fun `kotlin reports an empty catch`() {
        val diagnostics = StaticAnalyzers.analyze(
            "App.kt",
            "class App { fun run() { try { work() } catch (e: Exception) {} } }",
        )

        assertTrue(diagnostics.any { it.rule == "kotlin.empty-catch" })
    }

    @Test
    fun `kotlin valid file has no errors`() {
        val diagnostics = StaticAnalyzers.analyze(
            "App.kt",
            "package app\n\nclass App {\n    fun run(): Int = 42\n}\n",
        )

        assertTrue(diagnostics.toString(), diagnostics.none { it.severity == DiagnosticSeverity.ERROR })
    }

    @Test
    fun `python detects unbalanced parentheses`() {
        val diagnostics = StaticAnalyzers.analyze("app.py", "def run(:\n    pass\n")

        assertTrue(diagnostics.any { it.rule.startsWith("python.brackets") })
    }

    @Test
    fun `python ignores brackets inside strings and comments`() {
        val diagnostics = StaticAnalyzers.analyze(
            "app.py",
            "text = \"(non un errore\"\n# ( neanche questo\nprint(text)\n",
        )

        assertTrue(diagnostics.toString(), diagnostics.none { it.severity == DiagnosticSeverity.ERROR })
    }

    @Test
    fun `python reports a bare except`() {
        val diagnostics = StaticAnalyzers.analyze(
            "app.py",
            "try:\n    run()\nexcept:\n    pass\n",
        )

        assertTrue(diagnostics.any { it.rule == "python.bare-except" })
    }

    @Test
    fun `python reports mixed indentation`() {
        val diagnostics = StaticAnalyzers.analyze(
            "app.py",
            "def run():\n\tif True:\n        return 1\n",
        )

        assertTrue(diagnostics.any { it.rule == "python.mixed-indent" })
    }

    @Test
    fun `json syntax error becomes a diagnostic with its line`() {
        val diagnostics = StaticAnalyzers.analyze(
            "config.json",
            "{\n  \"a\": 1,\n  \"b\": \n}\n",
        )

        val error = diagnostics.single()
        assertEquals("json.syntax", error.rule)
        assertEquals(4, error.line)
    }

    @Test
    fun `valid json has no diagnostics`() {
        assertTrue(StaticAnalyzers.analyze("config.json", "{\"a\": [1, 2]}").isEmpty())
    }

    @Test
    fun `xml error becomes a diagnostic`() {
        val diagnostics = StaticAnalyzers.analyze("values.xml", "<a><b></a>")

        assertTrue(diagnostics.any { it.rule.startsWith("xml.wellformed") })
    }

    @Test
    fun `one unbalanced brace is reported once`() {
        val diagnostics = StaticAnalyzers.analyze("App.java", "public class App { void f() { }")

        assertEquals(1, diagnostics.count { it.rule.startsWith("java.braces") })
    }
}
