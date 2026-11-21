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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bigbai.app.AppContainer
import com.bigbai.app.ui.AppViewModelFactory
import com.bigbai.llm.ModelDownloadManager
import com.bigbai.llm.ModelRole
import com.bigbai.llm.ModelTier
import com.bigbai.llm.ModelVariant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(container: AppContainer, onBack: () -> Unit) {
    val factory = remember { AppViewModelFactory(container) }
    val viewModel: ModelManagerViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
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

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Indietro")
                    }
                },
                title = { Text("Modelli") },
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
                StorageCard(
                    used = state.storageUsed,
                    free = state.freeSpace,
                    budget = state.budget,
                    device = state.deviceSummary,
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
            state.message?.let { message ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = viewModel::dismissMessage) { Text("Chiudi") }
                        }
                    }
                }
            }

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
                        onDownload = { viewModel.download(variant) },
                        onPause = { viewModel.pause(variant) },
                        onDelete = { pendingDelete = variant },
                        onVerify = { viewModel.verify(variant) },
                        onActivate = { viewModel.setActive(variant) },
                        onOpenSource = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(variant.sourceUrl))) }
                        },
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
                            onDownload = { viewModel.download(variant) },
                            onPause = { viewModel.pause(variant) },
                            onDelete = { pendingDelete = variant },
                            onVerify = { viewModel.verify(variant) },
                            onActivate = { viewModel.setActive(variant) },
                            onOpenSource = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(variant.sourceUrl))) }
                            },
                        )
                    }
                }
            }

            item { SectionTitle("BIG B Code") }
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
                    onDownload = { viewModel.download(variant) },
                    onPause = { viewModel.pause(variant) },
                    onDelete = { pendingDelete = variant },
                    onVerify = { viewModel.verify(variant) },
                    onActivate = { viewModel.setActive(variant) },
                    onOpenSource = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(variant.sourceUrl))) }
                    },
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
                    onDownload = { viewModel.download(variant) },
                    onPause = { viewModel.pause(variant) },
                    onDelete = { pendingDelete = variant },
                    onVerify = { viewModel.verify(variant) },
                    onActivate = { viewModel.setActive(variant) },
                    onOpenSource = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(variant.sourceUrl))) }
                    },
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
                val fraction = if ((download.totalBytes) > 0) {
                    (download.bytesDownloaded.toFloat() / download.totalBytes).coerceIn(0f, 1f)
                } else {
                    0f
                }
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when (status) {
                        "DOWNLOADING" -> "Download in corso… ${(fraction * 100).toInt()}%"
                        "PAUSED" -> "In pausa · ${(fraction * 100).toInt()}%"
                        else -> download.error ?: "Download non riuscito"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status == "FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
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
