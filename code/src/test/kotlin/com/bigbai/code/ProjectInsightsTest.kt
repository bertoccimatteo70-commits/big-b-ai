package com.bigbai.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectInsightsTest {

    private val duplicatedBlock = """
        val primoValore = calcolaQualcosa(1, 2, 3)
        val secondoValore = primoValore + 10
        if (secondoValore > 20) {
            aggiornaStato(secondoValore)
        }
        val terzoValore = secondoValore * 2
        registraEvento("calcolo", terzoValore)
        val risultatoFinale = terzoValore + primoValore
        restituisci(risultatoFinale)
    """.trimIndent()

    @Test
    fun `duplicated blocks across two files are reported`() {
        val files = mapOf(
            "src/A.kt" to duplicatedBlock,
            "src/B.kt" to "package b\n\n$duplicatedBlock",
        )
        val insights = ProjectInsights.analyze(files)
        assertTrue(insights.any { it.kind == InsightKind.DUPLICATE_CODE })
    }

    @Test
    fun `an imported package that package json does not declare is reported`() {
        val files = mapOf(
            "package.json" to """{"dependencies":{"react":"^18.0.0"}}""",
            "src/app.js" to "import React from \"react\";\nimport _ from \"lodash\";\n",
        )
        val insights = ProjectInsights.analyze(files)
        val missing = insights.filter { it.kind == InsightKind.MISSING_DEPENDENCY }
        assertEquals(1, missing.size)
        assertTrue(missing.first().message.contains("lodash"))
        assertFalse(missing.any { it.message.contains("react") })
    }

    @Test
    fun `node builtins are not reported as missing dependencies`() {
        val files = mapOf(
            "package.json" to """{"dependencies":{"react":"^18.0.0"}}""",
            "src/app.js" to "import fs from \"fs\";\nimport path from \"path\";\n",
        )
        assertTrue(ProjectInsights.analyze(files).none { it.kind == InsightKind.MISSING_DEPENDENCY })
    }

    @Test
    fun `todo markers are reported once per file`() {
        val files = mapOf(
            "src/Main.kt" to "// TODO: finire\nval x = 1\n// FIXME: controllare\n",
        )
        val todos = ProjectInsights.analyze(files).filter { it.kind == InsightKind.TODO }
        assertEquals(1, todos.size)
        assertTrue(todos.first().message.contains("2"))
    }

    @Test
    fun `summary counts files and finds the entry point`() {
        val summary = ProjectInsights.summary(
            mapOf(
                "index.html" to "<html></html>",
                "src/app.js" to "console.log(1)",
                "src/Main.kt" to "fun main() {}",
            ),
        )
        assertTrue(summary.contains("File di testo: 3"))
        assertTrue(summary.contains("index.html"))
    }

    @Test
    fun `change report lists the written files and the checks`() {
        val report = ProjectInsights.changeReport(
            written = listOf("index.html", "style.css"),
            diagnostics = mapOf(
                "index.html" to listOf(
                    Diagnostic(DiagnosticSeverity.ERROR, 3, "Tag non chiuso", "html.unclosed-tag"),
                ),
            ),
            checks = listOf("Analisi statica", "Sandbox HTML"),
        )
        assertTrue(report.contains("File scritti: 2"))
        assertTrue(report.contains("index.html"))
        assertTrue(report.contains("1 errori"))
        assertTrue(report.contains("Sandbox HTML"))
        assertTrue(report.contains("Nessun compilatore"))
    }
}
