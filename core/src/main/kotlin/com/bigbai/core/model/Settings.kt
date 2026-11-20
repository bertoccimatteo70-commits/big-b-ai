package com.bigbai.core.model

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Design limits, not guarantees: the engine still clamps to the memory that
 *  Android actually reports as available. */
enum class RamMode(val fraction: Double) {
    NORMAL(0.50),
    ADVANCED(0.70),
}

enum class PerformanceMode { SAVER, BALANCED, PERFORMANCE }

data class AppSettings(
    val onboardingComplete: Boolean = false,
    val language: String = "system",
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val memoryEnabled: Boolean = true,
    val confirmMemoryWrites: Boolean = true,
    val ramMode: RamMode = RamMode.NORMAL,
    val performanceMode: PerformanceMode = PerformanceMode.BALANCED,
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val maxTokens: Int = 1024,
    val threads: Int = 0,
    val contextOverride: Int = 0,
    val batchSize: Int = 256,
    val activeModelId: String? = null,
    val activeCodeModelId: String? = null,
    val autoTitle: Boolean = true,
    val searchEnabled: Boolean = false,
    val searchProvider: String = "searxng",
    val searxEndpoint: String = "",
    val braveApiKey: String = "",
    val autoBackup: Boolean = false,
    val backupUri: String? = null,
    val lastBackupAt: Long = 0L,
    val lastBackupHash: String? = null,
    val backupIncludeProjects: Boolean = false,
    val advancedUnlocked: Boolean = false,
    val showPerfPanel: Boolean = false,
    val codeNetworkAllowed: Boolean = false,
    val reduceMotion: Boolean = false,
)
