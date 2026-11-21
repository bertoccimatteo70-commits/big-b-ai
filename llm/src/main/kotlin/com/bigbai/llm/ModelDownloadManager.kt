package com.bigbai.llm

import android.content.Context
import com.bigbai.core.db.DownloadDao
import com.bigbai.core.db.DownloadEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

sealed interface DownloadOutcome {
    data class Success(val installed: InstalledModel) : DownloadOutcome
    data object Paused : DownloadOutcome
    data class Failure(val message: String) : DownloadOutcome
}

/**
 * Resumable, integrity-checked model downloads.
 *
 * Partial data is written to `<name>.part` and resumed with an HTTP Range
 * request. A file is only promoted to its final name after its SHA-256 matches
 * the catalog value, so a truncated or corrupted download can never be loaded.
 */
class ModelDownloadManager(
    private val context: Context,
    private val store: ModelStore,
    private val downloadDao: DownloadDao,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) {
    private val paused = ConcurrentHashMap.newKeySet<String>()
    private val running = ConcurrentHashMap<String, AtomicBoolean>()

    fun isDownloading(modelId: String): Boolean = running[modelId]?.get() == true

    fun pause(modelId: String) {
        paused.add(modelId)
    }

    suspend fun download(
        variant: ModelVariant,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        paused.remove(variant.id)
        val flag = AtomicBoolean(true)
        running[variant.id] = flag
        try {
            val total = variant.totalBytes
            val free = store.freeSpaceBytes()
            val already = existingBytes(variant)
            if (free + already < total) {
                val message = "Spazio insufficiente: servono ${formatBytes(total - already)} liberi"
                record(variant, "FAILED", already, total, message)
                return@withContext DownloadOutcome.Failure(message)
            }

            var completed = already
            val files = variant.files + listOfNotNull(variant.mmproj)
            for (file in files) {
                val target = store.fileFor(variant, file)
                val part = File(target.parentFile, "${target.name}.part")
                if (target.isFile && target.length() == file.size) {
                    completed += file.size
                    onProgress(completed, total)
                    continue
                }
                val result = downloadFile(variant, file, part, completed, total) { delta ->
                    completed += delta
                    onProgress(completed, total)
                }
                if (result is FileResult.Paused) {
                    record(variant, "PAUSED", completed, total, null)
                    return@withContext DownloadOutcome.Paused
                }
                if (result is FileResult.Failed) {
                    record(variant, "FAILED", completed, total, result.message)
                    return@withContext DownloadOutcome.Failure(result.message)
                }
                val digest = store.sha256(part)
                if (digest == null || !digest.equals(file.sha256, ignoreCase = true)) {
                    part.delete()
                    val message = "Verifica SHA-256 fallita per ${file.name}"
                    record(variant, "FAILED", completed, total, message)
                    return@withContext DownloadOutcome.Failure(message)
                }
                if (!part.renameTo(target)) {
                    val message = "Impossibile finalizzare ${file.name}"
                    record(variant, "FAILED", completed, total, message)
                    return@withContext DownloadOutcome.Failure(message)
                }
            }

            val installed = store.installed(variant)
                ?: return@withContext DownloadOutcome.Failure("Installazione incompleta")
            store.markInstalled(variant, verified = true)
            record(variant, "INSTALLED", total, total, null)
            DownloadOutcome.Success(installed)
        } catch (cancel: CancellationException) {
            record(variant, "PAUSED", existingBytes(variant), variant.totalBytes, null)
            throw cancel
        } catch (error: Exception) {
            val message = error.message ?: "Download interrotto"
            record(variant, "FAILED", existingBytes(variant), variant.totalBytes, message)
            DownloadOutcome.Failure(message)
        } finally {
            running.remove(variant.id)
        }
    }

    private suspend fun downloadFile(
        variant: ModelVariant,
        file: CatalogFile,
        part: File,
        alreadyCompleted: Long,
        total: Long,
        onDelta: (Long) -> Unit,
    ): FileResult = withContext(Dispatchers.IO) {
        val existing = if (part.isFile) part.length() else 0L
        if (existing > file.size) part.delete()

        val requestBuilder = Request.Builder()
            .url(variant.downloadUrl(file))
            .header("User-Agent", "BigBAI/1.0 (Android; local model downloader)")
        if (existing > 0) {
            requestBuilder.header("Range", "bytes=$existing-")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext FileResult.Failed("HTTP ${response.code} per ${file.name}")
                }
                val body = response.body ?: return@withContext FileResult.Failed("Risposta vuota")
                val append = response.code == 206
                val sink = RandomAccessFile(part, "rw")
                try {
                    if (!append) sink.setLength(0)
                    sink.seek(if (append) sink.length() else 0L)
                    val input = body.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    var lastUpdate = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        if (paused.contains(variant.id)) return@withContext FileResult.Paused
                        val read = input.read(buffer)
                        if (read <= 0) break
                        sink.write(buffer, 0, read)
                        onDelta(read.toLong())
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 400) {
                            lastUpdate = now
                            record(
                                variant,
                                "DOWNLOADING",
                                (alreadyCompleted - existing) + sink.length(),
                                total,
                                null,
                            )
                        }
                    }
                } finally {
                    sink.close()
                }
                if (part.length() != file.size) {
                    return@withContext FileResult.Failed(
                        "Dimensione non valida per ${file.name}: ${part.length()} invece di ${file.size}",
                    )
                }
                FileResult.Completed
            }
        } catch (error: IOException) {
            if (paused.contains(variant.id)) FileResult.Paused
            else FileResult.Failed(error.message ?: "Errore di rete")
        }
    }

    private suspend fun record(
        variant: ModelVariant,
        status: String,
        downloaded: Long,
        total: Long,
        error: String?,
    ) {
        downloadDao.upsert(
            DownloadEntity(
                modelId = variant.id,
                status = status,
                bytesDownloaded = downloaded,
                totalBytes = total,
                error = error,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun existingBytes(variant: ModelVariant): Long {
        var sum = 0L
        for (file in variant.files + listOfNotNull(variant.mmproj)) {
            val target = store.fileFor(variant, file)
            val part = File(target.parentFile, "${target.name}.part")
            sum += when {
                target.isFile -> target.length()
                part.isFile -> part.length()
                else -> 0L
            }
        }
        return sum
    }

    private sealed interface FileResult {
        data object Completed : FileResult
        data object Paused : FileResult
        data class Failed(val message: String) : FileResult
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = listOf("B", "KB", "MB", "GB")
            var value = bytes.toDouble()
            var index = 0
            while (value >= 1024 && index < units.lastIndex) {
                value /= 1024
                index++
            }
            return String.format(java.util.Locale.US, "%.1f %s", value, units[index])
        }
    }
}
