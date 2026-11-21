package com.bigbai.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bigbai.app.AppContainer
import com.bigbai.app.ui.AppViewModelFactory
import com.bigbai.app.ui.LocalAppDrawer
import com.bigbai.core.lang.LanguageCoach
import com.bigbai.core.lang.TutorLevel
import com.bigbai.core.model.PerformanceMode
import com.bigbai.core.model.RamMode
import com.bigbai.core.model.ThemeMode
import com.bigbai.llm.ModelDownloadManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface PasswordAction {
    data class Export(val uri: Uri) : PasswordAction
    data class Restore(val uri: Uri) : PasswordAction
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val factory = remember { AppViewModelFactory(container) }
    val viewModel: SettingsViewModel = viewModel(factory = factory)
    val settings by viewModel.settings.collectAsState()
    val backup by viewModel.backup.collectAsState()

    val openDrawer = LocalAppDrawer.current
    var passwordAction by remember { mutableStateOf<PasswordAction?>(null) }
    var advanced by remember { mutableStateOf(settings.advancedUnlocked) }

    // The settings flow starts with defaults and emits the persisted values a
    // moment later; without this the advanced switch could stay off for a user
    // who had already unlocked it.
    androidx.compose.runtime.LaunchedEffect(settings.advancedUnlocked) {
        advanced = settings.advancedUnlocked
    }

    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) passwordAction = PasswordAction.Export(uri)
    }
    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) passwordAction = PasswordAction.Restore(uri)
    }

    passwordAction?.let { action ->
        PasswordDialog(
            title = if (action is PasswordAction.Export) "Password del backup" else "Password del backup",
            confirmLabel = if (action is PasswordAction.Export) "Crea backup" else "Ripristina",
            onDismiss = { passwordAction = null },
            onConfirm = { password ->
                passwordAction = null
                when (action) {
                    is PasswordAction.Export -> viewModel.export(action.uri, password)
                    is PasswordAction.Restore -> viewModel.restore(action.uri, password)
                }
            },
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
                title = { Text("Impostazioni") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val state = backup) {
                is BackupUi.Success -> Banner(state.message, false) { viewModel.dismissBackupMessage() }
                is BackupUi.Error -> Banner(state.message, true) { viewModel.dismissBackupMessage() }
                BackupUi.Working -> Banner("Operazione in corso…", false) { }
                BackupUi.Idle -> Unit
            }

            SectionCard("Aspetto e lingua") {
                Text("Tema", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Sistema", settings.theme == ThemeMode.SYSTEM) { viewModel.setTheme(ThemeMode.SYSTEM) }
                    Chip("Chiaro", settings.theme == ThemeMode.LIGHT) { viewModel.setTheme(ThemeMode.LIGHT) }
                    Chip("Scuro", settings.theme == ThemeMode.DARK) { viewModel.setTheme(ThemeMode.DARK) }
                }
                Spacer(Modifier.height(10.dp))
                SliderRow(
                    label = "Dimensione del testo",
                    value = settings.fontScale,
                    range = 0.85f..1.30f,
                    format = { "${(it * 100).toInt()}%" },
                    onChange = { viewModel.setFontScale(it) },
                )
                Spacer(Modifier.height(10.dp))
                Text("Lingua delle risposte", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Italiano", settings.language == "it") { viewModel.setLanguage("it") }
                    Chip("English", settings.language == "en") { viewModel.setLanguage("en") }
                }
                Spacer(Modifier.height(10.dp))
                SwitchRow(
                    "Riduci animazioni",
                    "Transizioni brevi o assenti, utile su dispositivi meno potenti.",
                    settings.reduceMotion,
                ) { viewModel.setReduceMotion(it) }
            }

            SectionCard("Tutor di lingua") {
                SwitchRow(
                    "Tutor di lingua",
                    "Lezioni, correzioni e vocaboli partendo dall'italiano. Il lessico appreso resta in memoria.",
                    settings.tutorEnabled,
                ) { viewModel.setTutorEnabled(it) }
                if (settings.tutorEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Text("Lingua da imparare", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        LanguageCoach.languages.forEach { language ->
                            Chip(
                                label = "${language.italianName} · ${language.nativeName}",
                                selected = settings.tutorTarget == language.code,
                            ) { viewModel.setTutorTarget(language.code) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Livello", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TutorLevel.entries.forEach { level ->
                            Chip(level.label, settings.tutorLevel == level) { viewModel.setTutorLevel(level) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Il modello non viene riaddestrato: l'app gli fornisce un glossario italiano → " +
                            "lingua scelta e salva le righe «Lingua:XX» che il modello produce nel lessico " +
                            "personale (categoria ${LanguageCoach.categoryName(settings.tutorTarget)} della Memoria). " +
                            "Ogni lezione successiva riparte da quel lessico.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard("Modello e prestazioni") {
                Text("Profilo RAM", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Normale (50%)", settings.ramMode == RamMode.NORMAL) { viewModel.setRamMode(RamMode.NORMAL) }
                    Chip("Avanzato (70%)", settings.ramMode == RamMode.ADVANCED) { viewModel.setRamMode(RamMode.ADVANCED) }
                }
                Text(
                    "Il profilo avanzato è un limite progettuale: il motore usa comunque solo la memoria " +
                        "realmente disponibile e rifiuta i modelli che non entrano.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text("Profilo prestazioni", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Risparmio", settings.performanceMode == PerformanceMode.SAVER) {
                        viewModel.setPerformanceMode(PerformanceMode.SAVER)
                    }
                    Chip("Bilanciato", settings.performanceMode == PerformanceMode.BALANCED) {
                        viewModel.setPerformanceMode(PerformanceMode.BALANCED)
                    }
                    Chip("Prestazioni", settings.performanceMode == PerformanceMode.PERFORMANCE) {
                        viewModel.setPerformanceMode(PerformanceMode.PERFORMANCE)
                    }
                }
                Text(
                    "Il profilo determina il numero di thread CPU usati dal motore.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                SwitchRow(
                    "Mostra pannello prestazioni",
                    "Token/s, RAM, thread e tempi misurati durante la generazione.",
                    settings.showPerfPanel,
                ) { viewModel.setShowPerfPanel(it) }
            }

            SectionCard("Generazione") {
                SliderRow(
                    "Temperatura",
                    settings.temperature,
                    0f..1.5f,
                    format = { "%.2f".format(Locale.US, it) },
                ) { viewModel.setTemperature(it) }
                SliderRow(
                    "Top-p",
                    settings.topP,
                    0.1f..1f,
                    format = { "%.2f".format(Locale.US, it) },
                ) { viewModel.setTopP(it) }
                SliderRow(
                    "Top-k",
                    settings.topK.toFloat(),
                    1f..200f,
                    format = { it.toInt().toString() },
                ) { viewModel.setTopK(it.toInt()) }
                SliderRow(
                    "Token massimi per risposta",
                    settings.maxTokens.toFloat(),
                    64f..4096f,
                    format = { it.toInt().toString() },
                ) { viewModel.setMaxTokens(it.toInt()) }
                SliderRow(
                    "Repetition penalty",
                    settings.repeatPenalty,
                    1f..1.5f,
                    format = { "%.2f".format(Locale.US, it) },
                ) { viewModel.setRepeatPenalty(it) }
                SliderRow(
                    "Min-p",
                    settings.minP,
                    0f..0.2f,
                    format = { if (it <= 0.001f) "off" else "%.3f".format(Locale.US, it) },
                ) { viewModel.setMinP(it) }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Impostazioni avanzate", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "Thread, context length e batch size.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = advanced,
                        onCheckedChange = {
                            advanced = it
                            viewModel.setAdvancedUnlocked(it)
                        },
                    )
                }
                if (advanced) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    SliderRow(
                        "Thread CPU (0 = automatico)",
                        settings.threads.toFloat(),
                        0f..viewModel.cores.toFloat(),
                        format = { if (it.toInt() == 0) "auto" else it.toInt().toString() },
                    ) { viewModel.setThreads(it.toInt()) }
                    SliderRow(
                        "Context length (0 = adattivo)",
                        settings.contextOverride.toFloat(),
                        0f..32768f,
                        format = { if (it.toInt() == 0) "adattivo" else it.toInt().toString() },
                    ) { viewModel.setContext(it.toInt()) }
                    SliderRow(
                        "Batch size",
                        settings.batchSize.toFloat(),
                        32f..1024f,
                        format = { it.toInt().toString() },
                    ) { viewModel.setBatch(it.toInt()) }
                    InfoRow("Accelerazione GPU", "non disponibile in questa build (solo CPU)")
                    InfoRow("Motore", viewModel.engineVersion)
                }
            }

            SectionCard("Memoria") {
                SwitchRow(
                    "Memoria persistente",
                    "Salva fatti durevoli sull'utente, sempre con conferma.",
                    settings.memoryEnabled,
                ) { viewModel.setMemoryEnabled(it) }
                SwitchRow(
                    "Chiedi conferma prima di salvare",
                    "Mostra una proposta prima di ogni nuovo ricordo.",
                    settings.confirmMemoryWrites,
                ) { viewModel.setConfirmMemory(it) }
                SwitchRow(
                    "Titoli automatici",
                    "Genera il titolo della conversazione dal primo messaggio.",
                    settings.autoTitle,
                ) { viewModel.setAutoTitle(it) }
                SwitchRow(
                    "Ricerca semantica locale",
                    "Usa il modello di embedding incluso per trovare i passaggi pertinenti di documenti e ricordi. Tutto sul dispositivo.",
                    settings.semanticMemoryEnabled,
                ) { viewModel.setSemanticMemory(it) }
            }

            SectionCard("Ricerca web (opzionale)") {
                SwitchRow(
                    "Abilita ricerca web",
                    "Richiede una configurazione e una connessione attiva.",
                    settings.searchEnabled,
                ) { viewModel.setSearchEnabled(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("SearXNG", settings.searchProvider == "searxng") { viewModel.setSearchProvider("searxng") }
                    Chip("Brave", settings.searchProvider == "brave") { viewModel.setSearchProvider("brave") }
                }
                if (settings.searchProvider == "searxng") {
                    OutlinedTextField(
                        value = settings.searxEndpoint,
                        onValueChange = viewModel::setSearxEndpoint,
                        label = { Text("Endpoint SearXNG (https://…)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                } else {
                    OutlinedTextField(
                        value = settings.braveApiKey,
                        onValueChange = viewModel::setBraveKey,
                        label = { Text("Chiave API Brave Search") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
                Text(
                    "La ricerca parte solo quando serve un'informazione aggiornata. Offline viene " +
                        "segnalata come non disponibile.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard("Backup cifrato") {
                Text(
                    "I backup sono cifrati con una password scelta da te (PBKDF2 + AES-256-GCM) e " +
                        "possono essere ripristinati su un altro dispositivo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val name = "ITAGPT-backup-" +
                                SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".bkb"
                            createBackup.launch(name)
                        },
                    ) { Text("Crea backup") }
                    OutlinedButton(onClick = { openBackup.launch(arrayOf("*/*")) }) {
                        Text("Ripristina")
                    }
                }
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    "Includi dati dei progetti",
                    "Memoria, chat e configurazioni di ITAGPT Code.",
                    settings.backupIncludeProjects,
                ) { viewModel.setBackupIncludeProjects(it) }
                SwitchRow(
                    "Backup automatico",
                    "Dopo modifiche importanti, solo quando c'è una destinazione configurata.",
                    settings.autoBackup,
                ) { viewModel.setAutoBackup(it) }
                Text(
                    text = "Ultimo backup: " + if (settings.lastBackupAt > 0) {
                        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(settings.lastBackupAt))
                    } else {
                        "mai"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard("ITAGPT Code") {
                SwitchRow(
                    "Consenti rete nei progetti",
                    "Disattivato per impostazione predefinita. Riguarda solo la sandbox dei progetti.",
                    settings.codeNetworkAllowed,
                ) { viewModel.setCodeNetwork(it) }
                Text(
                    "L'accesso ai file è limitato alle cartelle autorizzate tramite il selettore di sistema.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard("Dispositivo e privacy") {
                InfoRow("Dispositivo", viewModel.deviceSummary)
                InfoRow("RAM totale", ModelDownloadManager.formatBytes(viewModel.totalRamBytes))
                InfoRow("Core CPU", viewModel.cores.toString())
                InfoRow("Motore AI", viewModel.engineVersion)
                InfoRow("Backend", viewModel.capabilities["backend"] ?: "cpu")
                InfoRow(
                    "GPU offload",
                    if (viewModel.capabilities["gpu_offload"] == "true") "disponibile" else "non disponibile",
                )
                InfoRow("Versione app", com.bigbai.app.BuildConfig.VERSION_NAME)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Nessun account, nessun invio automatico delle conversazioni. Il database è cifrato " +
                        "con SQLCipher e la chiave è protetta da Android Keystore.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
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
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
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

@Composable
private fun Banner(message: String, error: Boolean, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
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

@Composable
private fun PasswordDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = password.length >= 8 && password == confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    "Scegli una password di almeno 8 caratteri. Serve per ripristinare il backup su " +
                        "qualsiasi dispositivo: senza di essa i dati non sono recuperabili.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("Conferma password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = valid) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}
