package com.bigbai.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * A model that is **not** shipped inside the APK.
 *
 * ITAGPT 2.1 removed every GGUF file from the package: the APK must install and
 * start on any device without carrying gigabytes of weights. The embedding
 * model is still supported, but it is optional and managed separately: the user
 * imports the exact file from local storage, the importer verifies its size and
 * SHA-256, and only then the semantic index can use it. No code path downloads
 * it automatically.
 */
data class EmbeddingModelSpec(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val label: String,
    val license: String,
    val sourceUrl: String,
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

        fun of(spec: EmbeddingModelSpec, file: File): InstallMarker =
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

object EmbeddingModels {
    /**
     * intfloat/multilingual-e5-small, Q8_0 GGUF published by TwinSunsLLC.
     * 384-dimensional multilingual embeddings (Italian included).
     *
     * License verified on the Hugging Face API on 21 November 2026:
     * the GGUF repository declares `license: mit` and
     * `base_model: intfloat/multilingual-e5-small` (also MIT).
     *
     * The file is deliberately **not** part of the APK. It is imported by the
     * user from Settings → Ricerca semantica and verified against these values.
     */
    val EMBEDDING = EmbeddingModelSpec(
        fileName = "multilingual-e5-small-q8_0.gguf",
        sizeBytes = 132_439_008L,
        sha256 = "e011debc1208e31bf7b6aebee2d9fc8bd2ca11694a77ed66ac9d0c9d0a877c93",
        label = "multilingual-e5-small · Q8_0",
        license = "MIT (intfloat/multilingual-e5-small; GGUF TwinSunsLLC, MIT)",
        sourceUrl = "https://huggingface.co/TwinSunsLLC/multilingual-e5-small-gguf",
    )
}

/**
 * Stores the optional embedding model in the app's private storage.
 *
 * There is no asset copy and no network download: [importModel] is the only
 * way a model enters this directory, and it accepts a stream the user chose
 * through the system file picker. The partial file is hashed before being
 * renamed, so a truncated or wrong file can never become the active model.
 */
class EmbeddingModelInstaller(private val context: Context) {

    private val root = File(context.filesDir, "embedding")

    fun directory(): File = root

    fun targetFile(spec: EmbeddingModelSpec = EmbeddingModels.EMBEDDING, directory: File = root): File =
        File(directory, spec.fileName)

    fun markerFile(spec: EmbeddingModelSpec = EmbeddingModels.EMBEDDING, directory: File = root): File =
        File(directory, "${spec.fileName}.sha256")

    /** Fast check used on every start: size, recorded digest and unchanged mtime. */
    fun isInstalled(spec: EmbeddingModelSpec = EmbeddingModels.EMBEDDING, directory: File = root): Boolean {
        val file = targetFile(spec, directory)
        if (!file.isFile || file.length() != spec.sizeBytes) return false
        val marker = readMarker(spec, directory) ?: return false
        return marker.sha256.equals(spec.sha256, ignoreCase = true) &&
            marker.sizeBytes == spec.sizeBytes &&
            marker.matches(file)
    }

    /** True when a file exists, even if its marker is missing (diagnostics can verify it). */
    fun hasFile(spec: EmbeddingModelSpec = EmbeddingModels.EMBEDDING, directory: File = root): Boolean {
        val file = targetFile(spec, directory)
        return file.isFile && file.length() == spec.sizeBytes
    }

    /** The verified file, or a failure explaining why semantic search is unavailable. */
    fun usableFile(spec: EmbeddingModelSpec = EmbeddingModels.EMBEDDING, directory: File = root): Result<File> {
        val file = targetFile(spec, directory)
        if (!file.isFile) {
            return Result.failure(
                IllegalStateException(
                    "Modello di embedding non importato. Apri Impostazioni → Ricerca semantica " +
                        "e importa ${spec.fileName}.",
                ),
            )
        }
        if (file.length() != spec.sizeBytes) {
            return Result.failure(
                IllegalStateException(
                    "Il modello di embedding ha una dimensione errata: ${file.length()} byte " +
                        "invece di ${spec.sizeBytes}.",
                ),
            )
        }
        if (!isInstalled(spec, directory)) {
            return Result.failure(
                IllegalStateException(
                    "Il modello di embedding non è ancora verificato. Usa «Verifica» nella " +
                        "schermata Diagnostica o reimportalo.",
                ),
            )
        }
        return Result.success(file)
    }

    /**
     * Imports and verifies the optional embedding model from a user-selected
     * stream. The exact pinned size and SHA-256 are required: a different file
     * is rejected instead of silently producing meaningless vectors.
     */
    suspend fun importModel(
        input: InputStream,
        spec: EmbeddingModelSpec = EmbeddingModels.EMBEDDING,
        directory: File = root,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        if (!directory.exists() && !directory.mkdirs()) {
            return@withContext Result.failure(
                IllegalStateException("Impossibile creare la cartella del modello di embedding."),
            )
        }
        val partial = File(directory, "${spec.fileName}.part")
        partial.delete()
        try {
            input.use { source ->
                partial.outputStream().buffered(1 shl 20).use { output ->
                    val buffer = ByteArray(1 shl 20)
                    var copied = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read <= 0) break
                        copied += read
                        if (copied > spec.sizeBytes) {
                            partial.delete()
                            return@withContext Result.failure(
                                IllegalStateException(
                                    "Il file scelto è più grande del modello atteso (${spec.sizeBytes} byte).",
                                ),
                            )
                        }
                        output.write(buffer, 0, read)
                        onProgress(copied, spec.sizeBytes)
                    }
                    output.flush()
                }
            }
            val actualSize = partial.length()
            if (actualSize != spec.sizeBytes) {
                partial.delete()
                return@withContext Result.failure(
                    IllegalStateException(
                        "File incompleto o errato: $actualSize byte invece di ${spec.sizeBytes}.",
                    ),
                )
            }
            val digest = sha256(partial)
            if (digest == null || !digest.equals(spec.sha256, ignoreCase = true)) {
                partial.delete()
                return@withContext Result.failure(
                    IllegalStateException(
                        "SHA-256 non corrispondente: il file non è ${spec.fileName}.",
                    ),
                )
            }
            val target = targetFile(spec, directory)
            target.delete()
            if (!partial.renameTo(target)) {
                val moved = runCatching {
                    Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }.isSuccess
                if (!moved) {
                    partial.delete()
                    return@withContext Result.failure(
                        IllegalStateException("Impossibile salvare ${spec.fileName}."),
                    )
                }
            }
            writeMarker(spec, target, directory)
            Result.success(target)
        } catch (error: Exception) {
            partial.delete()
            Result.failure(error)
        }
    }

    /** Re-hashes the imported copy. Used by diagnostics after a failed check. */
    suspend fun verify(spec: EmbeddingModelSpec = EmbeddingModels.EMBEDDING, directory: File = root): Boolean =
        withContext(Dispatchers.IO) {
            val file = targetFile(spec, directory)
            if (!file.isFile || file.length() != spec.sizeBytes) return@withContext false
            val digest = sha256(file) ?: return@withContext false
            val ok = digest.equals(spec.sha256, ignoreCase = true)
            if (ok) writeMarker(spec, file, directory) else markerFile(spec, directory).delete()
            ok
        }

    fun markerText(spec: EmbeddingModelSpec = EmbeddingModels.EMBEDDING, directory: File = root): String? =
        readMarker(spec, directory)?.encode()

    fun delete(spec: EmbeddingModelSpec = EmbeddingModels.EMBEDDING, directory: File = root) {
        targetFile(spec, directory).delete()
        markerFile(spec, directory).delete()
        File(directory, "${spec.fileName}.part").delete()
        File(directory, "${markerFile(spec, directory).name}.part").delete()
    }

    private fun readMarker(spec: EmbeddingModelSpec, directory: File): InstallMarker? =
        runCatching {
            InstallMarker.parse(markerFile(spec, directory).readText())
        }.getOrNull()

    /**
     * Marker writes are atomic and best-effort: if the marker cannot be written
     * the model is still valid, it will simply be hashed again on the next
     * start instead of being trusted.
     */
    private fun writeMarker(spec: EmbeddingModelSpec, file: File, directory: File) {
        val marker = InstallMarker.of(spec, file)
        val target = markerFile(spec, directory)
        val temp = File(directory, "${target.name}.part")
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

/** Immutable list of the optional models the app knows how to verify. */
object OptionalModels {
    val all: List<EmbeddingModelSpec> = listOf(EmbeddingModels.EMBEDDING)
}
