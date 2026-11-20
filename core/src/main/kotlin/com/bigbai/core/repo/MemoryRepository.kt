package com.bigbai.core.repo

import com.bigbai.core.db.AppDatabase
import com.bigbai.core.db.MemoryCategoryEntity
import com.bigbai.core.db.MemoryEntity
import com.bigbai.core.model.MemoryCategory
import com.bigbai.core.model.MemoryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class MemoryProposal(
    val content: String,
    val categoryName: String,
    val reason: String,
)

class MemoryRepository(private val db: AppDatabase) {

    fun categories(): Flow<List<MemoryCategory>> = db.memoryDao().observeCategories().map { list ->
        list.map { MemoryCategory(it.id, it.name, it.color, it.isDefault) }
    }

    fun memories(): Flow<List<MemoryItem>> = db.memoryDao().observeAll().map { list ->
        list.map {
            MemoryItem(
                id = it.id,
                categoryId = it.categoryId,
                categoryName = it.categoryName,
                content = it.content,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
                sourceConversationId = it.sourceConversationId,
            )
        }
    }

    suspend fun ensureDefaultCategories() {
        if (db.memoryDao().getCategories().isNotEmpty()) return
        DEFAULT_CATEGORIES.forEach { (name, color) ->
            db.memoryDao().insertCategory(MemoryCategoryEntity(name = name, color = color, isDefault = true))
        }
    }

    suspend fun categoryId(name: String): Long {
        ensureDefaultCategories()
        val existing = db.memoryDao().getCategories().firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (existing != null) return existing.id
        val created = db.memoryDao().insertCategory(
            MemoryCategoryEntity(name = name, color = 0xFF2563EB, isDefault = false),
        )
        return if (created > 0) created else db.memoryDao().getCategories().first { it.name == name }.id
    }

    suspend fun add(content: String, categoryName: String, conversationId: Long? = null): Long {
        val now = System.currentTimeMillis()
        return db.memoryDao().insert(
            MemoryEntity(
                categoryId = categoryId(categoryName),
                content = content.trim(),
                createdAt = now,
                updatedAt = now,
                sourceConversationId = conversationId,
            ),
        )
    }

    suspend fun update(id: Long, content: String) {
        val existing = db.memoryDao().recent(10_000).firstOrNull { it.id == id } ?: return
        db.memoryDao().update(
            MemoryEntity(
                id = existing.id,
                categoryId = existing.categoryId,
                content = content.trim(),
                createdAt = existing.createdAt,
                updatedAt = System.currentTimeMillis(),
                sourceConversationId = existing.sourceConversationId,
            ),
        )
    }

    suspend fun delete(id: Long) = db.memoryDao().delete(id)

    suspend fun clear() = db.memoryDao().clear()

    suspend fun search(query: String): List<MemoryItem> =
        if (query.isBlank()) emptyList()
        else db.memoryDao().search(query.trim()).map {
            MemoryItem(it.id, it.categoryId, it.categoryName, it.content, it.createdAt, it.updatedAt, it.sourceConversationId)
        }

    suspend fun createCategory(name: String): Long =
        db.memoryDao().insertCategory(MemoryCategoryEntity(name = name.trim(), color = 0xFF2563EB, isDefault = false))

    suspend fun deleteCategory(id: Long) = db.memoryDao().deleteCategory(id)

    /** Memory context injected into the system prompt, bounded to keep the window small. */
    suspend fun promptContext(maxItems: Int = 24, maxChars: Int = 2400): String {
        val items = db.memoryDao().recent(maxItems)
        if (items.isEmpty()) return ""
        val builder = StringBuilder()
        for (item in items) {
            val line = "- [${item.categoryName}] ${item.content}"
            if (builder.length + line.length > maxChars) break
            builder.append(line).append('\n')
        }
        return builder.toString().trim()
    }

    companion object {
        val DEFAULT_CATEGORIES = listOf(
            "Personale" to 0xFF2563EB,
            "Preferenze" to 0xFF0EA5E9,
            "Lavoro" to 0xFF1D4ED8,
            "Studio" to 0xFF38BDF8,
            "Progetti" to 0xFF6366F1,
        )
    }
}

/**
 * Rule-based extraction of explicit, durable user facts.
 *
 * This is deliberately conservative and fully local: it only proposes memories
 * when the user states something personal in an explicit form, and the UI asks
 * for confirmation before anything is stored. It never invents facts.
 */
object MemoryExtractor {
    private val patterns = listOf(
        Regex("(?i)\\bmi chiamo\\s+([A-ZÀ-Ý][\\p{L}'-]+(?:\\s+[A-ZÀ-Ý][\\p{L}'-]+)?)"),
        Regex("(?i)\\bmy name is\\s+([A-Z][\\p{L}'-]+(?:\\s+[A-Z][\\p{L}'-]+)?)"),
        Regex("(?i)\\b(?:preferisco|preferirei)\\s+([^.!?\\n]{3,120})"),
        Regex("(?i)\\bi prefer\\s+([^.!?\\n]{3,120})"),
        Regex("(?i)\\b(?:lavoro come|faccio il|faccio la)\\s+([^.!?\\n]{3,80})"),
        Regex("(?i)\\bi work as\\s+([^.!?\\n]{3,80})"),
        Regex("(?i)\\b(?:abito a|vivo a)\\s+([^.!?\\n]{2,60})"),
        Regex("(?i)\\bi live in\\s+([^.!?\\n]{2,60})"),
        Regex("(?i)\\b(?:ricorda|ricordati)\\s+(?:che\\s+)?([^.!?\\n]{4,160})"),
        Regex("(?i)\\bremember that\\s+([^.!?\\n]{4,160})"),
    )

    fun propose(text: String): List<MemoryProposal> {
        val sentences = text.split(Regex("(?<=[.!?])\\s+|\\n"))
        val proposals = LinkedHashMap<String, MemoryProposal>()
        for (sentence in sentences) {
            val trimmed = sentence.trim()
            if (trimmed.length < 8 || trimmed.length > 240) continue
            for (pattern in patterns) {
                if (pattern.containsMatchIn(trimmed)) {
                    val category = classify(trimmed)
                    proposals[trimmed.lowercase()] = MemoryProposal(
                        content = trimmed,
                        categoryName = category,
                        reason = "Fact stated explicitly by the user",
                    )
                    break
                }
            }
        }
        return proposals.values.take(3)
    }

    fun classify(text: String): String {
        val lower = text.lowercase()
        return when {
            listOf("lavoro", "ufficio", "cliente", "job", "work", "office", "azienda").any { it in lower } -> "Lavoro"
            listOf("studio", "università", "scuola", "esame", "study", "school", "university").any { it in lower } -> "Studio"
            listOf("progetto", "codice", "app", "project", "code").any { it in lower } -> "Progetti"
            listOf("preferisco", "prefer", "mi piace", "i like", "odio", "hate").any { it in lower } -> "Preferenze"
            else -> "Personale"
        }
    }
}
