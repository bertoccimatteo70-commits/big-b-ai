package com.bigbai.app.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bigbai.app.AppContainer
import com.bigbai.core.model.AppSettings
import com.bigbai.core.model.Attachment
import com.bigbai.core.model.ChatMessage
import com.bigbai.core.model.Conversation
import com.bigbai.core.model.Folder
import com.bigbai.core.model.GenerationMetrics
import com.bigbai.core.model.Role
import com.bigbai.core.model.Source
import com.bigbai.core.repo.MemoryExtractor
import com.bigbai.core.repo.MemoryProposal
import com.bigbai.core.search.SearchConfig
import com.bigbai.core.search.SearchFailure
import com.bigbai.core.search.SearchResult
import com.bigbai.llm.GenerationParams
import com.bigbai.llm.InstalledModel
import com.bigbai.llm.LlamaEngine
import com.bigbai.llm.ModelRole
import com.bigbai.llm.PromptMessage
import com.bigbai.llm.PromptTemplates
import com.bigbai.llm.VisionInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

data class ChatUiState(
    val conversationId: Long = 0L,
    val messages: List<ChatMessage> = emptyList(),
    val streamingText: String = "",
    val input: String = "",
    val attachments: List<Attachment> = emptyList(),
    val isGenerating: Boolean = false,
    val isThinking: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val metrics: GenerationMetrics? = null,
    val sources: List<Source> = emptyList(),
    val proposals: List<MemoryProposal> = emptyList(),
    val modelLabel: String? = null,
    val modelReady: Boolean = false,
    val visionReady: Boolean = false,
)

class ChatViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    val conversations: StateFlow<List<Conversation>> = container.chat.conversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folders: StateFlow<List<Folder>> = container.chat.folders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _installed = MutableStateFlow<List<InstalledModel>>(emptyList())
    val installed: StateFlow<List<InstalledModel>> = _installed.asStateFlow()

    val archivedConversations: StateFlow<List<Conversation>> = container.chat.archivedConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _searchResults = MutableStateFlow<List<Conversation>>(emptyList())
    val searchResults: StateFlow<List<Conversation>> = _searchResults.asStateFlow()

    private var messagesJob: Job? = null
    private var generationJob: Job? = null

    init {
        viewModelScope.launch {
            container.engine.state.collect { engineState ->
                val ready = engineState as? LlamaEngine.EngineState.Ready
                _state.update {
                    it.copy(
                        modelReady = ready != null,
                        modelLabel = ready?.model?.variant?.displayName ?: it.modelLabel,
                        visionReady = ready?.model?.vision == true,
                        status = when (engineState) {
                            is LlamaEngine.EngineState.Loading -> "Caricamento del modello…"
                            else -> it.status
                        },
                    )
                }
            }
        }
        refreshInstalled()
    }

    fun refreshInstalled() {
        viewModelScope.launch {
            _installed.value = container.modelStore.installedAll(container.catalog)
        }
    }

    fun onInputChange(value: String) = _state.update { it.copy(input = value) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun dismissStatus() = _state.update { it.copy(status = null) }

    fun newChat() {
        if (_state.value.isGenerating) return
        messagesJob?.cancel()
        _state.update {
            it.copy(
                conversationId = 0L,
                messages = emptyList(),
                streamingText = "",
                input = "",
                attachments = emptyList(),
                metrics = null,
                sources = emptyList(),
                proposals = emptyList(),
                error = null,
            )
        }
    }

    fun openConversation(id: Long) {
        if (_state.value.isGenerating) {
            _state.update { it.copy(error = "Interrompi la generazione prima di cambiare conversazione.") }
            return
        }
        messagesJob?.cancel()
        _state.update {
            it.copy(
                conversationId = id,
                messages = emptyList(),
                streamingText = "",
                metrics = null,
                sources = emptyList(),
                proposals = emptyList(),
            )
        }
        messagesJob = viewModelScope.launch {
            container.chat.messages(id).collect { list ->
                _state.update { it.copy(messages = list) }
            }
        }
    }

    fun attach(uri: Uri) {
        viewModelScope.launch {
            val resolver = container.context.contentResolver
            val name = queryName(uri) ?: "allegato"
            val mime = resolver.getType(uri) ?: ""
            when {
                mime.startsWith("image/") || name.lowercase().endsWith(".png") ||
                    name.lowercase().endsWith(".jpg") || name.lowercase().endsWith(".jpeg") ||
                    name.lowercase().endsWith(".webp") -> {
                    _state.update {
                        it.copy(
                            attachments = it.attachments + Attachment(
                                kind = Attachment.Kind.IMAGE,
                                uri = uri.toString(),
                                name = name,
                                mimeType = mime.ifBlank { "image/*" },
                            ),
                        )
                    }
                }
                mime == "application/pdf" || name.lowercase().endsWith(".pdf") -> {
                    _state.update { it.copy(status = "Estrazione del testo dal PDF…") }
                    val document = container.pdf.extract(uri, name)
                    document.onSuccess { pdf ->
                        if (!pdf.hasExtractableText) {
                            _state.update {
                                it.copy(
                                    error = "Il PDF non contiene testo estraibile (probabilmente è scansionato). " +
                                        "L'OCR non è supportato in questa versione.",
                                    status = null,
                                )
                            }
                        } else {
                            _state.update {
                                it.copy(
                                    attachments = it.attachments + Attachment(
                                        kind = Attachment.Kind.PDF,
                                        uri = uri.toString(),
                                        name = name,
                                        mimeType = "application/pdf",
                                        extractedText = pdf.fullText,
                                    ),
                                    status = null,
                                )
                            }
                        }
                    }.onFailure { error ->
                        _state.update { it.copy(error = error.message ?: "PDF non leggibile", status = null) }
                    }
                }
                else -> _state.update { it.copy(error = "Formato non supportato. Allega un'immagine o un PDF.") }
            }
        }
    }

    fun removeAttachment(attachment: Attachment) =
        _state.update { it.copy(attachments = it.attachments - attachment) }

    fun stop() {
        container.engine.stop()
    }

    fun send() {
        val snapshot = _state.value
        if (snapshot.isGenerating) return
        val text = snapshot.input.trim()
        if (text.isEmpty() && snapshot.attachments.isEmpty()) return

        generationJob = viewModelScope.launch {
            val priorMessages = snapshot.messages
            _state.update {
                it.copy(
                    input = "",
                    attachments = emptyList(),
                    error = null,
                    isGenerating = true,
                    isThinking = true,
                    streamingText = "",
                    metrics = null,
                    sources = emptyList(),
                    proposals = emptyList(),
                    status = "Preparazione…",
                )
            }
            val builder = StringBuilder()
            try {
                val settings = container.settings.current()
                val loaded = ensureModel(settings) ?: return@launch
                val conversationId = ensureConversation(loaded.variant.id)
                container.chat.appendMessage(
                    conversationId = conversationId,
                    role = Role.USER,
                    content = if (text.isBlank()) "(immagine allegata)" else text,
                    modelId = loaded.variant.id,
                    attachments = snapshot.attachments,
                )

                if (settings.memoryEnabled && text.isNotBlank()) {
                    val proposals = MemoryExtractor.propose(text)
                    if (proposals.isNotEmpty()) {
                        _state.update { it.copy(proposals = proposals) }
                    }
                }

                var sources: List<Source> = emptyList()
                if (settings.searchEnabled && text.isNotBlank() && shouldSearch(text)) {
                    _state.update { it.copy(status = "Ricerca web in corso…") }
                    val config = SearchConfig(settings.searchProvider, settings.searxEndpoint, settings.braveApiKey)
                    when (val result = container.search.search(text, config)) {
                        is SearchResult.Success -> {
                            sources = result.sources
                            _state.update { it.copy(sources = sources) }
                        }
                        is SearchResult.Failure -> {
                            val message = when (result.reason) {
                                SearchFailure.OFFLINE -> "Sei offline: la ricerca web non è disponibile."
                                SearchFailure.NOT_CONFIGURED -> "Ricerca web non configurata nelle impostazioni."
                                SearchFailure.EMPTY -> "La ricerca non ha prodotto risultati."
                                SearchFailure.NETWORK_ERROR -> "Ricerca web non raggiungibile."
                            }
                            _state.update { it.copy(status = message) }
                        }
                    }
                }

                val prompt = buildPrompt(text, snapshot.attachments, priorMessages, sources, loaded, settings)
                val vision = snapshot.attachments
                    .firstOrNull { it.kind == Attachment.Kind.IMAGE }
                    ?.let { loadVisionInput(it) }
                if (vision != null && !loaded.vision) {
                    _state.update {
                        it.copy(
                            error = "Il modello attivo non è multimodale. Installa Qwen2.5 VL per analizzare le immagini.",
                            isGenerating = false,
                            isThinking = false,
                            status = null,
                        )
                    }
                    return@launch
                }

                _state.update { it.copy(status = "AI sta pensando…", isThinking = true) }
                val params = GenerationParams(
                    maxTokens = settings.maxTokens.coerceIn(64, 4096),
                    temperature = settings.temperature.coerceIn(0f, 2f),
                    topP = settings.topP.coerceIn(0.1f, 1f),
                    topK = settings.topK.coerceIn(1, 200),
                )

                val result = container.engine.generate(prompt, params, vision) { token ->
                    builder.append(token)
                    _state.update { it.copy(streamingText = builder.toString(), isThinking = false) }
                }

                val finalText = builder.toString().trim()
                result.onSuccess { metrics ->
                    if (finalText.isNotEmpty()) {
                        container.chat.appendMessage(
                            conversationId = conversationId,
                            role = Role.ASSISTANT,
                            content = finalText,
                            modelId = loaded.variant.id,
                            sources = sources,
                            promptTokens = metrics.promptTokens,
                            completionTokens = metrics.completionTokens,
                            tokensPerSecond = metrics.tokensPerSecond,
                        )
                    }
                    _state.update { it.copy(metrics = metrics, streamingText = "", isThinking = false, status = null) }
                }.onFailure { error ->
                    if (finalText.isNotEmpty()) {
                        container.chat.appendMessage(
                            conversationId = conversationId,
                            role = Role.ASSISTANT,
                            content = finalText,
                            modelId = loaded.variant.id,
                            sources = sources,
                        )
                    }
                    _state.update {
                        it.copy(
                            error = error.message ?: "Generazione non riuscita",
                            streamingText = "",
                            isThinking = false,
                            status = null,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        error = error.message ?: "Errore imprevisto",
                        isThinking = false,
                        status = null,
                    )
                }
            } finally {
                _state.update { it.copy(isGenerating = false, isThinking = false, status = null) }
            }
        }
    }

    fun saveProposal(proposal: MemoryProposal) {
        viewModelScope.launch {
            container.memory.add(proposal.content, proposal.categoryName, _state.value.conversationId)
            _state.update { it.copy(proposals = it.proposals - proposal) }
        }
    }

    fun dismissProposals() = _state.update { it.copy(proposals = emptyList()) }

    fun saveMessageToMemory(message: ChatMessage, category: String) {
        viewModelScope.launch {
            container.memory.add(message.content.take(500), category, message.conversationId)
        }
    }

    fun selectModel(id: String) {
        viewModelScope.launch {
            if (_state.value.isGenerating) {
                _state.update { it.copy(error = "Interrompi la generazione prima di cambiare modello.") }
                return@launch
            }
            val model = container.modelStore.installedAll(container.catalog).firstOrNull { it.variant.id == id }
                ?: return@launch
            container.settings.setActiveModel(id)
            container.engine.unload()
            _state.update { it.copy(modelLabel = model.variant.displayName, modelReady = false) }
        }
    }

    fun deleteConversation(id: Long, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            container.chat.deleteConversation(id)
            if (_state.value.conversationId == id) newChat()
            onDeleted()
        }
    }

    fun toggleFavorite(conversation: Conversation) {
        viewModelScope.launch { container.chat.toggleFavorite(conversation.id, !conversation.favorite) }
    }

    fun archive(conversation: Conversation) {
        viewModelScope.launch { container.chat.setArchived(conversation.id, true) }
    }

    fun rename(conversation: Conversation, title: String) {
        viewModelScope.launch { container.chat.rename(conversation.id, title.trim()) }
    }

    fun createFolder(name: String) {
        viewModelScope.launch { container.chat.createFolder(name) }
    }

    fun unarchive(conversation: Conversation) {
        viewModelScope.launch { container.chat.setArchived(conversation.id, false) }
    }

    fun searchConversations(query: String) {
        viewModelScope.launch {
            _searchResults.value = if (query.isBlank()) emptyList() else container.chat.search(query)
        }
    }

    fun moveToFolder(conversation: Conversation, folderId: Long?) {
        viewModelScope.launch { container.chat.moveToFolder(conversation.id, folderId) }
    }

    private suspend fun ensureConversation(modelId: String): Long {
        val current = _state.value.conversationId
        if (current != 0L) return current
        val id = container.chat.createConversation(modelId)
        openConversation(id)
        return id
    }

    private suspend fun ensureModel(settings: AppSettings): com.bigbai.llm.LoadedModel? {
        container.engine.current()?.let { return it }
        val installed = container.modelStore.installedAll(container.catalog)
        if (installed.isEmpty()) {
            _state.update {
                it.copy(
                    error = "Nessun modello installato. Apri Modelli per scaricare «Veloce» (circa 490 MB).",
                    isGenerating = false,
                    isThinking = false,
                    status = null,
                )
            }
            return null
        }
        val selected = settings.activeModelId
        val chosen = if (selected != null) {
            installed.firstOrNull { it.variant.id == selected } ?: run {
                _state.update {
                    it.copy(
                        error = "Il modello selezionato non è installato. Scegli un altro modello dal selettore.",
                        isGenerating = false,
                        isThinking = false,
                        status = null,
                    )
                }
                return null
            }
        } else {
            installed.filter { it.variant.role == ModelRole.CHAT }.maxByOrNull { it.variant.tier.ordinal }
                ?: installed.first()
        }

        if (selected == null) {
            container.settings.setActiveModel(chosen.variant.id)
        }

        val budget = container.deviceProfile.budgetBytes(settings.ramMode)
        val context = if (settings.contextOverride > 0) {
            settings.contextOverride
        } else {
            container.catalog.adaptiveContext(chosen.variant, container.deviceProfile, budget)
        }
        val threads = if (settings.threads > 0) {
            settings.threads
        } else {
            container.deviceProfile.recommendedThreads(settings.performanceMode)
        }
        _state.update { it.copy(status = "Caricamento di ${chosen.variant.family}…") }
        return container.engine.load(
            variant = chosen.variant,
            modelPath = chosen.modelPath,
            mmprojPath = chosen.mmprojPath,
            contextSize = context,
            threads = threads,
            batchSize = settings.batchSize,
            budgetBytes = budget,
        ).getOrElse { error ->
            _state.update {
                it.copy(
                    error = error.message ?: "Caricamento del modello non riuscito",
                    isGenerating = false,
                    isThinking = false,
                    status = null,
                )
            }
            null
        }
    }

    private suspend fun buildPrompt(
        userText: String,
        attachments: List<Attachment>,
        priorMessages: List<ChatMessage>,
        sources: List<Source>,
        loaded: com.bigbai.llm.LoadedModel,
        settings: AppSettings,
    ): List<PromptMessage> {
        val memory = if (settings.memoryEnabled) container.memory.promptContext() else ""
        val document = attachments.firstOrNull { it.kind == Attachment.Kind.PDF }?.extractedText
            ?: priorMessages.asReversed().firstNotNullOfOrNull { message ->
                message.attachments.firstOrNull { it.kind == Attachment.Kind.PDF }?.extractedText
            }
        val system = PromptTemplates.chatSystemPrompt(
            language = settings.language,
            memory = memory,
            documentContext = document?.let { it.take(12_000) },
            sources = sources,
            visionAvailable = loaded.vision,
            hasImage = attachments.any { it.kind == Attachment.Kind.IMAGE },
        )

        val budget = loaded.contextSize - settings.maxTokens - 48
        var used = container.engine.countTokens(system) + container.engine.countTokens(userText) + 24
        val selected = ArrayDeque<PromptMessage>()
        for (message in priorMessages.takeLast(24).reversed()) {
            if (message.role == Role.SYSTEM) continue
            val content = message.content
            val tokens = container.engine.countTokens(content) + 8
            if (used + tokens > budget) break
            used += tokens
            selected.addFirst(
                PromptMessage(
                    role = if (message.role == Role.USER) "user" else "assistant",
                    content = content,
                ),
            )
        }
        return listOf(PromptMessage("system", system)) + selected +
            PromptMessage("user", userText.ifBlank { "(immagine allegata)" })
    }

    private fun shouldSearch(text: String): Boolean {
        val lower = text.lowercase()
        val markers = listOf(
            "oggi", "adesso", "ultime", "ultimo", "notizie", "meteo", "prezzo", "quotazione",
            "2025", "2026", "chi è", "quando è", "dove si trova", "documentazione",
            "latest", "today", "news", "price", "weather", "who is", "release", "version",
        )
        return markers.any { it in lower }
    }

    private fun loadVisionInput(attachment: Attachment): VisionInput? = runCatching {
        val uri = Uri.parse(attachment.uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        container.context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = container.context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val maxSide = 896
        val ratio = min(1f, maxSide.toFloat() / max(bitmap.width, bitmap.height))
        val scaled: Bitmap = if (ratio < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                max(1, (bitmap.width * ratio).toInt()),
                max(1, (bitmap.height * ratio).toInt()),
                true,
            )
        } else {
            bitmap
        }
        val width = scaled.width
        val height = scaled.height
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        val rgb = ByteArray(pixels.size * 3)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            rgb[index * 3] = ((pixel shr 16) and 0xFF).toByte()
            rgb[index * 3 + 1] = ((pixel shr 8) and 0xFF).toByte()
            rgb[index * 3 + 2] = (pixel and 0xFF).toByte()
        }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        VisionInput(rgb, width, height)
    }.getOrNull()

    private fun queryName(uri: Uri): String? = runCatching {
        container.context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    override fun onCleared() {
        generationJob?.cancel()
        messagesJob?.cancel()
        super.onCleared()
    }
}
