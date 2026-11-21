package com.bigbai.core.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.bigbai.core.db.AppDatabase
import com.bigbai.core.db.ConversationEntity
import com.bigbai.core.db.FolderEntity
import com.bigbai.core.db.MemoryCategoryEntity
import com.bigbai.core.db.MemoryEntity
import com.bigbai.core.db.MessageEntity
import com.bigbai.core.db.ProjectEntity
import com.bigbai.core.db.ProjectMemoryEntity
import com.bigbai.core.db.ProjectMessageEntity
import com.bigbai.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class BackupInfo(
    val bytes: Long,
    val sha256: String,
    val conversations: Int,
    val messages: Int,
    val memories: Int,
    val alreadyUpToDate: Boolean = false,
)

data class RestoreSummary(
    val conversations: Int,
    val messages: Int,
    val memories: Int,
    val projects: Int,
)

@Serializable
private data class Snapshot(
    val version: Int = 1,
    val createdAt: Long = 0,
    val folders: List<FolderDto> = emptyList(),
    val conversations: List<ConversationDto> = emptyList(),
    val messages: List<MessageDto> = emptyList(),
    val categories: List<CategoryDto> = emptyList(),
    val memories: List<MemoryDto> = emptyList(),
    val projects: List<ProjectDto> = emptyList(),
    val projectMemories: List<ProjectMemoryDto> = emptyList(),
    val projectMessages: List<ProjectMessageDto> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
)

@Serializable private data class FolderDto(val id: Long, val name: String, val createdAt: Long)
@Serializable private data class ConversationDto(
    val id: Long, val title: String, val folderId: Long?, val favorite: Boolean,
    val archived: Boolean, val createdAt: Long, val updatedAt: Long, val modelId: String?,
)
@Serializable private data class MessageDto(
    val id: Long, val conversationId: Long, val role: String, val content: String,
    val createdAt: Long, val modelId: String?, val tokensPerSecond: Double?,
    val promptTokens: Int, val completionTokens: Int, val attachmentsJson: String, val sourcesJson: String,
)
@Serializable private data class CategoryDto(val id: Long, val name: String, val color: Long, val isDefault: Boolean)
@Serializable private data class MemoryDto(
    val id: Long, val categoryId: Long, val content: String, val createdAt: Long,
    val updatedAt: Long, val sourceConversationId: Long?,
)
@Serializable private data class ProjectDto(
    val id: Long, val name: String, val treeUri: String, val language: String,
    val createdAt: Long, val updatedAt: Long, val lastOpenedAt: Long,
    val gitEnabled: Boolean, val networkAllowed: Boolean,
)
@Serializable private data class ProjectMemoryDto(
    val id: Long, val projectId: Long, val kind: String, val content: String, val createdAt: Long,
)
@Serializable private data class ProjectMessageDto(
    val id: Long, val projectId: Long, val role: String, val content: String, val createdAt: Long,
)

/**
 * Encrypted, portable backups.
 *
 * A user passphrase (PBKDF2-HMAC-SHA256, 150k iterations) derives the AES-256-GCM
 * key, so the archive can be restored on another device. Android Keystore keys
 * are intentionally not used here: they are device-bound and would make the
 * backup useless after a device change.
 */
class BackupManager(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val random = SecureRandom()

    suspend fun export(
        uri: Uri,
        passphrase: CharArray,
        includeProjects: Boolean,
    ): Result<BackupInfo> = withContext(Dispatchers.IO) {
        runCatching {
            require(passphrase.size >= 8) { "La password deve avere almeno 8 caratteri" }
            val snapshot = buildSnapshot(includeProjects)
            val plaintext = json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(plaintext)
                .joinToString("") { "%02x".format(it) }
            val current = settings.current()
            if (digest == current.lastBackupHash) {
                return@runCatching BackupInfo(
                    bytes = 0,
                    sha256 = digest,
                    conversations = snapshot.conversations.size,
                    messages = snapshot.messages.size,
                    memories = snapshot.memories.size,
                    alreadyUpToDate = true,
                )
            }

            val payload = encrypt(plaintext, passphrase)
            val resolver = context.contentResolver
            resolver.openOutputStream(uri, "wt")?.use { output -> output.write(payload) }
                ?: error("Impossibile scrivere il file di backup")

            settings.setLastBackupAt(System.currentTimeMillis())
            settings.setLastBackupHash(digest)
            BackupInfo(
                bytes = payload.size.toLong(),
                sha256 = digest,
                conversations = snapshot.conversations.size,
                messages = snapshot.messages.size,
                memories = snapshot.memories.size,
            )
        }
    }

    suspend fun restore(uri: Uri, passphrase: CharArray): Result<RestoreSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = context.contentResolver.openInputStream(uri)?.use { input ->
                    readBounded(input, MAX_BACKUP_BYTES)
                } ?: error("Impossibile leggere il file di backup")
                val plaintext = decrypt(payload, passphrase)
                val snapshot = json.decodeFromString<Snapshot>(plaintext.toString(Charsets.UTF_8))
                require(snapshot.version == 1) { "Versione di backup non supportata" }
                applySnapshot(snapshot)
                RestoreSummary(
                    conversations = snapshot.conversations.size,
                    messages = snapshot.messages.size,
                    memories = snapshot.memories.size,
                    projects = snapshot.projects.size,
                )
            }
        }

    private suspend fun buildSnapshot(includeProjects: Boolean): Snapshot {
        val current = settings.current()
        return Snapshot(
            createdAt = System.currentTimeMillis(),
            folders = db.folderDao().getAll().map { FolderDto(it.id, it.name, it.createdAt) },
            conversations = db.conversationDao().getAll().map {
                ConversationDto(it.id, it.title, it.folderId, it.favorite, it.archived, it.createdAt, it.updatedAt, it.modelId)
            },
            messages = db.messageDao().getAll().map {
                MessageDto(
                    it.id, it.conversationId, it.role, it.content, it.createdAt, it.modelId,
                    it.tokensPerSecond, it.promptTokens, it.completionTokens, it.attachmentsJson, it.sourcesJson,
                )
            },
            categories = db.memoryDao().getAllCategories().map {
                CategoryDto(it.id, it.name, it.color, it.isDefault)
            },
            memories = db.memoryDao().getAll().map {
                MemoryDto(it.id, it.categoryId, it.content, it.createdAt, it.updatedAt, it.sourceConversationId)
            },
            projects = if (includeProjects) {
                db.projectDao().getAll().map {
                    ProjectDto(it.id, it.name, it.treeUri, it.language, it.createdAt, it.updatedAt, it.lastOpenedAt, it.gitEnabled, it.networkAllowed)
                }
            } else {
                emptyList()
            },
            projectMemories = if (includeProjects) {
                db.projectMemoryDao().getAll().map {
                    ProjectMemoryDto(it.id, it.projectId, it.kind, it.content, it.createdAt)
                }
            } else {
                emptyList()
            },
            projectMessages = if (includeProjects) {
                db.projectMessageDao().getAll().map {
                    ProjectMessageDto(it.id, it.projectId, it.role, it.content, it.createdAt)
                }
            } else {
                emptyList()
            },
            settings = mapOf(
                "language" to current.language,
                "theme" to current.theme.name,
                "memoryEnabled" to current.memoryEnabled.toString(),
                "ramMode" to current.ramMode.name,
                "performanceMode" to current.performanceMode.name,
                "temperature" to current.temperature.toString(),
                "topP" to current.topP.toString(),
                "topK" to current.topK.toString(),
                "maxTokens" to current.maxTokens.toString(),
                "autoTitle" to current.autoTitle.toString(),
                "searchEnabled" to current.searchEnabled.toString(),
                "searchProvider" to current.searchProvider,
                "searxEndpoint" to current.searxEndpoint,
                "advancedUnlocked" to current.advancedUnlocked.toString(),
            ),
        )
    }

    private suspend fun applySnapshot(snapshot: Snapshot) {
        db.withTransaction {
            db.messageDao().clear()
            db.conversationDao().clear()
            db.folderDao().clear()
            db.memoryDao().clearAll()
            db.memoryDao().clearCategories()
            db.projectMessageDao().clearAll()
            db.projectMemoryDao().clearAll()
            db.codeChangeDao().clear()
            db.projectDao().clear()

            snapshot.folders.forEach { db.folderDao().insert(FolderEntity(it.id, it.name, it.createdAt)) }
            snapshot.conversations.forEach {
                db.conversationDao().insert(
                    ConversationEntity(it.id, it.title, it.folderId, it.favorite, it.archived, it.createdAt, it.updatedAt, it.modelId),
                )
            }
            snapshot.messages.forEach {
                db.messageDao().insert(
                    MessageEntity(
                        it.id, it.conversationId, it.role, it.content, it.createdAt, it.modelId,
                        it.tokensPerSecond, it.promptTokens, it.completionTokens, it.attachmentsJson, it.sourcesJson,
                    ),
                )
            }
            snapshot.categories.forEach {
                db.memoryDao().insertCategory(MemoryCategoryEntity(it.id, it.name, it.color, it.isDefault))
            }
            snapshot.memories.forEach {
                db.memoryDao().insert(
                    MemoryEntity(it.id, it.categoryId, it.content, it.createdAt, it.updatedAt, it.sourceConversationId),
                )
            }
            snapshot.projects.forEach {
                db.projectDao().insert(
                    ProjectEntity(
                        it.id, it.name, it.treeUri, it.language, it.createdAt, it.updatedAt,
                        it.lastOpenedAt, it.gitEnabled, it.networkAllowed,
                    ),
                )
            }
            snapshot.projectMemories.forEach {
                db.projectMemoryDao().insert(
                    ProjectMemoryEntity(it.id, it.projectId, it.kind, it.content, it.createdAt),
                )
            }
            snapshot.projectMessages.forEach {
                db.projectMessageDao().insert(
                    ProjectMessageEntity(it.id, it.projectId, it.role, it.content, it.createdAt),
                )
            }
        }

        val map = snapshot.settings
        if (map.isNotEmpty()) {
            settings.update { current ->
                current.copy(
                    language = map["language"] ?: current.language,
                    theme = runCatching { com.bigbai.core.model.ThemeMode.valueOf(map["theme"] ?: "") }
                        .getOrDefault(current.theme),
                    memoryEnabled = map["memoryEnabled"]?.toBooleanStrictOrNull() ?: current.memoryEnabled,
                    ramMode = runCatching { com.bigbai.core.model.RamMode.valueOf(map["ramMode"] ?: "") }
                        .getOrDefault(current.ramMode),
                    performanceMode = runCatching {
                        com.bigbai.core.model.PerformanceMode.valueOf(map["performanceMode"] ?: "")
                    }.getOrDefault(current.performanceMode),
                    temperature = map["temperature"]?.toFloatOrNull() ?: current.temperature,
                    topP = map["topP"]?.toFloatOrNull() ?: current.topP,
                    topK = map["topK"]?.toIntOrNull() ?: current.topK,
                    maxTokens = map["maxTokens"]?.toIntOrNull() ?: current.maxTokens,
                    autoTitle = map["autoTitle"]?.toBooleanStrictOrNull() ?: current.autoTitle,
                    searchEnabled = map["searchEnabled"]?.toBooleanStrictOrNull() ?: current.searchEnabled,
                    searchProvider = map["searchProvider"] ?: current.searchProvider,
                    searxEndpoint = map["searxEndpoint"] ?: current.searxEndpoint,
                    advancedUnlocked = map["advancedUnlocked"]?.toBooleanStrictOrNull() ?: current.advancedUnlocked,
                )
            }
        }
    }

    /** Reads at most [max] bytes; an oversized archive is rejected, not allocated. */
    private fun readBounded(input: InputStream, max: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            if (output.size() + read > max) {
                error("Backup troppo grande (max ${max / (1024 * 1024)} MB)")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun encrypt(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plaintext)
        return MAGIC + salt + cipher.iv + ciphertext
    }

    private fun decrypt(payload: ByteArray, passphrase: CharArray): ByteArray {
        require(payload.size > MAGIC.size + 16 + 12) { "File di backup non valido" }
        require(payload.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Formato di backup non riconosciuto" }
        val salt = payload.copyOfRange(MAGIC.size, MAGIC.size + 16)
        val iv = payload.copyOfRange(MAGIC.size + 16, MAGIC.size + 28)
        val ciphertext = payload.copyOfRange(MAGIC.size + 28, payload.size)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, 150_000, 256)
        val encoded = factory.generateSecret(spec).encoded
        return SecretKeySpec(encoded, "AES")
    }

    companion object {
        private val MAGIC = "BIGBAI1\n".toByteArray(Charsets.US_ASCII)
        private const val MAX_BACKUP_BYTES = 64 * 1024 * 1024
    }
}
