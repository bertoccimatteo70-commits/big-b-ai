package com.bigbai.app.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bigbai.app.AppContainer
import com.bigbai.app.ui.AppViewModelFactory
import com.bigbai.core.model.AppSettings
import com.bigbai.core.model.Attachment
import com.bigbai.core.model.Conversation
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    container: AppContainer,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenCode: () -> Unit,
) {
    val factory = remember { AppViewModelFactory(container) }
    val viewModel: ChatViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val archivedConversations by viewModel.archivedConversations.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val installed by viewModel.installed.collectAsState()
    val settings by container.settings.settings.collectAsState(initial = AppSettings())

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()

    var showModelMenu by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var metricsExpanded by remember { mutableStateOf(settings.showPerfPanel) }
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.attach(uri)
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.attach(uri)
    }

    LaunchedEffect(state.messages.size, state.streamingText.length) {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) {
            runCatching { listState.animateScrollToItem(total - 1) }
        }
    }

    pendingDelete?.let { conversation ->
        DeleteConversationDialog(
            conversation = conversation,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                viewModel.deleteConversation(conversation.id)
                pendingDelete = null
            },
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            HistoryDrawer(
                conversations = conversations,
                archived = archivedConversations,
                searchResults = searchResults,
                folders = folders,
                currentId = state.conversationId,
                onSelect = {
                    viewModel.openConversation(it.id)
                    scope.launch { drawerState.close() }
                },
                onNewChat = {
                    viewModel.newChat()
                    scope.launch { drawerState.close() }
                },
                onToggleFavorite = viewModel::toggleFavorite,
                onArchive = viewModel::archive,
                onUnarchive = viewModel::unarchive,
                onRename = viewModel::rename,
                onMove = viewModel::moveToFolder,
                onDelete = { pendingDelete = it },
                onCreateFolder = viewModel::createFolder,
                onSearch = viewModel::searchConversations,
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Outlined.Menu, contentDescription = "Cronologia")
                        }
                    },
                    title = {
                        Column {
                            Text("AI", style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = state.modelLabel ?: "Nessun modello attivo",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { showModelMenu = true }) {
                                Icon(Icons.Outlined.Tune, contentDescription = "Seleziona modello")
                            }
                            DropdownMenu(expanded = showModelMenu, onDismissRequest = { showModelMenu = false }) {
                                if (installed.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Nessun modello installato") },
                                        onClick = { showModelMenu = false; onOpenModels() },
                                    )
                                } else {
                                    installed.forEach { model ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(model.variant.family, style = MaterialTheme.typography.bodyMedium)
                                                    Text(
                                                        model.variant.quant,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            },
                                            leadingIcon = {
                                                RadioButton(
                                                    selected = model.variant.id == settings.activeModelId,
                                                    onClick = null,
                                                )
                                            },
                                            onClick = {
                                                showModelMenu = false
                                                viewModel.selectModel(model.variant.id)
                                            },
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Gestisci modelli") },
                                        onClick = { showModelMenu = false; onOpenModels() },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = viewModel::newChat) {
                            Icon(Icons.Outlined.Add, contentDescription = "Nuova chat")
                        }
                        Box {
                            IconButton(onClick = { showOverflow = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "Altro")
                            }
                            DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                                DropdownMenuItem(
                                    text = { Text("Memoria") },
                                    leadingIcon = { Icon(Icons.Outlined.Memory, null) },
                                    onClick = { showOverflow = false; onOpenMemory() },
                                )
                                DropdownMenuItem(
                                    text = { Text("BIG B Code") },
                                    leadingIcon = { Icon(Icons.Outlined.Psychology, null) },
                                    onClick = { showOverflow = false; onOpenCode() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Impostazioni") },
                                    leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                                    onClick = { showOverflow = false; onOpenSettings() },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
            bottomBar = {
                Composer(
                    state = state,
                    onInputChange = viewModel::onInputChange,
                    onSend = {
                        focusManager.clearFocus()
                        viewModel.send()
                    },
                    onStop = viewModel::stop,
                    onRemoveAttachment = viewModel::removeAttachment,
                    onAttachClick = { showAttachmentMenu = true },
                )
                AttachmentMenu(
                    expanded = showAttachmentMenu,
                    onDismiss = { showAttachmentMenu = false },
                    onImage = { showAttachmentMenu = false; imagePicker.launch(arrayOf("image/*")) },
                    onPdf = { showAttachmentMenu = false; pdfPicker.launch(arrayOf("application/pdf")) },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                state.error?.let { error ->
                    ErrorBanner(message = error, onDismiss = viewModel::dismissError)
                }
                state.status?.let { status ->
                    StatusStrip(text = status, onDismiss = viewModel::dismissStatus)
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    if (state.messages.isEmpty() && state.streamingText.isEmpty() && !state.isGenerating) {
                        item { EmptyChat(onSuggestion = viewModel::onInputChange) }
                    }
                    items(state.messages, key = { it.id }) { message ->
                        MessageBubble(
                            message = message,
                            onSaveToMemory = if (message.role == com.bigbai.core.model.Role.ASSISTANT) {
                                { viewModel.saveMessageToMemory(message, "Personale") }
                            } else {
                                null
                            },
                        )
                    }
                    if (state.isGenerating) {
                        item { StreamingBubble(state.streamingText, state.isThinking) }
                    }
                    item { Spacer(Modifier.height(10.dp)) }
                }

                MemoryProposalCard(
                    proposals = state.proposals,
                    onSave = viewModel::saveProposal,
                    onDismiss = viewModel::dismissProposals,
                )
                SourcesPanel(state.sources)
                state.metrics?.let { metrics ->
                    MetricsPanel(
                        metrics = metrics,
                        modelLabel = state.modelLabel,
                        threads = settings.threads.takeIf { it > 0 }
                            ?: container.deviceProfile.recommendedThreads(settings.performanceMode),
                        expanded = metricsExpanded,
                        onToggle = { metricsExpanded = !metricsExpanded },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusStrip(text: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun EmptyChat(onSuggestion: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Psychology,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(38.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text("Come posso aiutarti?", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "L'assistente funziona interamente sul tuo dispositivo.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(22.dp))
        listOf(
            "Spiega un concetto in modo semplice",
            "Riassumi un documento PDF",
            "Scrivi e revisiona un testo",
            "Risolvi un'espressione passo per passo",
        ).forEach { suggestion ->
            AssistChip(
                onClick = { onSuggestion(suggestion) },
                label = { Text(suggestion, style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier.padding(vertical = 3.dp),
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        }
    }
}

@Composable
private fun Composer(
    state: ChatUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRemoveAttachment: (Attachment) -> Unit,
    onAttachClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (state.attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.attachments.forEach { attachment ->
                        AssistChip(
                            onClick = { onRemoveAttachment(attachment) },
                            label = {
                                Text(
                                    text = attachment.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (attachment.kind == Attachment.Kind.PDF) Icons.Outlined.Description else Icons.Outlined.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                            trailingIcon = {
                                Icon(Icons.Outlined.Close, contentDescription = "Rimuovi", modifier = Modifier.size(14.dp))
                            },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onAttachClick, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.AttachFile, contentDescription = "Allega")
                }
                OutlinedTextField(
                    value = state.input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Scrivi un messaggio…") },
                    maxLines = 5,
                    shape = RoundedCornerShape(22.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                )
                Spacer(Modifier.width(6.dp))
                FilledIconButton(
                    onClick = if (state.isGenerating) onStop else onSend,
                    modifier = Modifier.size(46.dp),
                    enabled = state.isGenerating || state.input.isNotBlank() || state.attachments.isNotEmpty(),
                ) {
                    Icon(
                        if (state.isGenerating) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward,
                        contentDescription = if (state.isGenerating) "Interrompi" else "Invia",
                    )
                }
            }
            if (!state.modelReady) {
                Text(
                    text = "Il modello verrà caricato al primo messaggio.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 52.dp, top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun AttachmentMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onImage: () -> Unit,
    onPdf: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Immagine") },
            leadingIcon = { Icon(Icons.Outlined.Image, null) },
            onClick = onImage,
        )
        DropdownMenuItem(
            text = { Text("PDF") },
            leadingIcon = { Icon(Icons.Outlined.Description, null) },
            onClick = onPdf,
        )
    }
}

@Composable
fun DeleteConversationDialog(
    conversation: Conversation,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val code = remember(conversation.id) { (100_000..999_999).random().toString() }
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.SmartToy, contentDescription = null) },
        title = { Text("Eliminazione definitiva") },
        text = {
            Column {
                Text(
                    "La conversazione «${conversation.title}» e tutti i suoi messaggi verranno eliminati " +
                        "permanentemente. Non è previsto alcun cestino e non sarà possibile recuperarla.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text("Per confermare, digita il codice:", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = code,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { value -> input = value.filter { it.isDigit() }.take(6) },
                    label = { Text("Codice") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = input.trim() == code) {
                Text("Elimina definitivamente")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

