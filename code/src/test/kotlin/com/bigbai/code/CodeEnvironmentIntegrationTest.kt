package com.bigbai.code

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end tests of the Code environment without Android: the real agent
 * loop, the real terminal, the real verifier and the real rollback planner all
 * run against the same in-memory project. Only the model and the WebView runtime
 * are replaced, because a device model and a WebView cannot run on the JVM.
 */
class CodeEnvironmentIntegrationTest {

    private class ScriptedModel(vararg answers: String) : AgentModel {
        private val queue = ArrayDeque(answers.toList())
        val prompts = mutableListOf<List<AgentMessage>>()
        var delayMs: Long = 0

        override suspend fun complete(messages: List<AgentMessage>): String {
            prompts += messages
            if (delayMs > 0) delay(delayMs)
            return queue.removeFirstOrNull() ?: "<tool name=\"finish\">fine</tool>"
        }
    }

    private class ThrowingModel(private val message: String) : AgentModel {
        override suspend fun complete(messages: List<AgentMessage>): String =
            throw IllegalStateException(message)
    }

    private class SnapshotCheckpointer(private val workspace: InMemoryWorkspace) : AgentCheckpointer {
        var snapshot: Map<String, String>? = null
        var calls = 0

        override suspend fun checkpoint(reason: String): GitCommitInfo? {
            calls++
            snapshot = workspace.snapshot()
            return GitCommitInfo("0123456789abcdef", "0123456", reason, "ITAGPT", 0)
        }
    }

    private class FakeRuntime(
        private val result: ExecutionResult = ExecutionResult(
            success = true,
            logs = listOf("[LOG] avviato"),
            durationMs = 3,
        ),
    ) : ExecutionRuntime {
        val requests = mutableListOf<ExecutionRequest>()

        override suspend fun execute(request: ExecutionRequest): ExecutionResult {
            requests += request
            return result
        }
    }

    private fun write(path: String, content: String): String =
        "<tool name=\"write_file\" path=\"$path\">\n$content\n</tool>"

    private fun TerminalResult.text(): String = lines.joinToString("\n") { it.text }

    private suspend fun applyPlan(plan: RestorePlan, workspace: InMemoryWorkspace): List<String> {
        val failed = mutableListOf<String>()
        for ((path, content) in plan.restore) {
            if (!workspace.write(path, content)) failed += path
        }
        for (path in plan.delete) {
            if (!workspace.delete(path)) failed += path
        }
        return failed
    }

    @Test
    fun `from request to verified project in one flow`() = runBlocking {
        val workspace = InMemoryWorkspace()
        val runtime = FakeRuntime()
        val checkpointer = SnapshotCheckpointer(workspace)
        val model = ScriptedModel(
            write("index.html", "<!doctype html><html><body><h1>Ciao</h1></body></html>") + "\n" +
                write("app.js", "console.log('avviato')"),
            "<tool name=\"finish\">Creato il progetto</tool>",
        )

        val outcome = AgentRunner(workspace, model, checkpointer).run(
            prompts = { step, transcript -> listOf(AgentMessage("user", "passo $step $transcript")) },
        )

        assertTrue(outcome.finished)
        assertEquals(listOf("index.html", "app.js"), outcome.written)
        assertEquals(1, checkpointer.calls)

        val verifier = ProjectVerifier(runtime)
        val report = verifier.verify(workspace.snapshot())
        assertTrue(report.failed.toString(), report.passed)
        assertEquals("index.html", runtime.requests.single().path)

        val terminal = ProjectTerminal(
            workspace,
            TerminalHooks(runtime = runtime, verify = { report.toText() }),
        )
        val verify = terminal.execute("verify")
        assertTrue(verify.text().contains("VERIFICA DEL PROGETTO"))
        assertTrue(verify.text().contains("[OK] Esecuzione html"))
        val listing = terminal.execute("ls")
        assertTrue(listing.text().contains("app.js"))
        val content = terminal.execute("cat index.html")
        assertTrue(content.text().contains("<h1>Ciao</h1>"))
    }

    @Test
    fun `several files are changed together and all survive`() = runBlocking {
        val workspace = InMemoryWorkspace(mapOf("README.md" to "# progetto"))
        val model = ScriptedModel(
            write("src/main.kt", "fun main() = println(\"ciao\")") + "\n" +
                write("src/model.kt", "data class Item(val id: Int)") + "\n" +
                write("src/ui.kt", "fun render() = Unit"),
            "<tool name=\"finish\">Tre file</tool>",
        )

        val outcome = AgentRunner(workspace, model, SnapshotCheckpointer(workspace)).run(
            prompts = { _, _ -> listOf(AgentMessage("user", "crea il progetto")) },
        )

        assertEquals(3, outcome.written.size)
        assertEquals(3, workspace.snapshot().keys.count { it.startsWith("src/") })
        assertEquals("# progetto", workspace.read("README.md"))
    }

    @Test
    fun `a provider that cannot write never produces a success`() = runBlocking {
        val workspace = InMemoryWorkspace()
        workspace.failWrites = true
        val events = mutableListOf<AgentEvent>()
        val model = ScriptedModel(
            write("index.html", "<h1>perso</h1>"),
            "<tool name=\"finish\">fatto</tool>",
        )

        val outcome = AgentRunner(workspace, model, SnapshotCheckpointer(workspace)).run(
            prompts = { _, _ -> listOf(AgentMessage("user", "crea")) },
            onEvent = { events += it },
        )

        assertTrue(outcome.written.isEmpty())
        assertTrue(workspace.snapshot().isEmpty())
        assertTrue(events.any { it is AgentEvent.Step && it.status == AgentStepStatus.FAILED })
    }

    @Test
    fun `timeout leaves the project untouched`() = runBlocking {
        val workspace = InMemoryWorkspace(mapOf("keep.txt" to "intatto"))
        val model = ScriptedModel(write("keep.txt", "sovrascritto"))
        model.delayMs = 200

        val outcome = AgentRunner(
            workspace,
            model,
            SnapshotCheckpointer(workspace),
            AgentOptions(timeoutMs = 20),
        ).run(prompts = { _, _ -> listOf(AgentMessage("user", "modifica")) })

        assertTrue(outcome.timedOut)
        assertEquals("intatto", workspace.read("keep.txt"))
    }

    @Test
    fun `rollback restores the checkpoint and verifies the bytes`() = runBlocking {
        val workspace = InMemoryWorkspace(
            mapOf(
                "keep.txt" to "non toccato",
                "edit.txt" to "versione 1",
            ),
        )
        val checkpointer = SnapshotCheckpointer(workspace)
        val model = ScriptedModel(
            write("edit.txt", "versione 2") + "\n" + write("creato.txt", "nuovo"),
            "<tool name=\"finish\">modificato</tool>",
        )

        val outcome = AgentRunner(workspace, model, checkpointer).run(
            prompts = { _, _ -> listOf(AgentMessage("user", "aggiorna")) },
        )
        assertEquals(setOf("edit.txt", "creato.txt"), outcome.modified.toSet())

        val afterTask = RollbackGuard.fingerprints(workspace.snapshot())
        val plan = RollbackPlanner.plan(
            checkpoint = checkpointer.snapshot.orEmpty(),
            touched = outcome.modified.toSet(),
            currentHashes = afterTask,
            expectedHashes = afterTask,
        )

        assertEquals(mapOf("edit.txt" to "versione 1"), plan.restore)
        assertEquals(listOf("creato.txt"), plan.delete)
        assertTrue(plan.skipped.isEmpty())
        assertTrue(applyPlan(plan, workspace).isEmpty())

        val verification = RollbackGuard.verifyRestore(checkpointer.snapshot.orEmpty(), workspace.snapshot())
        assertTrue(verification.message(), verification.complete)
        assertEquals("non toccato", workspace.read("keep.txt"))
        assertFalse(workspace.exists("creato.txt"))
    }

    @Test
    fun `a user edit after the task is never overwritten`() = runBlocking {
        val workspace = InMemoryWorkspace(mapOf("edit.txt" to "versione 1"))
        val checkpointer = SnapshotCheckpointer(workspace)
        val model = ScriptedModel(write("edit.txt", "versione 2"), "<tool name=\"finish\">fatto</tool>")

        val outcome = AgentRunner(workspace, model, checkpointer).run(
            prompts = { _, _ -> listOf(AgentMessage("user", "aggiorna")) },
        )

        // The user edits the same file after the task.
        workspace.write("edit.txt", "versione 3")
        val current = workspace.snapshot()
        val afterTask = mapOf("edit.txt" to "versione 2")
        val edits = RollbackGuard.detectUserEdits(afterTask, current)
        val plan = RollbackPlanner.plan(
            checkpoint = checkpointer.snapshot.orEmpty(),
            touched = outcome.modified.toSet(),
            currentHashes = RollbackGuard.fingerprints(current),
            expectedHashes = RollbackGuard.fingerprints(afterTask),
        )

        assertEquals(listOf("edit.txt"), edits)
        assertTrue(plan.restore.isEmpty())
        assertEquals(listOf("edit.txt"), plan.skipped)
        assertFalse(
            RollbackGuard.shouldAutoRollback(
                enabled = true,
                verificationFailed = true,
                writtenFiles = outcome.written.size,
                checkpointId = outcome.checkpointId,
                userEdits = edits,
            ),
        )
        assertEquals("versione 3", workspace.read("edit.txt"))
    }

    @Test
    fun `verification separates compilation from real execution`() = runBlocking {
        val workspace = InMemoryWorkspace(
            mapOf(
                "Main.java" to "public class Main { public static void main(String[] a) { } }",
                "index.html" to "<!doctype html><html><body>ok</body></html>",
            ),
        )
        val runtime = FakeRuntime()

        val report = ProjectVerifier(runtime).verify(workspace.snapshot())
        val compilation = report.checks.first { it.category == CheckCategory.COMPILATION }
        val execution = report.checks.first { it.category == CheckCategory.EXECUTION }

        assertEquals(CheckStatus.UNSUPPORTED, compilation.status)
        assertEquals(CheckStatus.PASSED, execution.status)

        val terminal = ProjectTerminal(workspace, TerminalHooks(runtime = runtime))
        val java = terminal.execute("run Main.java")
        val html = terminal.execute("run index.html")

        assertEquals(1, java.exitCode)
        assertTrue(java.text().contains("javac"))
        assertEquals(0, html.exitCode)
        assertTrue(html.text().contains("Esecuzione completata"))
    }

    @Test
    fun `large projects stay within the documented limits`() = runBlocking {
        val files = (1..120).associate { "src/file$it.txt" to "contenuto $it" }
        val workspace = InMemoryWorkspace(files)
        val terminal = ProjectTerminal(workspace)

        val report = ProjectVerifier().verify(workspace.snapshot(), VerifyOptions(maxFiles = 40))
        val found = terminal.execute("find file")
        val listing = terminal.execute("ls src")

        assertEquals(40, report.files)
        assertTrue(found.lines.size <= 300)
        assertEquals(120, listing.lines.size)
    }

    @Test
    fun `a model that cannot load is reported without touching the project`() = runBlocking {
        val workspace = InMemoryWorkspace(mapOf("index.html" to "originale"))
        val events = mutableListOf<AgentEvent>()

        val outcome = AgentRunner(workspace, ThrowingModel("Nessun modello Code installato"), SnapshotCheckpointer(workspace))
            .run(
                prompts = { _, _ -> listOf(AgentMessage("user", "crea")) },
                onEvent = { events += it },
            )

        assertEquals("Nessun modello Code installato", outcome.error)
        assertTrue(outcome.written.isEmpty())
        assertEquals("originale", workspace.read("index.html"))
        assertTrue(events.none { it is AgentEvent.Assistant })
    }

    @Test
    fun `terminal and agent operate on the same project`() = runBlocking {
        val workspace = InMemoryWorkspace()
        val model = ScriptedModel(
            write("src/nota.txt", "scritto dall'agente"),
            "<tool name=\"finish\">fatto</tool>",
        )
        AgentRunner(workspace, model, SnapshotCheckpointer(workspace)).run(
            prompts = { _, _ -> listOf(AgentMessage("user", "scrivi")) },
        )

        val terminal = ProjectTerminal(workspace)
        val result = terminal.execute("cat src/nota.txt")

        assertEquals("scritto dall'agente", result.text())
    }
}
