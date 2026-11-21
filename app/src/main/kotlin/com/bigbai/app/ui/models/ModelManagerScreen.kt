package com.bigbai.app.ui.models

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bigbai.app.AppContainer
import com.bigbai.app.ui.AppViewModelFactory
import com.bigbai.app.ui.LocalAppDrawer
import com.bigbai.llm.ModelDownloadManager
import com.bigbai.llm.ModelRole
import com.bigbai.llm.ModelTier
import com.bigbai.llm.ModelVariant

/**
 * One screen, three clear jobs: browse the catalogue, manage downloads, manage
 * what is installed. The previous version mixed all three in one long list and
 * hid the installed models inside it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(container: AppContainer, initialTab: Int, onBack: () -> Unit) {
    val factory = remember { AppViewModelFactory(container) }
    val viewModel: ModelManagerViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val openDrawer = LocalAppDrawer.current
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    var pendingDelete by remember { mutableStateOf<ModelVariant?>(null) }

    pendingDelete?.let { variant ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Eliminare il modello?") },
            text = {
                Text(
                    "«${variant.family} ${variant.quant}» verrà rimosso dal dispositivo " +
                        "(${ModelDownloadManager.formatBytes(variant.totalBytes)} liberati).",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(variant); pendingDelete = null }) {
                    Text("Elimina")
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Annulla") } },
        )
    }

    val openSource: (ModelVariant) -> Unit = { variant ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(variant.sourceUrl))) }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = openDrawer) {
                            Icon(Icons.Outlined.Menu, contentDescription = "Menu")
                        }
                    },
                    title = { Text("Modelli AI") },
                )
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Libreria") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Download") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Installati") })
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> LibraryTab(
                    state = state,
                    viewModel = viewModel,
                    onDownload = viewModel::download,
                    onPause = viewModel::pause,
                    onCancel = viewModel::cancel,
                    onDelete = { pendingDelete = it },
                    onVerify = viewModel::verify,
                    onActivate = viewModel::setActive,
                    onOpenSource = openSource,
                )
                1 -> DownloadsTab(
                    state = state,
                    viewModel = viewModel,
                    onDownload = viewModel::download,
                    onPause = viewModel::pause,
                    onCancel = viewModel::cancel,
                    onDelete = { pendingDelete = it },
                    onVerify = viewModel::verify,
                    onActivate = viewModel::setActive,
                    onOpenSource = openSource,
                )
                else -> InstalledTab(
                    state = state,
                    viewModel = viewModel,
                    onBrowse = { tab = 0 },
                    onDelete = { pendingDelete = it },
                    onVerify = viewModel::verify,
                    onActivate = viewModel::setActive,
                    onActivateCode = viewModel::setActiveCode,
                    onOpenSource = openSource,
                )
            }
        }
    }
}

@Composable
private fun MessageBanner(state: ModelManagerState, onDismiss: () -> Unit) {
    state.message?.let { message ->
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Chiudi") }
            }
        }
    }
}

@Composable
private fun LibraryTab(
    state: ModelManagerState,
    viewModel: ModelManagerViewModel,
    onDownload: (ModelVariant) -> Unit,
    onPause: (ModelVariant) -> Unit,
    onCancel: (ModelVariant) -> Unit,
    onDelete: (ModelVariant) -> Unit,
    onVerify: (ModelVariant) -> Unit,
    onActivate: (ModelVariant) -> Unit,
    onOpenSource: (ModelVariant) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            StorageCard(
                used = state.storageUsed,
                free = state.freeSpace,
                budget = state.budget,
                device = state.deviceSummary,
            )
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Modalità avanzata", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Mostra tutti i file, le quantizzazioni e i dettagli tecnici.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.advanced, onCheckedChange = viewModel::setAdvanced)
            }
        }
        item { MessageBanner(state, viewModel::dismissMessage) }

        item { SectionTitle("Assistente") }
        if (state.advanced) {
            items(viewModel.variantsFor(ModelRole.CHAT), key = { it.id }) { variant ->
                ModelCard(
                    variant = variant,
                    installed = state.installed.any { it.variant.id == variant.id },
                    active = state.activeModelId == variant.id,
                    download = state.downloads[variant.id],
                    busy = state.busyModelId == variant.id,
                    advanced = true,
                    context = viewModel.contextFor(variant),
                    onDownload = { onDownload(variant) },
                    onPause = { onPause(variant) },
                    onCancel = { onCancel(variant) },
                    onDelete = { onDelete(variant) },
                    onVerify = { onVerify(variant) },
                    onActivate = { onActivate(variant) },
                    onOpenSource = { onOpenSource(variant) },
                )
            }
        } else {
            val tiers = listOf(
                ModelTier.FAST to "Veloce",
                ModelTier.BALANCED to "Equilibrato",
                ModelTier.SMART to "Intelligente",
                ModelTier.MAX to "Massimo",
            )
            items(tiers, key = { it.first.name }) { (tier, label) ->
                val variant = viewModel.bestForTier(ModelRole.CHAT, tier)
                if (variant != null) {
                    ModelCard(
                        variant = variant,
                        label = label,
                        installed = state.installed.any { it.variant.id == variant.id },
                        active = state.activeModelId == variant.id,
                        download = state.downloads[variant.id],
                        busy = state.busyModelId == variant.id,
                        advanced = false,
                        context = viewModel.contextFor(variant),
                        onDownload = { onDownload(variant) },
                        onPause = { onPause(variant) },
                        onCancel = { onCancel(variant) },
                        onDelete = { onDelete(variant) },
                        onVerify = { onVerify(variant) },
                        onActivate = { onActivate(variant) },
                        onOpenSource = { onOpenSource(variant) },
                    )
                }
            }
        }

        item { SectionTitle("ITAGPT Code") }
        items(
            if (state.advanced) viewModel.variantsFor(ModelRole.CODE)
            else listOfNotNull(
                viewModel.bestForTier(ModelRole.CODE, ModelTier.BALANCED),
                viewModel.bestForTier(ModelRole.CODE, ModelTier.SMART),
            ),
            key = { "code-${it.id}" },
        ) { variant ->
            ModelCard(
                variant = variant,
                installed = state.installed.any { it.variant.id == variant.id },
                active = state.activeModelId == variant.id,
                download = state.downloads[variant.id],
                busy = state.busyModelId == variant.id,
                advanced = state.advanced,
                context = viewModel.contextFor(variant),
                onDownload = { onDownload(variant) },
                onPause = { onPause(variant) },
                onCancel = { onCancel(variant) },
                onDelete = { onDelete(variant) },
                onVerify = { onVerify(variant) },
                onActivate = { onActivate(variant) },
                onOpenSource = { onOpenSource(variant) },
            )
        }

        item { SectionTitle("Immagini (multimodale)") }
        items(
            if (state.advanced) viewModel.variantsFor(ModelRole.VISION)
            else listOfNotNull(viewModel.bestForTier(ModelRole.VISION, ModelTier.SMART)),
            key = { "vision-${it.id}" },
        ) { variant ->
            ModelCard(
                variant = variant,
                installed = state.installed.any { it.variant.id == variant.id },
                active = state.activeModelId == variant.id,
                download = state.downloads[variant.id],
                busy = state.busyModelId == variant.id,
                advanced = state.advanced,
                context = viewModel.contextFor(variant),
                onDownload = { onDownload(variant) },
                onPause = { onPause(variant) },
                onCancel = { onCancel(variant) },
                onDelete = { onDelete(variant) },
                onVerify = { onVerify(variant) },
                onActivate = { onActivate(variant) },
                onOpenSource = { onOpenSource(variant) },
            )
        }

        item {
            Text(
                "I modelli sono scaricati dalle fonti originali indicate e verificati con SHA-256. " +
                    "Non vengono inclusi nell'APK. Licenze: Apache-2.0 (Qwen).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun DownloadsTab(
    state: ModelManagerState,
    viewModel: ModelManagerViewModel,
    onDownload: (ModelVariant) -> Unit,
    onPause: (ModelVariant) -> Unit,
    onCancel: (ModelVariant) -> Unit,
    onDelete: (ModelVariant) -> Unit,
    onVerify: (ModelVariant) -> Unit,
    onActivate: (ModelVariant) -> Unit,
    onOpenSource: (ModelVariant) -> Unit,
) {
    val pending = viewModel.pendingDownloads()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            StorageCard(
                used = state.storageUsed,
                free = state.freeSpace,
                budget = state.budget,
                device = state.deviceSummary,
            )
        }
        item { MessageBanner(state, viewModel::dismissMessage) }

        item { SectionTitle("In corso e in pausa") }
        if (pending.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Nessun download attivo", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Scegli un modello dalla libreria: il download riprende da dove si era " +
                                "interrotto e verifica SHA-256 prima di installarlo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        items(pending, key = { "pending-${it.id}" }) { variant ->
            ModelCard(
                variant = variant,
                installed = state.installed.any { it.variant.id == variant.id },
                active = state.activeModelId == variant.id,
                download = state.downloads[variant.id],
                busy = state.busyModelId == variant.id,
                advanced = true,
                context = viewModel.contextFor(variant),
                onDownload = { onDownload(variant) },
                onPause = { onPause(variant) },
                onCancel = { onCancel(variant) },
                onDelete = { onDelete(variant) },
                onVerify = { onVerify(variant) },
                onActivate = { onActivate(variant) },
                onOpenSource = { onOpenSource(variant) },
            )
        }

        item { SectionTitle("Scelte rapide") }
        items(
            listOf(
                ModelTier.FAST to "Veloce",
                ModelTier.BALANCED to "Equilibrato",
                ModelTier.SMART to "Intelligente",
            ),
            key = { "quick-${it.first.name}" },
        ) { (tier, label) ->
            val variant = viewModel.bestForTier(ModelRole.CHAT, tier)
            if (variant != null) {
                ModelCard(
                    variant = variant,
                    label = label,
                    installed = state.installed.any { it.variant.id == variant.id },
                    active = state.activeModelId == variant.id,
                    download = state.downloads[variant.id],
                    busy = state.busyModelId == variant.id,
                    advanced = false,
                    context = viewModel.contextFor(variant),
                    onDownload = { onDownload(variant) },
                    onPause = { onPause(variant) },
                    onCancel = { onCancel(variant) },
                    onDelete = { onDelete(variant) },
                    onVerify = { onVerify(variant) },
                    onActivate = { onActivate(variant) },
                    onOpenSource = { onOpenSource(variant) },
                )
            }
        }

        item {
            Text(
                "Il download usa una richiesta HTTP Range per riprendere, controlla lo spazio libero " +
                    "prima di iniziare e non installa mai un file con un hash diverso da quello del catalogo.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun InstalledTab(
    state: ModelManagerState,
    viewModel: ModelManagerViewModel,
    onBrowse: () -> Unit,
    onDelete: (ModelVariant) -> Unit,
    onVerify: (ModelVariant) -> Unit,
    onActivate: (ModelVariant) -> Unit,
    onActivateCode: (ModelVariant) -> Unit,
    onOpenSource: (ModelVariant) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            StorageCard(
                used = state.storageUsed,
                free = state.freeSpace,
                budget = state.budget,
                device = state.deviceSummary,
            )
        }
        item {
            Text(
                "Scegli un modello per la chat e, se vuoi, un modello diverso per ITAGPT Code: " +
                    "la scelta è indipendente e sempre visibile. Il modello viene caricato al primo messaggio.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.installed.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Nessun modello installato", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Apri la libreria e scarica «Veloce» per iniziare: è il modello più leggero " +
                                "e funziona anche su dispositivi con poca RAM.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onBrowse) { Text("Vai alla libreria") }
                    }
                }
            }
        }
        items(state.installed, key = { "installed-${it.variant.id}" }) { installed ->
            val isCodeModel = state.activeCodeModelId == installed.variant.id
            Column {
                ModelCard(
                    variant = installed.variant,
                    installed = true,
                    active = state.activeModelId == installed.variant.id,
                    download = state.downloads[installed.variant.id],
                    busy = state.busyModelId == installed.variant.id,
                    advanced = true,
                    context = viewModel.contextFor(installed.variant),
                    onDownload = {},
                    onPause = {},
                    onCancel = {},
                    onDelete = { onDelete(installed.variant) },
                    onVerify = { onVerify(installed.variant) },
                    onActivate = { onActivate(installed.variant) },
                    onOpenSource = { onOpenSource(installed.variant) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isCodeModel) {
                            "ITAGPT Code: modello attivo"
                        } else {
                            "ITAGPT Code: non selezionato"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onActivateCode(installed.variant) },
                        enabled = !isCodeModel,
                    ) {
                        Text(if (isCodeModel) "In uso" else "Usa in ITAGPT Code")
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 10.dp),
    )
}

@Composable
private fun StorageCard(used: Long, free: Long, budget: Long, device: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Archiviazione e memoria", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(device, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            InfoRow("Modelli installati", ModelDownloadManager.formatBytes(used))
            InfoRow("Spazio libero", ModelDownloadManager.formatBytes(free))
            InfoRow("Budget RAM per il modello", ModelDownloadManager.formatBytes(budget))
            Spacer(Modifier.height(6.dp))
            Text(
                "Il budget è calcolato sulla RAM realmente libera. Un modello la cui RAM stimata lo " +
                    "supera non viene caricato: l'app lo spiega invece di andare in crash.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ModelCard(
    variant: ModelVariant,
    installed: Boolean,
    active: Boolean,
    download: com.bigbai.core.db.DownloadEntity?,
    busy: Boolean,
    advanced: Boolean,
    context: Int,
    label: String? = null,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onVerify: () -> Unit,
    onActivate: () -> Unit,
    onOpenSource: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label?.let { "$it · ${variant.family}" } ?: variant.family,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${variant.params} · ${variant.quant} · ${ModelDownloadManager.formatBytes(variant.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (installed) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = "Installato",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (advanced) {
                Spacer(Modifier.height(8.dp))
                InfoRow("Quantizzazione", variant.quant)
                InfoRow("Dimensione file", ModelDownloadManager.formatBytes(variant.totalBytes))
                InfoRow(
                    "RAM stimata",
                    ModelDownloadManager.formatBytes(variant.estimatedRamBytes(context)),
                )
                InfoRow("Context", "$context token (max ${variant.contextTrain})")
                InfoRow("Licenza", variant.license)
                InfoRow("Provenienza", variant.repo)
                variant.mmproj?.let { projector ->
                    InfoRow("Proiettore visivo", projector.name)
                }
            }

            val status = download?.status
            if (status == "DOWNLOADING" || status == "PAUSED" || status == "FAILED") {
                Spacer(Modifier.height(8.dp))
                val fraction = if ((download?.totalBytes ?: 0L) > 0) {
                    (download!!.bytesDownloaded.toFloat() / download.totalBytes).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val animatedFraction by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = fraction,
                    animationSpec = androidx.compose.animation.core.tween(350),
                    label = "download-progress",
                )
                LinearProgressIndicator(progress = { animatedFraction }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when (status) {
                        "DOWNLOADING" -> "Download in corso… ${(fraction * 100).toInt()}% " +
                            "(${ModelDownloadManager.formatBytes(download!!.bytesDownloaded)} / " +
                            "${ModelDownloadManager.formatBytes(download.totalBytes)})"
                        "PAUSED" -> "In pausa · ${(fraction * 100).toInt()}%"
                        else -> download?.error ?: "Download non riuscito"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status == "FAILED") MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    installed && !active -> Button(onClick = onActivate) { Text("Usa") }
                    active -> OutlinedButton(onClick = {}) { Text("Attivo") }
                    status == "DOWNLOADING" -> OutlinedButton(onClick = onPause) {
                        Icon(Icons.Outlined.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Pausa")
                    }
                    else -> Button(onClick = onDownload) {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (status == "PAUSED") "Riprendi" else "Scarica")
                    }
                }
                if (status == "DOWNLOADING" || status == "PAUSED" || status == "FAILED") {
                    TextButton(onClick = onCancel) { Text("Annulla") }
                }
                if (installed) {
                    IconButton(onClick = onVerify, enabled = !busy) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Verified, contentDescription = "Verifica integrità")
                        }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Elimina")
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onOpenSource) {
                    Icon(Icons.Outlined.Info, contentDescription = "Fonte e licenza")
                }
            }
        }
    }
}
