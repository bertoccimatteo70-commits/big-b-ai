package com.bigbai.app.ui.memory

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bigbai.app.AppContainer
import com.bigbai.app.ui.AppViewModelFactory
import com.bigbai.core.model.MemoryCategory
import com.bigbai.core.model.MemoryItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(container: AppContainer, onBack: () -> Unit) {
    val factory = remember { AppViewModelFactory(container) }
    val viewModel: MemoryViewModel = viewModel(factory = factory)
    val memories by viewModel.memories.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val message by viewModel.message.collectAsState()

    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf<MemoryItem?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }
    var showCategory by remember { mutableStateOf(false) }

    val source = searchResults ?: memories
    val visible = if (selectedCategory == null) source else source.filter { it.categoryId == selectedCategory }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("Svuotare la memoria?") },
            text = { Text("Tutti i ricordi salvati verranno eliminati definitivamente.") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearAll(); showClear = false }) { Text("Elimina tutto") }
            },
            dismissButton = { TextButton(onClick = { showClear = false }) { Text("Annulla") } },
        )
    }

    if (showAdd) {
        MemoryEditorDialog(
            title = "Nuovo ricordo",
            initial = "",
            categories = categories,
            onDismiss = { showAdd = false },
            onConfirm = { content, category ->
                viewModel.add(content, category)
                showAdd = false
            },
        )
    }

    editing?.let { item ->
        MemoryEditorDialog(
            title = "Modifica ricordo",
            initial = item.content,
            categories = categories,
            onDismiss = { editing = null },
            onConfirm = { content, _ ->
                viewModel.update(item.id, content)
                editing = null
            },
        )
    }

    if (showCategory) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCategory = false },
            title = { Text("Nuova categoria") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Nome") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.createCategory(name); showCategory = false },
                    enabled = name.isNotBlank(),
                ) { Text("Crea") }
            },
            dismissButton = { TextButton(onClick = { showCategory = false }) { Text("Annulla") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Indietro")
                    }
                },
                title = { Text("Memoria") },
                actions = {
                    IconButton(onClick = { showCategory = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "Nuova categoria")
                    }
                    IconButton(onClick = { showClear = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Svuota memoria")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "Aggiungi ricordo")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; viewModel.search(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                placeholder = { Text("Cerca nella memoria") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                    label = { Text("Tutte") },
                )
                categories.forEach { category ->
                    FilterChip(
                        selected = selectedCategory == category.id,
                        onClick = { selectedCategory = category.id },
                        label = { Text(category.name) },
                    )
                }
            }
            message?.let {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::dismissMessage) { Text("Chiudi") }
                }
            }
            if (visible.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.Memory,
                        contentDescription = null,
                        modifier = Modifier.height(42.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Nessun ricordo. L'assistente proporrà di salvarne quando dici qualcosa " +
                            "di personale e duraturo.",
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
                    items(visible, key = { it.id }) { item ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Row(
                                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        item.categoryName.uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(item.content, style = MaterialTheme.typography.bodyMedium)
                                }
                                IconButton(onClick = { editing = item }) {
                                    Icon(Icons.Outlined.Edit, contentDescription = "Modifica")
                                }
                                IconButton(onClick = { viewModel.delete(item.id) }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Elimina")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryEditorDialog(
    title: String,
    initial: String,
    categories: List<MemoryCategory>,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var content by remember { mutableStateOf(initial) }
    var category by remember { mutableStateOf(categories.firstOrNull()?.name ?: "Personale") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it.take(600) },
                    label = { Text("Ricordo") },
                    minLines = 3,
                )
                Spacer(Modifier.height(8.dp))
                Text("Categoria", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    categories.forEach { option ->
                        Box(
                            modifier = Modifier
                                .background(
                                    if (category == option.name) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(50),
                                )
                                .clickable { category = option.name }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(option.name, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(content.trim(), category) }, enabled = content.isNotBlank()) {
                Text("Salva")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

