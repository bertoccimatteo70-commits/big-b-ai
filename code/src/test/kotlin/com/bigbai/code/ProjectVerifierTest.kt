package com.bigbai.code

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectVerifierTest {

    private class FakeRuntime(
        private val result: ExecutionResult = ExecutionResult(
            success = true,
            logs = listOf("[LOG] avviato"),
            durationMs = 4,
        ),
    ) : ExecutionRuntime {
        val requests = mutableListOf<ExecutionRequest>()

        override suspend fun execute(request: ExecutionRequest): ExecutionResult {
            requests += request
            return result
        }
    }

    @Test
    fun `valid web project passes static analysis and execution`() = runBlocking {
        val runtime = FakeRuntime()
        val verifier = ProjectVerifier(runtime)
        val files = mapOf(
            "index.html" to "<!doctype html><html><body><p>Ciao</p></body></html>",
            "data.json" to "{\"ok\": true}",
        )

        val report = verifier.verify(files)

        assertTrue(report.failed.toString(), report.passed)
        val execution = report.checks.first { it.category == CheckCategory.EXECUTION }
        assertEquals(CheckStatus.PASSED, execution.status)
        assertEquals("index.html", runtime.requests.single().path)
        assertTrue(report.checks.any { it.name == "Documenti JSON" && it.status == CheckStatus.PASSED })
    }

    @Test
    fun `invalid json fails the data validation check`() = runBlocking {
        val report = ProjectVerifier().verify(mapOf("config.json" to "{\"a\": }"))

        val json = report.checks.first { it.name == "Documenti JSON" }
        assertEquals(CheckStatus.FAILED, json.status)
        assertFalse(report.passed)
    }

    @Test
    fun `java compilation is declared unavailable instead of passed`() = runBlocking {
        val report = ProjectVerifier().verify(
            mapOf("App.java" to "public class App { void run() { } }"),
        )

        val compilation = report.checks.first { it.category == CheckCategory.COMPILATION }
        assertEquals(CheckStatus.UNSUPPORTED, compilation.status)
        assertTrue(compilation.detail.contains("javac"))
        assertTrue(report.toText().contains("non disponibile"))
    }

    @Test
    fun `missing local include is a real failure`() = runBlocking {
        val report = ProjectVerifier().verify(
            mapOf("main.cpp" to "#include \"missing.h\"\nint main() { return 0; }"),
        )

        val includes = report.checks.first { it.name == "Include locali C/C++" }
        assertEquals(CheckStatus.FAILED, includes.status)
        assertTrue(includes.issues.first().contains("missing.h"))
    }

    @Test
    fun `execution is unavailable without a runtime`() = runBlocking {
        val report = ProjectVerifier().verify(
            mapOf("index.html" to "<!doctype html><html><body></body></html>"),
        )

        val execution = report.checks.first { it.category == CheckCategory.EXECUTION }
        assertEquals(CheckStatus.UNSUPPORTED, execution.status)
        assertTrue(execution.detail.contains("Runtime non disponibile"))
    }

    @Test
    fun `runtime errors fail execution`() = runBlocking {
        val runtime = FakeRuntime(ExecutionResult(success = false, errors = listOf("ReferenceError: x")))
        val report = ProjectVerifier(runtime).verify(
            mapOf("app.js" to "console.log(x);"),
        )

        val execution = report.checks.first { it.category == CheckCategory.EXECUTION }
        assertEquals(CheckStatus.FAILED, execution.status)
        assertTrue(execution.issues.any { it.contains("ReferenceError") })
    }

    @Test
    fun `timeout is a failure, not a pass`() = runBlocking {
        val runtime = FakeRuntime(ExecutionResult(success = false, timedOut = true, durationMs = 8_000))
        val report = ProjectVerifier(runtime).verify(
            mapOf("index.html" to "<!doctype html><html><body><script>while(true){}</script></body></html>"),
        )

        val execution = report.checks.first { it.category == CheckCategory.EXECUTION }
        assertEquals(CheckStatus.FAILED, execution.status)
        assertTrue(execution.detail.contains("timeout"))
    }

    @Test
    fun `xml and csv problems are reported`() = runBlocking {
        val report = ProjectVerifier().verify(
            mapOf(
                "res/values.xml" to "<resources><string name=\"a\"></resources>",
                "data.csv" to "a,b\n1\n",
            ),
        )

        assertEquals(CheckStatus.FAILED, report.checks.first { it.name == "Documenti XML" }.status)
        assertEquals(CheckStatus.FAILED, report.checks.first { it.name == "File CSV/TSV" }.status)
    }

    @Test
    fun `undeclared imports are a warning`() = runBlocking {
        val report = ProjectVerifier().verify(
            mapOf(
                "src/app.js" to "import x from 'lodash';\nconsole.log(x);",
                "package.json" to "{\"dependencies\": {\"react\": \"18.0.0\"}}",
            ),
        )

        val dependencies = report.checks.first { it.name == "Dipendenze dichiarate" }
        assertEquals(CheckStatus.WARNING, dependencies.status)
        assertTrue(dependencies.issues.first().contains("lodash"))
    }

    @Test
    fun `report counts every category honestly`() = runBlocking {
        val report = ProjectVerifier().verify(
            mapOf(
                "index.html" to "<!doctype html><html><body></body></html>",
                "Main.kt" to "fun main() { println(\"ciao\") }",
                "notes.txt" to "solo testo",
            ),
        )

        val text = report.toText()
        assertTrue(text.contains("[—] Compilazione Java/Kotlin"))
        assertTrue(text.contains("non che sia passato"))
        assertEquals(0, report.failed.size)
    }

    @Test
    fun `empty project produces no checks`() = runBlocking {
        val report = ProjectVerifier().verify(emptyMap())

        assertTrue(report.checks.isEmpty())
        assertEquals("Nessun file da verificare.", report.summary())
    }
}
