package com.bigbai.app.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bigbai.app.AppContainer
import com.bigbai.core.db.DownloadEntity
import com.bigbai.core.model.RamMode
import com.bigbai.llm.InstalledModel
import com.bigbai.llm.ModelDownloadManager
import com.bigbai.llm.ModelRole
import com.bigbai.llm.ModelTier
import com.bigbai.llm.ModelVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ModelManagerState(
    val installed: List<InstalledModel> = emptyList(),
    val downloads: Map<String, DownloadEntity> = emptyMap(),
    val storageUsed: Long = 0,
    val freeSpace: Long = 0,
    val deviceSummary: String = "",
    val budget: Long = 0,
    val ramMode: RamMode = RamMode.NORMAL,
    val activeModelId: String? = null,
    val activeCodeModelId: String? = null,
    val advanced: Boolean = false,
    val message: String? = null,
    val busyModelId: String? = null,
)

class ModelManagerViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ModelManagerState())
    val state: StateFlow<ModelManagerState> = _state.asStateFlow()

    init {
        _state.update {
            it.copy(
                deviceSummary = container.deviceProfile.summary(),
                budget = container.deviceProfile.budgetBytes(RamMode.NORMAL),
            )
        }
        viewModelScope.launch {
            container.settings.settings.collect { settings ->
                _state.update {
                    it.copy(
                        activeModelId = settings.activeModelId,
                        activeCodeModelId = settings.activeCodeModelId,
                        advanced = settings.advancedUnlocked,
                        ramMode = settings.ramMode,
                        budget = container.deviceProfile.budgetBytes(settings.ramMode),
                    )
                }
            }
        }
        viewModelScope.launch {
            container.database.downloadDao().observeAll().collect { downloads ->
                _state.update { it.copy(downloads = downloads.associateBy { entry -> entry.modelId }) }
                refreshInstalled()
            }
        }
        refreshInstalled()
    }

    fun refreshInstalled() {
        viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) {
                container.modelStore.installedAll(container.catalog)
            }
            _state.update {
                it.copy(
                    installed = installed,
                    storageUsed = container.modelStore.totalBytes(),
                    freeSpace = container.modelStore.freeSpaceBytes(),
                )
            }
        }
    }

    fun setAdvanced(value: Boolean) {
        viewModelScope.launch { container.settings.setAdvancedUnlocked(value) }
    }

    fun bestForTier(role: ModelRole, tier: ModelTier): ModelVariant? {
        val budget = _state.value.budget
        val options = container.catalog.forRole(role).filter { it.tier == tier }
        if (options.isEmpty()) return null
        val ranked = options.sortedByDescending { rank(it.quant) }
        return ranked.firstOrNull {
            it.fitsIn(budget, container.catalog.adaptiveContext(it, container.deviceProfile, budget))
        } ?: options.minByOrNull { it.totalBytes }
    }

    fun variantById(id: String): ModelVariant? = container.catalog.byId(id)

    /** Active, paused and failed downloads, in catalog order. */
    fun pendingDownloads(): List<ModelVariant> = _state.value.downloads.values
        .filter { it.status == "DOWNLOADING" || it.status == "PAUSED" || it.status == "FAILED" }
        .mapNotNull { container.catalog.byId(it.modelId) }

    fun variantsFor(role: ModelRole): List<ModelVariant> =
        container.catalog.forRole(role).sortedWith(
            compareBy({ it.tier.ordinal }, { -rank(it.quant) }),
        )

    fun contextFor(variant: ModelVariant): Int =
        container.catalog.adaptiveContext(variant, container.deviceProfile, _state.value.budget)

    /** Chat and BIG B Code are chosen independently: no silent model switch. */
    fun setActive(variant: ModelVariant) {
        viewModelScope.launch {
            container.settings.setActiveModel(variant.id)
            container.engine.unload()
            _state.update {
                it.copy(message = "Modello della chat: ${variant.family} ${variant.quant}")
            }
        }
    }

    fun setActiveCode(variant: ModelVariant) {
        viewModelScope.launch {
            container.settings.setActiveCodeModel(variant.id)
            _state.update {
                it.copy(message = "Modello di BIG B Code: ${variant.family} ${variant.quant}")
            }
        }
    }

    fun download(variant: ModelVariant) {
        val free = _state.value.freeSpace
        if (free > 0 && free < variant.totalBytes) {
            _state.update {
                it.copy(
                    message = "Spazio insufficiente: servono ${ModelDownloadManager.formatBytes(variant.totalBytes)} " +
                        "e ne sono disponibili ${ModelDownloadManager.formatBytes(free)}.",
                )
            }
            return
        }
        com.bigbai.app.service.ModelDownloadService.start(container.context, variant.id)
        _state.update { it.copy(message = "Download avviato: ${variant.family} ${variant.quant}") }
    }

    fun pause(variant: ModelVariant) {
        com.bigbai.app.service.ModelDownloadService.pause(container.context, variant.id)
    }

    /**
     * Cancels a download and removes its partial data, so the next attempt starts
     * from a clean state instead of resuming a corrupt fragment.
     */
    fun cancel(variant: ModelVariant) {
        com.bigbai.app.service.ModelDownloadService.pause(container.context, variant.id)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.modelStore.discardPartial(variant) }
            refreshInstalled()
            _state.update {
                it.copy(
                    message = "Download annullato: ${variant.family} ${variant.quant}. " +
                        "I dati parziali sono stati rimossi.",
                )
            }
        }
    }

    fun delete(variant: ModelVariant) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.modelStore.delete(variant) }
            val settings = container.settings.current()
            if (settings.activeModelId == variant.id) container.settings.setActiveModel(null)
            if (settings.activeCodeModelId == variant.id) container.settings.setActiveCodeModel(null)
            if (container.engine.current()?.variant?.id == variant.id) container.engine.unload()
            refreshInstalled()
            _state.update { it.copy(message = "Modello eliminato: ${variant.family} ${variant.quant}") }
        }
    }

    fun verify(variant: ModelVariant) {
        viewModelScope.launch {
            _state.update { it.copy(busyModelId = variant.id, message = "Verifica SHA-256 in corso…") }
            val ok = withContext(Dispatchers.IO) { container.modelStore.verify(variant) }
            _state.update {
                it.copy(
                    busyModelId = null,
                    message = if (ok) {
                        "Integrità verificata: ${variant.family} ${variant.quant}"
                    } else {
                        "Verifica fallita: il file è corrotto o incompleto. Eliminalo e scaricalo di nuovo."
                    },
                )
            }
            refreshInstalled()
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun rank(quant: String): Int = when (quant) {
        "Q8_0" -> 7
        "Q6_K" -> 6
        "Q5_K_M" -> 5
        "Q4_K_M" -> 4
        "Q3_K_M" -> 3
        "Q2_K" -> 2
        else -> 0
    }
}
