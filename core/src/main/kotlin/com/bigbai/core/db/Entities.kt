package com.bigbai.core.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "conversations",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("folderId"), Index("updatedAt"), Index("archived")],
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val folderId: Long? = null,
    val favorite: Boolean = false,
    val archived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val modelId: String? = null,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId"), Index("createdAt")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val createdAt: Long,
    val modelId: String? = null,
    val tokensPerSecond: Double? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val attachmentsJson: String = "[]",
    val sourcesJson: String = "[]",
)

@Entity(tableName = "memory_categories", indices = [Index(value = ["name"], unique = true)])
data class MemoryCategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Long,
    val isDefault: Boolean = false,
)

@Entity(
    tableName = "memories",
    foreignKeys = [
        ForeignKey(
            entity = MemoryCategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("categoryId"), Index("updatedAt")],
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sourceConversationId: Long? = null,
)

@Entity(tableName = "projects", indices = [Index(value = ["treeUri"], unique = true)])
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val treeUri: String,
    val language: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastOpenedAt: Long,
    val gitEnabled: Boolean = false,
    val networkAllowed: Boolean = false,
)

@Entity(
    tableName = "project_memory",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId")],
)
data class ProjectMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val kind: String,
    val content: String,
    val createdAt: Long,
)

@Entity(
    tableName = "project_messages",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId"), Index("createdAt")],
)
data class ProjectMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val role: String,
    val content: String,
    val createdAt: Long,
)

@Entity(
    tableName = "code_changes",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId"), Index("createdAt")],
)
data class CodeChangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val path: String,
    val changeType: String,
    val diff: String,
    val status: String,
    val createdAt: Long,
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val modelId: String,
    val status: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val error: String? = null,
    val updatedAt: Long,
)
