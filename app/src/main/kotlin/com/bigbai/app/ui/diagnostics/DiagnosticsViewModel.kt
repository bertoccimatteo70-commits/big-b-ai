package com.bigbai.app.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bigbai.app.AppContainer
import com.bigbai.core.db.DownloadEntity
import com.bigbai.core.model.GenerationMetrics
import com.bigbai.llm.BundledModels
import com.bigbai.llm.LlamaEngine
import com.bigbai.llm.SemanticRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiagnosticsUiState(
    val engineStatus: String = "Inattivo",
    val modelName: String? = null,
    val modelDetail: String = "",
    val contextSize: Int = 0,
    val threads: Int = 0,
    val loadTimeMs: Long = 0,
    val vision: Boolean = false,
    val ramUsedBytes: Long = 0,
    val ramAvailableBytes: Long = 0,
    val ramTotalBytes: Long = 0,
    val ramPercent: Int = 0,
    val cpuCores: Int = 0,
    val metrics: GenerationMetrics? = null,
    val semantic: SemanticRepository.Stats? = null,
    val downloads: List<DownloadEntity> = emptyList(),
    val engineVersion: String = "",
    val capabilities: String = "",
    val deviceSummary: String = "",
    val bundledLabel: String = "",
    val bundledInstalled: Boolean = false,
    val verifying: Boolean = false,
    val verificationResult: String? = null,
)

/**
 * Read-only diagnostics built from the same objects the engine uses.
 *
 * Nothing here is estimated: memory comes from ActivityManager, timings from
 * llama.cpp, download state from the database and the embedding index from its
 * own statistics. The screen is the honest answer to "what is the app doing?".
 */
class DiagnosticsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(
        DiagnosticsUiState(
            engineVersion = container.engine.version(),
            capabilities = container.engine.capabilities().entries.joinToString(" · ") { "${it.key}=${it.value}" },
            deviceSummary = container.deviceProfile.summary(),
            cpuCores = container.deviceProfile.cpuCores,
            ramTotalBytes = container.deviceProfile.totalRamBytes,
            bundledLabel = BundledModels.EMBEDDING.label,
            bundledInstalled = container.bundledModels.isInstalled(BundledModels.EMBEDDING),
        ),
    )
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.engine.state.collect { engineState ->
                when (engineState) {
                    is LlamaEngine.EngineState.Idle -> _state.update {
                        it.copy(engineStatus = "Inattivo", modelName = null, modelDetail = "", contextSize = 0, threads = 0)
                    }
                    is LlamaEngine.EngineState.Loading -> _state.update {
                        it.copy(engineStatus = "Caricamento di ${engineState.modelId}…")
                    }
                    is LlamaEngine.EngineState.Error -> _state.update {
                        it.copy(engineStatus = "Errore: ${engineState.message}")
                    }
                    is LlamaEngine.EngineState.Ready -> _state.update {
                        val model = engineState.model
                        it.copy(
                            engineStatus = "Pronto",
                            modelName = model.variant.displayName,
                            modelDetail = model.info["desc"].orEmpty(),
                            contextSize = model.contextSize,
                            threads = model.threads,
                            loadTimeMs = model.loadTimeMs,
                            vision = model.vision,
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            container.database.downloadDao().observeAll().collect { downloads ->
                _state.update { it.copy(downloads = downloads) }
            }
        }
        viewModelScope.launch {
            container.lastMetrics.collect { metrics -> _state.update { it.copy(metrics = metrics) } }
        }
        viewModelScope.launch {
            while (true) {
                val available = container.memorySampler.available()
                val used = container.memorySampler.current()
                _state.update {
                    it.copy(
                        ramUsedBytes = used,
                        ramAvailableBytes = available,
                        ramPercent = container.memorySampler.percentOf(container.deviceProfile.totalRamBytes),
                    )
                }
                delay(2_000)
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val stats = container.semantic.stats()
            _state.update {
                it.copy(
                    semantic = stats,
                    bundledInstalled = container.bundledModels.isInstalled(BundledModels.EMBEDDING),
                )
            }
        }
    }

    fun verifyBundledModel() {
        if (_state.value.verifying) return
        viewModelScope.launch {
            _state.update { it.copy(verifying = true, verificationResult = null) }
            val ok = container.bundledModels.verify(BundledModels.EMBEDDING)
            _state.update {
                it.copy(
                    verifying = false,
                    bundledInstalled = container.bundledModels.isInstalled(BundledModels.EMBEDDING),
                    verificationResult = if (ok) {
                        "SHA-256 corretto: il modello incluso è integro."
                    } else {
                        "Verifica fallita: il modello incluso non corrisponde all'impronta attesa."
                    },
                )
            }
            refresh()
        }
    }

    fun clearVerification() = _state.update { it.copy(verificationResult = null) }
}
