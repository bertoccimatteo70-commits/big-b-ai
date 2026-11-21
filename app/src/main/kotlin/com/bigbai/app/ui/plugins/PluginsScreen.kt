package com.bigbai.app.ui.plugins

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bigbai.app.AppContainer
import com.bigbai.app.ui.AppViewModelFactory
import com.bigbai.app.ui.LocalAppDrawer
import com.bigbai.app.ui.settings.SettingsViewModel
import com.bigbai.core.lang.LanguageCoach
import com.bigbai.core.lang.TutorLevel

/**
 * Built-in integrations. This screen only lists what the app really implements:
 * no installable third-party plugins are advertised, because there is no plugin
 * runtime in this version.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(container: AppContainer, onBack: () -> Unit) {
    val factory = remember { AppViewModelFactory(container) }
    val viewModel: SettingsViewModel = viewModel(factory = factory)
    val settings by viewModel.settings.collectAsState()
    val openDrawer = LocalAppDrawer.current

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = openDrawer) {
                        Icon(Icons.Outlined.Menu, contentDescription = "Menu")
                    }
                },
                title = { Text("Plugin e integrazioni") },
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
            Text(
                "Integrazioni incluse nell'app. Non esiste un runtime per plugin di terze parti: " +
                    "questa schermata configura ciò che è realmente disponibile.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            IntegrationCard("Ricerca web", "Cerca sul web quando la risposta richiede informazioni aggiornate.") {
                SwitchRow(
                    "Abilita ricerca web",
                    "Disattivata per default; richiede una connessione e una configurazione.",
                    settings.searchEnabled,
                ) { viewModel.setSearchEnabled(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = settings.searchProvider == "searxng",
                        onClick = { viewModel.setSearchProvider("searxng") },
                        label = { Text("SearXNG") },
                    )
                    FilterChip(
                        selected = settings.searchProvider == "brave",
                        onClick = { viewModel.setSearchProvider("brave") },
                        label = { Text("Brave") },
                    )
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
            }

            IntegrationCard("Tutor di lingua", "Lezioni italiano → lingua scelta, con lessico personale persistente.") {
                SwitchRow(
                    "Abilita tutor",
                    "Aggiunge il tutor alla chat e salva i vocaboli in memoria.",
                    settings.tutorEnabled,
                ) { viewModel.setTutorEnabled(it) }
                if (settings.tutorEnabled) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        LanguageCoach.languages.forEach { language ->
                            FilterChip(
                                selected = settings.tutorTarget == language.code,
                                onClick = { viewModel.setTutorTarget(language.code) },
                                label = { Text(language.italianName) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TutorLevel.entries.forEach { level ->
                            FilterChip(
                                selected = settings.tutorLevel == level,
                                onClick = { viewModel.setTutorLevel(level) },
                                label = { Text(level.label) },
                            )
                        }
                    }
                }
            }

            IntegrationCard(
                "Backup cifrato",
                "Esporta chat, ricordi e progetti in un file .bkb protetto da password (AES-256-GCM).",
            ) {
                SwitchRow(
                    "Backup automatico",
                    "Richiede una destinazione configurata nelle impostazioni.",
                    settings.autoBackup,
                ) { viewModel.setAutoBackup(it) }
            }

            IntegrationCard(
                "Sandbox di BIG B Code",
                "L'anteprima HTML gira in una WebView usa-e-getta con rete bloccata.",
            ) {
                SwitchRow(
                    "Consenti rete nella sandbox",
                    "Riguarda solo i progetti; resta disattivato per default.",
                    settings.codeNetworkAllowed,
                ) { viewModel.setCodeNetwork(it) }
            }
        }
    }
}

@Composable
private fun IntegrationCard(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
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
