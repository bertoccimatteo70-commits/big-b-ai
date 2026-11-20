package com.bigbai.llm

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
data class InstalledRecord(
    val modelId: String,
    val installedAt: Long,
    val verified: Boolean,
)

data class InstalledModel(
    val variant: ModelVariant,
    val modelPath: String,
    val mmprojPath: String?,
    val installedAt: Long,
    val verified: Boolean,
) {
    val totalBytes: Long get() = variant.totalBytes
}

/**
 * Owns the on-disk model directory. A model counts as installed only when every
 * declared file exists with the exact expected byte size; integrity is verified
 * separately by hashing.
 */
class ModelStore(private val context: Context) {
    private val root = File(context.filesDir, "models")
    private val indexFile = File(root, "installed.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        root.mkdirs()
    }

    fun directoryFor(variant: ModelVariant): File = File(root, variant.id).apply { mkdirs() }

    fun fileFor(variant: ModelVariant, file: CatalogFile): File = File(directoryFor(variant), file.name)

    fun modelFile(variant: ModelVariant): File = fileFor(variant, variant.files.first())

    fun mmprojFile(variant: ModelVariant): File? = variant.mmproj?.let { fileFor(variant, it) }

    fun installed(variant: ModelVariant): InstalledModel? {
        val model = modelFile(variant)
        if (!model.isFile) return null
        if (variant.files.any { !fileFor(variant, it).isFile || fileFor(variant, it).length() != it.size }) {
            return null
        }
        val record = readIndex()[variant.id]
        val mmproj = mmprojFile(variant)
        return InstalledModel(
            variant = variant,
            modelPath = model.absolutePath,
            mmprojPath = mmproj?.takeIf { it.isFile }?.absolutePath,
            installedAt = record?.installedAt ?: model.lastModified(),
            verified = record?.verified ?: false,
        )
    }

    fun installedAll(catalog: ModelCatalog): List<InstalledModel> =
        catalog.all.mapNotNull { installed(it) }.sortedBy { it.installedAt }

    fun markInstalled(variant: ModelVariant, verified: Boolean) {
        val index = readIndex().toMutableMap()
        index[variant.id] = InstalledRecord(variant.id, System.currentTimeMillis(), verified)
        writeIndex(index)
    }

    fun markVerified(variant: ModelVariant, verified: Boolean) {
        val index = readIndex().toMutableMap()
        val existing = index[variant.id] ?: return
        index[variant.id] = existing.copy(verified = verified)
        writeIndex(index)
    }

    fun delete(variant: ModelVariant): Boolean {
        val dir = directoryFor(variant)
        val deleted = dir.deleteRecursively()
        val index = readIndex().toMutableMap()
        index.remove(variant.id)
        writeIndex(index)
        return deleted
    }

    fun totalBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun freeSpaceBytes(): Long = context.filesDir.usableSpace

    /** Streaming SHA-256; returns null when the file cannot be read. */
    fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    fun verify(variant: ModelVariant): Boolean {
        val files = variant.files + listOfNotNull(variant.mmproj)
        for (file in files) {
            val target = fileFor(variant, file)
            if (!target.isFile || target.length() != file.size) return false
            val digest = sha256(target) ?: return false
            if (!digest.equals(file.sha256, ignoreCase = true)) return false
        }
        markVerified(variant, true)
        return true
    }

    private fun readIndex(): Map<String, InstalledRecord> {
        if (!indexFile.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, InstalledRecord>>(indexFile.readText())
        }.getOrDefault(emptyMap())
    }

    private fun writeIndex(index: Map<String, InstalledRecord>) {
        runCatching { indexFile.writeText(json.encodeToString(index)) }
    }
}
