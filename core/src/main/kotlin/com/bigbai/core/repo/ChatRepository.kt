package com.bigbai.core.repo

import com.bigbai.core.db.AppDatabase
import com.bigbai.core.db.ConversationEntity
import com.bigbai.core.db.ConversationWithCount
import com.bigbai.core.db.FolderEntity
import com.bigbai.core.db.MessageEntity
import com.bigbai.core.model.ChatMessage
import com.bigbai.core.model.Conversation
import com.bigbai.core.model.Folder
import com.bigbai.core.model.Role
import com.bigbai.core.model.Source
import com.bigbai.core.model.Attachment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ChatRepository(private val db: AppDatabase) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun conversations(): Flow<List<Conversation>> =
        db.conversationDao().observeActiveWithCount().map { list -> list.map { it.toDomain() } }

    fun archivedConversations(): Flow<List<Conversation>> =
        db.conversationDao().observeArchived().map { list -> list.map { it.toDomain(0) } }

    fun conversationsInFolder(folderId: Long): Flow<List<Conversation>> =
        db.conversationDao().observeByFolder(folderId).map { list -> list.map { it.toDomain(0) } }

    fun conversation(id: Long): Flow<Conversation?> =
        db.conversationDao().observeById(id).map { it?.toDomain(0) }

    fun messages(conversationId: Long): Flow<List<ChatMessage>> =
        db.messageDao().observeForConversation(conversationId).map { list -> list.map { it.toDomain() } }

    suspend fun messagesNow(conversationId: Long): List<ChatMessage> =
        db.messageDao().getForConversation(conversationId).map { it.toDomain() }

    suspend fun createConversation(modelId: String?): Long {
        val now = System.currentTimeMillis()
        return db.conversationDao().insert(
            ConversationEntity(
                title = "",
                createdAt = now,
                updatedAt = now,
                modelId = modelId,
            ),
        )
    }

    suspend fun appendMessage(
        conversationId: Long,
        role: Role,
        content: String,
        modelId: String? = null,
        attachments: List<Attachment> = emptyList(),
        sources: List<Source> = emptyList(),
        promptTokens: Int = 0,
        completionTokens: Int = 0,
        tokensPerSecond: Double? = null,
    ): Long {
        val now = System.currentTimeMillis()
        val id = db.messageDao().insert(
            MessageEntity(
                conversationId = conversationId,
                role = role.name,
                content = content,
                createdAt = now,
                modelId = modelId,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                tokensPerSecond = tokensPerSecond,
                attachmentsJson = json.encodeToString(attachments),
                sourcesJson = json.encodeToString(sources),
            ),
        )
        val conversation = db.conversationDao().getById(conversationId)
        val title = conversation?.title.orEmpty()
        if (title.isBlank() && role == Role.USER) {
            db.conversationDao().touch(conversationId, now, deriveTitle(content))
        } else {
            db.conversationDao().touch(conversationId, now, title)
        }
        return id
    }

    suspend fun updateAssistantMessage(
        id: Long,
        content: String,
        tokensPerSecond: Double?,
        promptTokens: Int,
        completionTokens: Int,
    ) = db.messageDao().updateAssistant(id, content, tokensPerSecond, promptTokens, completionTokens)

    /** Rewrites a user message in place, then drops the answers that followed it. */
    suspend fun editUserMessage(messageId: Long, conversationId: Long, content: String) {
        db.messageDao().updateContent(messageId, content)
        db.messageDao().deleteAfter(conversationId, messageId)
    }

    /** Removes generated messages after [messageId], keeping the user's turn. */
    suspend fun deleteMessagesAfter(conversationId: Long, messageId: Long) =
        db.messageDao().deleteAfter(conversationId, messageId)

    suspend fun deleteMessage(messageId: Long) = db.messageDao().delete(messageId)

    suspend fun setTitle(conversationId: Long, title: String) {
        db.conversationDao().rename(conversationId, title)
        db.conversationDao().touch(conversationId, System.currentTimeMillis(), title)
    }

    suspend fun rename(conversationId: Long, title: String) = db.conversationDao().rename(conversationId, title)

    suspend fun toggleFavorite(conversationId: Long, favorite: Boolean) =
        db.conversationDao().setFavorite(conversationId, favorite)

    suspend fun setArchived(conversationId: Long, archived: Boolean) =
        db.conversationDao().setArchived(conversationId, archived)

    suspend fun moveToFolder(conversationId: Long, folderId: Long?) =
        db.conversationDao().moveToFolder(conversationId, folderId)

    /** Permanent deletion. The UI must have completed the typed-code confirmation first. */
    suspend fun deleteConversation(conversationId: Long) = db.conversationDao().delete(conversationId)

    suspend fun search(query: String): List<Conversation> {
        if (query.isBlank()) return emptyList()
        return db.conversationDao().search(query.trim()).map { it.toDomain(0) }
    }

    fun folders(): Flow<List<Folder>> = db.folderDao().observeAll().map { list ->
        list.map { Folder(it.id, it.name, it.createdAt) }
    }

    suspend fun createFolder(name: String): Long =
        db.folderDao().insert(FolderEntity(name = name.trim(), createdAt = System.currentTimeMillis()))

    suspend fun renameFolder(id: Long, name: String) = db.folderDao().rename(id, name.trim())

    suspend fun deleteFolder(id: Long) = db.folderDao().delete(id)

    suspend fun lastMessagePreview(conversationId: Long): String? =
        db.messageDao().recent(conversationId, 1).firstOrNull()?.content

    companion object {
        /**
         * Deterministic, language-agnostic title derivation from the first user
         * message. Keeps the sidebar useful without spending an inference pass.
         */
        fun deriveTitle(text: String, maxChars: Int = 52): String {
            val clean = text
                .replace(Regex("```[\\s\\S]*?```"), " ")
                .replace(Regex("[#*_>`]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (clean.isEmpty()) return ""
            if (clean.length <= maxChars) return clean
            val cut = clean.take(maxChars)
            val lastSpace = cut.lastIndexOf(' ')
            val base = if (lastSpace > 24) cut.take(lastSpace) else cut
            return "$base…"
        }
    }
}

private val messageJson = Json { ignoreUnknownKeys = true }

private fun ConversationWithCount.toDomain(): Conversation = Conversation(
    id = id,
    title = title.ifBlank { "—" },
    folderId = folderId,
    favorite = favorite,
    archived = archived,
    createdAt = createdAt,
    updatedAt = updatedAt,
    modelId = modelId,
    messageCount = messageCount,
)

private fun ConversationEntity.toDomain(count: Int): Conversation = Conversation(
    id = id,
    title = title.ifBlank { "—" },
    folderId = folderId,
    favorite = favorite,
    archived = archived,
    createdAt = createdAt,
    updatedAt = updatedAt,
    modelId = modelId,
    messageCount = count,
)

private fun MessageEntity.toDomain(): ChatMessage = ChatMessage(
    id = id,
    conversationId = conversationId,
    role = runCatching { Role.valueOf(role) }.getOrDefault(Role.ASSISTANT),
    content = content,
    createdAt = createdAt,
    modelId = modelId,
    tokensPerSecond = tokensPerSecond,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    attachments = runCatching {
        messageJson.decodeFromString<List<Attachment>>(attachmentsJson)
    }.getOrDefault(emptyList()),
    sources = runCatching {
        messageJson.decodeFromString<List<Source>>(sourcesJson)
    }.getOrDefault(emptyList()),
)
