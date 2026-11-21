package com.bigbai.app.ui.chat

/**
 * Pure state transitions for the chat screen.
 *
 * They live outside [ChatViewModel] so the guarantee "a new chat inherits
 * nothing" can be unit-tested on the JVM without Android. The previous
 * implementation copied a hand-written subset of fields and kept the draft,
 * the attachments and the performance panel of the previous conversation.
 */

/**
 * A brand-new chat: every per-conversation field back to its initial value.
 * Model readiness is global state (the engine stays loaded), so it is kept.
 */
fun ChatUiState.afterNewChat(): ChatUiState = ChatUiState(
    modelLabel = modelLabel,
    modelReady = modelReady,
    visionReady = visionReady,
    webSearchEnabled = webSearchEnabled,
)

/** Switching conversation clears all per-conversation UI state. */
fun ChatUiState.afterOpenConversation(id: Long): ChatUiState = copy(
    conversationId = id,
    messages = emptyList(),
    streamingText = "",
    input = "",
    attachments = emptyList(),
    isGenerating = false,
    isThinking = false,
    status = null,
    error = null,
    metrics = null,
    sources = emptyList(),
    searchingWeb = false,
    searchProvider = null,
    searchSummary = null,
    proposals = emptyList(),
    lexiconSaved = 0,
)
