package com.bigbai.app.ui.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bigbai.app.AppContainer
import com.bigbai.app.BuildConfig
import com.bigbai.app.ui.LocalAppDrawer
import com.bigbai.llm.ModelDownloadManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(container: AppContainer, onBack: () -> Unit) {
    val openDrawer = LocalAppDrawer.current
    val context = LocalContext.current
    val capabilities = remember { container.engine.capabilities() }
    val engineVersion = remember { container.engine.version() }
    val device = remember { container.deviceProfile.summary() }
    val storageUsed = remember { container.modelStore.totalBytes() }
    val freeSpace = remember { container.modelStore.freeSpaceBytes() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = openDrawer) {
                        Icon(Icons.Outlined.Menu, contentDescription = "Menu")
                    }
                },
                title = { Text("Informazioni sull'app") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        "BIG B AI",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Versione ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) · variante debug",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Assistente AI locale per Android: modelli GGUF eseguiti sul dispositivo tramite " +
                            "llama.cpp, memoria persistente cifrata e un ambiente di sviluppo integrato.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            InfoCard("Motore AI") {
                InfoRow("llama.cpp", engineVersion)
                InfoRow("Backend", capabilities["backend"] ?: "cpu")
                InfoRow(
                    "GPU offload",
                    if (capabilities["gpu_offload"] == "true") "disponibile" else "non disponibile (CPU)",
                )
                InfoRow("ABI native", "arm64-v8a, armeabi-v7a, x86_64")
                InfoRow("Libreria nativa", "libbigbai.so (llama.cpp + mtmd)")
            }

            InfoCard("Dispositivo e spazio") {
                InfoRow("Dispositivo", device)
                InfoRow("Core CPU", container.deviceProfile.cpuCores.toString())
                InfoRow(
                    "RAM totale",
                    ModelDownloadManager.formatBytes(container.deviceProfile.totalRamBytes),
                )
                InfoRow("Modelli installati", ModelDownloadManager.formatBytes(storageUsed))
                InfoRow("Spazio libero", ModelDownloadManager.formatBytes(freeSpace))
            }

            InfoCard("Privacy") {
                Text(
                    "• Nessun account e nessuna telemetria.\n" +
                        "• Le conversazioni restano nel database locale, cifrato con SQLCipher; la chiave è " +
                        "protetta da Android Keystore.\n" +
                        "• Le uniche connessioni di rete sono il download dei modelli scelti e la ricerca web " +
                        "se la abiliti.\n" +
                        "• BIG B Code accede solo alle cartelle che autorizzi.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            InfoCard("Font") {
                Text(
                    "Il carattere dell'interfaccia e dei messaggi è Tinos (Apache-2.0), metricamente " +
                        "compatibile con Times New Roman: stesse larghezze e proporzioni, ma distribuibile " +
                        "legalmente. Times New Roman è un font proprietario di Monotype e non può essere " +
                        "incluso in un APK. Il codice usa un font monospaziato di sistema.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            InfoCard("Componenti open source") {
                Text(
                    "llama.cpp e ggml (MIT) · AndroidX e Jetpack Compose (Apache-2.0) · Room (Apache-2.0) · " +
                        "SQLCipher (BSD) · OkHttp (Apache-2.0) · PDFBox-Android (Apache-2.0) · JGit (EPL-2.0) · " +
                        "Tinos (Apache-2.0).\n\n" +
                        "I modelli non sono inclusi nell'APK: si scaricano dalle fonti originali indicate nel " +
                        "catalogo e vengono verificati con SHA-256.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/bertoccimatteo70-commits/big-b-ai"),
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Apri il repository del progetto") }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
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
        Text(value, style = MaterialTheme.typography.labelSmall)
    }
}
