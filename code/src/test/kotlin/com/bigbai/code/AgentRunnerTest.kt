package com.bigbai.code

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunnerTest {

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
        override suspend fun complete(messages: List<AgentMessage>): String = throw IllegalStateException(message)
    }

    private class RecordingCheckpointer(
        private val succeeds: Boolean = true,
        private val workspace: InMemoryWorkspace? = null,
    ) : AgentCheckpointer {
        var calls = 0
        var snapshot: Map<String, String>? = null

        override suspend fun checkpoint(reason: String): GitCommitInfo? {
            calls++
            snapshot = workspace?.snapshot()
            return if (succeeds) {
                GitCommitInfo("abcdef1234567890", "abcdef1", reason, "ITAGPT", 0)
            } else {
                null
            }
        }
    }

    private fun write(path: String, content: String): String =
        "<tool name=\"write_file\" path=\"$path\">\n$content\n</tool>"

    private fun runner(
        workspace: InMemoryWorkspace,
        model: AgentModel,
        checkpointer: AgentCheckpointer = RecordingCheckpointer(workspace = workspace),
        options: AgentOptions = AgentOptions(),
        budget: EditBudget = EditBudget(),
    ) = AgentRunner(workspace, model, checkpointer, options, budget)

    private suspend fun AgentRunner.runTask(
        events: MutableList<AgentEvent> = mutableListOf(),
        shouldStop: () -> Boolean = { false },
        answerProblem: (String) -> String? = { null },
    ): AgentOutcome = run(
        prompts = { step, transcript -> listOf(AgentMessage("user", "passo $step $transcript")) },
        onEvent = { events += it },
        shouldStop = shouldStop,
        answerProblem = answerProblem,
    )

    @Test
    fun `writes a file and finishes with a real checkpoint`() = runBlocking {
        val workspace = InMemoryWorkspace()
        val checkpointer = RecordingCheckpointer(workspace = workspace)
        val events = mutableListOf<AgentEvent>()
        val model = ScriptedModel(
            write("index.html", "<h1>Ciao</h1>"),
            "<tool name=\"finish\">Creato index.html</tool>",
        )

        val outcome = runner(workspace, model, checkpointer).runTask(events)

        assertTrue(outcome.finished)
        assertEquals(listOf("index.html"), outcome.written)
        assertEquals("<h1>Ciao</h1>", workspace.read("index.html"))
        assertEquals(1, checkpointer.calls)
        assertEquals(2, outcome.stepsUsed)
        assertTrue(events.any { it is AgentEvent.Checkpoint })
        assertTrue(events.any { it is AgentEvent.Assistant && it.text.contains("Creato") })
    }

    @Test
    fun `writes several files in one step`() = runBlocking {
        val workspace = InMemoryWorkspace()
        val model = ScriptedModel(
            write("a.txt", "uno") + "\n" + write("b.txt", "due"),
            "<tool name=\"finish\">due file</tool>",
        )

        val outcome = runner(workspace, model).runTask()

        assertEquals(listOf("a.txt", "b.txt"), outcome.written)
        assertEquals("uno", workspace.read("a.txt"))
        assertEquals("due", workspace.read("b.txt"))
    }

    @Test
    fun `reads a file before writing`() = runBlocking {
        val workspace = InMemoryWorkspace(mapOf("app.js" to "console.log(1)"))
        val events = mutableListOf<AgentEvent>()
        val model = ScriptedModel(
            "<tool name=\"read_file\" path=\"app.js\"/>",
            write("app.js", "console.log(2)"),
            "<tool name=\"finish\">aggiornato</tool>",
        )

        val outcome = runner(workspace, model).runTask(events)

        assertEquals("console.log(2)", workspace.read("app.js"))
        assertTrue(events.any { it is AgentEvent.Step && it.label == "Letto app.js" })
        assertTrue(outcome.finished)
    }

    @Test
    fun `lists files through the workspace`() = runBlocking {
        val workspace = InMemoryWorkspace(mapOf("src/app.kt" to "fun main() {}"))
        val events = mutableListOf<AgentEvent>()
        val model = ScriptedModel(
            "<tool name=\"list_files\"/>",
            "<tool name=\"finish\">visto</tool>",
        )

        runner(workspace, model).runTask(events)

        assertTrue(events.any { it is AgentEvent.Step && it.label == "Elenco dei file" })
    }

    @Test
    fun `deletion needs confirmation and does not delete`() = runBlocking {
        val workspace = InMemoryWorkspace(mapOf("old.txt" to "contenuto"))
        val events = mutableListOf<AgentEvent>()
        val model = ScriptedModel("<tool name=\"delete_file\" path=\"old.txt\"/>")

        val outcome = runner(workspace, model).runTask(events)

        assertEquals("old.txt", outcome.pendingDelete)
        assertTrue(workspace.exists("old.txt"))
        assertTrue(events.any { it is AgentEvent.NeedsDeleteConfirmation })
        assertFalse(outcome.finished)
    }

    @Test
    fun `checkpoint failure blocks every write`() = runBlocking {
        val workspace = InMemoryWorkspace()
        val checkpointer = RecordingCheckpointer(succeeds = false)
        val events = mutableListOf<AgentEvent>()
        val model = ScriptedModel(write("a.txt", "x"))

        val outcome = runner(workspace, model, checkpointer).runTask(events)

        assertEquals(0, workspace.writeCount)
        assertTrue(outcome.error.orEmpty().contains("Checkpoint non riuscito"))
        assertTrue(events.any { it is AgentEvent.Step && it.status == AgentStepStatus.FAILED })
    }

    @Test
    fun `a failed write is reported and not counted`() = runBlocking {
        val workspace = InMemoryWorkspace()
        workspace.failWrites = true
        val events = mutableListOf<AgentEvent>()
        val model = ScriptedModel(write("a.txt", "x"), "<tool name=\"finish\">fatto</tool>")

        val outcome = runner(workspace, model).runTask(events)

        assertTrue(outcome.written.isEmpty())
        assertTrue(events.any { it is AgentEvent.Step && it.status == AgentStepStatus.FAILED })
    }

    @Test
    fun `file budget stops the task before the extra file`() = runBlocking {
        val workspace = InMemoryWorkspace()
        val model = ScriptedModel(
            write("a.txt", "uno") + "\n" + write("b.txt", "due"),
            "<tool name=\"finish\">fatto</tool>",
        )

        val outcome = runner(workspace, model, budget = EditBudget(maxFiles = 1, maxOperations = 20)).runTask()

        assertEquals(listOf("a.txt"), outcome.written)
        assertFalse(workspace.exists("b.txt"))
        assertTrue(outcome.limitReason.orEmpty().contains("1 file"))
        assertTrue(outcome.error == null)
    }

    @Test
    fun `operation budget stops repeated work`() = runBlocking {
        val workspace = InMemoryWorkspace()
        val model = ScriptedModel(write("a.txt", "uno"))

        val outcome = runner(workspace, model, budget = EditBudget(maxFiles = 5, maxOperations = 0)).runTask()

        assertTrue(outcome.limitReason.orEmpty().contains("0 operazioni"))
        assertEquals(0, workspace.writeCount)
    }

    @Test
    fun `plain text is delivered as an assistant answer`() = runBlocking {
        val workspace = InMemoryWorkspace()
        val events = mutableListOf<AgentEvent>()
        val model = ScriptedModel("Quale file vuoi modificare?")

        val outcome = runner(workspace, model).runTask(events)

        assertTrue(outcome.finished)
        assertTrue(events.any { it is AgentEvent.Assistant && it.text.contains("Quale file") })
        assertEquals(0, workspace.writeCount)
    }

    @Test
    fun `invalid tool name is reported`() = runBlocking {
        val workspace = InMemoryWorkspace()
        val events = mutableListOf<AgentEvent>()
        val model = ScriptedModel(
            "<tool name=\"shell\">rm -rf /</tool>",
            "<tool name=\"finish\">niente</tool>",
        )

        runner(workspace, model).runTask(events)

        assertTrue(events.any { it is AgentEvent.Step && it.label == "Strumento non valido" })
    }

    @Test
    fun `invalid path is refused`() = runBlocking {
        val workspace = InMemoryWorkspace()
        val model = ScriptedModel(write("../escape.txt", "x"))

        val outcome = runner(workspace, model).runTask()

        assertTrue(outcome.written.isEmpty())
        assertFalse(workspace.exists("escape.txt"))
    }

    @Test
    fun `model failure becomes an error instead of a success`() = runBlocking {
        val workspace = InMemoryWorkspace()

        val outcome = runner(workspace, ThrowingModel("modello non caricato")).runTask()

        assertEquals("modello non caricato", outcome.error)
        assertFalse(outcome.finished)
        assertTrue(outcome.written.isEmpty())
    }

    @Test
    fun `timeout interrupts the task`() = runBlocking {
        val workspace = InMemoryWorkspace()
        val model = ScriptedModel(write("a.txt", "x"))
        model.delayMs = 200

        val outcome = runner(
            workspace,
            model,
            options = AgentOptions(timeoutMs = 20),
        ).runTask()

        assertTrue(outcome.timedOut)
        assertTrue(outcome.interrupted)
        assertEquals(0, workspace.writeCount)
    }

    @Test
    fun `stop request before the first step is respected`() = runBlocking {
        val workspace = InMemoryWorkspace()
        val model = ScriptedModel(write("a.txt", "x"))

        val outcome = runner(workspace, model).runTask(shouldStop = { true })

        assertTrue(outcome.interrupted)
        assertTrue(model.prompts.isEmpty())
        assertEquals(0, workspace.writeCount)
    }

    @Test
    fun `a repetitive answer is rejected before writing`() = runBlocking {
        val workspace = InMemoryWorkspace()
        val model = ScriptedModel(write("a.txt", "x"))

        val outcome = runner(workspace, model).runTask(answerProblem = { "Risposta ripetitiva" })

        assertEquals("Risposta ripetitiva", outcome.error)
        assertEquals(0, workspace.writeCount)
    }

    @Test
    fun `tool transcript is included in the next prompt`() = runBlocking {
        val workspace = InMemoryWorkspace()
        val model = ScriptedModel(
            write("a.txt", "contenuto"),
            "<tool name=\"finish\">fatto</tool>",
        )

        runner(workspace, model).runTask()

        val second = model.prompts[1].single().content
        assertTrue(second.contains("OK: scritto a.txt"))
        assertNull(model.prompts[1].firstOrNull { it.role == "assistant" })
    }
}
