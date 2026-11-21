package com.bigbai.app.ui.code

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bigbai.app.AppContainer
import com.bigbai.app.ui.AppViewModelFactory
import com.bigbai.app.ui.LocalAppDrawer
import com.bigbai.code.Diagnostic
import com.bigbai.code.DiagnosticSeverity
import com.bigbai.code.FileNode
import com.bigbai.code.GitCommitInfo
import com.bigbai.code.PatchEngine
import com.bigbai.core.model.Project
import com.bigbai.core.model.ProjectMemoryKind
import com.bigbai.core.model.Role
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeScreen(container: AppContainer, onBack: () -> Unit) {
    val factory = remember { AppViewModelFactory(container) }
    val viewModel: CodeViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.addProject(uri)
        }
    }

    if (state.project == null) {
        ProjectsHome(
            state = state,
            onBack = onBack,
            onCreate = viewModel::createProject,
            onAdd = { treePicker.launch(null) },
            onOpen = viewModel::openProject,
            onDelete = viewModel::removeProject,
        )
        return
    }

    ProjectWorkspace(
        state = state,
        viewModel = viewModel,
        onClose = {
            viewModel.closeProject()
            onBack()
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectsHome(
    state: CodeUiState,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    onDelete: (Project) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<Project?>(null) }
    val openDrawer = LocalAppDrawer.current

    pendingDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Rimuovere il progetto?") },
            text = {
                Text(
                    "Il progetto «${project.name}» verrà rimosso da BIG B Code. " +
                        "I file nella cartella originale non vengono toccati.",
                )
            },
            confirmButton = {
                TextButton(onClick = { onDelete(project); pendingDelete = null }) { Text("Rimuovi") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Annulla") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = openDrawer) {
                        Icon(Icons.Outlined.Menu, contentDescription = "Menu")
                    }
                },
                title = { Text("BIG B Code") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            "Ambiente di sviluppo locale",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Crea un progetto con un tocco: la cartella e il file index.html vengono " +
                                "creati automaticamente, senza percorsi da inventare.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "HTML: anteprima e test nella sandbox con rete bloccata.\n" +
                                "Java e C++: analisi statica reale; la compilazione sul dispositivo non è " +
                                "disponibile perché Android non include javac/clang.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            item {
                Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Nuovo progetto")
                }
            }
            item {
                OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Apri una cartella già esistente")
                }
            }
            item {
                Text("Progetti", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
            }
            if (state.projects.isEmpty()) {
                item {
                    Text(
                        "Nessun progetto. Usa «Nuovo progetto» per iniziare subito, oppure apri una " +
                            "cartella esistente: BIG B Code riceve solo il permesso su quella cartella.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.projects, key = { it.id }) { project ->
                Card(
                    modifier = Modifier.clickable { onOpen(project.id) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(project.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "${project.language.name} · ultimo accesso " +
                                    SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(project.lastOpenedAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { pendingDelete = project }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Rimuovi")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectWorkspace(
    state: CodeUiState,
    viewModel: CodeViewModel,
    onClose: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    val project = state.project ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Chiudi progetto")
                    }
                },
                title = {
                    Column {
                        Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = state.currentBranch?.let { "Git · $it" } ?: "Progetto locale",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refreshTree) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Aggiorna")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Chat") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("File") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Git") })
                Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Strumenti") })
            }
            state.error?.let { error ->
                Banner(error, true) { viewModel.clearError() }
            }
            state.status?.let { status ->
                Banner(status, false) { viewModel.dismissStatus() }
            }
            Box(modifier = Modifier.weight(1f)) {
                when (tab) {
                    0 -> CodeChatTab(state, viewModel)
                    1 -> FilesTab(state, viewModel)
                    2 -> GitTab(state, viewModel)
                    else -> ToolsTab(state, viewModel)
                }
            }
        }
    }

    state.gitDiff?.let { diff ->
        DiffDialog(title = "Differenze", diff = diff, onDismiss = viewModel::dismissDiff)
    }
    if (state.proposals.isNotEmpty()) {
        ApprovalSheet(state = state, viewModel = viewModel)
    }
}

@Composable
private fun CodeChatTab(state: CodeUiState, viewModel: CodeViewModel) {
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.messages.isEmpty() && !state.generating) {
                item {
                    Text(
                        "Chiedi di spiegare il codice, correggere un errore o proporre una modifica. " +
                            "Le modifiche non vengono mai applicate senza la tua approvazione.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.messages, key = { it.id }) { message ->
                val isUser = message.role == Role.USER
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                    ),
                ) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = if (!isUser && message.content.contains("<<<<<<<")) {
                                FontFamily.Monospace
                            } else {
                                FontFamily.Serif
                            },
                        ),
                        color = Color.Black,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            if (state.generating) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White,
                            contentColor = Color.Black,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            if (state.streamingText.isBlank()) {
                                Text("AI sta pensando…", style = MaterialTheme.typography.labelMedium, color = Color.Black)
                            } else {
                                Text(
                                    state.streamingText,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Serif),
                                    color = Color.Black,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (state.proposals.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${state.proposals.size} modifiche da approvare",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::applyProposals) { Text("Approva") }
                TextButton(onClick = viewModel::discardProposals) { Text("Ignora") }
            }
        }
        Surface(tonalElevation = 2.dp, shadowElevation = 8.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = viewModel::onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Chiedi qualcosa sul progetto…") },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { viewModel.send() }),
                )
                Spacer(Modifier.width(8.dp))
                if (state.generating) {
                    IconButton(
                        onClick = viewModel::stopGeneration,
                        modifier = Modifier
                            .size(48.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(50)),
                    ) {
                        Icon(
                            Icons.Outlined.Stop,
                            contentDescription = "Interrompi",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                } else {
                    IconButton(
                        onClick = viewModel::send,
                        enabled = state.input.isNotBlank(),
                        modifier = Modifier
                            .size(48.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
                    ) {
                        Icon(
                            Icons.Outlined.ArrowUpward,
                            contentDescription = "Invia",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilesTab(state: CodeUiState, viewModel: CodeViewModel) {
    var newFileDialog by remember { mutableStateOf(false) }
    var renameNode by remember { mutableStateOf<FileNode?>(null) }

    renameNode?.let { node ->
        var value by remember(node.path) { mutableStateOf(node.name) }
        AlertDialog(
            onDismissRequest = { renameNode = null },
            title = { Text("Rinomina file") },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(120) },
                    label = { Text("Nuovo nome") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameFile(node, value)
                        renameNode = null
                    },
                    enabled = value.isNotBlank(),
                ) { Text("Rinomina") }
            },
            dismissButton = { TextButton(onClick = { renameNode = null }) { Text("Annulla") } },
        )
    }

    if (newFileDialog) {
        var path by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFileDialog = false },
            title = { Text("Nuovo file") },
            text = {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("Percorso relativo (es. src/main.html)") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.createFile(path.trim()); newFileDialog = false },
                    enabled = path.isNotBlank(),
                ) { Text("Crea") }
            },
            dismissButton = { TextButton(onClick = { newFileDialog = false }) { Text("Annulla") } },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (state.openPath == null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "File del progetto",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { newFileDialog = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Nuovo")
                }
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (state.tree.isEmpty()) {
                    item {
                        Text(
                            "Nessun file di testo trovato nella cartella autorizzata.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(flatten(state.tree), key = { it.node.path }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (!entry.node.isDirectory) viewModel.openFile(entry.node) }
                            .padding(start = (12 + entry.depth * 14).dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (entry.node.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            entry.node.name,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (!entry.node.isDirectory) {
                            IconButton(onClick = { renameNode = entry.node }, modifier = Modifier.size(30.dp)) {
                                Icon(
                                    Icons.Outlined.DriveFileRenameOutline,
                                    contentDescription = "Rinomina",
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                            IconButton(onClick = { viewModel.deleteFile(entry.node) }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                }
            }
        } else {
            EditorPane(state, viewModel)
        }
    }
}

private data class TreeEntry(val node: FileNode, val depth: Int)

private fun flatten(nodes: List<FileNode>, depth: Int = 0): List<TreeEntry> =
    nodes.flatMap { node -> listOf(TreeEntry(node, depth)) + flatten(node.children, depth + 1) }

@Composable
private fun EditorPane(state: CodeUiState, viewModel: CodeViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.openPath ?: "",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (state.dirty) {
                Text("●", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 6.dp))
            }
            IconButton(onClick = viewModel::saveFile, enabled = state.dirty) {
                Icon(Icons.Outlined.Save, contentDescription = "Salva")
            }
            IconButton(onClick = viewModel::runSandbox) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = "Esegui nella sandbox")
            }
            IconButton(onClick = viewModel::exportCurrentFile) {
                Icon(Icons.Outlined.SaveAlt, contentDescription = "Esporta in Download")
            }
            IconButton(onClick = viewModel::shareCurrentFile) {
                Icon(Icons.Outlined.Share, contentDescription = "Condividi il file")
            }
        }
        OutlinedTextField(
            value = state.editorText,
            onValueChange = viewModel::updateEditor,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
        )
        DiagnosticsPane(state.diagnostics)
        if (state.sandbox != null) {
            SandboxPane(state, viewModel)
        }
    }
}

@Composable
private fun DiagnosticsPane(diagnostics: List<Diagnostic>) {
    if (diagnostics.isEmpty()) {
        Text(
            "Analisi statica: nessun problema rilevato.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text("Analisi statica (${diagnostics.size})", style = MaterialTheme.typography.labelMedium)
        diagnostics.forEach { diagnostic ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Icon(
                    imageVector = when (diagnostic.severity) {
                        DiagnosticSeverity.ERROR -> Icons.Outlined.ErrorOutline
                        DiagnosticSeverity.WARNING -> Icons.Outlined.WarningAmber
                        DiagnosticSeverity.INFO -> Icons.Outlined.Code
                    },
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = when (diagnostic.severity) {
                        DiagnosticSeverity.ERROR -> MaterialTheme.colorScheme.error
                        DiagnosticSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
                        DiagnosticSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "riga ${diagnostic.line}: ${diagnostic.message}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun SandboxPane(state: CodeUiState, viewModel: CodeViewModel) {
    val result = state.sandbox ?: return
    Card(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (result.success) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (result.timedOut) "Sandbox: timeout"
                    else if (result.success) "Sandbox: esecuzione completata"
                    else "Sandbox: errori rilevati",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = viewModel::clearSandbox) {
                    Icon(Icons.Outlined.Close, contentDescription = "Chiudi", modifier = Modifier.size(16.dp))
                }
            }
            Text(
                "${result.durationMs} ms · ${result.blockedRequests} richieste di rete bloccate",
                style = MaterialTheme.typography.labelSmall,
            )
            result.errors.forEach { error ->
                Text("• $error", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            result.logs.take(12).forEach { log ->
                Text(log, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun GitTab(state: CodeUiState, viewModel: CodeViewModel) {
    var message by remember { mutableStateOf("") }
    var branchName by remember { mutableStateOf("") }
    var showBranch by remember { mutableStateOf(false) }

    if (showBranch) {
        AlertDialog(
            onDismissRequest = { showBranch = false },
            title = { Text("Nuovo branch") },
            text = {
                OutlinedTextField(
                    value = branchName,
                    onValueChange = { branchName = it },
                    label = { Text("Nome branch") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.createBranch(branchName.trim()); showBranch = false },
                    enabled = branchName.isNotBlank(),
                ) { Text("Crea") }
            },
            dismissButton = { TextButton(onClick = { showBranch = false }) { Text("Annulla") } },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text(
            "Versionamento Git",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            "Il repository è privato dell'app e conserva snapshot dei file di testo del progetto. " +
                "Nessun dato viene inviato a server Git esterni.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            label = { Text("Messaggio del commit") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.commitSnapshot(message); message = "" }) {
                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Commit")
            }
            OutlinedButton(onClick = { showBranch = true }) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Branch")
            }
            IconButton(onClick = viewModel::refreshGit) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Aggiorna cronologia")
            }
        }
        if (state.branches.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.branches.forEach { branch ->
                    OutlinedButton(
                        onClick = { viewModel.checkoutBranch(branch) },
                        enabled = branch != state.currentBranch,
                    ) { Text(branch, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Cronologia", style = MaterialTheme.typography.labelMedium)
        if (state.gitLog.isEmpty()) {
            Text(
                "Nessun commit. Crea il primo snapshot per iniziare a versionare il progetto.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.gitLog.forEach { commit ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(commit.message, style = MaterialTheme.typography.labelMedium)
                        Text(
                            "${commit.shortId} · ${commit.author} · " +
                                SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(commit.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { viewModel.showCommitDiff(commit) }) { Text("Diff") }
                    TextButton(onClick = { viewModel.restoreCommit(commit) }) { Text("Ripristina") }
                }
            }
        }
    }
}

@Composable
private fun ToolsTab(state: CodeUiState, viewModel: CodeViewModel) {
    var memoryText by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ProjectMemoryKind.NOTE) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text("Sandbox", style = MaterialTheme.typography.titleSmall)
        Text(
            "Esegue il file HTML aperto (o index.html) in una WebView isolata: rete bloccata, " +
                "accesso ai file disabilitato, timeout di 8 secondi.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Button(onClick = viewModel::runSandbox) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Esegui nella sandbox")
        }
        if (state.sandbox != null) {
            Spacer(Modifier.height(6.dp))
            SandboxPane(state, viewModel)
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))

        Text("Memoria del progetto", style = MaterialTheme.typography.titleSmall)
        Text(
            "Separata dalla memoria della chat. Ricorda obiettivi, decisioni, errori e stato dei lavori.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ProjectMemoryKind.entries.forEach { option ->
                OutlinedButton(
                    onClick = { kind = option },
                    enabled = option != kind,
                ) { Text(option.name.lowercase(), style = MaterialTheme.typography.labelSmall) }
            }
        }
        OutlinedTextField(
            value = memoryText,
            onValueChange = { memoryText = it },
            label = { Text("Nuova nota (${kind.name.lowercase()})") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = { viewModel.rememberFact(kind, memoryText); memoryText = "" },
            enabled = memoryText.isNotBlank(),
        ) { Text("Salva nella memoria del progetto") }

        Spacer(Modifier.height(10.dp))
        state.memory.forEach { item ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(item.kind.name.lowercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(item.content, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))
        Text("Note sui linguaggi", style = MaterialTheme.typography.titleSmall)
        Text(
            "HTML/CSS/JS: esecuzione reale in sandbox.\n" +
                "Java: analisi statica (bilanciamento, nome classe, catch vuoti). Nessun compilatore sul dispositivo.\n" +
                "C++: analisi statica (bilanciamento, include guard, uso di namespace). Nessun compilatore sul dispositivo.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ApprovalSheet(state: CodeUiState, viewModel: CodeViewModel) {
    var diffFor by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = viewModel::discardProposals,
        title = { Text("Approvare le modifiche?") },
        text = {
            Column {
                Text(
                    "Nessun file è stato modificato. Controlla il diff prima di applicare.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                state.proposals.forEach { change ->
                    val diff = state.proposalDiffs[change.path].orEmpty()
                    val stats = PatchEngine.stats(diff)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { diffFor = diff }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            change.path,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "+${stats.first} −${stats.second}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::applyProposals) { Text("Applica") }
        },
        dismissButton = { TextButton(onClick = viewModel::discardProposals) { Text("Annulla") } },
    )
    diffFor?.let { diff ->
        DiffDialog(title = "Diff proposto", diff = diff, onDismiss = { diffFor = null })
    }
}

@Composable
private fun DiffDialog(title: String, diff: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                text = diff,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                modifier = Modifier
                    .height(380.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
    )
}

@Composable
private fun Banner(message: String, error: Boolean, onDismiss: () -> Unit) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(14.dp))
            }
        }
    }
}
