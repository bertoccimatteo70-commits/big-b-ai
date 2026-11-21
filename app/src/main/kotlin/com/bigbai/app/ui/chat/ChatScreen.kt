package com.bigbai.app.ui.chat

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bigbai.app.AppContainer
import com.bigbai.app.files.FileExport
import com.bigbai.app.ui.LocalAppDrawer
import com.bigbai.app.ui.theme.BigBBrand
import com.bigbai.core.lang.LanguageCoach
import com.bigbai.core.model.AppSettings
import com.bigbai.core.model.Attachment
import com.bigbai.core.model.ChatMessage
import com.bigbai.core.model.Conversation
import com.bigbai.core.model.Role
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    container: AppContainer,
    viewModel: ChatViewModel,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenCode: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val installed by viewModel.installed.collectAsState()
    val settings by container.settings.settings.collectAsState(initial = AppSettings())

    val scope = rememberCoroutineScope()
    val openDrawer = LocalAppDrawer.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()

    var showModelMenu by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var metricsExpanded by remember { mutableStateOf(settings.showPerfPanel) }
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }
    var stickToBottom by remember { mutableStateOf(true) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.attach(uri)
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.attach(uri)
    }

    // Follow new content only while the user is reading the end of the chat.
    // The previous version animated to the last item on every token, which
    // cancelled itself continuously and yanked the list while scrolling.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canScrollForward) ->
                if (scrolling) stickToBottom = !canScrollForward
            }
    }
    LaunchedEffect(listState, state.messages.size, state.streamingText.length) {
        if (stickToBottom) {
            val total = listState.layoutInfo.totalItemsCount
            if (total > 0) listState.scrollToItem(total - 1)
        }
    }

    // A different conversation is a different screen state: return to the end of
    // the list instead of keeping the scroll position of the previous chat.
    LaunchedEffect(state.conversationId) {
        stickToBottom = true
        runCatching { listState.scrollToItem(0) }
    }

    val currentTitle = conversations.firstOrNull { it.id == state.conversationId }?.title
        ?.takeIf { it.isNotBlank() && it != "—" }

    fun saveMessage(message: ChatMessage) {
        val extension = FileExport.extensionFor(message.content)
        val name = FileExport.suggestedFileName(currentTitle ?: "risposta", extension, message.createdAt)
        FileExport.saveToDownloads(context, name, FileExport.mimeFor(extension), message.content)
            .onSuccess { Toast.makeText(context, "Salvato in Download/BIG B AI/$name", Toast.LENGTH_LONG).show() }
            .onFailure { error ->
                Toast.makeText(context, error.message ?: "Salvataggio non riuscito", Toast.LENGTH_LONG).show()
            }
    }

    fun shareMessage(message: ChatMessage) {
        val extension = FileExport.extensionFor(message.content)
        val name = FileExport.suggestedFileName(currentTitle ?: "risposta", extension, message.createdAt)
        FileExport.share(context, name, FileExport.mimeFor(extension), message.content)
            .onFailure { error ->
                Toast.makeText(context, error.message ?: "Condivisione non riuscita", Toast.LENGTH_LONG).show()
            }
    }

    fun exportConversation() {
        if (state.messages.isEmpty()) return
        val markdown = buildString {
            appendLine("# ${currentTitle ?: "Conversazione"}")
            appendLine()
            state.messages.forEach { message ->
                appendLine(if (message.role == Role.USER) "## Utente" else "## AI")
                appendLine()
                appendLine(message.content)
                appendLine()
            }
        }
        val name = FileExport.suggestedFileName(currentTitle, "md", System.currentTimeMillis())
        FileExport.saveToDownloads(context, name, FileExport.MIME_MARKDOWN, markdown)
            .onSuccess { Toast.makeText(context, "Conversazione salvata in Download/BIG B AI/$name", Toast.LENGTH_LONG).show() }
            .onFailure { error ->
                Toast.makeText(context, error.message ?: "Esportazione non riuscita", Toast.LENGTH_LONG).show()
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

    Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = openDrawer) {
                            Icon(Icons.Outlined.Menu, contentDescription = "Menu")
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = currentTitle ?: "Nuova chat",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .padding(end = 6.dp)
                                        .size(6.dp)
                                        .background(
                                            if (state.modelReady) BigBBrand.Success else MaterialTheme.colorScheme.outline,
                                            CircleShape,
                                        ),
                                )
                                Text(
                                    text = state.modelLabel ?: "Nessun modello attivo",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
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
                                                        "${model.variant.quant} · ${model.variant.params}",
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
                        IconButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.newChat()
                            },
                        ) {
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
                                    text = { Text("Esporta conversazione") },
                                    leadingIcon = { Icon(Icons.Outlined.Description, null) },
                                    onClick = { showOverflow = false; exportConversation() },
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
                    tutorLabel = if (settings.tutorEnabled) LanguageCoach.label(settings.tutorTarget) else null,
                    onInputChange = viewModel::onInputChange,
                    onSend = {
                        focusManager.clearFocus()
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.send()
                    },
                    onStop = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.stop()
                    },
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
                AnimatedVisibility(visible = state.error != null) {
                    state.error?.let { error ->
                        ErrorBanner(message = error, onDismiss = viewModel::dismissError)
                    }
                }
                state.status?.let { status ->
                    StatusStrip(text = status, onDismiss = viewModel::dismissStatus)
                }
                if (settings.tutorEnabled) {
                    TutorStrip(
                        label = LanguageCoach.label(settings.tutorTarget),
                        level = settings.tutorLevel.label,
                        onLesson = {
                            viewModel.onInputChange(
                                LanguageCoach.lessonPrompt(settings.tutorTarget, settings.tutorLevel),
                            )
                        },
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                    ) {
                        if (state.messages.isEmpty() && state.streamingText.isEmpty() && !state.isGenerating) {
                            item { EmptyChat(tutorLabel = if (settings.tutorEnabled) LanguageCoach.label(settings.tutorTarget) else null, onSuggestion = viewModel::onInputChange) }
                        }
                        items(
                            items = state.messages,
                            key = { it.id },
                            contentType = { it.role.name },
                        ) { message ->
                            MessageBubble(
                                message = message,
                                modifier = Modifier.animateItem(),
                                onSaveToMemory = if (message.role == Role.ASSISTANT) {
                                    { viewModel.saveMessageToMemory(message) }
                                } else {
                                    null
                                },
                                onSaveFile = { saveMessage(message) },
                                onShare = { shareMessage(message) },
                            )
                        }
                        if (state.isGenerating) {
                            item(key = "thinking") { ThinkingBubble() }
                        }
                        item(key = "bottom-spacer") { Spacer(Modifier.height(12.dp)) }
                    }

                    if (!stickToBottom) {
                        SmallFloatingActionButton(
                            onClick = {
                                scope.launch {
                                    val total = listState.layoutInfo.totalItemsCount
                                    if (total > 0) listState.animateScrollToItem(total - 1)
                                    stickToBottom = true
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(14.dp),
                        ) {
                            Icon(Icons.Outlined.ArrowDownward, contentDescription = "Vai all'ultimo messaggio")
                        }
                    }
                }

                if (state.lexiconSaved > 0) {
                    LexiconNotice(
                        count = state.lexiconSaved,
                        label = LanguageCoach.label(settings.tutorTarget),
                        onDismiss = viewModel::dismissLexiconNotice,
                    )
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

@Composable
private fun StatusStrip(text: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
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
private fun TutorStrip(label: String, level: String, onLesson: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.School,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Tutor · $label", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                Text(
                    "Livello $level · lessico salvato in memoria",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onLesson) { Text("Lezione") }
        }
    }
}

@Composable
private fun LexiconNotice(count: Int, label: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.School,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (count == 1) {
                    "1 nuova parola salvata nel lessico di $label."
                } else {
                    "$count nuove parole salvate nel lessico di $label."
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Chiudi",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun EmptyChat(tutorLabel: String?, onSuggestion: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .background(
                    Brush.linearGradient(listOf(BigBBrand.Indigo, BigBBrand.Cyan)),
                    RoundedCornerShape(24.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Psychology,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(42.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text("Come posso aiutarti?", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Tutto viene elaborato sul tuo dispositivo. Nessun account, nessun cloud.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(22.dp))
        val suggestions = buildList {
            if (tutorLabel != null) add("Fammi una lezione di $tutorLabel" to Icons.Outlined.School)
            add("Spiega un concetto in modo semplice" to Icons.Outlined.Lightbulb)
            add("Riassumi un documento PDF" to Icons.Outlined.Description)
            add("Scrivi e revisiona un testo" to Icons.Outlined.SmartToy)
        }
        suggestions.forEach { (text, icon) ->
            Surface(
                onClick = { onSuggestion(text) },
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(
    state: ChatUiState,
    tutorLabel: String?,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRemoveAttachment: (Attachment) -> Unit,
    onAttachClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 10.dp,
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
                    placeholder = {
                        Text(if (tutorLabel != null) "Scrivi o chiedi una lezione…" else "Scrivi un messaggio…")
                    },
                    maxLines = 6,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                )
                Spacer(Modifier.width(8.dp))
                if (state.isGenerating) {
                    FilledTonalIconButton(
                        onClick = onStop,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Outlined.Stop, contentDescription = "Interrompi")
                    }
                } else {
                    FilledIconButton(
                        onClick = onSend,
                        modifier = Modifier.size(48.dp),
                        enabled = state.input.isNotBlank() || state.attachments.isNotEmpty(),
                    ) {
                        Icon(Icons.Outlined.ArrowUpward, contentDescription = "Invia")
                    }
                }
            }
            if (!state.modelReady && !state.isGenerating) {
                Text(
                    text = "Il modello verrà caricato al primo messaggio.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 54.dp, top = 3.dp),
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
        icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
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
                    fontWeight = FontWeight.SemiBold,
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
