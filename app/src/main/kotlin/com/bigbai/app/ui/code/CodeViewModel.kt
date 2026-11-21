package com.bigbai.app.ui.code

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bigbai.app.AppContainer
import com.bigbai.app.files.FileExport
import com.bigbai.code.Diagnostic
import com.bigbai.code.FileNode
import com.bigbai.code.GitCommitInfo
import com.bigbai.code.JsSandbox
import com.bigbai.code.PatchEngine
import com.bigbai.code.ProposedChange
import com.bigbai.code.ProjectFiles
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
import com.bigbai.llm.ModelRole
import com.bigbai.llm.PromptMessage
import com.bigbai.llm.PromptTemplates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

class CodeViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(CodeUiState())
    val state: StateFlow<CodeUiState> = _state.asStateFlow()

    private var chatJob: Job? = null

    init {
        viewModelScope.launch {
            container.projects.observeProjects().collect { projects ->
                _state.update { it.copy(projects = projects) }
            }
        }
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

    /** Copies the open file to Download/BIG B AI with its own name. */
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
                        current.copy(status = "Esportato in Download/BIG B AI/$name", error = null)
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
            _state.update {
                it.copy(
                    input = "",
                    generating = true,
                    streamingText = "",
                    error = null,
                    status = "Preparazione…",
                    proposals = emptyList(),
                    proposalDiffs = emptyMap(),
                )
            }
            val builder = StringBuilder()
            try {
                val settings = container.settings.current()
                val loaded = ensureCodeModel(settings) ?: return@launch
                container.projects.appendMessage(project.id, Role.USER, text)
                val summary = container.projects.memorySummary(project.id)
                val system = PromptTemplates.codeSystemPrompt(
                    language = settings.language,
                    projectSummary = summary,
                    fileTree = container.projectFiles.treeText(_state.value.tree),
                    activeFile = _state.value.openPath?.let { path ->
                        "$path\n${_state.value.editorText.take(10_000)}"
                    },
                )
                val history = _state.value.messages.takeLast(10).map {
                    PromptMessage(if (it.role == Role.USER) "user" else "assistant", it.content)
                }
                val prompt = listOf(PromptMessage("system", system)) + history +
                    PromptMessage("user", text)

                _state.update { it.copy(status = "AI sta pensando…") }
                val result = container.engine.generate(
                    messages = prompt,
                    params = GenerationParams(
                        maxTokens = settings.maxTokens.coerceIn(256, 2048),
                        temperature = settings.temperature.coerceIn(0f, 1.2f),
                        topP = settings.topP,
                        topK = settings.topK,
                    ),
                ) { token ->
                    builder.append(token)
                    _state.update { it.copy(streamingText = AnswerSanitizer.clean(builder.toString())) }
                }

                val answer = AnswerSanitizer.clean(builder.toString()).trim()
                if (answer.isNotEmpty()) {
                    container.projects.appendMessage(project.id, Role.ASSISTANT, answer)
                }
                result.onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "Generazione non riuscita") }
                }

                val changes = PatchEngine.parse(answer)
                if (changes.isNotEmpty()) {
                    val root = container.projectFiles.root(project.treeUri)
                    val diffs = changes.associate { change ->
                        val old = root?.let { container.projectFiles.readText(it, change.path) }
                        change.path to PatchEngine.unifiedDiff(change.path, old, change.content)
                    }
                    _state.update {
                        it.copy(
                            proposals = changes,
                            proposalDiffs = diffs,
                            status = "${changes.size} modifiche proposte: controlla il diff e approva.",
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "Errore imprevisto") }
            } finally {
                _state.update { it.copy(generating = false, streamingText = "", status = null) }
            }
        }
    }

    fun stopGeneration() = container.engine.stop()

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
        super.onCleared()
    }
}
