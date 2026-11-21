package com.bigbai.app.ui.code

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bigbai.app.AppContainer
import com.bigbai.app.files.FileExport
import com.bigbai.code.AgentAction
import com.bigbai.code.AgentStep
import com.bigbai.code.AgentStepStatus
import com.bigbai.code.CodeAgent
import com.bigbai.code.CodeQuota
import com.bigbai.code.Diagnostic
import com.bigbai.code.FileNode
import com.bigbai.code.GitCommitInfo
import com.bigbai.code.JsSandbox
import com.bigbai.code.PatchEngine
import com.bigbai.code.ProposedChange
import com.bigbai.code.ProjectFiles
import com.bigbai.code.ProjectInsight
import com.bigbai.code.ProjectInsights
import com.bigbai.code.ProjectNaming
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
    /** Path waiting for an explicit confirmation before deletion. */
    val pendingDelete: String? = null,
    val codeModelLabel: String? = null,
    val chatModelLabel: String? = null,
    /** Project-wide findings: duplicated blocks, undeclared imports, TODO. */
    val insights: List<ProjectInsight> = emptyList(),
    /** Human-readable project shape, produced by the last analysis. */
    val projectSummary: String? = null,
    /** Report of the last agent task: files written, checks, static errors. */
    val changeReport: String? = null,
)

class CodeViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(CodeUiState())
    val state: StateFlow<CodeUiState> = _state.asStateFlow()

    private var chatJob: Job? = null
    private var timeoutJob: Job? = null
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
                    insights = emptyList(),
                    projectSummary = null,
                    changeReport = null,
                )
            }
            container.projects.touch(id)
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
            it.copy(project = null, tree = emptyList(), openPath = null, editorText = "", diagnostics = emptyList())
        }
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
        _state.update {
            it.copy(
                editorText = text,
                dirty = true,
                diagnostics = it.openPath?.let { path -> StaticAnalyzers.analyze(path, text) } ?: emptyList(),
            )
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

    fun deleteFile(node: FileNode) {
        val project = _state.value.project ?: return
        viewModelScope.launch {
            val root = container.projectFiles.root(project.treeUri) ?: return@launch
            val ok = container.projectFiles.delete(root, node.path)
            if (ok && _state.value.openPath == node.path) {
                _state.update { it.copy(openPath = null, editorText = "", diagnostics = emptyList()) }
            }
            _state.update {
                it.copy(
                    status = if (ok) "Elemento eliminato" else null,
                    error = if (ok) null else "Eliminazione non riuscita",
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
                    proposals = emptyList(),
                    proposalDiffs = emptyMap(),
                    status = "Analisi della richiesta…",
                )
            }

            var producedOutput = false
            var checkpointed = false
            try {
                val settings = container.settings.current()
                updateModelLabels(settings)
                val loaded = ensureCodeModel(settings)
                if (loaded == null) {
                    refundQuota(consumed)
                    return@launch
                }
                updateModelLabels(settings)
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

                var stepIndex = 1
                var finished = false
                var toolTranscript = ""
                val written = mutableListOf<String>()

                while (stepIndex <= CodeAgent.MAX_STEPS && !finished) {
                    if (cancelledByUser || timedOut) break
                    _state.update { it.copy(status = "Passo $stepIndex di ${CodeAgent.MAX_STEPS}…") }
                    val system = CodeAgent.systemPrompt(
                        language = settings.language,
                        projectName = project.name,
                        tree = container.projectFiles.treeText(_state.value.tree),
                        projectContext = projectContext(root),
                        step = stepIndex,
                        maxSteps = CodeAgent.MAX_STEPS,
                    )
                    val history = priorMessages.map {
                        PromptMessage(if (it.role == Role.USER) "user" else "assistant", it.content)
                    }
                    val userTurn = if (toolTranscript.isBlank()) {
                        text
                    } else {
                        "$text\n\nRISULTATI REALI DEGLI STRUMENTI (usali, non inventare altro):\n$toolTranscript"
                    }
                    val prompt = listOf(PromptMessage("system", system)) + history +
                        PromptMessage("user", userTurn)

                    val builder = StringBuilder()
                    startTimeout()
                    val result = container.engine.generate(prompt, params) { token -> builder.append(token) }
                    cancelTimeout()
                    val answer = AnswerSanitizer.clean(builder.toString()).trim()
                    result.getOrNull()?.let { metrics ->
                        if (metrics.completionTokens > 0 || metrics.promptTokens > 0) {
                            container.lastMetrics.value = metrics
                        }
                    }

                    if (cancelledByUser || timedOut) {
                        appendInterrupted(project.id, builder.toString())
                        break
                    }
                    if (answer.isBlank()) {
                        result.exceptionOrNull()?.let { error ->
                            _state.update { it.copy(error = error.message ?: "Generazione non riuscita") }
                        }
                        break
                    }
                    producedOutput = true
                    val quality = ResponseQuality.inspect(answer)
                    if (!quality.acceptable && quality.repetitionRatio > 0.45) {
                        _state.update {
                            it.copy(
                                steps = it.steps + AgentStep(
                                    "Risposta ripetitiva scartata",
                                    "Il modello ha iniziato a ripetersi: nessuna modifica applicata.",
                                    AgentStepStatus.FAILED,
                                ),
                            )
                        }
                        break
                    }

                    val actions = CodeAgent.parse(answer)
                    if (actions.isEmpty()) {
                        appendAssistant(project.id, answer)
                        finished = true
                        break
                    }

                    val transcript = StringBuilder()
                    var needsConfirmation = false
                    for (action in actions) {
                        when (action) {
                            is AgentAction.WriteFile -> {
                                if (!checkpointed) {
                                    checkpointed = true
                                    checkpoint(project, "Prima dell'attività AI")
                                }
                                val ok = container.projectFiles.writeText(root, action.path, action.content)
                                if (ok) {
                                    written += action.path
                                    transcript.append("OK: scritto ").append(action.path).append('\n')
                                    _state.update {
                                        it.copy(
                                            steps = it.steps + AgentStep(
                                                "Creato ${action.path}",
                                                "${action.content.length} caratteri",
                                            ),
                                        )
                                    }
                                } else {
                                    transcript.append("ERRORE: non è stato possibile scrivere ")
                                        .append(action.path).append('\n')
                                    _state.update {
                                        it.copy(
                                            steps = it.steps + AgentStep(
                                                "Errore su ${action.path}",
                                                "Scrittura non riuscita",
                                                AgentStepStatus.FAILED,
                                            ),
                                        )
                                    }
                                }
                            }
                            is AgentAction.ReadFile -> {
                                val content = container.projectFiles.readText(root, action.path)
                                if (content == null) {
                                    transcript.append("ERRORE: ").append(action.path)
                                        .append(" non esiste o non è leggibile\n")
                                } else {
                                    transcript.append("FILE ").append(action.path).append('\n')
                                        .append(content.take(CodeAgent.MAX_READ_CHARS)).append('\n')
                                    _state.update {
                                        it.copy(steps = it.steps + AgentStep("Letto ${action.path}"))
                                    }
                                }
                            }
                            AgentAction.ListFiles -> {
                                val nodes = container.projectFiles.list(root)
                                transcript.append("ALBERO\n")
                                    .append(container.projectFiles.treeText(nodes)).append('\n')
                                _state.update { it.copy(tree = nodes) }
                            }
                            is AgentAction.DeleteFile -> {
                                _state.update {
                                    it.copy(
                                        pendingDelete = action.path,
                                        steps = it.steps + AgentStep(
                                            "Eliminazione da confermare: ${action.path}",
                                            "Operazione distruttiva: serve il tuo consenso.",
                                            AgentStepStatus.NEEDS_USER,
                                        ),
                                    )
                                }
                                needsConfirmation = true
                            }
                            is AgentAction.Finish -> {
                                appendAssistant(project.id, action.summary)
                                finished = true
                            }
                            is AgentAction.Invalid -> {
                                transcript.append("ERRORE: ").append(action.reason).append('\n')
                                _state.update {
                                    it.copy(
                                        steps = it.steps + AgentStep(
                                            "Strumento non valido",
                                            action.reason,
                                            AgentStepStatus.FAILED,
                                        ),
                                    )
                                }
                            }
                        }
                        if (finished || needsConfirmation) break
                    }

                    if (finished || needsConfirmation) break
                    if (transcript.isNotEmpty()) toolTranscript = transcript.toString()
                    stepIndex += 1
                }

                // A timeout is a technical failure: if nothing was written, the
                // request is refunded instead of being counted as used.
                if (timedOut && written.isEmpty()) refundQuota(consumed)
                if (!finished && !cancelledByUser && !timedOut && stepIndex > CodeAgent.MAX_STEPS) {
                    appendAssistant(
                        project.id,
                        "Ho raggiunto il limite di ${CodeAgent.MAX_STEPS} passi per questa attività. " +
                            "Scrivi «continua» per proseguire da dove sono arrivato.",
                    )
                }
                if (written.isNotEmpty()) {
                    analyzeWritten(project, written)
                }
            } catch (cancelled: CancellationException) {
                if (!producedOutput) refundQuota(consumed)
                throw cancelled
            } catch (error: Exception) {
                if (!producedOutput) refundQuota(consumed)
                _state.update { it.copy(error = error.message ?: "Errore imprevisto") }
            } finally {
                cancelTimeout()
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
            val ok = container.projectFiles.delete(root, path)
            if (ok) checkpoint(project, "Eliminazione di $path")
            _state.update {
                it.copy(
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

    private suspend fun checkpoint(project: Project, reason: String) {
        val root = container.projectFiles.root(project.treeUri) ?: return
        val files = container.projectFiles.snapshot(root, maxFiles = 200)
        if (files.isEmpty()) return
        val commit = container.git.commit(project.id, files, reason)
        if (commit != null) {
            _state.update {
                it.copy(steps = it.steps + AgentStep("Checkpoint ${commit.shortId}", reason))
            }
        }
    }

    private suspend fun projectContext(root: androidx.documentfile.provider.DocumentFile): String {
        val files = container.projectFiles.snapshot(root, maxFiles = 24, maxBytesPerFile = 24_000)
        val builder = StringBuilder()
        for ((path, content) in files) {
            if (builder.length > CodeAgent.MAX_CONTEXT_CHARS) break
            builder.append("FILE ").append(path).append('\n')
            builder.append(content.take(6_000)).append("\n\n")
        }
        return builder.toString().take(CodeAgent.MAX_CONTEXT_CHARS)
    }

    private suspend fun updateModelLabels(settings: com.bigbai.core.model.AppSettings) {
        val installed = container.modelStore.installedAll(container.catalog)
        val chat = installed.firstOrNull { it.variant.id == settings.activeModelId }
            ?: installed.firstOrNull()
        val code = installed.firstOrNull { it.variant.id == settings.activeCodeModelId }
            ?: installed.firstOrNull { it.variant.role == ModelRole.CODE }
            ?: chat
        _state.update {
            it.copy(
                codeModelLabel = code?.variant?.displayName?.let { name ->
                    if (settings.activeCodeModelId == null) "$name (automatico)" else name
                },
                chatModelLabel = chat?.variant?.displayName,
            )
        }
    }

    /** Runs the static analyzers on the files the agent wrote and reports them. */
    private suspend fun analyzeWritten(project: Project, paths: List<String>) {
        val root = container.projectFiles.root(project.treeUri) ?: return
        var problems = 0
        val collected = LinkedHashMap<String, List<Diagnostic>>()
        for (path in paths.distinct()) {
            val text = container.projectFiles.readText(root, path) ?: continue
            val diagnostics = StaticAnalyzers.analyze(path, text)
            collected[path] = diagnostics
            problems += diagnostics.count { it.severity == com.bigbai.code.DiagnosticSeverity.ERROR }
            if (diagnostics.isNotEmpty()) {
                _state.update {
                    it.copy(
                        diagnostics = diagnostics,
                        steps = it.steps + AgentStep(
                            "Controllo $path",
                            "${diagnostics.size} segnalazioni (${diagnostics.count { d -> d.severity == com.bigbai.code.DiagnosticSeverity.ERROR }} errori)",
                            if (diagnostics.any { d -> d.severity == com.bigbai.code.DiagnosticSeverity.ERROR }) {
                                AgentStepStatus.FAILED
                            } else {
                                AgentStepStatus.DONE
                            },
                        ),
                    )
                }
            }
        }
        if (problems == 0 && paths.isNotEmpty()) {
            _state.update {
                it.copy(steps = it.steps + AgentStep("Controlli completati", "Nessun errore statico rilevato"))
            }
        }

        // Real execution where the platform allows it: an HTML entry point is
        // run in the isolated WebView so the agent cannot claim it works without
        // the page having actually loaded.
        val htmlPath = paths.firstOrNull { it.endsWith(".html", true) || it.endsWith(".htm", true) }
            ?: "index.html".takeIf { container.projectFiles.readText(root, it) != null }
        val checks = mutableListOf("Analisi statica dei file scritti")
        if (htmlPath != null) {
            val html = container.projectFiles.readText(root, htmlPath)
            if (html != null) {
                val allowNetwork = project.networkAllowed || container.settings.current().codeNetworkAllowed
                val sandbox = container.sandbox.run(html, timeoutMs = 8000, allowNetwork = allowNetwork)
                checks.add("Esecuzione in sandbox di $htmlPath")
                _state.update {
                    it.copy(
                        sandbox = sandbox,
                        steps = it.steps + AgentStep(
                            if (sandbox.success) "Sandbox: $htmlPath eseguito" else "Sandbox: errori in $htmlPath",
                            "${sandbox.errors.size} errori · ${sandbox.blockedRequests} richieste di rete bloccate",
                            if (sandbox.success) AgentStepStatus.DONE else AgentStepStatus.FAILED,
                        ),
                    )
                }
            }
        }

        val report = ProjectInsights.changeReport(paths, collected, checks)
        val snapshot = container.projectFiles.snapshot(root, maxFiles = 120, maxBytesPerFile = 120_000)
        val insights = ProjectInsights.analyze(snapshot)
        _state.update {
            it.copy(
                changeReport = report,
                insights = insights,
                projectSummary = ProjectInsights.summary(snapshot),
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
        val path = _state.value.openPath?.takeIf { it.endsWith(".html", true) || it.endsWith(".htm", true) }
            ?: "index.html"
        viewModelScope.launch {
            _state.update { it.copy(busy = true, status = "Esecuzione nella sandbox…") }
            val root = container.projectFiles.root(project.treeUri)
            val html = root?.let { container.projectFiles.readText(it, path) }
            if (html == null) {
                _state.update {
                    it.copy(busy = false, status = null, error = "Nessun file HTML da eseguire ($path)")
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
        if (installed.isEmpty()) {
            _state.update { it.copy(error = "Nessun modello installato. Apri Modelli per scaricarne uno.") }
            return null
        }
        val preferredId = settings.activeCodeModelId ?: settings.activeModelId
        val chosen = preferredId?.let { id -> installed.firstOrNull { it.variant.id == id } }
            ?: installed.filter { it.variant.role == ModelRole.CODE }.maxByOrNull { it.variant.tier.ordinal }
            ?: installed.first()

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
        chatJob?.cancel()
        cancelTimeout()
        super.onCleared()
    }

    private companion object {
        /** A local model that has not produced anything in four minutes is stuck. */
        const val TASK_TIMEOUT_MS = 240_000L
    }
}
