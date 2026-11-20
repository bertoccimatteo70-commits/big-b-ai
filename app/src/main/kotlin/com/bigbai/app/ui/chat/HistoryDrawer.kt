package com.bigbai.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bigbai.core.model.Conversation
import com.bigbai.core.model.Folder
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private sealed interface DrawerDialog {
    data class Rename(val conversation: Conversation) : DrawerDialog
    data class Move(val conversation: Conversation) : DrawerDialog
    data object NewFolder : DrawerDialog
}

@Composable
fun HistoryDrawer(
    conversations: List<Conversation>,
    archived: List<Conversation>,
    searchResults: List<Conversation>,
    folders: List<Folder>,
    currentId: Long,
    onSelect: (Conversation) -> Unit,
    onNewChat: () -> Unit,
    onToggleFavorite: (Conversation) -> Unit,
    onArchive: (Conversation) -> Unit,
    onUnarchive: (Conversation) -> Unit,
    onRename: (Conversation, String) -> Unit,
    onMove: (Conversation, Long?) -> Unit,
    onDelete: (Conversation) -> Unit,
    onCreateFolder: (String) -> Unit,
    onSearch: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedFolder by remember { mutableStateOf<Long?>(null) }
    var showArchived by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Long?>(null) }
    var dialog by remember { mutableStateOf<DrawerDialog?>(null) }

    LaunchedEffect(query) {
        if (query.isNotBlank()) {
            delay(250)
            onSearch(query)
        }
    }

    val visible = when {
        query.isNotBlank() -> searchResults
        showArchived -> archived
        selectedFolder != null -> conversations.filter { it.folderId == selectedFolder }
        else -> conversations
    }
    val favorites = if (query.isBlank() && !showArchived) visible.filter { it.favorite } else emptyList()
    val others = if (favorites.isEmpty()) visible else visible.filterNot { it.favorite }

    ModalDrawerSheet(modifier = Modifier.width(316.dp)) {
        Column(modifier = Modifier.fillMaxHeight()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("AI", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Cronologia",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Impostazioni")
                }
            }

            FilledTonalButton(
                onClick = onNewChat,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Nuova chat")
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it; if (it.isBlank()) onSearch("") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                placeholder = { Text("Cerca chat e messaggi") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = selectedFolder == null && !showArchived,
                    onClick = { selectedFolder = null; showArchived = false },
                    label = { Text("Tutte") },
                )
                folders.forEach { folder ->
                    FilterChip(
                        selected = selectedFolder == folder.id,
                        onClick = { selectedFolder = folder.id; showArchived = false },
                        label = { Text(folder.name, maxLines = 1) },
                    )
                }
                FilterChip(
                    selected = showArchived,
                    onClick = { showArchived = true; selectedFolder = null },
                    label = { Text("Archiviate") },
                )
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                if (favorites.isNotEmpty()) {
                    item {
                        SectionLabel("Preferiti")
                    }
                    items(favorites, key = { "fav-${it.id}" }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            selected = conversation.id == currentId,
                            onClick = { onSelect(conversation) },
                            menuExpanded = menuFor == conversation.id,
                            onMenuClick = { menuFor = conversation.id },
                            onMenuDismiss = { menuFor = null },
                            onToggleFavorite = { onToggleFavorite(conversation); menuFor = null },
                            onRename = { dialog = DrawerDialog.Rename(conversation); menuFor = null },
                            onMove = { dialog = DrawerDialog.Move(conversation); menuFor = null },
                            archived = showArchived,
                            onArchive = {
                                if (showArchived) onUnarchive(conversation) else onArchive(conversation)
                                menuFor = null
                            },
                            onDelete = { onDelete(conversation); menuFor = null },
                        )
                    }
                }
                item { SectionLabel(if (showArchived) "Archiviate" else "Conversazioni") }
                if (others.isEmpty()) {
                    item {
                        Text(
                            text = if (query.isBlank()) "Nessuna conversazione" else "Nessun risultato",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(others, key = { "all-${it.id}" }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        selected = conversation.id == currentId,
                        onClick = { onSelect(conversation) },
                        menuExpanded = menuFor == conversation.id,
                        onMenuClick = { menuFor = conversation.id },
                        onMenuDismiss = { menuFor = null },
                        onToggleFavorite = { onToggleFavorite(conversation); menuFor = null },
                        onRename = { dialog = DrawerDialog.Rename(conversation); menuFor = null },
                        onMove = { dialog = DrawerDialog.Move(conversation); menuFor = null },
                        archived = showArchived,
                        onArchive = {
                            if (showArchived) onUnarchive(conversation) else onArchive(conversation)
                            menuFor = null
                        },
                        onDelete = { onDelete(conversation); menuFor = null },
                    )
                }
            }

            HorizontalDivider()
            TextButton(
                onClick = { dialog = DrawerDialog.NewFolder },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Nuova cartella")
            }
        }
    }

    when (val current = dialog) {
        is DrawerDialog.Rename -> RenameDialog(
            initial = current.conversation.title,
            onDismiss = { dialog = null },
            onConfirm = { onRename(current.conversation, it); dialog = null },
        )
        is DrawerDialog.Move -> MoveDialog(
            conversation = current.conversation,
            folders = folders,
            onDismiss = { dialog = null },
            onConfirm = { onMove(current.conversation, it); dialog = null },
        )
        DrawerDialog.NewFolder -> RenameDialog(
            initial = "",
            title = "Nuova cartella",
            label = "Nome cartella",
            onDismiss = { dialog = null },
            onConfirm = { if (it.isNotBlank()) onCreateFolder(it); dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    selected: Boolean,
    onClick: () -> Unit,
    menuExpanded: Boolean,
    onMenuClick: () -> Unit,
    onMenuDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    archived: Boolean,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 2.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (conversation.favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (conversation.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = conversation.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = relativeDate(conversation.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = onMenuClick, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = onMenuDismiss) {
                DropdownMenuItem(
                    text = { Text(if (conversation.favorite) "Rimuovi dai preferiti" else "Aggiungi ai preferiti") },
                    leadingIcon = {
                        Icon(if (conversation.favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, null)
                    },
                    onClick = onToggleFavorite,
                )
                DropdownMenuItem(
                    text = { Text("Rinomina") },
                    leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                    onClick = onRename,
                )
                DropdownMenuItem(
                    text = { Text("Sposta in cartella") },
                    leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                    onClick = onMove,
                )
                DropdownMenuItem(
                    text = { Text(if (archived) "Ripristina" else "Archivia") },
                    leadingIcon = {
                        Icon(if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, null)
                    },
                    onClick = onArchive,
                )
                DropdownMenuItem(
                    text = { Text("Elimina") },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    title: String = "Rinomina conversazione",
    label: String = "Titolo",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(120) },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun MoveDialog(
    conversation: Conversation,
    folders: List<Folder>,
    onDismiss: () -> Unit,
    onConfirm: (Long?) -> Unit,
) {
    var selected by remember { mutableStateOf(conversation.folderId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sposta in cartella") },
        text = {
            Column {
                FolderOption("Nessuna cartella", selected == null) { selected = null }
                folders.forEach { folder ->
                    FolderOption(folder.name, selected == folder.id) { selected = folder.id }
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
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text("Sposta") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun FolderOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun relativeDate(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val dayDiff = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && dayDiff == 0 -> "Oggi " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        sameYear && dayDiff == 1 -> "Ieri"
        sameYear -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(timestamp))
        else -> SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}
