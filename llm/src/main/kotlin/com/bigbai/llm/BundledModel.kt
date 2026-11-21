package com.bigbai.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * A model that ships inside the APK instead of being downloaded later.
 *
 * The APK is the only reliable distribution channel for this app, so the one
 * component that must never be missing — the embedding model used for local
 * retrieval — is bundled. It is copied out of the (read-only, compressed)
 * assets into the app's private storage exactly once, and the copy is accepted
 * only after its SHA-256 matches the pinned value.
 */
data class BundledModelSpec(
    val assetPath: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val label: String,
    val license: String,
)

object BundledModels {
    /**
     * intfloat/multilingual-e5-small, Q8_0 GGUF published by cstr.
     * 384-dimensional multilingual embeddings (Italian included), Apache-2.0
     * weights. The digest is the Hugging Face LFS object id of the exact file.
     */
    val EMBEDDING = BundledModelSpec(
        assetPath = "models/multilingual-e5-small-q8_0.gguf",
        fileName = "multilingual-e5-small-q8_0.gguf",
        sizeBytes = 132_439_008L,
        sha256 = "e011debc1208e31bf7b6aebee2d9fc8bd2ca11694a77ed66ac9d0c9d0a877c93",
        label = "multilingual-e5-small · Q8_0",
        license = "MIT (modello intfloat/multilingual-e5-small, GGUF TwinSunsLLC)",
    )
}

class BundledModelInstaller(private val context: Context) {

    private val root = File(context.filesDir, "bundled")

    fun targetFile(spec: BundledModelSpec): File = File(root, spec.fileName)

    fun markerFile(spec: BundledModelSpec): File = File(root, "${spec.fileName}.sha256")

    /**
     * True when the extracted copy exists with the expected size and a marker
     * that records the verified digest. A missing marker triggers one real
     * hash check in [ensure], never a silent acceptance.
     */
    fun isInstalled(spec: BundledModelSpec): Boolean {
        val file = targetFile(spec)
        if (!file.isFile || file.length() != spec.sizeBytes) return false
        val marker = markerFile(spec)
        return marker.isFile && marker.readText().trim().equals(spec.sha256, ignoreCase = true)
    }

    /** Copies and verifies the asset. Returns the usable file or a failure. */
    suspend fun ensure(
        spec: BundledModelSpec,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        val target = targetFile(spec)
        if (isInstalled(spec)) return@withContext Result.success(target)
        root.mkdirs()
        if (target.isFile && target.length() == spec.sizeBytes) {
            // Same size but no marker (for example after an app update that
            // changed the digest): verify before trusting it.
            val digest = sha256(target)
            if (digest != null && digest.equals(spec.sha256, ignoreCase = true)) {
                markerFile(spec).writeText(spec.sha256)
                return@withContext Result.success(target)
            }
            target.delete()
        }
        val partial = File(root, "${spec.fileName}.part")
        partial.delete()
        try {
            context.assets.open(spec.assetPath).use { input ->
                partial.outputStream().buffered(1 shl 20).use { output ->
                    val buffer = ByteArray(1 shl 20)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(copied, spec.sizeBytes)
                    }
                    output.flush()
                }
            }
            if (partial.length() != spec.sizeBytes) {
                partial.delete()
                return@withContext Result.failure(
                    IllegalStateException("Copia incompleta: ${partial.length()} byte invece di ${spec.sizeBytes}"),
                )
            }
            val digest = sha256(partial)
            if (digest == null || !digest.equals(spec.sha256, ignoreCase = true)) {
                partial.delete()
                return@withContext Result.failure(
                    IllegalStateException("Verifica SHA-256 fallita per ${spec.label}"),
                )
            }
            if (!partial.renameTo(target)) {
                partial.delete()
                return@withContext Result.failure(IllegalStateException("Impossibile finalizzare ${spec.label}"))
            }
            markerFile(spec).writeText(spec.sha256)
            Result.success(target)
        } catch (error: Exception) {
            partial.delete()
            Result.failure(error)
        }
    }

    /** Re-hashes the extracted copy. Used by the diagnostics screen. */
    suspend fun verify(spec: BundledModelSpec): Boolean = withContext(Dispatchers.IO) {
        val file = targetFile(spec)
        if (!file.isFile || file.length() != spec.sizeBytes) return@withContext false
        val digest = sha256(file) ?: return@withContext false
        val ok = digest.equals(spec.sha256, ignoreCase = true)
        if (ok) markerFile(spec).writeText(spec.sha256) else markerFile(spec).delete()
        ok
    }

    fun delete(spec: BundledModelSpec) {
        targetFile(spec).delete()
        markerFile(spec).delete()
    }

    private fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 20).use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
