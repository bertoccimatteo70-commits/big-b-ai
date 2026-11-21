package com.bigbai.core.model

import kotlinx.serialization.Serializable

enum class Role { USER, ASSISTANT, SYSTEM }

/** An attachment stored with a message. Images are kept by content URI and analysed
 *  by a multimodal model; PDFs carry their extracted text so the model can quote it. */
@Serializable
data class Attachment(
    val kind: Kind,
    val uri: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long = 0L,
    val extractedText: String? = null,
) {
    enum class Kind { IMAGE, PDF }
}

@Serializable
data class Source(
    val title: String,
    val url: String,
    val snippet: String = "",
)

data class Conversation(
    val id: Long,
    val title: String,
    val folderId: Long?,
    val favorite: Boolean,
    val archived: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val modelId: String?,
    val messageCount: Int = 0,
)

data class ChatMessage(
    val id: Long,
    val conversationId: Long,
    val role: Role,
    val content: String,
    val createdAt: Long,
    val modelId: String? = null,
    val tokensPerSecond: Double? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val attachments: List<Attachment> = emptyList(),
    val sources: List<Source> = emptyList(),
    val memoryProposal: List<String> = emptyList(),
)

data class Folder(
    val id: Long,
    val name: String,
    val createdAt: Long,
)

data class MemoryCategory(
    val id: Long,
    val name: String,
    val color: Long,
    val isDefault: Boolean,
)

data class MemoryItem(
    val id: Long,
    val categoryId: Long,
    val categoryName: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sourceConversationId: Long?,
)

enum class ProjectLanguage { HTML, CPP, JAVA, OTHER }

data class Project(
    val id: Long,
    val name: String,
    val treeUri: String,
    val language: ProjectLanguage,
    val createdAt: Long,
    val updatedAt: Long,
    val lastOpenedAt: Long,
    val gitEnabled: Boolean,
    val networkAllowed: Boolean,
)

enum class ProjectMemoryKind { STRUCTURE, GOAL, DECISION, ERROR, TEST, NOTE }

data class ProjectMemoryItem(
    val id: Long,
    val projectId: Long,
    val kind: ProjectMemoryKind,
    val content: String,
    val createdAt: Long,
)

data class ProjectChatMessage(
    val id: Long,
    val projectId: Long,
    val role: Role,
    val content: String,
    val createdAt: Long,
)

data class CodeChange(
    val id: Long,
    val projectId: Long,
    val path: String,
    val changeType: String,
    val diff: String,
    val status: String,
    val createdAt: Long,
)

/** Measured generation statistics. Every field is sampled at runtime; nothing here
 *  is estimated or synthesized. */
/**
 * Why the native generation loop stopped. `LENGTH` means the model hit the
 * token budget and the answer is genuinely cut; `EOS` means the model declared
 * the answer finished, even if the text does not end with a full stop.
 */
enum class FinishReason { UNKNOWN, EOS, LENGTH, CANCELLED, TIMEOUT, ERROR }

data class GenerationMetrics(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val promptMs: Long = 0,
    val generationMs: Long = 0,
    val firstTokenMs: Long = 0,
    val tokensPerSecond: Double = 0.0,
    val peakRamBytes: Long = 0,
    val modelId: String = "",
    val finishReason: FinishReason = FinishReason.UNKNOWN,
)
