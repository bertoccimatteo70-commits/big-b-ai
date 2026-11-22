package com.bigbai.code

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTerminalTest {

    private fun workspace(): InMemoryWorkspace = InMemoryWorkspace(
        mapOf(
            "index.html" to "<!doctype html><html><body><h1>Ciao</h1></body></html>",
            "src/app.js" to "console.log('ciao');\n",
            "src/util.js" to "export const answer = 42;\n",
            "data/config.json" to "{\"name\": \"ITAGPT\"}",
            "notes.md" to "# Appunti\nriga due\nriga tre\n",
        ),
    )

    private suspend fun ProjectTerminal.runAll(vararg commands: String): TerminalResult {
        var last = TerminalResult(emptyList(), 0, cwd)
        for (command in commands) last = execute(command)
        return last
    }

    private fun TerminalResult.text(): String = lines.joinToString("\n") { it.text }

    @Test
    fun `pwd starts at the project root`() = runBlocking {
        val terminal = ProjectTerminal(workspace())

        val result = terminal.execute("pwd")

        assertEquals("/", result.text())
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `ls lists directories before files`() = runBlocking {
        val terminal = ProjectTerminal(workspace())

        val lines = terminal.execute("ls").lines.map { it.text }

        assertTrue(lines.first().startsWith("data") || lines.first().startsWith("src"))
        assertTrue(lines.any { it == "index.html" })
        assertTrue(lines.any { it == "src/" })
    }

    @Test
    fun `cd enters a directory and refuses to leave the project`() = runBlocking {
        val terminal = ProjectTerminal(workspace())

        terminal.execute("cd src")
        val pwd = terminal.execute("pwd")
        val escape = terminal.execute("cd ../..")

        assertEquals("/src", pwd.text())
        assertEquals(1, escape.exitCode)
        assertTrue(escape.text().contains("non valido"))
        assertEquals("src", terminal.cwd)
    }

    @Test
    fun `cat reads a file and reports a missing one`() = runBlocking {
        val terminal = ProjectTerminal(workspace())

        val content = terminal.execute("cat notes.md")
        val missing = terminal.execute("cat missing.txt")

        assertTrue(content.text().contains("riga due"))
        assertEquals(0, content.exitCode)
        assertEquals(1, missing.exitCode)
        assertTrue(missing.text().contains("non leggibile"))
    }

    @Test
    fun `echo redirect writes and appends`() = runBlocking {
        val workspace = workspace()
        val terminal = ProjectTerminal(workspace)

        val first = terminal.execute("echo \"prima riga\" > out.txt")
        val second = terminal.execute("echo seconda >> out.txt")

        assertEquals(0, first.exitCode)
        assertEquals(0, second.exitCode)
        assertEquals("prima riga\nseconda\n", workspace.read("out.txt"))
    }

    @Test
    fun `mkdir and touch create real entries`() = runBlocking {
        val workspace = workspace()
        val terminal = ProjectTerminal(workspace)

        terminal.execute("mkdir docs/api")
        terminal.execute("touch docs/api/readme.txt")

        assertTrue(workspace.isDirectory("docs/api"))
        assertTrue(workspace.exists("docs/api/readme.txt"))
    }

    @Test
    fun `rm refuses a directory without recursive flag`() = runBlocking {
        val workspace = workspace()
        val terminal = ProjectTerminal(workspace)

        val refused = terminal.execute("rm src")
        val removed = terminal.execute("rm -r src")

        assertEquals(1, refused.exitCode)
        assertTrue(refused.text().contains("-r"))
        assertEquals(0, removed.exitCode)
        assertFalse(workspace.exists("src/app.js"))
    }

    @Test
    fun `cp and mv operate on files`() = runBlocking {
        val workspace = workspace()
        val terminal = ProjectTerminal(workspace)

        assertEquals(0, terminal.execute("cp notes.md copia.md").exitCode)
        assertEquals(0, terminal.execute("mv copia.md rinominata.md").exitCode)

        assertTrue(workspace.exists("rinominata.md"))
        assertFalse(workspace.exists("copia.md"))
        assertEquals(workspace.read("notes.md"), workspace.read("rinominata.md"))
    }

    @Test
    fun `grep reports file and line`() = runBlocking {
        val terminal = ProjectTerminal(workspace())

        val result = terminal.execute("grep -n riga notes.md")

        assertEquals(0, result.exitCode)
        assertTrue(result.text().contains("notes.md:2: riga due"))
        assertTrue(result.text().contains("notes.md:3: riga tre"))
    }

    @Test
    fun `find matches names recursively`() = runBlocking {
        val terminal = ProjectTerminal(workspace())

        val result = terminal.execute("find util")

        assertTrue(result.text().contains("src/util.js"))
    }

    @Test
    fun `sha256 hashes the real content`() = runBlocking {
        val workspace = InMemoryWorkspace(mapOf("abc.txt" to "abc"))
        val terminal = ProjectTerminal(workspace)

        val result = terminal.execute("sha256 abc.txt")

        assertTrue(result.text().startsWith("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
    }

    @Test
    fun `run refuses languages Android cannot execute`() = runBlocking {
        val workspace = InMemoryWorkspace(mapOf("Main.kt" to "fun main() {}"))
        val runtime = RecordingRuntime()
        val terminal = ProjectTerminal(workspace, TerminalHooks(runtime = runtime))

        val result = terminal.execute("run Main.kt")

        assertEquals(1, result.exitCode)
        assertTrue(result.text().contains("kotlinc"))
        assertTrue(runtime.requests.isEmpty())
    }

    @Test
    fun `run validates JSON without a runtime`() = runBlocking {
        val workspace = InMemoryWorkspace(
            mapOf(
                "ok.json" to "{\"a\": [1, 2, 3]}",
                "bad.json" to "{\"a\": 1,}",
            ),
        )
        val terminal = ProjectTerminal(workspace)

        val ok = terminal.execute("run ok.json")
        val bad = terminal.execute("run bad.json")

        assertEquals(0, ok.exitCode)
        assertTrue(ok.text().contains("JSON valido"))
        assertEquals(1, bad.exitCode)
        assertTrue(bad.text().contains("JSON non valido"))
    }

    @Test
    fun `run executes JavaScript through the runtime`() = runBlocking {
        val workspace = InMemoryWorkspace(mapOf("src/app.js" to "console.log('ciao')"))
        val runtime = RecordingRuntime(
            ExecutionResult(
                success = true,
                logs = listOf("[LOG] ciao"),
                durationMs = 12,
            ),
        )
        val terminal = ProjectTerminal(workspace, TerminalHooks(runtime = runtime))

        val result = terminal.execute("run src/app.js")

        assertEquals(0, result.exitCode)
        assertTrue(result.text().contains("[LOG] ciao"))
        assertEquals("javascript", runtime.requests.single().language)
        assertEquals("src/app.js", runtime.requests.single().path)
    }

    @Test
    fun `run reports runtime errors`() = runBlocking {
        val workspace = InMemoryWorkspace(mapOf("app.js" to "throw new Error('x')"))
        val runtime = RecordingRuntime(
            ExecutionResult(success = false, errors = listOf("Errore JavaScript"), durationMs = 3),
        )
        val terminal = ProjectTerminal(workspace, TerminalHooks(runtime = runtime))

        val result = terminal.execute("run app.js")

        assertEquals(1, result.exitCode)
        assertTrue(result.text().contains("Errore JavaScript"))
    }

    @Test
    fun `help documents the environment and the limits`() = runBlocking {
        val terminal = ProjectTerminal(workspace())

        val result = terminal.execute("help")

        assertTrue(result.text().contains("SAF"))
        assertTrue(result.text().contains("non il sistema Android"))
        assertTrue(result.text().contains("run <file>"))
        assertTrue(result.text().contains("verify"))
    }

    @Test
    fun `unknown command fails with a hint`() = runBlocking {
        val terminal = ProjectTerminal(workspace())

        val result = terminal.execute("curl http://example.com")

        assertEquals(1, result.exitCode)
        assertTrue(result.text().contains("sconosciuto"))
        assertTrue(result.text().contains("help"))
    }

    @Test
    fun `unbalanced quotes are refused`() = runBlocking {
        val terminal = ProjectTerminal(workspace())

        val result = terminal.execute("echo \"non chiuso")

        assertEquals(1, result.exitCode)
        assertTrue(result.text().contains("Virgolette"))
    }

    @Test
    fun `output is capped`() = runBlocking {
        val workspace = InMemoryWorkspace(mapOf("big.txt" to "x\n".repeat(5_000)))
        val terminal = ProjectTerminal(workspace, limits = TerminalLimits(maxOutputChars = 200))

        val result = terminal.execute("cat big.txt")

        assertTrue(result.text().contains("output troncato"))
        assertTrue(result.text().length < 500)
    }

    @Test
    fun `history keeps the commands`() = runBlocking {
        val terminal = ProjectTerminal(workspace())

        terminal.execute("pwd")
        terminal.execute("ls")
        val history = terminal.execute("history")

        assertTrue(history.text().contains("pwd"))
        assertTrue(history.text().contains("ls"))
        assertEquals(listOf("pwd", "ls", "history"), terminal.history)
    }

    @Test
    fun `clear asks the UI to reset`() = runBlocking {
        val terminal = ProjectTerminal(workspace())

        val result = terminal.execute("clear")

        assertTrue(result.clearScreen)
    }

    @Test
    fun `arguments respect quotes`() {
        val terminal = ProjectTerminal(workspace())

        assertEquals(listOf("echo", "ciao mondo"), terminal.splitArguments("echo \"ciao mondo\""))
        assertEquals(listOf("echo", "a b", "c"), terminal.splitArguments("echo 'a b' c"))
        assertNull(terminal.splitArguments("echo \"aperto"))
    }

    @Test
    fun `verify delegates to the real verifier`() = runBlocking {
        val terminal = ProjectTerminal(
            workspace(),
            TerminalHooks(verify = { "REPORT REALE" }),
        )

        val result = terminal.execute("verify")

        assertEquals("REPORT REALE", result.text())
        assertEquals(0, result.exitCode)
    }

    private class RecordingRuntime(
        private val result: ExecutionResult = ExecutionResult(success = true),
    ) : ExecutionRuntime {
        val requests = mutableListOf<ExecutionRequest>()

        override suspend fun execute(request: ExecutionRequest): ExecutionResult {
            requests += request
            return result
        }
    }
}
