package com.bigbai.app.ui.onboarding

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bigbai.app.AppContainer
import com.bigbai.app.service.ModelDownloadService
import com.bigbai.core.device.DeviceProfile
import com.bigbai.core.device.DeviceTier
import com.bigbai.core.model.AppSettings
import com.bigbai.llm.ModelDownloadManager
import com.bigbai.llm.ModelRole
import com.bigbai.llm.ModelTier
import com.bigbai.llm.ModelVariant
import kotlinx.coroutines.launch

private const val TOTAL_STEPS = 5

@Composable
fun OnboardingScreen(container: AppContainer, settings: AppSettings) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val downloads by container.database.downloadDao().observeAll().collectAsState(initial = emptyList())
    val profile = remember { container.deviceProfile }
    val budget = remember(profile, settings.ramMode) { profile.budgetBytes(settings.ramMode) }
    val recommended = remember(budget) { pickRecommended(container, profile, budget) }
    val download = downloads.firstOrNull { it.modelId == recommended?.id }
    val installed = remember(downloads, recommended) {
        recommended?.let { container.modelStore.installed(it) }
    }

    LaunchedEffect(download?.status, installed) {
        if (installed != null && recommended != null && settings.activeModelId == null) {
            container.settings.setActiveModel(recommended.id)
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(TOTAL_STEPS) { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(width = if (index == step) 22.dp else 7.dp, height = 7.dp)
                            .background(
                                if (index <= step) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                RoundedCornerShape(50),
                            ),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (step) {
                    0 -> WelcomeStep()
                    1 -> LanguageStep(
                        current = settings.language,
                        onSelect = { language ->
                            scope.launch { container.settings.setLanguage(language) }
                        },
                    )
                    2 -> PrivacyStep()
                    3 -> ModelStep(
                        container = container,
                        profile = profile,
                        variant = recommended,
                        downloadStatus = download?.status,
                        downloaded = download?.bytesDownloaded ?: 0,
                        total = download?.totalBytes ?: (recommended?.totalBytes ?: 0),
                        installed = installed != null,
                        onStart = {
                            recommended?.let { ModelDownloadService.start(context, it.id) }
                        },
                        onPause = {
                            recommended?.let { ModelDownloadService.pause(context, it.id) }
                        },
                    )
                    else -> ReadyStep(
                        installedName = recommended?.family,
                        installed = installed != null,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) {
                    TextButton(onClick = { step -= 1 }) { Text("Indietro") }
                }
                Spacer(Modifier.weight(1f))
                if (step < TOTAL_STEPS - 1) {
                    Button(onClick = { step += 1 }) { Text("Continua") }
                } else {
                    Button(
                        onClick = {
                            scope.launch {
                                container.settings.setOnboardingComplete(true)
                            }
                        },
                    ) { Text("Inizia a utilizzare AI") }
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    Spacer(Modifier.height(28.dp))
    Box(
        modifier = Modifier
            .size(104.dp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(30.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Psychology,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(60.dp),
        )
    }
    Spacer(Modifier.height(26.dp))
    Text(
        text = "Benvenuto in AI",
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        text = "Il tuo assistente personale che funziona interamente sul dispositivo. " +
            "Nessun account, nessun cloud obbligatorio.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun LanguageStep(current: String, onSelect: (String) -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text("Scegli la lingua", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(6.dp))
    Text(
        "Le risposte del modello e l'interfaccia useranno questa lingua.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(22.dp))
    LanguageCard("Italiano", "Italiano", current == "it") { onSelect("it") }
    Spacer(Modifier.height(10.dp))
    LanguageCard("English", "English", current == "en") { onSelect("en") }
}

@Composable
private fun LanguageCard(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun PrivacyStep() {
    Spacer(Modifier.height(16.dp))
    Icon(
        Icons.Outlined.Lock,
        contentDescription = null,
        modifier = Modifier.size(42.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(14.dp))
    Text("Privacy e sicurezza", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(16.dp))
    listOf(
        "L'inferenza avviene sul dispositivo: le conversazioni non vengono inviate a servizi esterni.",
        "Il database locale è cifrato con una chiave protetta da Android Keystore.",
        "Non servono account né login.",
        "ITAGPT Code non ha accesso alla rete se non lo autorizzi esplicitamente.",
        "Puoi disattivare la memoria in qualsiasi momento dalle impostazioni.",
    ).forEach { line ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(17.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Text(line, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ModelStep(
    container: AppContainer,
    profile: DeviceProfile,
    variant: ModelVariant?,
    downloadStatus: String?,
    downloaded: Long,
    total: Long,
    installed: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
) {
    Spacer(Modifier.height(10.dp))
    Text("Configurazione del modello", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(6.dp))
    Text(
        "Abbiamo scelto il modello più adatto a questo dispositivo. Non serve configurare nulla.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(18.dp))

    if (variant == null) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(
                "Nessun modello compatibile con la memoria disponibile. Potrai comunque usare " +
                    "ITAGPT Code e le funzioni locali.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(variant.family, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${variant.params} · ${variant.quant} · ${variant.license}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            DetailRow("Dimensione download", ModelDownloadManager.formatBytes(variant.totalBytes))
            DetailRow(
                "RAM stimata",
                ModelDownloadManager.formatBytes(
                    variant.estimatedRamBytes(
                        container.catalog.adaptiveContext(variant, profile, profile.budgetBytes(com.bigbai.core.model.RamMode.NORMAL)),
                    ),
                ),
            )
            DetailRow("Memoria del dispositivo", "${profile.totalRamBytes / DeviceProfile.GB} GB · ${profile.cpuCores} core")
            DetailRow("Fonte", variant.repo)
            Spacer(Modifier.height(14.dp))

            when {
                installed -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Modello installato e pronto", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                downloadStatus == "DOWNLOADING" || downloadStatus == "PAUSED" || downloadStatus == "FAILED" -> {
                    val fraction = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${ModelDownloadManager.formatBytes(downloaded)} / ${ModelDownloadManager.formatBytes(total)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        if (downloadStatus == "DOWNLOADING") {
                            OutlinedButton(onClick = onPause) { Text("Pausa") }
                        } else {
                            Button(onClick = onStart) {
                                Text(if (downloadStatus == "FAILED") "Riprova" else "Riprendi")
                            }
                        }
                    }
                }
                else -> {
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                        Text("Scarica ${ModelDownloadManager.formatBytes(variant.totalBytes)}")
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        "Puoi scaricare altri modelli (Veloce, Equilibrato, Intelligente, Massimo) in seguito dalla sezione Modelli.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ReadyStep(installedName: String?, installed: Boolean) {
    Spacer(Modifier.height(34.dp))
    Icon(
        Icons.Outlined.CheckCircle,
        contentDescription = null,
        modifier = Modifier.size(64.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(18.dp))
    Text("Tutto pronto", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        text = if (installed && installedName != null) {
            "«$installedName» è installato. Puoi iniziare subito."
        } else {
            "Puoi iniziare subito. Se non hai ancora scaricato un modello, potrai farlo dalla sezione Modelli."
        },
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
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

private fun pickRecommended(container: AppContainer, profile: DeviceProfile, budget: Long): ModelVariant? {
    val tier = if (profile.tier == DeviceTier.LOW) ModelTier.FAST else ModelTier.BALANCED
    val options = container.catalog.forRole(ModelRole.CHAT).filter { it.tier == tier }
    if (options.isEmpty()) return null
    val ranked = options.sortedByDescending { quantRank(it.quant) }
    return ranked.firstOrNull {
        it.fitsIn(budget, container.catalog.adaptiveContext(it, profile, budget))
    } ?: options.minByOrNull { it.totalBytes }
}

private fun quantRank(quant: String): Int = when (quant) {
    "Q6_K" -> 6
    "Q5_K_M" -> 5
    "Q4_K_M" -> 4
    "Q3_K_M" -> 3
    "Q2_K" -> 2
    else -> 0
}
