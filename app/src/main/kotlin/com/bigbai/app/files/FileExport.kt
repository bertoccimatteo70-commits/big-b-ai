package com.bigbai.app.files

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.bigbai.core.model.ChatMessage
import com.bigbai.core.model.Role
import com.bigbai.llm.AnswerSanitizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saving, exporting and sharing text produced by the assistant.
 *
 * The user never has to invent a file name or a path: the name is derived from
 * the conversation or from the first words of the answer, and the file lands in
 * Download/ITAGPT. Sharing goes through the app's FileProvider, so no storage
 * permission is required on Android 12+.
 */
object FileExport {

    const val MIME_MARKDOWN = "text/markdown"
    const val MIME_TEXT = "text/plain"
    const val MIME_PDF = "application/pdf"

    /** Pure naming rule, unit-tested: `itagpt-<slug>-<timestamp>.<ext>`. */
    fun suggestedFileName(title: String?, extension: String, timestamp: Long): String {
        val slug = slugOf(title)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timestamp))
        val ext = extension.lowercase(Locale.US).filter { it.isLetterOrDigit() }.ifBlank { "md" }
        return "itagpt-$slug-$stamp.$ext"
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

    /** Markdown transcription of a conversation, used by the export menu. */
    fun conversationMarkdown(title: String?, messages: List<ChatMessage>): String = buildString {
        appendLine("# ${title?.takeIf { it.isNotBlank() } ?: "Conversazione"}")
        appendLine()
        messages.forEach { message ->
            appendLine(if (message.role == Role.USER) "## Utente" else "## ITAGPT")
            appendLine()
            appendLine(message.content)
            appendLine()
        }
    }

    /** Plain-text transcription of a conversation. */
    fun conversationPlainText(title: String?, messages: List<ChatMessage>): String = buildString {
        appendLine(title?.takeIf { it.isNotBlank() } ?: "Conversazione")
        appendLine("=".repeat(40))
        appendLine()
        messages.forEach { message ->
            appendLine(if (message.role == Role.USER) "UTENTE" else "ITAGPT")
            appendLine(message.content)
            appendLine()
            appendLine("-".repeat(40))
            appendLine()
        }
    }

    /**
     * Builds a real PDF (paginated, selectable text) with the Android PDF
     * writer. No external service and no extra dependency: the document is
     * written locally, page by page.
     */
    fun conversationPdf(title: String?, messages: List<ChatMessage>): ByteArray {
        val pageWidth = 595
        val pageHeight = 842
        val margin = 44f
        val titlePaint = Paint().apply {
            isAntiAlias = true
            textSize = 17f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            color = android.graphics.Color.BLACK
        }
        val bodyPaint = Paint().apply {
            isAntiAlias = true
            textSize = 11f
            typeface = Typeface.SERIF
            color = android.graphics.Color.BLACK
        }
        val metaPaint = Paint().apply {
            isAntiAlias = true
            textSize = 9f
            color = android.graphics.Color.DKGRAY
        }
        val speakerPaint = Paint().apply {
            isAntiAlias = true
            textSize = 11f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            color = android.graphics.Color.BLACK
        }
        val usableWidth = pageWidth - margin * 2
        val document = PdfDocument()
        try {
            var pageNumber = 1
            var page = document.startPage(
                PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create(),
            )
            var y = margin + 20f
            val lineHeight = 15f
            val bottom = pageHeight - margin

            fun newPage() {
                page.canvas.drawText("ITAGPT · pagina $pageNumber", margin, pageHeight - 20f, metaPaint)
                document.finishPage(page)
                pageNumber += 1
                page = document.startPage(
                    PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create(),
                )
                y = margin
            }

            page.canvas.drawText(title?.takeIf { it.isNotBlank() } ?: "Conversazione", margin, y, titlePaint)
            y += 26f

            messages.forEach { message ->
                val speaker = if (message.role == Role.USER) "UTENTE" else "ITAGPT"
                if (y + lineHeight * 2 > bottom) newPage()
                page.canvas.drawText(speaker, margin, y, speakerPaint)
                y += lineHeight
                val lines = wrapLines(AnswerSanitizer.clean(message.content), bodyPaint, usableWidth)
                for (line in lines) {
                    if (y + lineHeight > bottom) newPage()
                    page.canvas.drawText(line, margin, y, bodyPaint)
                    y += lineHeight
                }
                y += 8f
            }
            page.canvas.drawText("ITAGPT · pagina $pageNumber", margin, pageHeight - 20f, metaPaint)
            document.finishPage(page)
            return ByteArrayOutputStream().use { output ->
                document.writeTo(output)
                output.toByteArray()
            }
        } finally {
            document.close()
        }
    }

    /** Greedy word wrap using the same Paint that draws the page. */
    private fun wrapLines(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        text.split('\n').forEach { paragraph ->
            if (paragraph.isBlank()) {
                lines.add("")
                return@forEach
            }
            var line = ""
            paragraph.split(' ').forEach { word ->
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    line = candidate
                } else {
                    if (line.isNotEmpty()) lines.add(line)
                    var rest = word
                    while (paint.measureText(rest) > maxWidth) {
                        val count = paint.breakText(rest, true, maxWidth, null).coerceAtLeast(1)
                        lines.add(rest.take(count))
                        rest = rest.drop(count)
                    }
                    line = rest
                }
            }
            if (line.isNotEmpty()) lines.add(line)
        }
        return lines
    }

    /** Writes bytes into Download/ITAGPT through MediaStore (no permission needed). */
    fun saveBytesToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): Result<Uri> = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/ITAGPT",
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Impossibile creare il file nella cartella Download")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(bytes)
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

    /** Text convenience wrapper kept for the existing message export. */
    fun saveToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        content: String,
    ): Result<Uri> = saveBytesToDownloads(context, fileName, mimeType, content.toByteArray(Charsets.UTF_8))

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
        // The chooser may be started from the Application context (ITAGPT Code),
        // which requires NEW_TASK; it is harmless from an Activity too.
        context.startActivity(
            Intent.createChooser(intent, "Condividi").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
