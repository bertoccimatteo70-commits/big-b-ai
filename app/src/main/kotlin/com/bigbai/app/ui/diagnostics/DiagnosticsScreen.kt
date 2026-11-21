package com.bigbai.app.ui.diagnostics

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bigbai.app.AppContainer
import com.bigbai.app.ui.AppViewModelFactory
import com.bigbai.llm.ModelDownloadManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(container: AppContainer, onBack: () -> Unit) {
    val factory = remember { AppViewModelFactory(container) }
    val viewModel: DiagnosticsViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Indietro")
                    }
                },
                title = { Text("Diagnostica") },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Aggiorna")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Section("Motore AI", Icons.Outlined.Speed) {
                Info("Stato", state.engineStatus)
                Info("Modello attivo", state.modelName ?: "nessuno")
                if (state.modelDetail.isNotBlank()) Info("Descrizione", state.modelDetail)
                Info("Context", if (state.contextSize > 0) "${state.contextSize} token" else "—")
                Info("Thread", if (state.threads > 0) state.threads.toString() else "—")
                Info("Caricamento", if (state.loadTimeMs > 0) "${state.loadTimeMs} ms" else "—")
                Info("Immagini", if (state.vision) "supportate dal modello attivo" else "non supportate")
                Info("Versione", state.engineVersion)
                Info("Backend", state.capabilities)
            }

            Section("Memoria", Icons.Outlined.Memory) {
                Info(
                    "RAM del processo",
                    "${ModelDownloadManager.formatBytes(state.ramUsedBytes)} " +
                        "(${state.ramPercent}% del totale)",
                )
                Info("RAM disponibile", ModelDownloadManager.formatBytes(state.ramAvailableBytes))
                Info("RAM totale", ModelDownloadManager.formatBytes(state.ramTotalBytes))
                LinearProgressIndicator(
                    progress = { state.ramPercent / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                state.metrics?.let { metrics ->
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    Info("Ultima generazione", "${metrics.completionTokens} token")
                    Info("Prompt", "${metrics.promptTokens} token in ${metrics.promptMs} ms")
                    Info("Velocità", "%.1f token/s".format(metrics.tokensPerSecond))
                    Info("Primo token", "${metrics.firstTokenMs} ms")
                }
            }

            Section("Ricerca semantica locale", Icons.Outlined.CheckCircle) {
                val stats = state.semantic
                Info("Modello incluso", state.bundledLabel)
                Info("Estratto e verificato", if (state.bundledInstalled) "sì" else "no")
                Info("Dimensione vettori", if ((stats?.dimension ?: 0) > 0) "${stats?.dimension} dimensioni" else "—")
                Info("Caricato in memoria", if (stats?.modelLoaded == true) "sì" else "no")
                Info("Passaggi indicizzati", (stats?.passages ?: 0).toString())
                Info("Documenti e ricordi", (stats?.sources ?: 0).toString())
                Info("Indice su disco", ModelDownloadManager.formatBytes(stats?.indexBytes ?: 0L))
                Info("Ultimo salvataggio", if (stats?.persisted == true) "verificato" else "non riuscito")
                if (stats?.recovered == true) Info("Recupero", "indice ripristinato dal backup")
                if (stats?.corrupt == true) Info("Corruzione", "file principale danneggiato, quarantena creata")
                Info(
                    "RAM per caricare",
                    "${ModelDownloadManager.formatBytes(state.embedderRequiredBytes)} richiesti · " +
                        "${ModelDownloadManager.formatBytes(state.embedderAvailableBytes)} disponibili" +
                        if (state.embedderPressure > 0) " · pressione ${state.embedderPressure}/3" else "",
                )
                stats?.failure?.let { failure -> Info("Ultimo errore", failure) }
                stats?.lastError?.let { error -> Info("Persistenza", error) }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = viewModel::verifyBundledModel,
                    enabled = !state.verifying,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.verifying) "Verifica in corso…" else "Verifica integrità SHA-256")
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = viewModel::rebuildIndex,
                    enabled = !state.rebuilding,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.rebuilding) "Ricostruzione…" else "Ricostruisci indice dai ricordi")
                }
                state.verificationResult?.let { result ->
                    Spacer(Modifier.height(4.dp))
                    Text(result, style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = viewModel::clearVerification) { Text("Chiudi") }
                }
            }

            Section("Download", Icons.Outlined.CloudDownload) {
                if (state.downloads.isEmpty()) {
                    Info("Stato", "nessun download registrato")
                } else {
                    state.downloads.take(6).forEach { download ->
                        Info(
                            download.modelId,
                            "${download.status} · ${ModelDownloadManager.formatBytes(download.bytesDownloaded)}" +
                                " / ${ModelDownloadManager.formatBytes(download.totalBytes)}",
                        )
                        download.error?.let { error -> Info("Errore", error) }
                    }
                }
            }

            Section("Dispositivo", Icons.Outlined.Memory) {
                Info("Hardware", state.deviceSummary)
                Info("Core CPU", state.cpuCores.toString())
            }

            Text(
                "Le misure provengono dal sistema operativo e dal motore nativo. " +
                    "Nessun valore è stimato o simulato.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun Section(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(150.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = if (value.contains("=") || value.contains("/")) FontFamily.Monospace else null,
            modifier = Modifier.weight(1f),
        )
    }
}
