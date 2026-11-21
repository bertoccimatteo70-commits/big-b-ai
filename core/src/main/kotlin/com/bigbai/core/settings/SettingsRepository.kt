package com.bigbai.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bigbai.core.lang.TutorLevel
import com.bigbai.core.model.AppSettings
import com.bigbai.core.model.PerformanceMode
import com.bigbai.core.model.RamMode
import com.bigbai.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "bigbai_settings")

class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs.write(next)
        }
    }

    suspend fun setOnboardingComplete(value: Boolean) = update { it.copy(onboardingComplete = value) }
    suspend fun setLanguage(value: String) = update { it.copy(language = value) }
    suspend fun setTheme(value: ThemeMode) = update { it.copy(theme = value) }
    suspend fun setMemoryEnabled(value: Boolean) = update { it.copy(memoryEnabled = value) }
    suspend fun setConfirmMemoryWrites(value: Boolean) = update { it.copy(confirmMemoryWrites = value) }
    suspend fun setRamMode(value: RamMode) = update { it.copy(ramMode = value) }
    suspend fun setPerformanceMode(value: PerformanceMode) = update { it.copy(performanceMode = value) }
    suspend fun setActiveModel(value: String?) = update { it.copy(activeModelId = value) }
    suspend fun setActiveCodeModel(value: String?) = update { it.copy(activeCodeModelId = value) }
    suspend fun setAdvancedUnlocked(value: Boolean) = update { it.copy(advancedUnlocked = value) }
    suspend fun setSearchEnabled(value: Boolean) = update { it.copy(searchEnabled = value) }
    suspend fun setBackupUri(value: String?) = update { it.copy(backupUri = value) }
    suspend fun setAutoBackup(value: Boolean) = update { it.copy(autoBackup = value) }
    suspend fun setLastBackupAt(value: Long) = update { it.copy(lastBackupAt = value) }
    suspend fun setShowPerfPanel(value: Boolean) = update { it.copy(showPerfPanel = value) }
    suspend fun setLastBackupHash(value: String?) = update { it.copy(lastBackupHash = value) }
    suspend fun setReduceMotion(value: Boolean) = update { it.copy(reduceMotion = value) }
    suspend fun setTutorEnabled(value: Boolean) = update { it.copy(tutorEnabled = value) }
    suspend fun setTutorTarget(value: String) = update { it.copy(tutorTarget = value) }
    suspend fun setTutorLevel(value: TutorLevel) = update { it.copy(tutorLevel = value) }

    companion object {
        private val KEY_ONBOARDING = booleanPreferencesKey("onboarding_complete")
        private val KEY_LANGUAGE = stringPreferencesKey("language")
        private val KEY_THEME = stringPreferencesKey("theme")
        private val KEY_MEMORY = booleanPreferencesKey("memory_enabled")
        private val KEY_CONFIRM_MEMORY = booleanPreferencesKey("confirm_memory")
        private val KEY_RAM_MODE = stringPreferencesKey("ram_mode")
        private val KEY_PERF_MODE = stringPreferencesKey("perf_mode")
        private val KEY_TEMP = floatPreferencesKey("temperature")
        private val KEY_TOP_P = floatPreferencesKey("top_p")
        private val KEY_TOP_K = intPreferencesKey("top_k")
        private val KEY_MAX_TOKENS = intPreferencesKey("max_tokens")
        private val KEY_THREADS = intPreferencesKey("threads")
        private val KEY_CONTEXT = intPreferencesKey("context_override")
        private val KEY_BATCH = intPreferencesKey("batch_size")
        private val KEY_ACTIVE_MODEL = stringPreferencesKey("active_model")
        private val KEY_ACTIVE_CODE_MODEL = stringPreferencesKey("active_code_model")
        private val KEY_AUTO_TITLE = booleanPreferencesKey("auto_title")
        private val KEY_SEARCH_ENABLED = booleanPreferencesKey("search_enabled")
        private val KEY_SEARCH_PROVIDER = stringPreferencesKey("search_provider")
        private val KEY_SEARX = stringPreferencesKey("searx_endpoint")
        private val KEY_BRAVE = stringPreferencesKey("brave_api_key")
        private val KEY_AUTO_BACKUP = booleanPreferencesKey("auto_backup")
        private val KEY_BACKUP_URI = stringPreferencesKey("backup_uri")
        private val KEY_LAST_BACKUP = longPreferencesKey("last_backup")
        private val KEY_LAST_BACKUP_HASH = stringPreferencesKey("last_backup_hash")
        private val KEY_BACKUP_PROJECTS = booleanPreferencesKey("backup_projects")
        private val KEY_ADVANCED = booleanPreferencesKey("advanced_unlocked")
        private val KEY_PERF_PANEL = booleanPreferencesKey("perf_panel")
        private val KEY_CODE_NETWORK = booleanPreferencesKey("code_network")
        private val KEY_REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        private val KEY_TUTOR_ENABLED = booleanPreferencesKey("tutor_enabled")
        private val KEY_TUTOR_TARGET = stringPreferencesKey("tutor_target")
        private val KEY_TUTOR_LEVEL = stringPreferencesKey("tutor_level")

        private fun Preferences.toSettings(): AppSettings = AppSettings(
            onboardingComplete = this[KEY_ONBOARDING] ?: false,
            language = this[KEY_LANGUAGE] ?: "system",
            theme = runCatching { ThemeMode.valueOf(this[KEY_THEME] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
            memoryEnabled = this[KEY_MEMORY] ?: true,
            confirmMemoryWrites = this[KEY_CONFIRM_MEMORY] ?: true,
            ramMode = runCatching { RamMode.valueOf(this[KEY_RAM_MODE] ?: "NORMAL") }.getOrDefault(RamMode.NORMAL),
            performanceMode = runCatching { PerformanceMode.valueOf(this[KEY_PERF_MODE] ?: "BALANCED") }
                .getOrDefault(PerformanceMode.BALANCED),
            temperature = this[KEY_TEMP] ?: 0.7f,
            topP = this[KEY_TOP_P] ?: 0.95f,
            topK = this[KEY_TOP_K] ?: 40,
            maxTokens = this[KEY_MAX_TOKENS] ?: 1024,
            threads = this[KEY_THREADS] ?: 0,
            contextOverride = this[KEY_CONTEXT] ?: 0,
            batchSize = this[KEY_BATCH] ?: 256,
            activeModelId = this[KEY_ACTIVE_MODEL],
            activeCodeModelId = this[KEY_ACTIVE_CODE_MODEL],
            autoTitle = this[KEY_AUTO_TITLE] ?: true,
            searchEnabled = this[KEY_SEARCH_ENABLED] ?: false,
            searchProvider = this[KEY_SEARCH_PROVIDER] ?: "searxng",
            searxEndpoint = this[KEY_SEARX] ?: "",
            braveApiKey = this[KEY_BRAVE] ?: "",
            autoBackup = this[KEY_AUTO_BACKUP] ?: false,
            backupUri = this[KEY_BACKUP_URI],
            lastBackupAt = this[KEY_LAST_BACKUP] ?: 0L,
            lastBackupHash = this[KEY_LAST_BACKUP_HASH],
            backupIncludeProjects = this[KEY_BACKUP_PROJECTS] ?: false,
            advancedUnlocked = this[KEY_ADVANCED] ?: false,
            showPerfPanel = this[KEY_PERF_PANEL] ?: false,
            codeNetworkAllowed = this[KEY_CODE_NETWORK] ?: false,
            reduceMotion = this[KEY_REDUCE_MOTION] ?: false,
            tutorEnabled = this[KEY_TUTOR_ENABLED] ?: false,
            tutorTarget = this[KEY_TUTOR_TARGET] ?: "es",
            tutorLevel = runCatching { TutorLevel.valueOf(this[KEY_TUTOR_LEVEL] ?: "BASE") }
                .getOrDefault(TutorLevel.BASE),
        )

        private fun androidx.datastore.preferences.core.MutablePreferences.write(s: AppSettings) {
            this[KEY_ONBOARDING] = s.onboardingComplete
            this[KEY_LANGUAGE] = s.language
            this[KEY_THEME] = s.theme.name
            this[KEY_MEMORY] = s.memoryEnabled
            this[KEY_CONFIRM_MEMORY] = s.confirmMemoryWrites
            this[KEY_RAM_MODE] = s.ramMode.name
            this[KEY_PERF_MODE] = s.performanceMode.name
            this[KEY_TEMP] = s.temperature
            this[KEY_TOP_P] = s.topP
            this[KEY_TOP_K] = s.topK
            this[KEY_MAX_TOKENS] = s.maxTokens
            this[KEY_THREADS] = s.threads
            this[KEY_CONTEXT] = s.contextOverride
            this[KEY_BATCH] = s.batchSize
            if (s.activeModelId != null) this[KEY_ACTIVE_MODEL] = s.activeModelId else remove(KEY_ACTIVE_MODEL)
            if (s.activeCodeModelId != null) {
                this[KEY_ACTIVE_CODE_MODEL] = s.activeCodeModelId
            } else {
                remove(KEY_ACTIVE_CODE_MODEL)
            }
            this[KEY_AUTO_TITLE] = s.autoTitle
            this[KEY_SEARCH_ENABLED] = s.searchEnabled
            this[KEY_SEARCH_PROVIDER] = s.searchProvider
            this[KEY_SEARX] = s.searxEndpoint
            this[KEY_BRAVE] = s.braveApiKey
            this[KEY_AUTO_BACKUP] = s.autoBackup
            if (s.backupUri != null) this[KEY_BACKUP_URI] = s.backupUri else remove(KEY_BACKUP_URI)
            this[KEY_LAST_BACKUP] = s.lastBackupAt
            if (s.lastBackupHash != null) this[KEY_LAST_BACKUP_HASH] = s.lastBackupHash else remove(KEY_LAST_BACKUP_HASH)
            this[KEY_BACKUP_PROJECTS] = s.backupIncludeProjects
            this[KEY_ADVANCED] = s.advancedUnlocked
            this[KEY_PERF_PANEL] = s.showPerfPanel
            this[KEY_CODE_NETWORK] = s.codeNetworkAllowed
            this[KEY_REDUCE_MOTION] = s.reduceMotion
            this[KEY_TUTOR_ENABLED] = s.tutorEnabled
            this[KEY_TUTOR_TARGET] = s.tutorTarget
            this[KEY_TUTOR_LEVEL] = s.tutorLevel.name
        }
    }
}
