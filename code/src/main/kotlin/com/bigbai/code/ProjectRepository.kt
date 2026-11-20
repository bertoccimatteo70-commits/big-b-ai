package com.bigbai.code

import android.content.Context
import com.bigbai.core.db.AppDatabase
import com.bigbai.core.db.CodeChangeEntity
import com.bigbai.core.db.ProjectEntity
import com.bigbai.core.db.ProjectMemoryEntity
import com.bigbai.core.db.ProjectMessageEntity
import com.bigbai.core.model.CodeChange
import com.bigbai.core.model.Project
import com.bigbai.core.model.ProjectChatMessage
import com.bigbai.core.model.ProjectLanguage
import com.bigbai.core.model.ProjectMemoryItem
import com.bigbai.core.model.ProjectMemoryKind
import com.bigbai.core.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProjectRepository(
    private val db: AppDatabase,
    private val files: ProjectFiles,
) {
    fun observeProjects(): Flow<List<Project>> = db.projectDao().observeAll().map { list ->
        list.map { it.toDomain() }
    }

    fun observeProject(id: Long): Flow<Project?> = db.projectDao().observeById(id).map { it?.toDomain() }

    suspend fun project(id: Long): Project? = db.projectDao().getById(id)?.toDomain()

    suspend fun addProject(treeUri: String, name: String, language: ProjectLanguage): Long {
        val existing = db.projectDao().findByUri(treeUri)
        val now = System.currentTimeMillis()
        if (existing != null) {
            db.projectDao().touch(existing.id, now)
            return existing.id
        }
        return db.projectDao().insert(
            ProjectEntity(
                name = name,
                treeUri = treeUri,
                language = language.name,
                createdAt = now,
                updatedAt = now,
                lastOpenedAt = now,
            ),
        )
    }

    suspend fun touch(id: Long) = db.projectDao().touch(id, System.currentTimeMillis())

    suspend fun rename(id: Long, name: String) = db.projectDao().rename(id, name, System.currentTimeMillis())

    suspend fun setNetworkAllowed(id: Long, allowed: Boolean) = db.projectDao().setNetworkAllowed(id, allowed)

    suspend fun setGitEnabled(id: Long, enabled: Boolean) = db.projectDao().setGitEnabled(id, enabled)

    /** Removes only the app-side project record; the user's folder is never touched. */
    suspend fun removeProject(id: Long) = db.projectDao().delete(id)

    fun memory(projectId: Long): Flow<List<ProjectMemoryItem>> =
        db.projectMemoryDao().observe(projectId).map { list ->
            list.map {
                ProjectMemoryItem(
                    id = it.id,
                    projectId = it.projectId,
                    kind = runCatching { ProjectMemoryKind.valueOf(it.kind) }.getOrDefault(ProjectMemoryKind.NOTE),
                    content = it.content,
                    createdAt = it.createdAt,
                )
            }
        }

    suspend fun remember(projectId: Long, kind: ProjectMemoryKind, content: String): Long =
        db.projectMemoryDao().insert(
            ProjectMemoryEntity(
                projectId = projectId,
                kind = kind.name,
                content = content.trim(),
                createdAt = System.currentTimeMillis(),
            ),
        )

    suspend fun forget(memoryId: Long) = db.projectMemoryDao().delete(memoryId)

    suspend fun memorySummary(projectId: Long, maxChars: Int = 2000): String {
        val items = db.projectMemoryDao().get(projectId)
        if (items.isEmpty()) return ""
        val builder = StringBuilder()
        for (item in items) {
            val line = "- ${item.kind}: ${item.content}"
            if (builder.length + line.length > maxChars) break
            builder.append(line).append('\n')
        }
        return builder.toString().trim()
    }

    fun messages(projectId: Long): Flow<List<ProjectChatMessage>> =
        db.projectMessageDao().observe(projectId).map { list ->
            list.map {
                ProjectChatMessage(
                    id = it.id,
                    projectId = it.projectId,
                    role = runCatching { Role.valueOf(it.role) }.getOrDefault(Role.ASSISTANT),
                    content = it.content,
                    createdAt = it.createdAt,
                )
            }
        }

    suspend fun appendMessage(projectId: Long, role: Role, content: String): Long =
        db.projectMessageDao().insert(
            ProjectMessageEntity(
                projectId = projectId,
                role = role.name,
                content = content,
                createdAt = System.currentTimeMillis(),
            ),
        )

    suspend fun clearMessages(projectId: Long) = db.projectMessageDao().clear(projectId)

    fun changes(projectId: Long): Flow<List<CodeChange>> = db.codeChangeDao().observe(projectId).map { list ->
        list.map {
            CodeChange(it.id, it.projectId, it.path, it.changeType, it.diff, it.status, it.createdAt)
        }
    }

    suspend fun recordChange(
        projectId: Long,
        path: String,
        changeType: String,
        diff: String,
        status: String,
    ): Long = db.codeChangeDao().insert(
        CodeChangeEntity(
            projectId = projectId,
            path = path,
            changeType = changeType,
            diff = diff,
            status = status,
            createdAt = System.currentTimeMillis(),
        ),
    )

    suspend fun setChangeStatus(id: Long, status: String) = db.codeChangeDao().setStatus(id, status)

    suspend fun applyChanges(
        projectId: Long,
        treeUri: String,
        changes: List<ProposedChange>,
    ): ApplyResult {
        val root = files.root(treeUri) ?: return ApplyResult(false, "Cartella del progetto non accessibile")
        val applied = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (change in changes) {
            val safe = files.sanitize(change.path)
            if (safe == null) {
                failed.add("${change.path}: percorso non valido")
                continue
            }
            val ok = if (change.delete) {
                files.delete(root, safe)
            } else {
                files.writeText(root, safe, change.content ?: "")
            }
            if (ok) applied.add(safe) else failed.add(safe)
        }
        return ApplyResult(failed.isEmpty(), "Applicati ${applied.size}, falliti ${failed.size}", applied, failed)
    }
}

data class ApplyResult(
    val success: Boolean,
    val message: String,
    val applied: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
)

private fun ProjectEntity.toDomain(): Project = Project(
    id = id,
    name = name,
    treeUri = treeUri,
    language = runCatching { ProjectLanguage.valueOf(language) }.getOrDefault(ProjectLanguage.OTHER),
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastOpenedAt = lastOpenedAt,
    gitEnabled = gitEnabled,
    networkAllowed = networkAllowed,
)
