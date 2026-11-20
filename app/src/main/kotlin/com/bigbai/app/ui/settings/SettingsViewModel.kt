package com.bigbai.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bigbai.app.AppContainer
import com.bigbai.core.model.AppSettings
import com.bigbai.core.model.PerformanceMode
import com.bigbai.core.model.RamMode
import com.bigbai.core.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface BackupUi {
    data object Idle : BackupUi
    data object Working : BackupUi
    data class Success(val message: String) : BackupUi
    data class Error(val message: String) : BackupUi
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<AppSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val deviceSummary: String = container.deviceProfile.summary()
    val cores: Int = container.deviceProfile.cpuCores
    val totalRamBytes: Long = container.deviceProfile.totalRamBytes
    val engineVersion: String = container.engine.version()
    val capabilities: Map<String, String> = container.engine.capabilities()

    private val _backup = MutableStateFlow<BackupUi>(BackupUi.Idle)
    val backup: StateFlow<BackupUi> = _backup.asStateFlow()

    fun setTheme(value: ThemeMode) = update { it.copy(theme = value) }
    fun setLanguage(value: String) = update { it.copy(language = value) }
    fun setMemoryEnabled(value: Boolean) = update { it.copy(memoryEnabled = value) }
    fun setConfirmMemory(value: Boolean) = update { it.copy(confirmMemoryWrites = value) }
    fun setShowPerfPanel(value: Boolean) = update { it.copy(showPerfPanel = value) }
    fun setAutoTitle(value: Boolean) = update { it.copy(autoTitle = value) }
    fun setCodeNetwork(value: Boolean) = update { it.copy(codeNetworkAllowed = value) }
    fun setAdvancedUnlocked(value: Boolean) = update { it.copy(advancedUnlocked = value) }
    fun setSearchEnabled(value: Boolean) = update { it.copy(searchEnabled = value) }
    fun setSearchProvider(value: String) = update { it.copy(searchProvider = value) }
    fun setSearxEndpoint(value: String) = update { it.copy(searxEndpoint = value.trim()) }
    fun setBraveKey(value: String) = update { it.copy(braveApiKey = value.trim()) }
    fun setAutoBackup(value: Boolean) = update { it.copy(autoBackup = value) }
    fun setBackupIncludeProjects(value: Boolean) = update { it.copy(backupIncludeProjects = value) }
    fun setBackupUri(value: String?) = update { it.copy(backupUri = value) }

    fun setRamMode(value: RamMode) {
        update { it.copy(ramMode = value) }
        if (value == RamMode.ADVANCED) {
            _backup.value = BackupUi.Success(
                "Profilo avanzato attivo: il limite è il 70% della RAM fisica, ma resta sempre " +
                    "subordinato alla memoria realmente disponibile.",
            )
        }
    }

    fun setPerformanceMode(value: PerformanceMode) = update { it.copy(performanceMode = value) }

    fun setTemperature(value: Float) = update { it.copy(temperature = value.coerceIn(0f, 1.5f)) }
    fun setTopP(value: Float) = update { it.copy(topP = value.coerceIn(0.1f, 1f)) }
    fun setTopK(value: Int) = update { it.copy(topK = value.coerceIn(1, 200)) }
    fun setMaxTokens(value: Int) = update { it.copy(maxTokens = value.coerceIn(64, 4096)) }
    fun setThreads(value: Int) = update { it.copy(threads = value.coerceIn(0, cores)) }
    fun setContext(value: Int) = update { it.copy(contextOverride = value.coerceIn(0, 32768)) }
    fun setBatch(value: Int) = update { it.copy(batchSize = value.coerceIn(32, 1024)) }

    fun export(uri: Uri, password: String) {
        viewModelScope.launch {
            _backup.value = BackupUi.Working
            val includeProjects = settings.value.backupIncludeProjects
            val result = container.backup.export(uri, password.toCharArray(), includeProjects)
            _backup.value = result.fold(
                onSuccess = { info ->
                    if (info.alreadyUpToDate) {
                        BackupUi.Success("Nessuna modifica da salvare: il backup esistente è già aggiornato.")
                    } else {
                        BackupUi.Success(
                            "Backup cifrato creato: ${info.conversations} chat, ${info.messages} messaggi, " +
                                "${info.memories} ricordi.",
                        )
                    }
                },
                onFailure = { BackupUi.Error(it.message ?: "Backup non riuscito") },
            )
            container.notifications.backup(
                success = result.isSuccess,
                message = (_backup.value as? BackupUi.Success)?.message
                    ?: (_backup.value as? BackupUi.Error)?.message.orEmpty(),
            )
        }
    }

    fun restore(uri: Uri, password: String) {
        viewModelScope.launch {
            _backup.value = BackupUi.Working
            val result = container.backup.restore(uri, password.toCharArray())
            _backup.value = result.fold(
                onSuccess = {
                    BackupUi.Success(
                        "Ripristino completato: ${it.conversations} chat, ${it.messages} messaggi, " +
                            "${it.memories} ricordi, ${it.projects} progetti.",
                    )
                },
                onFailure = { BackupUi.Error(it.message ?: "Ripristino non riuscito") },
            )
        }
    }

    fun dismissBackupMessage() {
        _backup.value = BackupUi.Idle
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { container.settings.update(transform) }
    }
}
