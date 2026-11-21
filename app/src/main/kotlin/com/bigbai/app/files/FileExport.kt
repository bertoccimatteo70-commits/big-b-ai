package com.bigbai.app.files

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.bigbai.llm.AnswerSanitizer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saving, exporting and sharing text produced by the assistant.
 *
 * The user never has to invent a file name or a path: the name is derived from
 * the conversation or from the first words of the answer, and the file lands in
 * Download/BIG B AI. Sharing goes through the app's FileProvider, so no storage
 * permission is required on Android 12+.
 */
object FileExport {

    const val MIME_MARKDOWN = "text/markdown"
    const val MIME_TEXT = "text/plain"

    /** Pure naming rule, unit-tested: `bigb-<slug>-<timestamp>.<ext>`. */
    fun suggestedFileName(title: String?, extension: String, timestamp: Long): String {
        val slug = slugOf(title)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timestamp))
        val ext = extension.lowercase(Locale.US).filter { it.isLetterOrDigit() }.ifBlank { "md" }
        return "bigb-$slug-$stamp.$ext"
    }

    /** Slug used inside file names: lowercase ASCII words, max five. */
    fun slugOf(title: String?): String {
        val words = title.orEmpty()
            .lowercase(Locale.ROOT)
            .map { character ->
                if (character.isLetterOrDigit()) character else ' '
            }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() }
            .take(5)
        val slug = words.joinToString("-").take(48).trim('-')
        return slug.ifBlank { "chat" }
    }

    /**
     * Picks the extension from the first fenced code block, or Markdown when the
     * answer is prose. The code fence language is what the model declared.
     */
    fun extensionFor(content: String): String {
        val fence = Regex("```([a-zA-Z0-9+#.-]{1,16})").find(content)?.groupValues?.get(1)
        return extensionForLanguage(fence) ?: "md"
    }

    fun extensionForLanguage(language: String?): String? = when (language?.lowercase(Locale.US)) {
        null, "" -> null
        "kotlin", "kt", "kts", "gradle" -> "kt"
        "java" -> "java"
        "python", "py" -> "py"
        "javascript", "js", "node" -> "js"
        "typescript", "ts" -> "ts"
        "html", "htm" -> "html"
        "css" -> "css"
        "json" -> "json"
        "xml" -> "xml"
        "c" -> "c"
        "cpp", "c++", "cxx", "h", "hpp" -> "cpp"
        "csharp", "c#", "cs" -> "cs"
        "bash", "sh", "shell", "zsh" -> "sh"
        "sql" -> "sql"
        "yaml", "yml" -> "yml"
        "rust", "rs" -> "rs"
        "go", "golang" -> "go"
        "php" -> "php"
        "ruby", "rb" -> "rb"
        "swift" -> "swift"
        "markdown", "md" -> "md"
        else -> null
    }

    fun mimeFor(extension: String): String = when (extension.lowercase(Locale.US)) {
        "md" -> MIME_MARKDOWN
        "json" -> "application/json"
        "xml" -> "application/xml"
        "html" -> "text/html"
        "css" -> "text/css"
        "js" -> "application/javascript"
        else -> MIME_TEXT
    }

    /** Writes into Download/BIG B AI through MediaStore (no permission needed). */
    fun saveToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        content: String,
    ): Result<Uri> = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/BIG B AI",
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Impossibile creare il file nella cartella Download")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
            } ?: error("Impossibile scrivere il file")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    /** Shares a text file; the copy lives in the app cache, not in the user's storage. */
    fun share(
        context: Context,
        fileName: String,
        mimeType: String,
        content: String,
    ): Result<Unit> = runCatching {
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(directory, fileName)
        file.writeText(AnswerSanitizer.clean(content), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // The chooser may be started from the Application context (BIG B Code),
        // which requires NEW_TASK; it is harmless from an Activity too.
        context.startActivity(
            Intent.createChooser(intent, "Condividi").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
