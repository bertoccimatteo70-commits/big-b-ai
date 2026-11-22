package com.bigbai.code

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One message of the agent conversation, independent from any model engine. */
data class AgentMessage(val role: String, val content: String)

/** The language model seen by the agent: a single blocking completion. */
interface AgentModel {
    suspend fun complete(messages: List<AgentMessage>): String
}

/** Creates a checkpoint before the first write of a task. */
interface AgentCheckpointer {
    suspend fun checkpoint(reason: String): GitCommitInfo?
}

data class AgentOptions(
    val maxSteps: Int = CodeAgent.MAX_STEPS,
    val timeoutMs: Long = 240_000L,
)

sealed interface AgentEvent {
    data class Progress(val step: Int, val max: Int) : AgentEvent
    data class Step(
        val label: String,
        val detail: String? = null,
        val status: AgentStepStatus = AgentStepStatus.DONE,
    ) : AgentEvent
    data class Assistant(val text: String) : AgentEvent
    data class Checkpoint(val commit: GitCommitInfo) : AgentEvent
    data class NeedsDeleteConfirmation(val path: String) : AgentEvent
}

data class AgentOutcome(
    val written: List<String> = emptyList(),
    val modified: List<String> = emptyList(),
    val checkpointId: String? = null,
    val finished: Boolean = false,
    val interrupted: Boolean = false,
    val timedOut: Boolean = false,
    val limitReason: String? = null,
    val pendingDelete: String? = null,
    val error: String? = null,
    val stepsUsed: Int = 0,
    val producedOutput: Boolean = false,
)

/**
 * The agent loop, extracted from the ViewModel so it can be tested without
 * Android, a real model or SAF.
 *
 * It performs only real operations: it writes, reads, lists and deletes through
 * [ProjectWorkspace], and it asks for a checkpoint before the first write. Every
 * limitation (budget, timeout, confirmation, failure) is part of the returned
 * outcome, so the caller can report exactly what happened.
 */
class AgentRunner(
    private val workspace: ProjectWorkspace,
    private val model: AgentModel,
    private val checkpointer: AgentCheckpointer,
    private val options: AgentOptions = AgentOptions(),
    private val budget: EditBudget = EditBudget(),
) {

    suspend fun run(
        prompts: (step: Int, toolTranscript: String) -> List<AgentMessage>,
        onEvent: suspend (AgentEvent) -> Unit = {},
        shouldStop: () -> Boolean = { false },
        answerProblem: (String) -> String? = { null },
        stopModel: () -> Unit = {},
    ): AgentOutcome = coroutineScope {
        val progress = Progress()
        var timedOut = false
        val watchdog = launch {
            delay(options.timeoutMs)
            timedOut = true
            runCatching { stopModel() }
        }
        val outcome = try {
            // The watchdog stops the model; the hard timeout guarantees that a
            // runtime ignoring stop() cannot hang the task forever.
            withTimeoutOrNull(options.timeoutMs + HARD_TIMEOUT_GRACE_MS) {
                loop(prompts, onEvent, shouldStop, { timedOut }, answerProblem, progress)
            } ?: progress.toOutcome(
                finished = false,
                interrupted = true,
                timedOut = true,
                error = null,
                limitReason = null,
            )
        } finally {
            watchdog.cancel()
        }
        outcome.copy(timedOut = outcome.timedOut || timedOut)
    }

    private suspend fun loop(
        prompts: (step: Int, toolTranscript: String) -> List<AgentMessage>,
        onEvent: suspend (AgentEvent) -> Unit,
        shouldStop: () -> Boolean,
        timedOut: () -> Boolean,
        answerProblem: (String) -> String?,
        progress: Progress,
    ): AgentOutcome {
        var step = 1
        var finished = false
        var toolTranscript = ""
        var limitReason: String? = null
        var error: String? = null
        var checkpointFailed = false

        while (step <= options.maxSteps && !finished && limitReason == null &&
            progress.pendingDelete == null && !checkpointFailed
        ) {
            if (shouldStop() || timedOut()) {
                progress.interrupted = true
                break
            }
            progress.stepsUsed = step
            onEvent(AgentEvent.Progress(step, options.maxSteps))
            val answer = try {
                model.complete(prompts(step, toolTranscript))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Generazione non riuscita"
                break
            }
            if (shouldStop() || timedOut()) {
                progress.interrupted = true
                break
            }
            if (answer.isBlank()) {
                error = "Il modello non ha prodotto testo."
                break
            }
            progress.producedOutput = true

            val problem = answerProblem(answer)
            if (problem != null) {
                onEvent(AgentEvent.Step("Risposta scartata", problem, AgentStepStatus.FAILED))
                error = problem
                break
            }

            val actions = CodeAgent.parse(answer)
            if (actions.isEmpty()) {
                onEvent(AgentEvent.Assistant(answer))
                finished = true
                break
            }

            val transcript = StringBuilder()
            for (action in actions) {
                if (!budget.beginOperation()) {
                    limitReason = budget.blockReason()
                    transcript.append("ERRORE: ").append(limitReason).append('\n')
                    break
                }
                when (action) {
                    is AgentAction.WriteFile -> {
                        if (!budget.canChange(action.path)) {
                            limitReason = budget.blockReason()
                            transcript.append("ERRORE: ").append(limitReason)
                                .append(" File non modificato: ").append(action.path).append('\n')
                            break
                        }
                        if (progress.checkpointId == null) {
                            val commit = checkpointer.checkpoint("Prima dell'attività AI")
                            if (commit == null) {
                                val message = "Checkpoint non riuscito: non ho modificato nessun file. " +
                                    "Controlla lo spazio disponibile e riprova."
                                onEvent(AgentEvent.Step("Modifiche bloccate", message, AgentStepStatus.FAILED))
                                onEvent(AgentEvent.Assistant(message))
                                error = message
                                checkpointFailed = true
                                break
                            }
                            progress.checkpointId = commit.id
                            onEvent(AgentEvent.Checkpoint(commit))
                        }
                        if (workspace.write(action.path, action.content)) {
                            budget.recordChange(action.path)
                            progress.record(action.path)
                            transcript.append("OK: scritto ").append(action.path).append('\n')
                            onEvent(
                                AgentEvent.Step(
                                    "Scritto ${action.path}",
                                    "${action.content.length} caratteri",
                                ),
                            )
                        } else {
                            transcript.append("ERRORE: non è stato possibile scrivere ")
                                .append(action.path).append('\n')
                            onEvent(
                                AgentEvent.Step(
                                    "Errore su ${action.path}",
                                    "Scrittura non riuscita",
                                    AgentStepStatus.FAILED,
                                ),
                            )
                        }
                    }

                    is AgentAction.ReadFile -> {
                        val content = workspace.read(action.path)
                        if (content == null) {
                            transcript.append("ERRORE: ").append(action.path)
                                .append(" non esiste o non è leggibile\n")
                            onEvent(
                                AgentEvent.Step(
                                    "Lettura non riuscita: ${action.path}",
                                    null,
                                    AgentStepStatus.FAILED,
                                ),
                            )
                        } else {
                            transcript.append("FILE ").append(action.path).append('\n')
                                .append(content.take(CodeAgent.MAX_READ_CHARS)).append('\n')
                            onEvent(AgentEvent.Step("Letto ${action.path}"))
                        }
                    }

                    AgentAction.ListFiles -> {
                        val text = renderTree()
                        transcript.append("ALBERO\n").append(text).append('\n')
                        onEvent(AgentEvent.Step("Elenco dei file"))
                    }

                    is AgentAction.DeleteFile -> {
                        if (!budget.canChange(action.path)) {
                            limitReason = budget.blockReason()
                            transcript.append("ERRORE: ").append(limitReason)
                                .append(" File non eliminato: ").append(action.path).append('\n')
                            break
                        }
                        budget.recordChange(action.path)
                        progress.record(action.path)
                        progress.pendingDelete = action.path
                        onEvent(AgentEvent.NeedsDeleteConfirmation(action.path))
                        onEvent(
                            AgentEvent.Step(
                                "Eliminazione da confermare: ${action.path}",
                                "Operazione distruttiva: serve il tuo consenso.",
                                AgentStepStatus.NEEDS_USER,
                            ),
                        )
                    }

                    is AgentAction.Finish -> {
                        onEvent(AgentEvent.Assistant(action.summary))
                        finished = true
                    }

                    is AgentAction.Invalid -> {
                        transcript.append("ERRORE: ").append(action.reason).append('\n')
                        onEvent(AgentEvent.Step("Strumento non valido", action.reason, AgentStepStatus.FAILED))
                    }
                }
                if (finished || progress.pendingDelete != null || checkpointFailed) break
            }

            if (checkpointFailed) break
            if (limitReason != null) {
                val message = "Mi sono fermato: $limitReason Nessuna altra modifica è stata applicata " +
                    "in questa attività. Il progetto resta nello stato mostrato nell'activity log."
                onEvent(AgentEvent.Assistant(message))
                onEvent(
                    AgentEvent.Step(
                        "Limite dell'attività raggiunto",
                        limitReason,
                        AgentStepStatus.FAILED,
                    ),
                )
                break
            }
            if (finished || progress.pendingDelete != null) break
            if (transcript.isNotEmpty()) toolTranscript = transcript.toString()
            step += 1
        }

        if (!finished && !progress.interrupted && progress.pendingDelete == null &&
            limitReason == null && error == null && !checkpointFailed && step > options.maxSteps
        ) {
            onEvent(
                AgentEvent.Assistant(
                    "Ho raggiunto il limite di ${options.maxSteps} passi per questa attività. " +
                        "Scrivi «continua» per proseguire da dove sono arrivato.",
                ),
            )
        }

        return progress.toOutcome(finished, progress.interrupted, false, error, limitReason)
    }

    private suspend fun renderTree(maxLines: Int = 120): String {
        val builder = StringBuilder()
        var lines = 0
        suspend fun walk(directory: String, indent: String, depth: Int) {
            if (depth > 6 || lines >= maxLines) return
            for (entry in workspace.list(directory)) {
                if (lines >= maxLines) return
                builder.append(indent).append(if (entry.isDirectory) "📁 " else "📄 ")
                    .append(WorkspacePaths.name(entry.path)).append('\n')
                lines++
                if (entry.isDirectory) walk(entry.path, "$indent  ", depth + 1)
            }
        }
        walk("", "", 0)
        return builder.toString().trimEnd()
    }

    private class Progress {
        val written = mutableListOf<String>()
        val modified = linkedSetOf<String>()
        var checkpointId: String? = null
        var pendingDelete: String? = null
        var producedOutput = false
        var interrupted = false
        var stepsUsed = 0

        fun record(path: String) {
            written += path
            modified += path
        }

        fun toOutcome(
            finished: Boolean,
            interrupted: Boolean,
            timedOut: Boolean,
            error: String?,
            limitReason: String?,
        ): AgentOutcome = AgentOutcome(
            written = written.distinct(),
            modified = modified.toList(),
            checkpointId = checkpointId,
            finished = finished,
            interrupted = interrupted,
            timedOut = timedOut,
            limitReason = limitReason,
            pendingDelete = pendingDelete,
            error = error,
            stepsUsed = stepsUsed,
            producedOutput = producedOutput,
        )
    }

    private companion object {
        const val HARD_TIMEOUT_GRACE_MS = 5_000L
    }
}
