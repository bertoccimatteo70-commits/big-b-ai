package com.bigbai.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticAnalyzersTest {

    @Test
    fun `html detects closing tag without opening`() {
        val diagnostics = StaticAnalyzers.analyze("index.html", "<div></span></div>")

        assertTrue(diagnostics.any { it.rule == "html.unmatched-close" && it.severity == DiagnosticSeverity.ERROR })
    }

    @Test
    fun `html detects unclosed tags`() {
        val diagnostics = StaticAnalyzers.analyze("index.html", "<div><p>testo</p>")

        assertTrue(diagnostics.any { it.rule == "html.unclosed-tag" })
    }

    @Test
    fun `html ignores void and self closing tags`() {
        val diagnostics = StaticAnalyzers.analyze(
            "index.html",
            "<!doctype html><html><body><img src=\"a.png\"><br/><input type=\"text\"></body></html>",
        )

        assertTrue(diagnostics.none { it.severity == DiagnosticSeverity.ERROR })
    }

    @Test
    fun `java detects empty catch block`() {
        val content = """
            public class App {
                void run() {
                    try {
                        work();
                    } catch (Exception e) {}
                }
                void work() {}
            }
        """.trimIndent()

        val diagnostics = StaticAnalyzers.analyze("App.java", content)

        assertTrue(diagnostics.any { it.rule == "java.empty-catch" && it.severity == DiagnosticSeverity.WARNING })
    }

    @Test
    fun `java detects public class name mismatch`() {
        val content = "public class Other { }"
        val diagnostics = StaticAnalyzers.analyze("App.java", content)

        assertTrue(diagnostics.any { it.rule == "java.public-class-name" })
    }

    @Test
    fun `java detects unbalanced braces`() {
        val diagnostics = StaticAnalyzers.analyze("App.java", "public class App { void f() { }")

        assertTrue(diagnostics.any { it.rule.startsWith("java.braces") })
    }

    @Test
    fun `cpp header without include guard is flagged`() {
        val diagnostics = StaticAnalyzers.analyze("widget.h", "class Widget {};")

        assertTrue(diagnostics.any { it.rule == "cpp.include-guard" })
    }

    @Test
    fun `cpp header with pragma once is accepted`() {
        val diagnostics = StaticAnalyzers.analyze("widget.h", "#pragma once\nclass Widget {};")

        assertTrue(diagnostics.none { it.rule == "cpp.include-guard" })
    }

    @Test
    fun `css reports unbalanced blocks`() {
        val diagnostics = StaticAnalyzers.analyze("style.css", "body { color: red; ")

        assertEquals(1, diagnostics.count { it.rule == "css.braces" })
    }

    @Test
    fun `unknown extension produces no diagnostics`() {
        assertTrue(StaticAnalyzers.analyze("notes.md", "# titolo").isEmpty())
    }
}
