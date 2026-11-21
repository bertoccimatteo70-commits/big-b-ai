package com.bigbai.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * A model that ships inside the APK instead of being downloaded later.
 *
 * The APK is the only reliable distribution channel for this app, so the one
 * component that must never be missing — the embedding model used for local
 * retrieval — is bundled. It is copied out of the read-only assets into the
 * app's private storage exactly once, and the copy is accepted only after its
 * SHA-256 matches the pinned value.
 */
data class BundledModelSpec(
    val assetPath: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val label: String,
    val license: String,
)

/**
 * What was verified, and when. The marker is not trusted on its own: it is only
 * accepted while the file's size **and** modification time still match the
 * values recorded after the last full hash. A copied, restored or modified file
 * changes the mtime and triggers one real SHA-256 check instead of a silent
 * acceptance; an unchanged 132 MB file is not re-hashed on every app start.
 */
data class InstallMarker(
    val sha256: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
) {
    fun encode(): String = "$FORMAT|$sha256|$sizeBytes|$modifiedAt"

    fun matches(file: File): Boolean =
        file.isFile && file.length() == sizeBytes && file.lastModified() == modifiedAt

    companion object {
        /** Bump when the verification rules change: old markers force one hash. */
        const val FORMAT = 2

        fun of(spec: BundledModelSpec, file: File): InstallMarker =
            InstallMarker(spec.sha256.lowercase(), file.length(), file.lastModified())

        fun parse(text: String): InstallMarker? {
            val parts = text.trim().split('|')
            if (parts.size != 4) return null
            if (parts[0].toIntOrNull() != FORMAT) return null
            val sha = parts[1].trim().lowercase()
            if (sha.length != 64 || sha.any { it !in "0123456789abcdef" }) return null
            val size = parts[2].toLongOrNull() ?: return null
            val modified = parts[3].toLongOrNull() ?: return null
            if (size <= 0L || modified <= 0L) return null
            return InstallMarker(sha, size, modified)
        }
    }
}

object BundledModels {
    /**
     * intfloat/multilingual-e5-small, Q8_0 GGUF published by TwinSunsLLC.
     * 384-dimensional multilingual embeddings (Italian included).
     *
     * License verified on the Hugging Face API on 21 November 2026:
     * the GGUF repository declares `license: mit` and
     * `base_model: intfloat/multilingual-e5-small` (also MIT). The previous
     * KDoc said Apache-2.0, which was wrong; the weights are MIT.
     */
    val EMBEDDING = BundledModelSpec(
        assetPath = "models/multilingual-e5-small-q8_0.gguf",
        fileName = "multilingual-e5-small-q8_0.gguf",
        sizeBytes = 132_439_008L,
        sha256 = "e011debc1208e31bf7b6aebee2d9fc8bd2ca11694a77ed66ac9d0c9d0a877c93",
        label = "multilingual-e5-small · Q8_0",
        license = "MIT (intfloat/multilingual-e5-small; GGUF TwinSunsLLC, MIT)",
    )
}

class BundledModelInstaller(private val context: Context) {

    private val root = File(context.filesDir, "bundled")

    fun targetFile(spec: BundledModelSpec): File = File(root, spec.fileName)

    fun markerFile(spec: BundledModelSpec): File = File(root, "${spec.fileName}.sha256")

    /**
     * Fast check used on every start: size, recorded digest and unchanged
     * modification time. A missing or legacy marker returns false, so [ensure]
     * performs one real hash before trusting the file.
     */
    fun isInstalled(spec: BundledModelSpec): Boolean {
        val file = targetFile(spec)
        if (!file.isFile || file.length() != spec.sizeBytes) return false
        val marker = readMarker(spec) ?: return false
        return marker.sha256.equals(spec.sha256, ignoreCase = true) &&
            marker.sizeBytes == spec.sizeBytes &&
            marker.matches(file)
    }

    /** Copies and verifies the asset. Returns the usable file or a failure. */
    suspend fun ensure(
        spec: BundledModelSpec,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        val target = targetFile(spec)
        if (isInstalled(spec)) return@withContext Result.success(target)
        if (!root.exists() && !root.mkdirs()) {
            return@withContext Result.failure(IllegalStateException("Impossibile creare la cartella dei modelli"))
        }
        if (target.isFile && target.length() == spec.sizeBytes) {
            // Same size but no valid marker (legacy marker, app update, restored
            // file): verify before trusting it.
            val digest = sha256(target)
            if (digest != null && digest.equals(spec.sha256, ignoreCase = true)) {
                writeMarker(spec, target)
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
                val actual = partial.length()
                partial.delete()
                return@withContext Result.failure(
                    IllegalStateException("Copia incompleta: $actual byte invece di ${spec.sizeBytes}"),
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
                val moved = runCatching {
                    Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }.isSuccess
                if (!moved) {
                    partial.delete()
                    return@withContext Result.failure(
                        IllegalStateException("Impossibile finalizzare ${spec.label}"),
                    )
                }
            }
            writeMarker(spec, target)
            Result.success(target)
        } catch (error: Exception) {
            partial.delete()
            Result.failure(error)
        }
    }

    /** Re-hashes the extracted copy. Used by diagnostics and after failures. */
    suspend fun verify(spec: BundledModelSpec): Boolean = withContext(Dispatchers.IO) {
        val file = targetFile(spec)
        if (!file.isFile || file.length() != spec.sizeBytes) return@withContext false
        val digest = sha256(file) ?: return@withContext false
        val ok = digest.equals(spec.sha256, ignoreCase = true)
        if (ok) writeMarker(spec, file) else markerFile(spec).delete()
        ok
    }

    fun markerText(spec: BundledModelSpec): String? = readMarker(spec)?.encode()

    fun delete(spec: BundledModelSpec) {
        targetFile(spec).delete()
        markerFile(spec).delete()
        File(root, "${spec.fileName}.part").delete()
        File(root, "${markerFile(spec).name}.part").delete()
    }

    private fun readMarker(spec: BundledModelSpec): InstallMarker? = runCatching {
        InstallMarker.parse(markerFile(spec).readText())
    }.getOrNull()

    /**
     * Marker writes are atomic and best-effort: if the marker cannot be
     * written the model is still valid, it will simply be hashed again on the
     * next start instead of being trusted.
     */
    private fun writeMarker(spec: BundledModelSpec, file: File) {
        val marker = InstallMarker.of(spec, file)
        val target = markerFile(spec)
        val temp = File(root, "${target.name}.part")
        runCatching {
            temp.writeText(marker.encode())
            if (!temp.renameTo(target)) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure {
            temp.delete()
        }
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
