package com.bigbai.app.ui.code

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bigbai.app.AppContainer
import com.bigbai.app.files.FileExport
import com.bigbai.code.AgentAction
import com.bigbai.code.AgentStep
import com.bigbai.code.AgentStepStatus
import com.bigbai.code.AgentCheckpointer
import com.bigbai.code.AgentEvent
import com.bigbai.code.AgentMessage
import com.bigbai.code.AgentModel
import com.bigbai.code.AgentOptions
import com.bigbai.code.AgentOutcome
import com.bigbai.code.AgentRunner
import com.bigbai.code.LanguageSupport
import com.bigbai.code.ProjectTerminal
import com.bigbai.code.ProjectVerifier
import com.bigbai.code.RollbackGuard
import com.bigbai.code.RollbackPlanner
import com.bigbai.code.SafWorkspace
import com.bigbai.code.TerminalHooks
import com.bigbai.code.TerminalLine
import com.bigbai.code.TerminalTone
import com.bigbai.code.VerificationReport
import com.bigbai.code.VerifyOptions
import com.bigbai.code.CodeAgent
import com.bigbai.code.CodeQuota
import com.bigbai.code.Diagnostic
import com.bigbai.code.DiagnosticSeverity
import com.bigbai.code.EditBudget
import com.bigbai.code.FileNode
import com.bigbai.code.GitCommitInfo
import com.bigbai.code.JsSandbox
import com.bigbai.code.PatchEngine
import com.bigbai.code.ProposedChange
import com.bigbai.code.ProjectFiles
import com.bigbai.code.ProjectInsight
import com.bigbai.code.ProjectInsights
import com.bigbai.code.ProjectContextBuilder
import com.bigbai.code.ProjectNaming
import com.bigbai.code.ProjectPaths
import com.bigbai.code.SandboxResult
import com.bigbai.code.StaticAnalyzers
import com.bigbai.core.model.Project
import com.bigbai.core.model.ProjectChatMessage
import com.bigbai.core.model.ProjectLanguage
import com.bigbai.core.model.ProjectMemoryItem
import com.bigbai.core.model.ProjectMemoryKind
import com.bigbai.core.model.Role
import com.bigbai.llm.AnswerSanitizer
import com.bigbai.llm.GenerationParams
import com.bigbai.llm.ModelProfiles
import com.bigbai.llm.ModelRole
import com.bigbai.llm.PromptMessage
import com.bigbai.llm.ResponseQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class CodeUiState(
    val project: Project? = null,
    val projects: List<Project> = emptyList(),
    val tree: List<FileNode> = emptyList(),
    val openPath: String? = null,
    val editorText: String = "",
    val dirty: Boolean = false,
    val diagnostics: List<Diagnostic> = emptyList(),
    val messages: List<ProjectChatMessage> = emptyList(),
    val memory: List<ProjectMemoryItem> = emptyList(),
    val input: String = "",
    val generating: Boolean = false,
    val streamingText: String = "",
    val status: String? = null,
    val error: String? = null,
    val proposals: List<ProposedChange> = emptyList(),
    val proposalDiffs: Map<String, String> = emptyMap(),
    val gitLog: List<GitCommitInfo> = emptyList(),
    val branches: List<String> = emptyList(),
    val currentBranch: String? = null,
    val gitDiff: String? = null,
    val sandbox: SandboxResult? = null,
    val busy: Boolean = false,
    /** Activity log of the current agent task: done, failed, needs user. */
    val steps: List<AgentStep> = emptyList(),
    val quota: CodeQuota = CodeQuota(CodeQuota.today(), 0),
    /** Path the agent asked to delete, waiting for explicit confirmation. */
    val pendingDelete: String? = null,
    /** File or folder the user asked to delete from the explorer. */
    val pendingManualDelete: FileNode? = null,
    /** Checkpoint taken before the current/last agent task, if it succeeded. */
    val taskCheckpoint: GitCommitInfo? = null,
    /** Paths the last task wrote, used to undo exactly those files. */
    val taskModifiedPaths: List<String> = emptyList(),
    val codeModelLabel: String? = null,
    val chatModelLabel: String? = null,
    /** Project-wide findings: duplicated blocks, undeclared imports, TODO. */
    val insights: List<ProjectInsight> = emptyList(),
    /** Human-readable project shape, produced by the last analysis. */
    val projectSummary: String? = null,
    /** Report of the last agent task: files written, checks, static errors. */
    val changeReport: String? = null,
    /** Terminal output of the current project session. */
    val terminalLines: List<TerminalLine> = emptyList(),
    val terminalInput: String = "",
    val terminalCwd: String = "",
    val terminalRunning: Boolean = false,
    val terminalHistory: List<String> = emptyList(),
    /** Last real verification of the project. */
    val verification: VerificationReport? = null,
    val verifying: Boolean = false,
    /** Automatic rollback preference, mirrored from settings. */
    val autoRollback: Boolean = false,
    /** Result of the last restore, with the byte-level verification. */
    val restoreVerification: String? = null,
    /** SHA-256 of each file the last task wrote, used to detect user edits. */
    val taskWrittenHashes: Map<String, String> = emptyMap(),
)

class CodeViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(CodeUiState())
    val state: StateFlow<CodeUiState> = _state.asStateFlow()

    private var chatJob: Job? = null
    private var terminal: ProjectTerminal? = null
    private var terminalJob: Job? = null
    private var terminalHistoryIndex = -1
    private var codeNetworkAllowed = false
    private var lastRawAnswer = ""
    private var timeoutJob: Job? = null
    private var editorAnalysisJob: Job? = null
    private var cancelledByUser = false
    private var timedOut = false

    init {
        viewModelScope.launch {
            container.projects.observeProjects().collect { projects ->
                _state.update { it.copy(projects = projects) }
            }
        }
        viewModelScope.launch {
            container.settings.codeQuota.collect { (date, used) ->
                val quota = CodeQuota.of(CodeQuota.today(), date, used)
                _state.update { it.copy(quota = quota) }
            }
        }
    }

    private suspend fun refreshQuota(): CodeQuota {
        val (date, used) = container.settings.codeQuotaNow()
        val quota = CodeQuota.of(CodeQuota.today(), date, used)
        _state.update { it.copy(quota = quota) }
        return quota
    }

    fun clearError() = _state.update { it.copy(error = null) }
    fun clearStatus() = _state.update { it.copy(status = null) }

    fun addProject(uri: Uri) {
        viewModelScope.launch {
            val name = container.projectFiles.displayName(uri.toString()) ?: "Progetto"
            val id = container.projects.addProject(uri.toString(), name, ProjectLanguage.OTHER)
            openProject(id)
        }
    }

    /**
     * Creates a complete project without asking for a folder, a name or a file:
     * the folder is named from the date, `index.html` is written and opened, and
     * the project appears in the list. This is the "automatic files" path.
     */
    fun createProject() {
        viewModelScope.launch {
            val base = File(
                container.context.getExternalFilesDir(null) ?: container.context.filesDir,
                "projects",
            )
            if (!base.isDirectory && !base.mkdirs()) {
                _state.update { it.copy(error = "Impossibile creare la cartella dei progetti") }
                return@launch
            }
            var folder = File(base, ProjectNaming.folderName(System.currentTimeMillis()))
            var suffix = 2
            while (folder.exists()) {
                folder = File(base, "${ProjectNaming.folderName(System.currentTimeMillis())}-${suffix}")
                suffix += 1
            }
            if (!folder.mkdirs()) {
                _state.update { it.copy(error = "Impossibile creare la cartella del progetto") }
                return@launch
            }
            val uri = Uri.fromFile(folder).toString()
            val root = container.projectFiles.root(uri)
            if (root == null) {
                _state.update { it.copy(error = "Cartella del progetto non accessibile") }
                return@launch
            }
            container.projectFiles.writeText(
                root,
                ProjectNaming.ENTRY_FILE,
                ProjectNaming.starterHtml(folder.name),
            )
            val id = container.projects.addProject(uri, folder.name, ProjectLanguage.HTML)
            openProject(id)
            _state.update {
                it.copy(status = "Progetto «${folder.name}» creato con ${ProjectNaming.ENTRY_FILE}")
            }
        }
    }

    fun renameFile(node: FileNode, newName: String) {
        val project = _state.value.project ?: return
        val clean = newName.trim().replace("/", "_")
        if (clean.isBlank()) return
        viewModelScope.launch {
            val root = container.projectFiles.root(project.treeUri) ?: return@launch
            val ok = container.projectFiles.rename(root, node.path, clean)
            if (ok && _state.value.openPath == node.path) {
                val parent = node.path.substringBeforeLast('/', "")
                val newPath = if (parent.isEmpty()) clean else "$parent/$clean"
                _state.update { it.copy(openPath = newPath) }
            }
            if (ok) refreshTree()
            _state.update {
                it.copy(
                    status = if (ok) "File rinominato in «$clean»" else null,
                    error = if (ok) null else "Rinomina non riuscita",
                )
            }
        }
    }

    /** Copies the open file to Download/ITAGPT with its own name. */
    fun exportCurrentFile() {
        val path = _state.value.openPath ?: return
        val name = path.substringAfterLast('/')
        val content = _state.value.editorText
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                FileExport.saveToDownloads(
                    container.context,
                    name,
                    ProjectFiles.mimeFor(name),
                    content,
                )
            }
            _state.update { current ->
                result.fold(
                    onSuccess = {
                        current.copy(status = "Esportato in Download/ITAGPT/$name", error = null)
                    },
                    onFailure = { error ->
                        current.copy(error = error.message ?: "Esportazione non riuscita")
                    },
                )
            }
        }
    }

    fun shareCurrentFile() {
        val path = _state.value.openPath ?: return
        val name = path.substringAfterLast('/')
        FileExport.share(
            container.context,
            name,
            ProjectFiles.mimeFor(name),
            _state.value.editorText,
        ).onFailure { error ->
            _state.update { it.copy(error = error.message ?: "Condivisione non riuscita") }
        }
    }

    fun removeProject(project: Project) {
        viewModelScope.launch {
            container.projects.removeProject(project.id)
            if (_state.value.project?.id == project.id) {
                _state.update { CodeUiState(projects = _state.value.projects) }
            }
        }
    }

    fun openProject(id: Long) {
        viewModelScope.launch {
            val project = container.projects.project(id) ?: return@launch
            _state.update {
                it.copy(
                    project = project,
                    openPath = null,
                    editorText = "",
                    diagnostics = emptyList(),
                    proposals = emptyList(),
                    proposalDiffs = emptyMap(),
                    gitDiff = null,
                    sandbox = null,
                    steps = emptyList(),
                    pendingDelete = null,
                    pendingManualDelete = null,
                    taskCheckpoint = null,
                    taskModifiedPaths = emptyList(),
                    insights = emptyList(),
                    projectSummary = null,
                    changeReport = null,
                )
            }
            container.projects.touch(id)
            val settings = container.settings.current()
            updateModelLabels(settings)
            codeNetworkAllowed = settings.codeNetworkAllowed
            terminal = null
            terminalJob?.cancel()
            terminalJob = null
            terminalHistoryIndex = -1
            _state.update {
                it.copy(
                    autoRollback = settings.codeAutoRollback,
                    terminalLines = emptyList(),
                    terminalInput = "",
                    terminalCwd = "",
                    terminalHistory = emptyList(),
                    verification = null,
                    restoreVerification = null,
                )
            }
            refreshTree()
            viewModelScope.launch {
                container.projects.messages(id).collect { messages ->
                    _state.update { it.copy(messages = messages) }
                }
            }
            viewModelScope.launch {
                container.projects.memory(id).collect { memory ->
                    _state.update { it.copy(memory = memory) }
                }
            }
            refreshGit()
        }
    }

    fun closeProject() {
        _state.update {
            it.copy(
                project = null,
                tree = emptyList(),
                openPath = null,
                editorText = "",
                diagnostics = emptyList(),
                pendingDelete = null,
                pendingManualDelete = null,
                taskCheckpoint = null,
                taskModifiedPaths = emptyList(),
                taskWrittenHashes = emptyMap(),
                terminalLines = emptyList(),
                terminalInput = "",
                terminalCwd = "",
                terminalHistory = emptyList(),
                terminalRunning = false,
                verification = null,
                restoreVerification = null,
            )
        }
        terminal = null
        terminalJob?.cancel()
        terminalJob = null
    }

    fun refreshTree() {
        val project = _state.value.project ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val root = container.projectFiles.root(project.treeUri)
            val nodes = if (root == null) emptyList() else container.projectFiles.list(root)
            _state.update { it.copy(tree = nodes, busy = false) }
        }
    }

    fun openFile(node: FileNode) {
        if (node.isDirectory) return
        val project = _state.value.project ?: return
        viewModelScope.launch {
            val root = container.projectFiles.root(project.treeUri) ?: return@launch
            val text = container.projectFiles.readText(root, node.path)
            if (text == null) {
                _state.update { it.copy(error = "File non leggibile o troppo grande (${node.path})") }
                return@launch
            }
            editorAnalysisJob?.cancel()
            _state.update {
                it.copy(
                    openPath = node.path,
                    editorText = text,
                    dirty = false,
                    diagnostics = StaticAnalyzers.analyze(node.path, text),
                    sandbox = null,
                )
            }
        }
    }

    fun updateEditor(text: String) {
        _state.update { it.copy(editorText = text, dirty = true) }
        val path = _state.value.openPath ?: return
        // Static analysis is deterministic but not free: run it after the typing
        // pause, off the main thread, and discard results for stale text.
        editorAnalysisJob?.cancel()
        editorAnalysisJob = viewModelScope.launch {
            delay(250)
            val diagnostics = withContext(Dispatchers.Default) { StaticAnalyzers.analyze(path, text) }
            _state.update {
                if (it.openPath == path && it.editorText == text) it.copy(diagnostics = diagnostics) else it
            }
        }
    }

    fun saveFile() {
        val project = _state.value.project ?: return
        val path = _state.value.openPath ?: return
        viewModelScope.launch {
            val root = container.projectFiles.root(project.treeUri)
            if (root == null) {
                _state.update { it.copy(error = "Cartella non accessibile") }
                return@launch
            }
            val ok = container.projectFiles.writeText(root, path, _state.value.editorText)
            _state.update {
                it.copy(
                    dirty = !ok,
                    error = if (ok) null else "Salvataggio non riuscito",
                    status = if (ok) "File salvato" else null,
                )
            }
            if (ok) refreshTree()
        }
    }

    fun createFile(path: String) {
        val project = _state.value.project ?: return
        viewModelScope.launch {
            val root = container.projectFiles.root(project.treeUri) ?: return@launch
            val ok = container.projectFiles.createFile(root, path)
            _state.update {
                it.copy(
                    status = if (ok) "File creato" else null,
                    error = if (ok) null else "Impossibile creare il file",
                )
            }
            if (ok) refreshTree()
        }
    }

    /** Never deletes immediately: the explorer must ask for confirmation. */
    fun deleteFile(node: FileNode) {
        _state.update { it.copy(pendingManualDelete = node, error = null) }
    }

    fun cancelManualDelete() = _state.update {
        it.copy(pendingManualDelete = null, status = "Eliminazione annullata") }

    fun confirmManualDelete() {
        val node = _state.value.pendingManualDelete ?: return
        val project = _state.value.project ?: return
        viewModelScope.launch {
            val root = container.projectFiles.root(project.treeUri)
            if (root == null) {
                _state.update {
                    it.copy(pendingManualDelete = null, error = "Cartella del progetto non accessibile")
                }
                return@launch
            }
            _state.update { it.copy(busy = true, pendingManualDelete = null) }
            val ok = container.projectFiles.delete(root, node.path)
            if (ok) {
                _state.update { current ->
                    val openInside = current.openPath?.let { path ->
                        if (node.isDirectory) ProjectPaths.isInside(path, node.path) else path == node.path
                    } == true
                    current.copy(
                        openPath = if (openInside) null else current.openPath,
                        editorText = if (openInside) "" else current.editorText,
                        diagnostics = if (openInside) emptyList() else current.diagnostics,
                        dirty = if (openInside) false else current.dirty,
                    )
                }
            }
            _state.update {
                it.copy(
                    busy = false,
                    status = if (ok) {
                        if (node.isDirectory) "Cartella «${node.name}» eliminata" else "File «${node.name}» eliminato"
                    } else null,
                    error = if (ok) null else "Eliminazione non riuscita: ${node.name}",
                )
            }
            if (ok) refreshTree()
        }
    }

    fun onInputChange(value: String) = _state.update { it.copy(input = value) }

    fun send() {
        val project = _state.value.project ?: return
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.generating) return

        chatJob = viewModelScope.launch {
            val quota = refreshQuota()
            if (quota.exhausted) {
                _state.update {
                    it.copy(
                        input = text,
                        error = "Hai esaurito le 5 richieste di ITAGPT Code per oggi. " +
                            "Il contatore si azzera domani; i passaggi interni non consumano richieste.",
                    )
                }
                return@launch
            }
            val consumed = quota.consume()
            container.settings.setCodeQuota(consumed.date, consumed.used)
            _state.update {
                it.copy(
                    input = "",
                    generating = true,
                    quota = consumed,
                    error = null,
                    steps = emptyList(),
                    pendingDelete = null,
                    taskCheckpoint = null,
                    taskModifiedPaths = emptyList(),
                    taskWrittenHashes = emptyMap(),
                    proposals = emptyList(),
                    proposalDiffs = emptyMap(),
                    verification = null,
                    status = "Analisi della richiesta…",
                )
            }

            var producedOutput = false
            try {
                val settings = container.settings.current()
                codeNetworkAllowed = settings.codeNetworkAllowed
                val loaded = ensureCodeModel(settings)
                if (loaded == null) {
                    refundQuota(consumed)
                    return@launch
                }
                val profile = ModelProfiles.forVariant(loaded.variant)
                val params = profile.params(settings)

                // History is captured before the new user turn: otherwise the
                // same request would be sent twice (history + current message).
                val priorMessages = container.projects.messages(project.id).first().takeLast(6)
                container.projects.appendMessage(project.id, Role.USER, text)
                val root = container.projectFiles.root(project.treeUri)
                if (root == null) {
                    _state.update { it.copy(error = "Cartella del progetto non accessibile") }
                    refundQuota(consumed)
                    return@launch
                }

                // The project is read once per task, not once per step.
                val initialContext = projectContext(root, text)
                val initialTree = container.projectFiles.treeText(_state.value.tree)
                val language = settings.language
                val projectName = project.name

                val agentModel = object : AgentModel {
                    override suspend fun complete(messages: List<AgentMessage>): String {
                        val prompt = messages.map { PromptMessage(it.role, it.content) }
                        val builder = StringBuilder()
                        val result = container.engine.generate(prompt, params) { token -> builder.append(token) }
                        lastRawAnswer = builder.toString()
                        result.getOrNull()?.let { metrics ->
                            if (metrics.completionTokens > 0 || metrics.promptTokens > 0) {
                                container.lastMetrics.value = metrics
                            }
                        }
                        result.exceptionOrNull()?.let { throw it }
                        return AnswerSanitizer.clean(builder.toString()).trim()
                    }
                }
                val checkpointer = object : AgentCheckpointer {
                    override suspend fun checkpoint(reason: String): GitCommitInfo? = checkpoint(project, reason)
                }
                val runner = AgentRunner(
                    workspace = SafWorkspace(container.projectFiles, root),
                    model = agentModel,
                    checkpointer = checkpointer,
                    options = AgentOptions(maxSteps = CodeAgent.MAX_STEPS, timeoutMs = TASK_TIMEOUT_MS),
                )
                val outcome = runner.run(
                    prompts = { step, transcript ->
                        val system = CodeAgent.systemPrompt(
                            language = language,
                            projectName = projectName,
                            tree = initialTree,
                            projectContext = initialContext,
                            step = step,
                            maxSteps = CodeAgent.MAX_STEPS,
                        )
                        val history = priorMessages.map { message ->
                            AgentMessage(
                                if (message.role == Role.USER) "user" else "assistant",
                                message.content,
                            )
                        }
                        val userTurn = if (transcript.isBlank()) {
                            text
                        } else {
                            "$text\n\nRISULTATI REALI DEGLI STRUMENTI (usali, non inventare altro):\n$transcript"
                        }
                        listOf(AgentMessage("system", system)) + history + AgentMessage("user", userTurn)
                    },
                    onEvent = { event -> handleAgentEvent(project.id, event) },
                    shouldStop = { cancelledByUser || timedOut },
                    answerProblem = { answer ->
                        val quality = ResponseQuality.inspect(answer)
                        if (!quality.acceptable && quality.repetitionRatio > 0.45) {
                            "Il modello ha iniziato a ripetersi: nessuna modifica applicata."
                        } else {
                            null
                        }
                    },
                    stopModel = { container.engine.stop() },
                )
                timedOut = outcome.timedOut
                producedOutput = outcome.producedOutput

                if (outcome.timedOut) {
                    appendInterrupted(project.id, lastRawAnswer)
                    _state.update {
                        it.copy(
                            error = "Il modello non ha risposto in tempo. Controlla l'activity log " +
                                "per le operazioni già eseguite.",
                        )
                    }
                } else if (outcome.interrupted) {
                    appendInterrupted(project.id, lastRawAnswer)
                }
                outcome.error?.let { message -> _state.update { it.copy(error = message) } }

                if (outcome.written.isNotEmpty()) {
                    _state.update { it.copy(taskModifiedPaths = outcome.modified) }
                    val hashes = outcome.modified.mapNotNull { path ->
                        container.projectFiles.readText(root, path)?.let { content ->
                            path to RollbackGuard.fingerprint(content)
                        }
                    }.toMap()
                    _state.update { it.copy(taskWrittenHashes = hashes) }
                    val report = analyzeWritten(project, outcome.written)
                    maybeAutoRollback(project, outcome, report)
                }

                if (outcome.written.isEmpty() &&
                    (outcome.error != null || outcome.timedOut || outcome.interrupted)
                ) {
                    refundQuota(consumed)
                }
                if ((cancelledByUser || outcome.timedOut) && outcome.written.isNotEmpty()) {
                    val checkpointId = _state.value.taskCheckpoint?.shortId
                    _state.update {
                        it.copy(
                            error = "Attività interrotta dopo ${outcome.written.distinct().size} file modificati. " +
                                (checkpointId?.let { "Checkpoint $it disponibile: puoi ripristinarlo dalla chat." }
                                    ?: "Nessun checkpoint disponibile."),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                if (!producedOutput) refundQuota(consumed)
                throw cancelled
            } catch (error: Exception) {
                if (!producedOutput) refundQuota(consumed)
                _state.update { it.copy(error = error.message ?: "Errore imprevisto") }
            } finally {
                _state.update { it.copy(generating = false, status = null) }
                refreshTree()
            }
        }
    }

    fun stopGeneration() {
        cancelledByUser = true
        container.engine.stop()
        cancelTimeout()
        _state.update { it.copy(status = "Interruzione…") }
    }

    /** Executes the deletion the agent asked for, only after explicit consent. */
    fun confirmPendingDelete() {
        val path = _state.value.pendingDelete ?: return
        val project = _state.value.project ?: return
        viewModelScope.launch {
            val root = container.projectFiles.root(project.treeUri)
            if (root == null) {
                _state.update { it.copy(error = "Cartella non accessibile", pendingDelete = null) }
                return@launch
            }
            // The checkpoint must exist before the deletion, otherwise the file
            // could not be recovered from Git.
            val cp = checkpoint(project, "Prima di eliminare $path")
            if (cp == null) {
                val message = "Checkpoint non riuscito: eliminazione annullata. Il file «$path» è ancora presente."
                _state.update {
                    it.copy(
                        error = message,
                        steps = it.steps + AgentStep(
                            "Eliminazione non eseguita",
                            message,
                            AgentStepStatus.FAILED,
                        ),
                    )
                }
                return@launch
            }
            val ok = container.projectFiles.delete(root, path)
            _state.update {
                it.copy(
                    taskCheckpoint = cp,
                    pendingDelete = null,
                    steps = it.steps + AgentStep(
                        if (ok) "Eliminato $path" else "Eliminazione non riuscita: $path",
                        status = if (ok) AgentStepStatus.DONE else AgentStepStatus.FAILED,
                    ),
                    status = if (ok) "File eliminato" else null,
                    error = if (ok) null else "Eliminazione non riuscita",
                )
            }
            refreshTree()
        }
    }

    fun cancelPendingDelete() = _state.update {
        it.copy(
            pendingDelete = null,
            status = "Eliminazione annullata",
            steps = it.steps + AgentStep("Eliminazione annullata"),
        )
    }

    /**
     * Restores the files the last agent task touched. User edits made after the
     * task are detected and left untouched, and the restore is verified byte by
     * byte against the checkpoint.
     */
    fun rollbackTask() {
        val project = _state.value.project ?: return
        val target = _state.value.taskCheckpoint ?: return
        if (_state.value.generating) return
        val touched = _state.value.taskModifiedPaths.toSet()
        viewModelScope.launch {
            _state.update { it.copy(busy = true, status = "Ripristino del checkpoint…") }
            performRollback(project, target.id, touched, automatic = false)
        }
    }

    /**
     * Automatic rollback is opt-in and only runs when every safety condition
     * holds. A user edit detected after the task disables it for that task.
     */
    private suspend fun maybeAutoRollback(
        project: Project,
        outcome: AgentOutcome,
        report: VerificationReport?,
    ) {
        if (report == null || report.passed) return
        val settings = container.settings.current()
        if (!settings.codeAutoRollback) return
        val checkpointId = outcome.checkpointId ?: return
        val root = container.projectFiles.root(project.treeUri) ?: return
        val currentHashes = outcome.modified.mapNotNull { path ->
            container.projectFiles.readText(root, path)?.let { content ->
                path to RollbackGuard.fingerprint(content)
            }
        }.toMap()
        val userEdits = outcome.modified.filter { path ->
            val expected = _state.value.taskWrittenHashes[path]
            val now = currentHashes[path]
            expected != null && now != null && expected != now
        }
        if (!RollbackGuard.shouldAutoRollback(
                enabled = true,
                verificationFailed = true,
                writtenFiles = outcome.written.size,
                checkpointId = checkpointId,
                userEdits = userEdits,
            )
        ) {
            return
        }
        _state.update {
            it.copy(
                steps = it.steps + AgentStep(
                    "Verifica fallita: ripristino automatico",
                    "Nessuna modifica tua rilevata dopo l'attività; il checkpoint di sicurezza resta reversibile.",
                    AgentStepStatus.NEEDS_USER,
                ),
            )
        }
        performRollback(project, checkpointId, outcome.modified.toSet(), automatic = true)
    }

    private suspend fun performRollback(
        project: Project,
        checkpointId: String,
        touched: Set<String>,
        automatic: Boolean,
    ) {
        // A safety checkpoint makes the rollback itself reversible.
        val safety = checkpoint(project, "Prima del ripristino al checkpoint ${checkpointId.take(7)}")
        if (safety == null) {
            _state.update {
                it.copy(
                    busy = false,
                    status = null,
                    error = "Ripristino annullato: non è stato possibile creare il checkpoint di sicurezza. " +
                        "Il progetto non è stato modificato.",
                )
            }
            return
        }
        val root = container.projectFiles.root(project.treeUri)
        val committed = container.git.pathsAt(project.id, checkpointId)
        if (root == null || committed == null) {
            _state.update {
                it.copy(
                    busy = false,
                    status = null,
                    error = "Ripristino non riuscito: progetto o checkpoint non leggibili. " +
                        "Il progetto non è stato modificato.",
                )
            }
            return
        }

        val checkpointFiles = committed.filter { it in touched }.mapNotNull { path ->
            container.git.readFileAt(project.id, checkpointId, path)?.let { path to it }
        }.toMap()
        val currentContents = touched.mapNotNull { path ->
            container.projectFiles.readText(root, path)?.let { path to it }
        }.toMap()
        val plan = RollbackPlanner.plan(
            checkpoint = checkpointFiles,
            touched = touched,
            currentHashes = RollbackGuard.fingerprints(currentContents),
            expectedHashes = _state.value.taskWrittenHashes,
        )

        val restored = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for ((path, content) in plan.restore) {
            if (container.projectFiles.writeText(root, path, content)) {
                restored += path
            } else {
                failed += path
            }
        }
        for (path in plan.delete) {
            if (container.projectFiles.delete(root, path)) removed += path else failed += path
        }

        // A restore is complete only when the bytes on disk match the checkpoint.
        val restoredNow = plan.restore.keys.mapNotNull { path ->
            container.projectFiles.readText(root, path)?.let { path to it }
        }.toMap()
        val verification = RollbackGuard.verifyRestore(
            checkpointFiles.filterKeys { it in plan.restore.keys },
            restoredNow,
        )
        val summary = buildString {
            append(if (automatic) "Ripristino automatico: " else "Ripristino: ")
            append(restored.size).append(" file ripristinati")
            if (removed.isNotEmpty()) append(", ").append(removed.size).append(" creati dall'attività rimossi")
            if (plan.skipped.isNotEmpty()) {
                append(", ").append(plan.skipped.size).append(" modificati da te e lasciati intatti")
            }
            if (failed.isNotEmpty()) append(", ").append(failed.size).append(" non riusciti")
            append(". ").append(verification.message())
        }
        val complete = failed.isEmpty() && verification.complete
        val problems = (failed + verification.mismatched + verification.missing).distinct()
        _state.update {
            it.copy(
                busy = false,
                status = summary,
                error = if (complete) null else "Ripristino incompleto: ${problems.joinToString()}",
                dirty = false,
                taskCheckpoint = null,
                taskModifiedPaths = emptyList(),
                taskWrittenHashes = emptyMap(),
                restoreVerification = verification.message(),
                steps = it.steps + AgentStep(
                    if (complete) "Progetto ripristinato e verificato" else "Ripristino incompleto",
                    summary,
                    if (complete) AgentStepStatus.DONE else AgentStepStatus.FAILED,
                ),
            )
        }
        refreshTree()
        refreshGit()
        val openPath = _state.value.openPath
        if (openPath != null) {
            val text = container.projectFiles.readText(root, openPath)
            _state.update {
                it.copy(
                    editorText = text ?: "",
                    dirty = false,
                    diagnostics = text?.let { value -> StaticAnalyzers.analyze(openPath, value) }.orEmpty(),
                )
            }
        }
    }

    private suspend fun handleAgentEvent(projectId: Long, event: AgentEvent) {
        when (event) {
            is AgentEvent.Progress -> _state.update {
                it.copy(status = "Passo ${event.step} di ${event.max}…")
            }
            is AgentEvent.Step -> _state.update {
                it.copy(steps = it.steps + AgentStep(event.label, event.detail, event.status))
            }
            is AgentEvent.Assistant -> {
                if (event.text.isNotBlank()) {
                    container.projects.appendMessage(projectId, Role.ASSISTANT, event.text)
                    _state.update { it.copy(steps = it.steps + AgentStep("Risposta consegnata")) }
                }
            }
            is AgentEvent.Checkpoint -> _state.update { it.copy(taskCheckpoint = event.commit) }
            is AgentEvent.NeedsDeleteConfirmation -> _state.update { it.copy(pendingDelete = event.path) }
        }
    }

    // -------------------------------------------------------------- terminal

    fun onTerminalInputChange(value: String) = _state.update { it.copy(terminalInput = value) }

    fun runTerminalCommand() {
        val text = _state.value.terminalInput.trim()
        if (text.isEmpty() || _state.value.terminalRunning) return
        val project = _state.value.project ?: return
        _state.update {
            it.copy(
                terminalInput = "",
                terminalRunning = true,
                terminalLines = appendTerminal(it.terminalLines, TerminalLine("› $text", TerminalTone.COMMAND)),
            )
        }
        terminalHistoryIndex = -1
        terminalJob = viewModelScope.launch {
            try {
                val root = container.projectFiles.root(project.treeUri)
                if (root == null) {
                    _state.update {
                        it.copy(
                            terminalRunning = false,
                            terminalLines = appendTerminal(
                                it.terminalLines,
                                TerminalLine("Cartella del progetto non accessibile.", TerminalTone.ERROR),
                            ),
                        )
                    }
                    return@launch
                }
                val current = terminal ?: ProjectTerminal(
                    SafWorkspace(container.projectFiles, root),
                    terminalHooks(project, root),
                ).also { terminal = it }
                val result = current.execute(text)
                _state.update { state ->
                    state.copy(
                        terminalLines = appendTerminal(
                            if (result.clearScreen) emptyList() else state.terminalLines,
                            *result.lines.toTypedArray(),
                        ),
                        terminalCwd = result.cwd,
                        terminalHistory = current.history,
                        terminalRunning = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(terminalRunning = false) }
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        terminalRunning = false,
                        terminalLines = appendTerminal(
                            it.terminalLines,
                            TerminalLine(error.message ?: "Errore del terminale", TerminalTone.ERROR),
                        ),
                    )
                }
            }
        }
    }

    fun stopTerminal() {
        terminalJob?.cancel()
        terminalJob = null
        _state.update {
            it.copy(
                terminalRunning = false,
                terminalLines = appendTerminal(
                    it.terminalLines,
                    TerminalLine("Comando interrotto dall'utente.", TerminalTone.ERROR),
                ),
            )
        }
    }

    fun clearTerminal() = _state.update { it.copy(terminalLines = emptyList()) }

    /** Up (-1) and Down (+1) through the commands already executed. */
    fun terminalHistory(direction: Int) {
        val history = _state.value.terminalHistory
        if (history.isEmpty()) return
        val next = if (direction < 0) {
            if (terminalHistoryIndex <= 0) 0 else terminalHistoryIndex - 1
        } else {
            if (terminalHistoryIndex >= history.lastIndex) history.size else terminalHistoryIndex + 1
        }
        terminalHistoryIndex = next
        val value = if (next >= history.size) "" else history[next]
        _state.update { it.copy(terminalInput = value) }
    }

    fun setAutoRollback(value: Boolean) {
        viewModelScope.launch {
            container.settings.setCodeAutoRollback(value)
            _state.update { it.copy(autoRollback = value) }
        }
    }

    fun languageMatrix(): String = LanguageSupport.matrix()

    // ------------------------------------------------------------ verification

    fun runVerification() {
        val project = _state.value.project ?: return
        if (_state.value.verifying) return
        viewModelScope.launch {
            _state.update { it.copy(verifying = true, status = "Verifica del progetto…") }
            val root = container.projectFiles.root(project.treeUri)
            val snapshot = root?.let {
                container.projectFiles.snapshot(it, maxFiles = 200, maxBytesPerFile = 200_000)
            }.orEmpty()
            val report = withContext(Dispatchers.Default) {
                ProjectVerifier(container.sandbox).verify(snapshot)
            }
            _state.update { state ->
                state.copy(
                    verifying = false,
                    verification = report,
                    status = report.summary(),
                    sandbox = report.execution?.let { execution ->
                        SandboxResult(
                            success = execution.success,
                            logs = execution.logs,
                            errors = execution.errors,
                            durationMs = execution.durationMs,
                            timedOut = execution.timedOut,
                            blockedRequests = execution.blockedRequests,
                        )
                    } ?: state.sandbox,
                )
            }
        }
    }

    private fun terminalHooks(
        project: Project,
        root: androidx.documentfile.provider.DocumentFile,
    ): TerminalHooks = TerminalHooks(
        runtime = container.sandbox,
        allowNetwork = { project.networkAllowed || codeNetworkAllowed },
        verify = {
            val snapshot = container.projectFiles.snapshot(root, maxFiles = 200, maxBytesPerFile = 200_000)
            val report = withContext(Dispatchers.Default) {
                ProjectVerifier(container.sandbox).verify(snapshot)
            }
            _state.update { it.copy(verification = report) }
            report.toText()
        },
        git = { args -> gitCommand(project, args) },
    )

    private suspend fun gitCommand(project: Project, args: List<String>): String =
        when (args.firstOrNull() ?: "status") {
            "status" -> container.git.status(project.id)
                .joinToString("\n")
                .ifEmpty { "Nessuna modifica rispetto all'ultimo commit." }
            "log" -> container.git.log(project.id, 20)
                .joinToString("\n") { "${it.shortId} ${it.message}" }
                .ifEmpty { "Nessun commit." }
            "diff" -> {
                val head = container.git.head(project.id)
                if (head == null) "Nessun commit." else {
                    container.git.diff(project.id, head).ifBlank { "Nessuna differenza." }
                }
            }
            "branch" -> container.git.branches(project.id).joinToString("\n").ifEmpty { "Nessun branch." }
            else -> "Uso: git status | log | diff | branch"
        }

    private fun appendTerminal(lines: List<TerminalLine>, vararg additions: TerminalLine): List<TerminalLine> {
        val merged = lines + additions
        return if (merged.size <= MAX_TERMINAL_LINES) merged else merged.takeLast(MAX_TERMINAL_LINES)
    }

    private fun startTimeout() {
        cancelTimeout()
        timeoutJob = viewModelScope.launch {
            delay(TASK_TIMEOUT_MS)
            if (_state.value.generating) {
                timedOut = true
                container.engine.stop()
                _state.update {
                    it.copy(
                        error = "Il modello non ha risposto in tempo. Controlla l'activity log " +
                            "per le operazioni già eseguite.",
                    )
                }
            }
        }
    }

    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    private suspend fun refundQuota(quota: CodeQuota) {
        val refunded = quota.refund()
        container.settings.setCodeQuota(refunded.date, refunded.used)
        _state.update { it.copy(quota = refunded) }
    }

    private suspend fun appendAssistant(projectId: Long, text: String) {
        if (text.isBlank()) return
        container.projects.appendMessage(projectId, Role.ASSISTANT, text)
        _state.update { it.copy(steps = it.steps + AgentStep("Risposta consegnata")) }
    }

    /** A cancelled or timed-out answer is stored but clearly marked as interrupted. */
    private suspend fun appendInterrupted(projectId: Long, raw: String) {
        val visible = AnswerSanitizer.clean(raw)
            .substringBefore("<tool")
            .trim()
        val content = if (visible.isBlank()) {
            "Attività interrotta prima di produrre una risposta. Controlla l'activity log " +
                "per le operazioni eventualmente eseguite."
        } else {
            "$visible\n\n_(risposta interrotta)_"
        }
        container.projects.appendMessage(projectId, Role.ASSISTANT, content)
        _state.update {
            it.copy(steps = it.steps + AgentStep("Attività interrotta", status = AgentStepStatus.NEEDS_USER))
        }
    }

    private suspend fun checkpoint(project: Project, reason: String): GitCommitInfo? {
        val root = container.projectFiles.root(project.treeUri) ?: return null
        val files = container.projectFiles.snapshot(
            root,
            maxFiles = 600,
            maxBytesPerFile = CodeAgent.MAX_FILE_BYTES,
            maxTotalChars = 12_000_000,
        )
        // An empty project is still a valid baseline (the commit is allowed empty).
        val commit = runCatching { container.git.commit(project.id, files, reason) }.getOrNull()
            ?: return null
        _state.update {
            it.copy(steps = it.steps + AgentStep("Checkpoint ${commit.shortId}", reason))
        }
        return commit
    }

        private suspend fun projectContext(
        root: androidx.documentfile.provider.DocumentFile,
        request: String,
    ): String {
        val files = container.projectFiles.snapshot(
            root,
            maxFiles = 300,
            maxBytesPerFile = 32_000,
            maxTotalChars = 6_000_000,
        )
        return ProjectContextBuilder.build(files, request, CodeAgent.MAX_CONTEXT_CHARS)
    }

    private suspend fun updateModelLabels(settings: com.bigbai.core.model.AppSettings) {
        val installed = container.modelStore.installedAll(container.catalog)
        val candidates = installed.map { model ->
            CodeModelCandidate(
                id = model.variant.id,
                label = model.variant.displayName,
                isCode = model.variant.role == ModelRole.CODE,
                rank = model.variant.tier.ordinal,
            )
        }
        val choice = CodeModelResolver.resolve(
            installed = candidates,
            activeCodeModelId = settings.activeCodeModelId,
            activeChatModelId = settings.activeModelId,
        )
        val chat = installed.firstOrNull { it.variant.id == settings.activeModelId }
            ?: installed.firstOrNull { it.variant.role != ModelRole.CODE }
            ?: installed.firstOrNull()
        _state.update {
            it.copy(
                codeModelLabel = CodeModelResolver.label(choice),
                chatModelLabel = chat?.variant?.displayName,
            )
        }
    }

    /** Refreshes the editor only when the user has no unsaved local changes. */
    private suspend fun reloadOpenFileIfClean(
        project: Project,
        root: androidx.documentfile.provider.DocumentFile,
    ) {
        val path = _state.value.openPath ?: return
        if (_state.value.dirty) return
        val text = container.projectFiles.readText(root, path) ?: return
        _state.update {
            it.copy(
                editorText = text,
                diagnostics = StaticAnalyzers.analyze(path, text),
            )
        }
    }

    /**
     * Runs the real verification on the files the agent wrote: static analysis,
     * JSON/XML/CSV parsing and, for HTML/JavaScript, execution in the sandbox.
     * The returned report is the same one shown in the UI, never a summary made
     * up for the chat.
     */
    private suspend fun analyzeWritten(project: Project, paths: List<String>): VerificationReport? {
        val root = container.projectFiles.root(project.treeUri) ?: return null
        val collected = LinkedHashMap<String, List<Diagnostic>>()
        for (path in paths.distinct()) {
            val text = container.projectFiles.readText(root, path) ?: continue
            val diagnostics = StaticAnalyzers.analyze(path, text)
            collected[path] = diagnostics
            if (diagnostics.isNotEmpty()) {
                val errors = diagnostics.count { it.severity == DiagnosticSeverity.ERROR }
                _state.update {
                    it.copy(
                        diagnostics = diagnostics,
                        steps = it.steps + AgentStep(
                            "Controllo $path",
                            "${diagnostics.size} segnalazioni ($errors errori)",
                            if (errors > 0) AgentStepStatus.FAILED else AgentStepStatus.DONE,
                        ),
                    )
                }
            }
        }

        val writtenFiles = paths.distinct().mapNotNull { path ->
            container.projectFiles.readText(root, path)?.let { path to it }
        }.toMap()
        val report = ProjectVerifier(container.sandbox).verify(writtenFiles, VerifyOptions(maxFiles = 40))
        report.execution?.let { execution ->
            _state.update {
                it.copy(
                    sandbox = SandboxResult(
                        success = execution.success,
                        logs = execution.logs,
                        errors = execution.errors,
                        durationMs = execution.durationMs,
                        timedOut = execution.timedOut,
                        blockedRequests = execution.blockedRequests,
                    ),
                )
            }
        }

        val checks = report.checks.map { "${it.category.label}: ${it.name} — ${it.status.label}" }
        val changeReport = ProjectInsights.changeReport(paths.distinct(), collected, checks) +
            "\n\n" + report.toText()
        val snapshot = container.projectFiles.snapshot(root, maxFiles = 120, maxBytesPerFile = 120_000)
        val insights = ProjectInsights.analyze(snapshot)
        _state.update {
            it.copy(
                verification = report,
                changeReport = changeReport,
                insights = insights,
                projectSummary = ProjectInsights.summary(snapshot),
                steps = it.steps + AgentStep(
                    "Verifica dei file scritti",
                    report.summary(),
                    if (report.passed) AgentStepStatus.DONE else AgentStepStatus.FAILED,
                ),
            )
        }
        if (insights.isNotEmpty()) {
            _state.update {
                it.copy(
                    steps = it.steps + AgentStep(
                        "Analisi del progetto",
                        "${insights.size} segnalazioni: duplicati, dipendenze mancanti, TODO",
                    ),
                )
            }
        }
        return report
    }

    /** On-demand project analysis from the Tools tab. */
    fun runProjectAnalysis() {
        val project = _state.value.project ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, status = "Analisi del progetto…") }
            val root = container.projectFiles.root(project.treeUri)
            val snapshot = root?.let {
                container.projectFiles.snapshot(it, maxFiles = 150, maxBytesPerFile = 120_000)
            }.orEmpty()
            val insights = ProjectInsights.analyze(snapshot)
            _state.update {
                it.copy(
                    busy = false,
                    status = if (snapshot.isEmpty()) {
                        "Nessun file di testo da analizzare"
                    } else {
                        "Analisi completata: ${insights.size} segnalazioni su ${snapshot.size} file"
                    },
                    insights = insights,
                    projectSummary = ProjectInsights.summary(snapshot),
                )
            }
        }
    }

    /**
     * Asks the agent for tests on the open file. It is a normal task: it uses
     * the same quota and the same tools, and the model is told to write real
     * test files rather than describe them.
     */
    fun generateTests() {
        val path = _state.value.openPath ?: return
        if (_state.value.generating) return
        _state.update {
            it.copy(
                input = "Genera test automatici per il file $path. Crea i file di test necessari " +
                    "nello stesso progetto, con casi validi e casi limite, poi esegui i controlli disponibili " +
                    "e riporta cosa hai verificato e cosa no.",
            )
        }
        send()
    }

    fun applyProposals() {
        val project = _state.value.project ?: return
        val changes = _state.value.proposals
        if (changes.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = container.projects.applyChanges(project.id, project.treeUri, changes)
            for (change in changes) {
                val diff = _state.value.proposalDiffs[change.path].orEmpty()
                container.projects.recordChange(
                    projectId = project.id,
                    path = change.path,
                    changeType = if (change.delete) "delete" else "write",
                    diff = diff,
                    status = if (result.success) "APPLIED" else "FAILED",
                )
            }
            if (result.success) {
                commitSnapshot("Modifiche applicate: ${changes.size} file")
                container.projects.remember(
                    project.id,
                    ProjectMemoryKind.DECISION,
                    "Applicate ${changes.size} modifiche: ${changes.joinToString { it.path }}",
                )
            }
            _state.update {
                it.copy(
                    proposals = if (result.success) emptyList() else it.proposals,
                    proposalDiffs = if (result.success) emptyMap() else it.proposalDiffs,
                    busy = false,
                    status = result.message,
                    error = if (result.success) null else result.message,
                )
            }
            refreshTree()
            if (result.success) {
                _state.value.openPath?.let { path ->
                    val root = container.projectFiles.root(project.treeUri)
                    val text = root?.let { container.projectFiles.readText(it, path) }
                    if (text != null) {
                        _state.update {
                            it.copy(
                                editorText = text,
                                dirty = false,
                                diagnostics = StaticAnalyzers.analyze(path, text),
                            )
                        }
                    }
                }
            }
        }
    }

    fun discardProposals() {
        _state.update { it.copy(proposals = emptyList(), proposalDiffs = emptyMap(), status = null) }
    }

    fun runSandbox() {
        val project = _state.value.project ?: return
        val direct = _state.value.openPath?.takeIf {
            it.endsWith(".html", true) || it.endsWith(".htm", true)
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, status = "Esecuzione nella sandbox…") }
            val root = container.projectFiles.root(project.treeUri)
            val entry = "index.html"
            val path = direct ?: entry.takeIf {
                root != null && container.projectFiles.readText(root, it) != null
            }
            if (path == null || root == null) {
                _state.update {
                    it.copy(
                        busy = false,
                        status = null,
                        error = "La sandbox esegue solo HTML/JavaScript e non c'è un file HTML da avviare. " +
                            "Java, Kotlin e C++ richiedono una toolchain che Android non include: " +
                            "questi file restano soggetti alla sola analisi statica.",
                    )
                }
                return@launch
            }
            val html = container.projectFiles.readText(root, path)
            if (html == null) {
                _state.update {
                    it.copy(busy = false, status = null, error = "File HTML non leggibile ($path)")
                }
                return@launch
            }
            val allowNetwork = project.networkAllowed || container.settings.current().codeNetworkAllowed
            val result = container.sandbox.run(html, timeoutMs = 8000, allowNetwork = allowNetwork)
            _state.update { it.copy(busy = false, sandbox = result, status = null) }
        }
    }

    fun refreshGit() {
        val project = _state.value.project ?: return
        viewModelScope.launch {
            val log = container.git.log(project.id)
            val branches = container.git.branches(project.id)
            val branch = container.git.currentBranch(project.id)
            _state.update { it.copy(gitLog = log, branches = branches, currentBranch = branch) }
        }
    }

    fun commitSnapshot(message: String) {
        val project = _state.value.project ?: return
        viewModelScope.launch {
            val root = container.projectFiles.root(project.treeUri) ?: return@launch
            val files = container.projectFiles.snapshot(root)
            val commit = container.git.commit(project.id, files, message.ifBlank { "Snapshot del progetto" })
            _state.update {
                it.copy(status = if (commit != null) "Commit ${commit.shortId} creato" else "Commit non riuscito")
            }
            refreshGit()
        }
    }

    fun showCommitDiff(commit: GitCommitInfo) {
        val project = _state.value.project ?: return
        viewModelScope.launch {
            val diff = container.git.diff(project.id, commit.id)
            _state.update { it.copy(gitDiff = diff.ifBlank { "Nessuna differenza rispetto al commit precedente." }) }
        }
    }

    fun dismissDiff() = _state.update { it.copy(gitDiff = null) }

    fun restoreCommit(commit: GitCommitInfo) {
        val project = _state.value.project ?: return
        viewModelScope.launch {
            val root = container.projectFiles.root(project.treeUri) ?: return@launch
            val files = container.projectFiles.snapshot(root)
            // Reconstruct the snapshot of that commit into the project folder.
            val changes = mutableListOf<ProposedChange>()
            for (path in files.keys) {
                val content = container.git.readFileAt(project.id, commit.id, path)
                if (content != null) changes.add(ProposedChange(path, content, false))
            }
            val result = container.projects.applyChanges(project.id, project.treeUri, changes)
            _state.update {
                it.copy(
                    status = if (result.success) "Ripristinati ${changes.size} file dal commit ${commit.shortId}" else result.message,
                    error = if (result.success) null else result.message,
                )
            }
            refreshTree()
        }
    }

    fun createBranch(name: String) {
        val project = _state.value.project ?: return
        viewModelScope.launch {
            val ok = container.git.createBranch(project.id, name)
            _state.update {
                it.copy(
                    status = if (ok) "Branch «$name» creato" else null,
                    error = if (ok) null else "Nome branch non valido",
                )
            }
            refreshGit()
        }
    }

    fun checkoutBranch(name: String) {
        val project = _state.value.project ?: return
        viewModelScope.launch {
            val ok = container.git.checkout(project.id, name)
            _state.update {
                it.copy(
                    status = if (ok) "Branch attivo: $name" else null,
                    error = if (ok) null else "Cambio branch non riuscito",
                )
            }
            refreshGit()
        }
    }

    fun rememberFact(kind: ProjectMemoryKind, content: String) {
        val project = _state.value.project ?: return
        if (content.isBlank()) return
        viewModelScope.launch { container.projects.remember(project.id, kind, content) }
    }

    fun clearSandbox() = _state.update { it.copy(sandbox = null) }

    fun dismissStatus() = _state.update { it.copy(status = null) }

    private suspend fun ensureCodeModel(settings: com.bigbai.core.model.AppSettings): com.bigbai.llm.LoadedModel? {
        cancelledByUser = false
        timedOut = false
        val installed = container.modelStore.installedAll(container.catalog)
        val candidates = installed.map { model ->
            CodeModelCandidate(
                id = model.variant.id,
                label = model.variant.displayName,
                isCode = model.variant.role == ModelRole.CODE,
                rank = model.variant.tier.ordinal,
            )
        }
        val choice = CodeModelResolver.resolve(
            installed = candidates,
            activeCodeModelId = settings.activeCodeModelId,
            activeChatModelId = settings.activeModelId,
        )
        val chat = installed.firstOrNull { it.variant.id == settings.activeModelId }
            ?: installed.firstOrNull { it.variant.role != ModelRole.CODE }
            ?: installed.firstOrNull()
        _state.update {
            it.copy(
                codeModelLabel = CodeModelResolver.label(choice),
                chatModelLabel = chat?.variant?.displayName,
            )
        }
        if (choice !is CodeModelChoice.Selected) {
            val message = (choice as CodeModelChoice.Unavailable).message
            _state.update { it.copy(error = message) }
            return null
        }
        val chosen = installed.firstOrNull { it.variant.id == choice.candidate.id }
        if (chosen == null) {
            _state.update {
                it.copy(error = "Il modello selezionato non è più disponibile. Aprilo da Modelli e riprova.")
            }
            return null
        }

        val current = container.engine.current()
        if (current?.variant?.id == chosen.variant.id) return current

        val budget = container.deviceProfile.budgetBytes(settings.ramMode)
        val context = if (settings.contextOverride > 0) {
            settings.contextOverride
        } else {
            container.catalog.adaptiveContext(chosen.variant, container.deviceProfile, budget)
        }
        val threads = if (settings.threads > 0) {
            settings.threads
        } else {
            container.deviceProfile.recommendedThreads(settings.performanceMode)
        }
        _state.update { it.copy(status = "Caricamento di ${chosen.variant.family}…") }
        return container.engine.load(
            chosen.variant,
            chosen.modelPath,
            chosen.mmprojPath,
            context,
            threads,
            settings.batchSize,
            budget,
        ).getOrElse { error ->
            _state.update { it.copy(error = error.message ?: "Caricamento non riuscito") }
            null
        }
    }

    override fun onCleared() {
        // Closing the screen must release the native generation too, not only
        // cancel the coroutine that is waiting for it.
        cancelledByUser = true
        container.engine.stop()
        chatJob?.cancel()
        editorAnalysisJob?.cancel()
        cancelTimeout()
        super.onCleared()
    }

    private companion object {
        /** A local model that has not produced anything in four minutes is stuck. */
        const val TASK_TIMEOUT_MS = 240_000L
        /** Scrollback kept in memory: older lines are dropped, never swapped. */
        const val MAX_TERMINAL_LINES = 400
    }
}
