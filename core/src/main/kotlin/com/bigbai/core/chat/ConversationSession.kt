package com.bigbai.core.chat

/**
 * Single source of truth for the conversation currently open in the chat.
 *
 * ## Why this class exists
 *
 * In 1.0.0 the chat view model created a conversation and then called the
 * "open conversation" path to show it. That path refuses to switch while a
 * generation is running — and `send()` had already set `isGenerating = true`.
 * The new id was therefore never committed: the state kept `conversationId = 0`,
 * the next message created a *second* conversation and the user was silently
 * moved out of the chat they were reading. The UI also cleared the message list
 * as part of the refused switch.
 *
 * Keeping the identity in this small, UI-independent object makes that class of
 * bug impossible: [resolve] never replaces an already bound conversation, and
 * binding is a separate operation from "show this conversation in the UI".
 * It is plain Kotlin, so the regression is covered by a JVM test.
 */
class ConversationSession {

    var conversationId: Long = NO_CONVERSATION
        private set

    val isBound: Boolean get() = conversationId > NO_CONVERSATION

    /** Forget the active conversation (explicit "new chat"). */
    fun reset() {
        conversationId = NO_CONVERSATION
    }

    /**
     * Make [id] the active conversation.
     *
     * @return true when the active id changed; false for an invalid id or when
     *         [id] was already active (so callers do not reload the same chat).
     */
    fun bind(id: Long): Boolean {
        if (id <= NO_CONVERSATION) return false
        if (conversationId == id) return false
        conversationId = id
        return true
    }

    /**
     * Return the active conversation id, creating and binding one only when no
     * conversation is active.
     *
     * [create] persists the conversation and returns its row id. It is invoked
     * at most once for a given session, which is exactly the guarantee that was
     * missing when consecutive messages created a new chat each time.
     */
    fun resolve(create: () -> Long): Long {
        val existing = conversationId
        if (existing > NO_CONVERSATION) return existing
        val created = create()
        check(created > NO_CONVERSATION) {
            "Conversation creation returned an invalid id: $created"
        }
        conversationId = created
        return created
    }

    /**
     * Suspending variant used by the view model, whose persistence layer is
     * asynchronous. Same guarantee: [create] runs only when no conversation is
     * active, and its result becomes the identity before anything else happens.
     */
    suspend fun resolveDeferred(create: suspend () -> Long): Long {
        val existing = conversationId
        if (existing > NO_CONVERSATION) return existing
        val created = create()
        check(created > NO_CONVERSATION) {
            "Conversation creation returned an invalid id: $created"
        }
        conversationId = created
        return created
    }

    companion object {
        const val NO_CONVERSATION: Long = 0L
    }
}
