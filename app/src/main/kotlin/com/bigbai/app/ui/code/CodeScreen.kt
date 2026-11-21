package com.bigbai.app.ui.code

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bigbai.app.AppContainer
import com.bigbai.app.ui.AppViewModelFactory
import com.bigbai.app.ui.LocalAppDrawer
import com.bigbai.app.ui.chat.ThinkingIndicator
import com.bigbai.code.AgentStep
import com.bigbai.code.AgentStepStatus
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
                    "Il progetto «${project.name}» verrà rimosso da ITAGPT Code. " +
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
                title = { Text("ITAGPT Code") },
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
                            "Scrivi cosa vuoi creare: ITAGPT Code crea i file, scrive il codice, " +
                                "controlla il risultato e corregge da solo. Non devi inventare nomi o percorsi.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Oggi: ${state.quota.remaining} richieste su ${state.quota.limit}. " +
                                "Una richiesta = una attività; i passaggi interni sono inclusi.",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
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
                            "cartella esistente: ITAGPT Code riceve solo il permesso su quella cartella.",
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
                            text = buildString {
                                append(state.codeModelLabel ?: "Modello da scegliere")
                                append(" · ").append(state.quota.remaining).append("/")
                                append(state.quota.limit).append(" oggi")
                                state.currentBranch?.let { append(" · Git ").append(it) }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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

    state.pendingDelete?.let { path ->
        AlertDialog(
            onDismissRequest = viewModel::cancelPendingDelete,
            title = { Text("Eliminare «$path»?") },
            text = {
                Text(
                    "L'AI ha chiesto di eliminare questo file. È un'operazione distruttiva: " +
                        "prima viene creato un checkpoint, poi il file viene rimosso dal progetto.",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmPendingDelete) { Text("Elimina") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelPendingDelete) { Text("Annulla") }
            },
        )
    }

    state.pendingManualDelete?.let { node ->
        AlertDialog(
            onDismissRequest = viewModel::cancelManualDelete,
            title = {
                Text(if (node.isDirectory) "Eliminare la cartella «${node.name}»?" else "Eliminare il file «${node.name}»?")
            },
            text = {
                Text(
                    if (node.isDirectory) {
                        "La cartella e tutto il suo contenuto verranno rimossi dal progetto. " +
                            "L'operazione non può essere annullata dall'esplora file."
                    } else {
                        "Il file verrà rimosso dal progetto. L'operazione non può essere annullata " +
                            "dall'esplora file."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmManualDelete) {
                    Text(if (node.isDirectory) "Elimina cartella" else "Elimina file")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelManualDelete) { Text("Annulla") }
            },
        )
    }
}

@Composable
private fun CodeChatTab(state: CodeUiState, viewModel: CodeViewModel) {
    var confirmRollback by remember { mutableStateOf(false) }
    if (confirmRollback) {
        AlertDialog(
            onDismissRequest = { confirmRollback = false },
            title = { Text("Ripristinare lo stato precedente?") },
            text = {
                Text(
                    "I file toccati dall'ultima attività verranno riportati al checkpoint e quelli " +
                        "creati dall'attività verranno rimossi. Prima del ripristino viene creato un " +
                        "checkpoint di sicurezza, quindi l'operazione resta recuperabile.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmRollback = false; viewModel.rollbackTask() }) {
                    Text("Ripristina")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRollback = false }) { Text("Annulla") }
            },
        )
    }
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.messages.isEmpty() && !state.generating) {
                item {
                    Column {
                        Text(
                            "Descrivi l'obiettivo, non i file: «Crea un'app per gestire le attività». " +
                                "ITAGPT Code pianifica, crea i file, scrive il codice e controlla il risultato. " +
                                "Le eliminazioni richiedono sempre una conferma.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            AssistChip(
                                onClick = {
                                    viewModel.onInputChange(
                                        "Rivedi il codice del progetto: cerca bug, errori di gestione e problemi " +
                                            "di sicurezza, poi correggi ciò che trovi e riporta le verifiche fatte.",
                                    )
                                },
                                label = { Text("Rivedi il codice") },
                            )
                            AssistChip(
                                onClick = {
                                    viewModel.onInputChange(
                                        "Spiega la struttura del progetto: file principali, responsabilità e flusso " +
                                            "dei dati. Non modificare nulla.",
                                    )
                                },
                                label = { Text("Struttura") },
                            )
                            AssistChip(
                                onClick = viewModel::runProjectAnalysis,
                                label = { Text("Analisi progetto") },
                            )
                        }
                    }
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
                                com.bigbai.app.ui.theme.ItaGptSerif
                            },
                        ),
                        color = Color.Black,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            if (state.steps.isNotEmpty()) {
                item(key = "steps") {
                    AgentStepsPanel(
                        steps = state.steps,
                        canRollback = state.taskCheckpoint != null && !state.generating,
                        onRollback = { confirmRollback = true },
                    )
                }
            }
            if (state.generating) {
                item(key = "thinking") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White,
                            contentColor = Color.Black,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            ThinkingIndicator()
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "ITAGPT Code sta lavorando in autonomia…",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF4A4A4A),
                            )
                        }
                    }
                }
            }
        }
        if (state.quota.exhausted) {
            Text(
                "Hai usato le ${state.quota.limit} richieste di oggi. Il contatore si azzera domani; " +
                    "puoi continuare a modificare i file a mano.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
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
                        enabled = state.input.isNotBlank() && !state.quota.exhausted,
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
                        }
                        IconButton(onClick = { viewModel.deleteFile(entry.node) }, modifier = Modifier.size(30.dp)) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = if (entry.node.isDirectory) {
                                    "Elimina cartella ${entry.node.name}"
                                } else {
                                    "Elimina file ${entry.node.name}"
                                },
                                modifier = Modifier.size(15.dp),
                            )
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
    val path = state.openPath ?: return
    var value by remember(path) { mutableStateOf(TextFieldValue(state.editorText, TextRange(0))) }
    var undoStack by remember(path) { mutableStateOf<List<String>>(emptyList()) }
    var redoStack by remember(path) { mutableStateOf<List<String>>(emptyList()) }
    var lastEditAt by remember(path) { mutableStateOf(0L) }
    var showFind by remember(path) { mutableStateOf(false) }
    var query by remember(path) { mutableStateOf("") }
    var replacement by remember(path) { mutableStateOf("") }
    val findFocus = remember { FocusRequester() }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val editorStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val keywordColor = MaterialTheme.colorScheme.tertiary
    val stringColor = MaterialTheme.colorScheme.primary
    val commentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val numberColor = MaterialTheme.colorScheme.secondary

    fun commit(newText: String, selection: TextRange = TextRange(newText.length)) {
        if (newText == value.text) {
            value = value.copy(selection = selection)
            return
        }
        val now = System.currentTimeMillis()
        val bigJump = kotlin.math.abs(newText.length - value.text.length) > 1
        if (now - lastEditAt > 800L || bigJump) {
            undoStack = (undoStack + value.text).takeLast(60)
        }
        lastEditAt = now
        redoStack = emptyList()
        value = TextFieldValue(newText, selection)
        viewModel.updateEditor(newText)
    }

    fun undo() {
        val previous = undoStack.lastOrNull() ?: return
        undoStack = undoStack.dropLast(1)
        redoStack = (redoStack + value.text).takeLast(60)
        value = TextFieldValue(previous, TextRange(previous.length))
        viewModel.updateEditor(previous)
    }

    fun redo() {
        val next = redoStack.lastOrNull() ?: return
        redoStack = redoStack.dropLast(1)
        undoStack = (undoStack + value.text).takeLast(60)
        value = TextFieldValue(next, TextRange(next.length))
        viewModel.updateEditor(next)
    }

    fun findNext() {
        if (query.isBlank()) return
        val text = value.text
        val start = value.selection.end.coerceIn(0, text.length)
        var index = text.indexOf(query, start, ignoreCase = true)
        if (index < 0) index = text.indexOf(query, ignoreCase = true)
        if (index >= 0) {
            value = value.copy(selection = TextRange(index, index + query.length))
        }
    }

    fun replaceCurrent() {
        val selection = value.selection
        val selected = if (selection.length > 0) value.text.substring(selection.start, selection.end) else ""
        if (query.isNotBlank() && selected.equals(query, ignoreCase = true)) {
            val newText = value.text.replaceRange(selection.start, selection.end, replacement)
            commit(newText, TextRange(selection.start, selection.start + replacement.length))
        } else {
            findNext()
        }
    }

    fun replaceAll() {
        if (query.isBlank()) return
        val newText = value.text.replace(query, replacement, ignoreCase = true)
        if (newText != value.text) commit(newText)
    }

    // Text can change from the agent, a restore or another tab: keep the local
    // editor in sync without resetting the caret on every normal keystroke.
    LaunchedEffect(state.editorText) {
        if (state.editorText != value.text) {
            value = TextFieldValue(state.editorText, TextRange(state.editorText.length))
        }
    }
    LaunchedEffect(showFind) {
        if (showFind) runCatching { findFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (!event.isCtrlPressed && !event.isMetaPressed) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.S -> { viewModel.saveFile(); true }
                    Key.F -> { showFind = true; true }
                    Key.Z -> { undo(); true }
                    Key.Y -> { redo(); true }
                    Key.A -> {
                        value = value.copy(selection = TextRange(0, value.text.length))
                        true
                    }
                    else -> false
                }
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = path,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (state.dirty) {
                Text("●", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 6.dp))
            }
            IconButton(onClick = viewModel::saveFile, enabled = state.dirty) {
                Icon(Icons.Outlined.Save, contentDescription = "Salva (Ctrl+S)")
            }
            IconButton(onClick = viewModel::runSandbox) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = "Esegui nella sandbox")
            }
            TextButton(onClick = viewModel::generateTests) { Text("Test") }
            IconButton(onClick = viewModel::exportCurrentFile) {
                Icon(Icons.Outlined.SaveAlt, contentDescription = "Esporta in Download")
            }
            IconButton(onClick = viewModel::shareCurrentFile) {
                Icon(Icons.Outlined.Share, contentDescription = "Condividi il file")
            }
        }
        val beforeCaret = value.text.take(value.selection.start.coerceIn(0, value.text.length))
        val caretLine = beforeCaret.count { it == '\n' } + 1
        val caretColumn = beforeCaret.length - beforeCaret.lastIndexOf('\n')
        val lineCount = value.text.count { it == '\n' } + 1
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$lineCount righe · riga $caretLine, colonna $caretColumn",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showFind = !showFind }, enabled = true) { Text("Cerca") }
            TextButton(onClick = { undo() }, enabled = undoStack.isNotEmpty()) { Text("Undo") }
            TextButton(onClick = { redo() }, enabled = redoStack.isNotEmpty()) { Text("Redo") }
        }
        if (showFind) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f).focusRequester(findFocus),
                        placeholder = { Text("Cerca nel file") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { findNext() }) { Text("Successivo") }
                    TextButton(onClick = { showFind = false }) { Text("Chiudi") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = replacement,
                        onValueChange = { replacement = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Sostituisci con") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { replaceCurrent() }) { Text("Sostituisci") }
                    TextButton(onClick = { replaceAll() }) { Text("Tutto") }
                }
            }
        }
        val language = path.substringAfterLast('.', "").lowercase()
        val gutterWidth = ((lineCount.toString().length * 8) + 14).dp
        // Give the field the width of its longest line so code does not wrap:
        // the line-number gutter stays aligned and the horizontal scroll is real.
        val longestLine = value.text.lineSequence().maxOfOrNull { it.length } ?: 0
        val editorWidth = ((longestLine * 7.4f) + 32f).dp.coerceIn(280.dp, 6000.dp)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(vertical)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = (1..lineCount).joinToString("\n"),
                        modifier = Modifier.width(gutterWidth).padding(start = 4.dp, top = 8.dp, end = 4.dp),
                        style = editorStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        textAlign = TextAlign.End,
                    )
                    Box(modifier = Modifier.weight(1f).horizontalScroll(horizontal)) {
                        BasicTextField(
                            value = value,
                            onValueChange = { updated ->
                                if (updated.text == value.text) value = updated else commit(updated.text, updated.selection)
                            },
                            modifier = Modifier.width(editorWidth).padding(8.dp),
                            textStyle = editorStyle,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            visualTransformation = remember(language, keywordColor, stringColor, commentColor, numberColor) {
                                CodeSyntaxTransformation(language, keywordColor, stringColor, commentColor, numberColor)
                            },
                        )
                    }
                }
            }
        }
        DiagnosticsPane(state.diagnostics)
        if (state.sandbox != null) {
            SandboxPane(state, viewModel)
        }
    }
}

/**
 * Colours comments, strings, numbers and keywords without changing a single
 * character of the text, so selections and offsets stay valid.
 */
private class CodeSyntaxTransformation(
    private val language: String,
    private val keywordColor: Color,
    private val stringColor: Color,
    private val commentColor: Color,
    private val numberColor: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (text.length > 120_000) return TransformedText(text, OffsetMapping.Identity)
        val builder = AnnotatedString.Builder(text.text)
        STRINGS.findAll(text.text).forEach { match ->
            builder.addStyle(SpanStyle(color = stringColor), match.range.first, match.range.last + 1)
        }
        KEYWORDS.findAll(text.text).forEach { match ->
            if (match.value in KEYWORDS_SET) {
                builder.addStyle(SpanStyle(color = keywordColor), match.range.first, match.range.last + 1)
            }
        }
        NUMBERS.findAll(text.text).forEach { match ->
            builder.addStyle(SpanStyle(color = numberColor), match.range.first, match.range.last + 1)
        }
        val commentRegex = when (language) {
            "py", "sh", "yaml", "yml", "properties" -> HASH_COMMENT
            "html", "htm", "xml" -> HTML_COMMENT
            else -> SLASH_COMMENT
        }
        commentRegex.findAll(text.text).forEach { match ->
            builder.addStyle(SpanStyle(color = commentColor), match.range.first, match.range.last + 1)
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    private companion object {
        val STRINGS = Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'")
        val KEYWORDS = Regex("\\b[A-Za-z_][A-Za-z0-9_]*\\b")
        val NUMBERS = Regex("\\b\\d+(?:\\.\\d+)?\\b")
        val SLASH_COMMENT = Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/")
        val HASH_COMMENT = Regex("#[^\\n]*")
        val HTML_COMMENT = Regex("<!--[\\s\\S]*?-->")
        val KEYWORDS_SET = setOf(
            "fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for",
            "while", "return", "import", "package", "private", "public", "internal", "data",
            "const", "suspend", "try", "catch", "finally", "throw", "new", "this", "super",
            "function", "let", "async", "await", "export", "default", "from", "typeof",
            "true", "false", "null", "undefined", "struct", "def", "self", "elif", "except",
            "void", "int", "float", "double", "char", "bool", "constexpr", "namespace", "template",
            "public", "protected", "static", "final", "abstract", "override", "open", "enum",
        )
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

        Text("Analisi del progetto", style = MaterialTheme.typography.titleSmall)
        Text(
            "Cerca blocchi duplicati tra file, import senza dipendenza dichiarata, TODO e file troppo grandi. " +
                "È analisi statica: non è una compilazione.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Button(onClick = viewModel::runProjectAnalysis) {
            Icon(Icons.Outlined.Psychology, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Analizza il progetto")
        }
        state.projectSummary?.let { summary ->
            Spacer(Modifier.height(8.dp))
            Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        state.insights.take(12).forEach { insight ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        "${insight.path}:${insight.line}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(insight.message, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        state.changeReport?.let { report ->
            Spacer(Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Text(
                    report,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
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

/**
 * Activity log of the autonomous agent. Every line states what actually
 * happened — created, read, checked, failed, waiting for the user — so the
 * result is never a vague "done".
 */
@Composable
private fun AgentStepsPanel(
    steps: List<AgentStep>,
    canRollback: Boolean,
    onRollback: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Attività di ITAGPT Code",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            steps.takeLast(10).forEach { step ->
                Row(
                    modifier = Modifier.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    val (icon, tint) = when (step.status) {
                        AgentStepStatus.DONE -> Icons.Outlined.Check to MaterialTheme.colorScheme.primary
                        AgentStepStatus.FAILED -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
                        AgentStepStatus.NEEDS_USER -> Icons.Outlined.WarningAmber to MaterialTheme.colorScheme.tertiary
                        AgentStepStatus.RUNNING -> Icons.Outlined.Refresh to MaterialTheme.colorScheme.secondary
                    }
                    Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = tint)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(step.label, style = MaterialTheme.typography.labelMedium)
                        step.detail?.let { detail ->
                            Text(
                                detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (canRollback) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRollback, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Ripristina lo stato prima dell'attività")
                }
            }
        }
    }
}
