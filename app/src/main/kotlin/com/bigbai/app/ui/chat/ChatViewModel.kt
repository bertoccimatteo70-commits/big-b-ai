package com.bigbai.app.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bigbai.app.AppContainer
import com.bigbai.core.chat.ConversationSession
import com.bigbai.core.chat.StreamingBuffer
import com.bigbai.core.lang.LanguageCoach
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
import com.bigbai.llm.AnswerSanitizer
import com.bigbai.llm.GenerationParams
import com.bigbai.llm.InstalledModel
import com.bigbai.llm.LlamaEngine
import com.bigbai.llm.ModelProfiles
import com.bigbai.llm.ModelRole
import com.bigbai.llm.PromptMessage
import com.bigbai.llm.PromptTemplates
import com.bigbai.llm.VisionInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /** Number of vocabulary lines stored by the tutor after the last answer. */
    val lexiconSaved: Int = 0,
)

class ChatViewModel(
    private val container: AppContainer,
    private val savedState: SavedStateHandle? = null,
) : ViewModel() {

    private val restoredConversationId: Long = savedState?.get<Long>(KEY_CONVERSATION) ?: 0L
    private val restoredInput: String = savedState?.get<String>(KEY_INPUT).orEmpty()

    private val _state = MutableStateFlow(
        ChatUiState(
            conversationId = restoredConversationId,
            input = restoredInput,
        ),
    )
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /**
     * Identity of the open conversation. Kept outside [ChatUiState] so that no
     * UI flag (especially `isGenerating`) can ever cause a second conversation
     * to be created for the same chat.
     */
    private val session = ConversationSession()

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
    private var timeoutJob: Job? = null
    private var cancelledByUser = false
    private var timedOut = false

    /**
     * Invalidates the updates of an in-flight generation when the user starts a
     * new chat or switches conversation. Without it, the tail of the previous
     * answer could paint metrics, errors or streaming text into the new chat:
     * that is exactly the "new chat inherits the previous one" bug.
     */
    private var generationSeq = 0
    private var activeGeneration = 0

    private inline fun updateIfCurrent(generation: Int, transform: (ChatUiState) -> ChatUiState) {
        _state.update { current ->
            if (generation == activeGeneration) transform(current) else current
        }
    }

    init {
        if (restoredConversationId > 0L) {
            session.bind(restoredConversationId)
            observeMessages(restoredConversationId)
        }
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
            _installed.value = installedModels()
        }
    }

    private suspend fun installedModels(): List<InstalledModel> = withContext(Dispatchers.IO) {
        container.modelStore.installedAll(container.catalog)
    }

    fun onInputChange(value: String) {
        _state.update { it.copy(input = value) }
        savedState?.set(KEY_INPUT, value)
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun dismissStatus() = _state.update { it.copy(status = null) }

    fun dismissLexiconNotice() = _state.update { it.copy(lexiconSaved = 0) }

    /**
     * Opens a brand-new chat. Any running generation is stopped first, so the
     * button always works and never leaves a half-written answer behind. The
     * state is rebuilt from scratch: no messages, no attachments, no draft, no
     * metrics, no error and no streaming text from the previous chat.
     */
    fun newChat() {
        activeGeneration = ++generationSeq
        container.engine.stop()
        cancelTimeout()
        generationJob?.cancel()
        messagesJob?.cancel()
        session.reset()
        savedState?.set(KEY_CONVERSATION, 0L)
        savedState?.set(KEY_INPUT, "")
        _state.update { it.afterNewChat() }
    }

    fun openConversation(id: Long) {
        if (id <= 0L || id == session.conversationId) return
        if (_state.value.isGenerating) {
            _state.update { it.copy(error = "Interrompi la generazione prima di cambiare conversazione.") }
            return
        }
        activeGeneration = ++generationSeq
        session.bind(id)
        savedState?.set(KEY_CONVERSATION, id)
        savedState?.set(KEY_INPUT, "")
        messagesJob?.cancel()
        _state.update { it.afterOpenConversation(id) }
        observeMessages(id)
    }

    fun attach(uri: Uri) {
        viewModelScope.launch {
            val resolver = container.context.contentResolver
            val (name, mime, size) = withContext(Dispatchers.IO) {
                Triple(queryName(uri) ?: "allegato", resolver.getType(uri) ?: "", querySize(uri))
            }
            val kind = AttachmentPolicy.kindFor(name, mime)
            if (kind == null) {
                _state.update { it.copy(error = "Formato non supportato. Allega un'immagine o un PDF.") }
                return@launch
            }
            AttachmentPolicy.sizeError(kind, size)?.let { message ->
                _state.update { it.copy(error = message) }
                return@launch
            }
            if (kind == Attachment.Kind.IMAGE) {
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
            } else {
                _state.update { it.copy(status = "Estrazione del testo dal PDF…") }
                container.pdf.extract(uri, name).onSuccess { pdf ->
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
        }
    }

    fun removeAttachment(attachment: Attachment) =
        _state.update { it.copy(attachments = it.attachments - attachment) }

    fun stop() {
        cancelledByUser = true
        _state.update { it.copy(status = "Interruzione…") }
        container.engine.stop()
    }

    fun send() {
        val snapshot = _state.value
        if (snapshot.isGenerating) return
        val text = snapshot.input.trim()
        if (text.isEmpty() && snapshot.attachments.isEmpty()) return

        val generation = ++generationSeq
        activeGeneration = generation
        cancelledByUser = false
        timedOut = false

        generationJob = viewModelScope.launch {
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
                    lexiconSaved = 0,
                    status = "Preparazione…",
                )
            }
            savedState?.set(KEY_INPUT, "")

            val buffer = StreamingBuffer()
            var conversationId = session.conversationId
            var userMessagePersisted = false
            try {
                val settings = container.settings.current()
                val loaded = ensureModel(settings)
                if (loaded == null) {
                    restoreDraft(text, snapshot.attachments, generation)
                    return@launch
                }
                // The user may have pressed + while the model was loading.
                if (generation != activeGeneration) return@launch

                // The database is authoritative. Reading `state.messages` here
                // could miss history right after process death, before the Room
                // collector has emitted the restored conversation.
                val priorMessages = if (conversationId > 0L) {
                    container.chat.messagesNow(conversationId)
                } else {
                    emptyList()
                }

                val imageAttachment = snapshot.attachments.firstOrNull { it.kind == Attachment.Kind.IMAGE }
                val vision = imageAttachment?.let { attachment -> loadVisionInput(attachment) }
                if (imageAttachment != null && vision == null) {
                    _state.update {
                        it.copy(
                            error = "Immagine non leggibile o troppo grande. Prova con un'altra immagine.",
                            isGenerating = false,
                            isThinking = false,
                            status = null,
                        )
                    }
                    restoreDraft(text, snapshot.attachments, generation)
                    return@launch
                }
                if (vision != null && !loaded.vision) {
                    _state.update {
                        it.copy(
                            error = "Il modello attivo non è multimodale. Installa Qwen2.5 VL per analizzare le immagini.",
                            isGenerating = false,
                            isThinking = false,
                            status = null,
                        )
                    }
                    restoreDraft(text, snapshot.attachments, generation)
                    return@launch
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

                // Persist the turn only after every fallible validation passed.
                // Otherwise a failed send would leave a user message behind and
                // a retry would duplicate it.
                conversationId = ensureConversation(loaded.variant.id)
                container.chat.appendMessage(
                    conversationId = conversationId,
                    role = Role.USER,
                    content = if (text.isBlank()) "(immagine allegata)" else text,
                    modelId = loaded.variant.id,
                    attachments = snapshot.attachments,
                )
                userMessagePersisted = true

                if (settings.memoryEnabled && text.isNotBlank()) {
                    val proposals = MemoryExtractor.propose(text)
                    if (proposals.isNotEmpty()) {
                        _state.update { it.copy(proposals = proposals) }
                    }
                }

                _state.update { it.copy(status = "AI sta pensando…", isThinking = true) }
                val params = ModelProfiles.forVariant(loaded.variant).params(settings)

                startTimeout(generation)
                // The answer is generated in the background and shown only when
                // complete: no partial text reaches the chat while streaming.
                val result = container.engine.generate(prompt, params, vision) { token ->
                    buffer.append(token, SystemClock.elapsedRealtime())
                }
                cancelTimeout()
                val finalText = AnswerSanitizer.clean(buffer.flush()).trim()

                if (cancelledByUser || timedOut) {
                    if (finalText.isNotEmpty()) {
                        container.chat.appendMessage(
                            conversationId = conversationId,
                            role = Role.ASSISTANT,
                            content = "$finalText\n\n_(risposta interrotta)_",
                            modelId = loaded.variant.id,
                            sources = sources,
                        )
                    }
                    updateIfCurrent(generation) {
                        it.copy(
                            streamingText = "",
                            isThinking = false,
                            status = null,
                            error = if (timedOut) {
                                "Il modello non ha risposto in tempo. La risposta parziale è marcata come interrotta."
                            } else {
                                null
                            },
                        )
                    }
                    return@launch
                }

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
                    val savedWords = saveTutorLexicon(settings, finalText, conversationId)
                    val measured = metrics.takeIf {
                        it.completionTokens > 0 || it.promptTokens > 0 || finalText.isNotEmpty()
                    }
                    updateIfCurrent(generation) {
                        it.copy(
                            metrics = measured,
                            streamingText = "",
                            isThinking = false,
                            status = null,
                            lexiconSaved = savedWords,
                        )
                    }
                }.onFailure { error ->
                    // A failed generation is not a final answer: the partial text
                    // is discarded and the error is shown instead.
                    if (!userMessagePersisted) restoreDraft(text, snapshot.attachments, generation)
                    updateIfCurrent(generation) {
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
                if (!userMessagePersisted) restoreDraft(text, snapshot.attachments, generation)
                updateIfCurrent(generation) {
                    it.copy(
                        error = error.message ?: "Errore imprevisto",
                        streamingText = "",
                        isThinking = false,
                        status = null,
                    )
                }
            } finally {
                updateIfCurrent(generation) {
                    it.copy(isGenerating = false, isThinking = false, status = null)
                }
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

    fun saveMessageToMemory(message: ChatMessage) {
        viewModelScope.launch {
            val settings = container.settings.current()
            val category = if (settings.tutorEnabled) {
                LanguageCoach.categoryName(settings.tutorTarget)
            } else {
                "Personale"
            }
            container.memory.add(message.content.take(500), category, message.conversationId)
        }
    }

    fun selectModel(id: String) {
        viewModelScope.launch {
            if (_state.value.isGenerating) {
                _state.update { it.copy(error = "Interrompi la generazione prima di cambiare modello.") }
                return@launch
            }
            val model = installedModels().firstOrNull { it.variant.id == id } ?: return@launch
            container.settings.setActiveModel(id)
            container.engine.unload()
            _state.update { it.copy(modelLabel = model.variant.displayName, modelReady = false) }
        }
    }

    fun deleteConversation(id: Long, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            container.chat.deleteConversation(id)
            if (session.conversationId == id || _state.value.conversationId == id) newChat()
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

    /**
     * Returns the open conversation, creating it exactly once when this chat has
     * no conversation yet.
     *
     * Deliberately does NOT go through [openConversation]: that method must keep
     * refusing to switch while generating, and the first message is sent while
     * `isGenerating` is already true. Binding identity and showing a chat are two
     * different operations.
     */
    private suspend fun ensureConversation(modelId: String): Long {
        val hadConversation = session.isBound
        val id = session.resolveDeferred { container.chat.createConversation(modelId) }
        if (!hadConversation) {
            savedState?.set(KEY_CONVERSATION, id)
            _state.update { it.copy(conversationId = id) }
            observeMessages(id)
        }
        return id
    }

    private fun observeMessages(conversationId: Long) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            container.chat.messages(conversationId).collect { list ->
                _state.update { it.copy(messages = list) }
            }
        }
    }

    private suspend fun ensureModel(settings: AppSettings): com.bigbai.llm.LoadedModel? {
        container.engine.current()?.let { return it }
        val installed = installedModels()
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

        // Budget against memory available *now*, not at app start.
        val budget = container.deviceProfile.budgetBytes(
            settings.ramMode,
            container.memorySampler.available(),
        )
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
            val hint = if (container.deviceProfile.tier == com.bigbai.core.device.DeviceTier.LOW) {
                " Chiudi le altre app e scegli un modello più piccolo (Q2/Q3)."
            } else {
                " Scegli un modello più piccolo o riduci la context length."
            }
            _state.update {
                it.copy(
                    error = (error.message ?: "Caricamento del modello non riuscito") + hint,
                    isGenerating = false,
                    isThinking = false,
                    status = null,
                )
            }
            null
        }
    }

    /**
     * Local generation is synchronous inside the native engine, so a timeout is
     * enforced by asking the engine to stop: cancelling the coroutine alone would
     * leave the native loop running.
     */
    private fun startTimeout(generation: Int) {
        cancelTimeout()
        timeoutJob = viewModelScope.launch {
            delay(RESPONSE_TIMEOUT_MS)
            if (activeGeneration == generation && _state.value.isGenerating) {
                timedOut = true
                container.engine.stop()
            }
        }
    }

    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
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
        val tutor = if (settings.tutorEnabled) {
            val lexicon = container.memory.forCategory(
                LanguageCoach.categoryName(settings.tutorTarget),
                limit = 16,
            ).map { it.content }
            LanguageCoach.promptSection(settings.tutorTarget, settings.tutorLevel, lexicon)
        } else {
            ""
        }
        val system = PromptTemplates.chatSystemPrompt(
            language = settings.language,
            memory = memory,
            documentContext = document?.let { it.take(12_000) },
            sources = sources,
            visionAvailable = loaded.vision,
            hasImage = attachments.any { it.kind == Attachment.Kind.IMAGE },
            tutor = tutor,
            modelStyle = ModelProfiles.forVariant(loaded.variant)
                .styleHint(settings.language.startsWith("it")),
        )

        val budget = (loaded.contextSize - settings.maxTokens - 48).coerceAtLeast(512)
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

    /**
     * Stores the vocabulary lines emitted by the tutor so later prompts can
     * re-inject the user's personal lexicon. Returns how many new entries were
     * added (duplicates are ignored).
     */
    private suspend fun saveTutorLexicon(
        settings: AppSettings,
        answer: String,
        conversationId: Long,
    ): Int {
        if (!settings.tutorEnabled || answer.isBlank()) return 0
        val category = LanguageCoach.categoryName(settings.tutorTarget)
        var saved = 0
        LanguageCoach.parseLexicon(settings.tutorTarget, answer).forEach { line ->
            if (container.memory.addIfAbsent(line, category, conversationId)) saved += 1
        }
        return saved
    }

    private fun restoreDraft(text: String, attachments: List<Attachment>, generation: Int) {
        // Never restore a draft into a chat the user has already left.
        if (generation != activeGeneration) return
        _state.update {
            it.copy(
                input = if (it.input.isBlank()) text else it.input,
                attachments = if (it.attachments.isEmpty()) attachments else it.attachments,
            )
        }
        savedState?.set(KEY_INPUT, _state.value.input)
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

    /**
     * Reads and decodes the image entirely off the main thread. Every bitmap is
     * recycled in `finally`, including when decoding or scaling fails halfway.
     */
    private suspend fun loadVisionInput(attachment: Attachment): VisionInput? =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(attachment.uri)
            if (querySize(uri) > AttachmentPolicy.MAX_IMAGE_BYTES) return@withContext null
            var bitmap: Bitmap? = null
            var scaled: Bitmap? = null
            try {
                val resolver = container.context.contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                var sample = 1
                while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                bitmap = resolver.openInputStream(uri)
                    ?.use { BitmapFactory.decodeStream(it, null, options) } ?: return@withContext null
                val original = bitmap ?: return@withContext null
                val maxSide = 896
                val ratio = min(1f, maxSide.toFloat() / max(original.width, original.height))
                val working = if (ratio < 1f) {
                    Bitmap.createScaledBitmap(
                        original,
                        max(1, (original.width * ratio).toInt()),
                        max(1, (original.height * ratio).toInt()),
                        true,
                    ).also { scaled = it }
                } else {
                    original
                }
                val width = working.width
                val height = working.height
                val pixels = IntArray(width * height)
                working.getPixels(pixels, 0, width, 0, 0, width, height)
                val rgb = ByteArray(pixels.size * 3)
                for (index in pixels.indices) {
                    val pixel = pixels[index]
                    rgb[index * 3] = ((pixel shr 16) and 0xFF).toByte()
                    rgb[index * 3 + 1] = ((pixel shr 8) and 0xFF).toByte()
                    rgb[index * 3 + 2] = (pixel and 0xFF).toByte()
                }
                VisionInput(rgb, width, height)
            } catch (error: Exception) {
                null
            } catch (error: OutOfMemoryError) {
                null
            } finally {
                val scaledBitmap = scaled
                if (scaledBitmap != null && scaledBitmap !== bitmap) scaledBitmap.recycle()
                bitmap?.recycle()
            }
        }

    private fun queryName(uri: Uri): String? = runCatching {
        container.context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun querySize(uri: Uri): Long = runCatching {
        container.context.contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    cursor.getLong(index)
                } else {
                    -1L
                }
            } ?: -1L
    }.getOrDefault(-1L)

    override fun onCleared() {
        // A native generation loop is not cancellable through the coroutine
        // machinery alone; signal it before cancelling the job so the model is
        // not left decoding after the screen is gone.
        container.engine.stop()
        cancelTimeout()
        generationJob?.cancel()
        messagesJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val KEY_CONVERSATION = "chat_conversation_id"
        private const val KEY_INPUT = "chat_draft_input"

        /** Three minutes: enough for a long local answer, short enough to fail clearly. */
        private const val RESPONSE_TIMEOUT_MS = 180_000L
    }
}
