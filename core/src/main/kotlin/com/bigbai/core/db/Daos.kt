package com.bigbai.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE archived = 0 ORDER BY updatedAt DESC")
    fun observeActive(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE archived = 1 ORDER BY updatedAt DESC")
    fun observeArchived(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE folderId = :folderId ORDER BY updatedAt DESC")
    fun observeByFolder(folderId: Long): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: Long): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: Long): ConversationEntity?

    @Query(
        "SELECT * FROM conversations WHERE title LIKE '%' || :query || '%' OR id IN " +
            "(SELECT conversationId FROM messages WHERE content LIKE '%' || :query || '%') " +
            "ORDER BY updatedAt DESC LIMIT 200",
    )
    suspend fun search(query: String): List<ConversationEntity>

    @Query(
        "SELECT c.*, (SELECT COUNT(*) FROM messages m WHERE m.conversationId = c.id) AS messageCount " +
            "FROM conversations c WHERE c.archived = 0 ORDER BY c.updatedAt DESC",
    )
    fun observeActiveWithCount(): Flow<List<ConversationWithCount>>

    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query("UPDATE conversations SET updatedAt = :at, title = :title WHERE id = :id")
    suspend fun touch(id: Long, at: Long, title: String)

    @Query("UPDATE conversations SET folderId = :folderId WHERE id = :id")
    suspend fun moveToFolder(id: Long, folderId: Long?)

    @Query("UPDATE conversations SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE conversations SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM conversations ORDER BY id ASC")
    suspend fun getAll(): List<ConversationEntity>

    @Query("DELETE FROM conversations")
    suspend fun clear()
}

data class ConversationWithCount(
    val id: Long,
    val title: String,
    val folderId: Long?,
    val favorite: Boolean,
    val archived: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val modelId: String?,
    val messageCount: Int,
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun observeForConversation(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    suspend fun getForConversation(conversationId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recent(conversationId: Long, limit: Int): List<MessageEntity>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("UPDATE messages SET content = :content, tokensPerSecond = :tps, promptTokens = :prompt, completionTokens = :completion WHERE id = :id")
    suspend fun updateAssistant(id: Long, content: String, tps: Double?, prompt: Int, completion: Int)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun countForConversation(conversationId: Long): Int

    @Query("SELECT * FROM messages ORDER BY id ASC")
    suspend fun getAll(): List<MessageEntity>

    @Query("DELETE FROM messages")
    suspend fun clear()
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<FolderEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: FolderEntity): Long

    @Query("UPDATE folders SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM folders ORDER BY id ASC")
    suspend fun getAll(): List<FolderEntity>

    @Query("DELETE FROM folders")
    suspend fun clear()
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory_categories ORDER BY id ASC")
    fun observeCategories(): Flow<List<MemoryCategoryEntity>>

    @Query("SELECT * FROM memory_categories ORDER BY id ASC")
    suspend fun getCategories(): List<MemoryCategoryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategory(category: MemoryCategoryEntity): Long

    @Query("DELETE FROM memory_categories WHERE id = :id AND isDefault = 0")
    suspend fun deleteCategory(id: Long)

    @Query(
        "SELECT m.*, c.name AS categoryName FROM memories m " +
            "JOIN memory_categories c ON c.id = m.categoryId ORDER BY m.updatedAt DESC",
    )
    fun observeAll(): Flow<List<MemoryWithCategory>>

    @Query(
        "SELECT m.*, c.name AS categoryName FROM memories m " +
            "JOIN memory_categories c ON c.id = m.categoryId WHERE m.content LIKE '%' || :query || '%' " +
            "ORDER BY m.updatedAt DESC",
    )
    suspend fun search(query: String): List<MemoryWithCategory>

    @Query(
        "SELECT m.*, c.name AS categoryName FROM memories m " +
            "JOIN memory_categories c ON c.id = m.categoryId ORDER BY m.updatedAt DESC LIMIT :limit",
    )
    suspend fun recent(limit: Int): List<MemoryWithCategory>

    @Query(
        "SELECT m.*, c.name AS categoryName FROM memories m " +
            "JOIN memory_categories c ON c.id = m.categoryId WHERE c.name = :name " +
            "ORDER BY m.updatedAt DESC LIMIT :limit",
    )
    suspend fun recentByCategory(name: String, limit: Int): List<MemoryWithCategory>

    @Query(
        "SELECT COUNT(*) FROM memories m JOIN memory_categories c ON c.id = m.categoryId " +
            "WHERE c.name = :name AND m.content = :content",
    )
    suspend fun countByCategoryAndContent(name: String, content: String): Int

    @Insert
    suspend fun insert(memory: MemoryEntity): Long

    @Update
    suspend fun update(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM memories")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Int

    @Query("SELECT * FROM memories ORDER BY id ASC")
    suspend fun getAll(): List<MemoryEntity>

    @Query("DELETE FROM memories")
    suspend fun clearAll()

    @Query("SELECT * FROM memory_categories ORDER BY id ASC")
    suspend fun getAllCategories(): List<MemoryCategoryEntity>

    @Query("DELETE FROM memory_categories")
    suspend fun clearCategories()
}

data class MemoryWithCategory(
    val id: Long,
    val categoryId: Long,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sourceConversationId: Long?,
    val categoryName: String,
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY lastOpenedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observeById(id: Long): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: Long): ProjectEntity?

    @Query("SELECT * FROM projects WHERE treeUri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(project: ProjectEntity): Long

    @Query("UPDATE projects SET lastOpenedAt = :at, updatedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    @Query("UPDATE projects SET name = :name, updatedAt = :at WHERE id = :id")
    suspend fun rename(id: Long, name: String, at: Long)

    @Query("UPDATE projects SET gitEnabled = :enabled WHERE id = :id")
    suspend fun setGitEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE projects SET networkAllowed = :allowed WHERE id = :id")
    suspend fun setNetworkAllowed(id: Long, allowed: Boolean)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM projects ORDER BY id ASC")
    suspend fun getAll(): List<ProjectEntity>

    @Query("DELETE FROM projects")
    suspend fun clear()
}

@Dao
interface ProjectMemoryDao {
    @Query("SELECT * FROM project_memory WHERE projectId = :projectId ORDER BY createdAt DESC")
    fun observe(projectId: Long): Flow<List<ProjectMemoryEntity>>

    @Query("SELECT * FROM project_memory WHERE projectId = :projectId ORDER BY createdAt DESC")
    suspend fun get(projectId: Long): List<ProjectMemoryEntity>

    @Insert
    suspend fun insert(item: ProjectMemoryEntity): Long

    @Query("DELETE FROM project_memory WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM project_memory WHERE projectId = :projectId")
    suspend fun clear(projectId: Long)

    @Query("SELECT * FROM project_memory ORDER BY id ASC")
    suspend fun getAll(): List<ProjectMemoryEntity>

    @Query("DELETE FROM project_memory")
    suspend fun clearAll()
}

@Dao
interface ProjectMessageDao {
    @Query("SELECT * FROM project_messages WHERE projectId = :projectId ORDER BY createdAt ASC, id ASC")
    fun observe(projectId: Long): Flow<List<ProjectMessageEntity>>

    @Insert
    suspend fun insert(message: ProjectMessageEntity): Long

    @Query("DELETE FROM project_messages WHERE projectId = :projectId")
    suspend fun clear(projectId: Long)

    @Query("SELECT * FROM project_messages ORDER BY id ASC")
    suspend fun getAll(): List<ProjectMessageEntity>

    @Query("DELETE FROM project_messages")
    suspend fun clearAll()
}

@Dao
interface CodeChangeDao {
    @Query("SELECT * FROM code_changes WHERE projectId = :projectId ORDER BY createdAt DESC LIMIT 100")
    fun observe(projectId: Long): Flow<List<CodeChangeEntity>>

    @Insert
    suspend fun insert(change: CodeChangeEntity): Long

    @Query("UPDATE code_changes SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("DELETE FROM code_changes")
    suspend fun clear()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE modelId = :modelId")
    suspend fun get(modelId: String): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE modelId = :modelId")
    suspend fun delete(modelId: String)
}

@Dao
interface BackupStateDao {
    @Query("SELECT COUNT(*) FROM messages")
    suspend fun messageCount(): Int

    @Query("SELECT MAX(updatedAt) FROM conversations")
    suspend fun lastConversationUpdate(): Long?
}
