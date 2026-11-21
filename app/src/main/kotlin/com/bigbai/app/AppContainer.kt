package com.bigbai.app

import android.app.Application
import com.bigbai.code.GitManager
import com.bigbai.code.JsSandbox
import com.bigbai.code.ProjectFiles
import com.bigbai.code.ProjectRepository
import com.bigbai.core.backup.BackupManager
import com.bigbai.core.db.AppDatabase
import com.bigbai.core.device.DeviceProfile
import com.bigbai.core.device.MemorySampler
import com.bigbai.core.pdf.PdfExtractor
import com.bigbai.core.repo.ChatRepository
import com.bigbai.core.repo.MemoryRepository
import com.bigbai.core.search.WebSearchClient
import com.bigbai.core.security.CryptoManager
import com.bigbai.core.model.GenerationMetrics
import com.bigbai.core.settings.SettingsRepository
import com.bigbai.app.ocr.OcrEngine
import com.bigbai.llm.EmbeddingEngine
import com.bigbai.llm.EmbeddingModelInstaller
import com.bigbai.llm.LlamaEngine
import com.bigbai.llm.ModelCatalog
import com.bigbai.llm.ModelDownloadManager
import com.bigbai.llm.ModelStore
import com.bigbai.llm.SemanticRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Manual dependency container.
 *
 * Hilt would add a compiler and a second build pipeline for no benefit at this
 * size; every dependency here has exactly one lifetime, and lazy initialisation
 * keeps the database, Keystore and native library off the application startup
 * path (startup time is a first-class requirement).
 */
class AppContainer(private val app: Application) {

    val context: android.content.Context get() = app

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val deviceProfile: DeviceProfile by lazy { DeviceProfile.read(app) }
    val memorySampler: MemorySampler by lazy { MemorySampler(app) }
    val crypto: CryptoManager by lazy { CryptoManager(app) }
    val settings: SettingsRepository by lazy { SettingsRepository(app) }

    val database: AppDatabase by lazy {
        AppDatabase.open(app, crypto.databasePassphrase())
    }

    val chat: ChatRepository by lazy { ChatRepository(database) }
    val memory: MemoryRepository by lazy { MemoryRepository(database) }

    val catalog: ModelCatalog by lazy { ModelCatalog.load(app) }
    val modelStore: ModelStore by lazy { ModelStore(app) }
    val downloader: ModelDownloadManager by lazy {
        ModelDownloadManager(app, modelStore, database.downloadDao())
    }
    val engine: LlamaEngine by lazy { LlamaEngine(app, memorySampler) }

    /** Last measured generation, shared with the diagnostics screen. */
    val lastMetrics = MutableStateFlow<GenerationMetrics?>(null)

    /**
     * Optional embedding model. ITAGPT 2.1 ships no model in the APK: the user
     * imports this file from local storage in Settings and it is verified by
     * size and SHA-256 before semantic retrieval can use it.
     */
    val embeddingModels: EmbeddingModelInstaller by lazy { EmbeddingModelInstaller(app) }

    /** Offline OCR for images and scanned PDFs (bundled ML Kit Latin model). */
    val ocr: OcrEngine by lazy { OcrEngine(app) }

    val embedder: EmbeddingEngine by lazy { EmbeddingEngine(embeddingModels, memorySampler) }
    val semantic: SemanticRepository by lazy { SemanticRepository(app, embedder) }

    val pdf: PdfExtractor by lazy { PdfExtractor(app) }
    val search: WebSearchClient by lazy { WebSearchClient(app) }

    val projectFiles: ProjectFiles by lazy { ProjectFiles(app) }
    val projects: ProjectRepository by lazy { ProjectRepository(database, projectFiles) }
    val git: GitManager by lazy { GitManager(app) }
    val sandbox: JsSandbox by lazy { JsSandbox(app) }

    val backup: BackupManager by lazy { BackupManager(app, database, settings) }
    val notifications: Notifications by lazy { Notifications(app) }

    suspend fun budgetBytes(): Long {
        val current = settings.current()
        return deviceProfile.budgetBytes(current.ramMode)
    }

    fun isLowEndDevice(): Boolean = deviceProfile.tier == com.bigbai.core.device.DeviceTier.LOW
}
