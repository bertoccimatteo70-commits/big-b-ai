package com.bigbai.app.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bigbai.app.AppContainer
import com.bigbai.core.db.DownloadEntity
import com.bigbai.core.device.PerformanceAdvisor
import com.bigbai.core.model.GenerationMetrics
import com.bigbai.llm.EmbeddingModels
import com.bigbai.llm.LlamaEngine
import com.bigbai.llm.SemanticRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    val thermal: String = "",
    val metrics: GenerationMetrics? = null,
    val semantic: SemanticRepository.Stats? = null,
    val downloads: List<DownloadEntity> = emptyList(),
    val engineVersion: String = "",
    val capabilities: String = "",
    val deviceSummary: String = "",
    val embeddingLabel: String = "",
    val embeddingInstalled: Boolean = false,
    val verifying: Boolean = false,
    val verificationResult: String? = null,
    val embedderRequiredBytes: Long = 0,
    val embedderAvailableBytes: Long = 0,
    val embedderPressure: Int = 0,
    val rebuilding: Boolean = false,
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
            thermal = PerformanceAdvisor.label(
                PerformanceAdvisor.thermalStatus(container.context),
            ) + if (PerformanceAdvisor.powerSaver(container.context)) " · risparmio energetico" else "",
            ramTotalBytes = container.deviceProfile.totalRamBytes,
            embeddingLabel = EmbeddingModels.EMBEDDING.label,
            embeddingInstalled = container.embeddingModels.isInstalled(EmbeddingModels.EMBEDDING),
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
                        thermal = PerformanceAdvisor.label(
                            PerformanceAdvisor.thermalStatus(container.context),
                        ) + if (PerformanceAdvisor.powerSaver(container.context)) " · risparmio energetico" else "",
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
            val decision = container.embedder.decision()
            _state.update {
                it.copy(
                    semantic = stats,
                    embeddingInstalled = container.embeddingModels.isInstalled(EmbeddingModels.EMBEDDING),
                    embedderRequiredBytes = decision.requiredBytes,
                    embedderAvailableBytes = decision.availableBytes,
                    embedderPressure = decision.pressureLevel,
                )
            }
        }
    }

    /** Rebuilds the memory vectors from the database after a corrupt index. */
    fun rebuildIndex() {
        if (_state.value.rebuilding) return
        viewModelScope.launch {
            _state.update { it.copy(rebuilding = true, verificationResult = null) }
            val seeds = container.memory.memories().first().map { item ->
                SemanticRepository.MemorySeed(
                    id = item.id,
                    text = item.content,
                    label = item.categoryName,
                )
            }
            val outcome = container.semantic.rebuildMemories(seeds)
            _state.update {
                it.copy(
                    rebuilding = false,
                    verificationResult = if (outcome.persisted) {
                        "Indice ricostruito: ${outcome.passages} passaggi da ${seeds.size} ricordi."
                    } else {
                        "Ricostruzione non riuscita: ${outcome.error ?: "errore sconosciuto"}"
                    },
                )
            }
            refresh()
        }
    }

    fun verifyEmbeddingModel() {
        if (_state.value.verifying) return
        viewModelScope.launch {
            _state.update { it.copy(verifying = true, verificationResult = null) }
            val ok = container.embeddingModels.verify(EmbeddingModels.EMBEDDING)
            _state.update {
                it.copy(
                    verifying = false,
                    embeddingInstalled = container.embeddingModels.isInstalled(EmbeddingModels.EMBEDDING),
                    verificationResult = if (ok) {
                        "SHA-256 corretto: il modello di embedding importato è integro."
                    } else {
                        "Verifica fallita: il modello di embedding non corrisponde all'impronta attesa."
                    },
                )
            }
            refresh()
        }
    }

    fun clearVerification() = _state.update { it.copy(verificationResult = null) }
}
