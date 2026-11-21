package com.bigbai.app.ui.history

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bigbai.app.AppContainer
import com.bigbai.app.ui.LocalAppDrawer
import com.bigbai.app.ui.chat.DeleteConversationDialog
import com.bigbai.core.model.Conversation
import com.bigbai.core.model.Folder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HistoryUiState(
    val conversations: List<Conversation> = emptyList(),
    val archived: List<Conversation> = emptyList(),
    val results: List<Conversation> = emptyList(),
    val query: String = "",
    val showArchived: Boolean = false,
)

class HistoryViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    val folders: StateFlow<List<Folder>> = container.chat.folders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            container.chat.conversations().collect { list ->
                _state.update { it.copy(conversations = list) }
            }
        }
        viewModelScope.launch {
            container.chat.archivedConversations().collect { list ->
                _state.update { it.copy(archived = list) }
            }
        }
    }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
        viewModelScope.launch {
            val results = if (value.isBlank()) emptyList() else container.chat.search(value)
            _state.update { current -> if (current.query == value) current.copy(results = results) else current }
        }
    }

    fun setShowArchived(value: Boolean) = _state.update { it.copy(showArchived = value) }

    fun toggleFavorite(conversation: Conversation) {
        viewModelScope.launch { container.chat.toggleFavorite(conversation.id, !conversation.favorite) }
    }

    fun rename(conversation: Conversation, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch { container.chat.rename(conversation.id, title.trim()) }
    }

    fun setArchived(conversation: Conversation, archived: Boolean) {
        viewModelScope.launch { container.chat.setArchived(conversation.id, archived) }
    }

    fun delete(conversation: Conversation) {
        viewModelScope.launch { container.chat.deleteConversation(conversation.id) }
    }

    fun createFolder(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { container.chat.createFolder(name.trim()) }
    }

    fun moveToFolder(conversation: Conversation, folderId: Long?) {
        viewModelScope.launch { container.chat.moveToFolder(conversation.id, folderId) }
    }
}

/**
 * Full history and search. The drawer keeps the ten main destinations; this
 * screen is where conversations are actually managed (search, rename, archive,
 * delete) without hiding those actions behind a side panel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    container: AppContainer,
    autoFocusSearch: Boolean,
    onOpenConversation: (Long) -> Unit,
) {
    val viewModel: HistoryViewModel = viewModel(
        factory = viewModelFactory {
            initializer { HistoryViewModel(container) }
        },
    )
    val state by viewModel.state.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val openDrawer = LocalAppDrawer.current
    val focusRequester = remember { FocusRequester() }

    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }
    var pendingRename by remember { mutableStateOf<Conversation?>(null) }
    var pendingMove by remember { mutableStateOf<Conversation?>(null) }
    var newFolderDialog by remember { mutableStateOf(false) }
    var selectedFolder by remember { mutableStateOf<Long?>(null) }
    var menuFor by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(autoFocusSearch) {
        if (autoFocusSearch) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    pendingDelete?.let { conversation ->
        DeleteConversationDialog(
            conversation = conversation,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                viewModel.delete(conversation)
                pendingDelete = null
            },
        )
    }

    pendingRename?.let { conversation ->
        var title by remember(conversation.id) { mutableStateOf(conversation.title) }
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("Rinomina conversazione") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(120) },
                    label = { Text("Titolo") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.rename(conversation, title)
                        pendingRename = null
                    },
                    enabled = title.isNotBlank(),
                ) { Text("Salva") }
            },
            dismissButton = { TextButton(onClick = { pendingRename = null }) { Text("Annulla") } },
        )
    }

    pendingMove?.let { conversation ->
        var target by remember(conversation.id) { mutableStateOf(conversation.folderId) }
        AlertDialog(
            onDismissRequest = { pendingMove = null },
            title = { Text("Sposta in cartella") },
            text = {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { target = null }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = target == null, onClick = { target = null })
                        Text("Nessuna cartella")
                    }
                    folders.forEach { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { target = folder.id }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = target == folder.id, onClick = { target = folder.id })
                            Text(folder.name)
                        }
                    }
                    if (folders.isEmpty()) {
                        Text(
                            "Non hai ancora creato cartelle.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.moveToFolder(conversation, target)
                        pendingMove = null
                    },
                ) { Text("Sposta") }
            },
            dismissButton = { TextButton(onClick = { pendingMove = null }) { Text("Annulla") } },
        )
    }

    if (newFolderDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolderDialog = false },
            title = { Text("Nuova cartella") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Nome cartella") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.createFolder(name); newFolderDialog = false },
                    enabled = name.isNotBlank(),
                ) { Text("Crea") }
            },
            dismissButton = { TextButton(onClick = { newFolderDialog = false }) { Text("Annulla") } },
        )
    }

    val list = when {
        state.query.isNotBlank() -> state.results
        state.showArchived -> state.archived
        selectedFolder != null -> state.conversations.filter { it.folderId == selectedFolder }
        else -> state.conversations
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = openDrawer) {
                        Icon(Icons.Outlined.Menu, contentDescription = "Menu")
                    }
                },
                title = { Text("Cronologia") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focusRequester),
                placeholder = { Text("Cerca chat e messaggi") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = !state.showArchived && selectedFolder == null,
                    onClick = { viewModel.setShowArchived(false); selectedFolder = null },
                    label = { Text("Tutte") },
                )
                folders.forEach { folder ->
                    FilterChip(
                        selected = selectedFolder == folder.id,
                        onClick = { selectedFolder = folder.id; viewModel.setShowArchived(false) },
                        label = { Text(folder.name) },
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = { newFolderDialog = true },
                    label = { Text("+ Cartella") },
                )
                FilterChip(
                    selected = state.showArchived,
                    onClick = { viewModel.setShowArchived(true); selectedFolder = null },
                    label = { Text("Archiviate (${state.archived.size})") },
                )
            }

            if (list.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = when {
                            state.query.isNotBlank() -> "Nessun risultato per «${state.query}»"
                            state.showArchived -> "Nessuna conversazione archiviata"
                            else -> "Nessuna conversazione. Inizia una nuova chat."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(list, key = { it.id }) { conversation ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenConversation(conversation.id) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        conversation.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${formatDate(conversation.updatedAt)} · " +
                                            "${conversation.messageCount} messaggi",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { viewModel.toggleFavorite(conversation) }) {
                                    Icon(
                                        if (conversation.favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                        contentDescription = "Preferito",
                                        tint = if (conversation.favorite) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Box {
                                    IconButton(onClick = { menuFor = conversation.id }) {
                                        Icon(Icons.Outlined.MoreVert, contentDescription = "Azioni")
                                    }
                                    DropdownMenu(
                                        expanded = menuFor == conversation.id,
                                        onDismissRequest = { menuFor = null },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Rinomina") },
                                            leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                                            onClick = { pendingRename = conversation; menuFor = null },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Sposta in cartella") },
                                            leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                                            onClick = { pendingMove = conversation; menuFor = null },
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(if (conversation.archived) "Ripristina" else "Archivia")
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    if (conversation.archived) Icons.Outlined.Unarchive
                                                    else Icons.Outlined.Archive,
                                                    null,
                                                )
                                            },
                                            onClick = {
                                                viewModel.setArchived(conversation, !conversation.archived)
                                                menuFor = null
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Elimina") },
                                            leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                            onClick = { pendingDelete = conversation; menuFor = null },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

private fun formatDate(timestamp: Long): String {
    if (timestamp <= 0) return "—"
    return SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
}
