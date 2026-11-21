package com.bigbai.code

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

data class FileNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val children: List<FileNode> = emptyList(),
)

/**
 * All project file access goes through the Storage Access Framework tree the
 * user explicitly granted. Paths are relative to that tree and are validated
 * against traversal, so a project can never reach outside its authorised folder.
 */
class ProjectFiles(private val context: Context) {

    /**
     * Resolves a project root. Two kinds of roots are supported:
     *  * a SAF tree URI chosen by the user (any folder they authorise);
     *  * a `file://` directory created by the app itself under its documents
     *    folder, so "Nuovo progetto" needs no folder picker at all.
     */
    fun root(treeUri: String): DocumentFile? {
        val uri = Uri.parse(treeUri)
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            val file = File(path)
            return if (file.isDirectory) DocumentFile.fromFile(file) else null
        }
        return runCatching { DocumentFile.fromTreeUri(context, uri) }
            .getOrNull()
            ?.takeIf { it.exists() && it.isDirectory }
    }

    /** Returns a normalised relative path, or null when it is unsafe. */
    fun sanitize(path: String): String? {
        val normalised = path.replace('\\', '/').trim().removePrefix("/")
        if (normalised.isBlank()) return null
        if (normalised.contains("..")) return null
        // Hidden files are outside the project surface the user granted.
        if (normalised.startsWith(".")) return null
        return normalised
    }

    fun find(root: DocumentFile, path: String): DocumentFile? {
        val safe = sanitize(path) ?: return null
        var current = root
        for (segment in safe.split('/').filter { it.isNotBlank() }) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    suspend fun list(root: DocumentFile, maxEntries: Int = 600, maxDepth: Int = 7): List<FileNode> =
        withContext(Dispatchers.IO) {
            var counted = 0
            fun walk(directory: DocumentFile, prefix: String, depth: Int): List<FileNode> {
                if (depth > maxDepth || counted > maxEntries) return emptyList()
                val nodes = mutableListOf<FileNode>()
                val children = runCatching { directory.listFiles() }.getOrDefault(emptyArray())
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name?.lowercase() ?: "" }))
                for (child in children) {
                    if (counted++ > maxEntries) break
                    val name = child.name ?: continue
                    if (name.startsWith(".")) continue
                    val path = if (prefix.isEmpty()) name else "$prefix/$name"
                    nodes.add(
                        if (child.isDirectory) {
                            FileNode(name, path, true, 0, walk(child, path, depth + 1))
                        } else {
                            FileNode(name, path, false, child.length())
                        },
                    )
                }
                return nodes
            }
            walk(root, "", 0)
        }

    suspend fun readText(root: DocumentFile, path: String, maxBytes: Int = 1_500_000): String? =
        withContext(Dispatchers.IO) {
            val file = find(root, path) ?: return@withContext null
            if (!file.isFile || file.length() > maxBytes) return@withContext null
            runCatching {
                context.contentResolver.openInputStream(file.uri)?.use { input ->
                    readBounded(input, maxBytes)
                }
            }.getOrNull()
        }

    suspend fun writeText(root: DocumentFile, path: String, content: String): Boolean =
        withContext(Dispatchers.IO) {
            val safe = sanitize(path) ?: return@withContext false
            val segments = safe.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return@withContext false
            var parent = root
            for (segment in segments.dropLast(1)) {
                parent = parent.findFile(segment) ?: parent.createDirectory(segment) ?: return@withContext false
            }
            val name = segments.last()
            val target = parent.findFile(name)
                ?: parent.createFile(mimeFor(name), name)
                ?: return@withContext false
            runCatching {
                context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
                    output.write(content.toByteArray(Charsets.UTF_8))
                } ?: return@withContext false
            }.isSuccess
        }

    suspend fun createFile(root: DocumentFile, path: String): Boolean = writeText(root, path, "")

    suspend fun createDirectory(root: DocumentFile, path: String): Boolean = withContext(Dispatchers.IO) {
        val safe = sanitize(path) ?: return@withContext false
        var parent = root
        for (segment in safe.split('/').filter { it.isNotBlank() }) {
            parent = parent.findFile(segment) ?: parent.createDirectory(segment) ?: return@withContext false
        }
        true
    }

    suspend fun delete(root: DocumentFile, path: String): Boolean = withContext(Dispatchers.IO) {
        find(root, path)?.delete() ?: false
    }

    suspend fun rename(root: DocumentFile, path: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val file = find(root, path) ?: return@withContext false
        val clean = newName.trim().replace("/", "_")
        if (clean.isBlank()) return@withContext false
        runCatching { file.renameTo(clean) }.getOrDefault(false)
    }

    suspend fun displayName(treeUri: String): String? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(treeUri)
        if (uri.scheme == "file") return@withContext uri.lastPathSegment
        runCatching {
            context.contentResolver.query(Uri.parse(treeUri), null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
    }

    /** Reads every small text file, bounded, for analysis and versioning. */
    suspend fun snapshot(
        root: DocumentFile,
        maxFiles: Int = 300,
        maxBytesPerFile: Int = 400_000,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val result = LinkedHashMap<String, String>()
        fun walk(directory: DocumentFile, prefix: String, depth: Int) {
            if (depth > 6 || result.size >= maxFiles) return
            val children = runCatching { directory.listFiles() }.getOrDefault(emptyArray())
            for (child in children) {
                if (result.size >= maxFiles) return
                val name = child.name ?: continue
                if (name.startsWith(".") || name == "build" || name == "node_modules") continue
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                if (child.isDirectory) {
                    walk(child, path, depth + 1)
                } else if (isTextFile(name) && child.length() <= maxBytesPerFile) {
                    runCatching {
                        context.contentResolver.openInputStream(child.uri)?.use { input ->
                            readBounded(input, maxBytesPerFile)?.let { text -> result[path] = text }
                        }
                    }
                }
            }
        }
        walk(root, "", 0)
        result
    }

    fun treeText(nodes: List<FileNode>, maxLines: Int = 120): String {
        val builder = StringBuilder()
        fun render(node: FileNode, indent: String) {
            if (builder.lines().size > maxLines) return
            builder.append(indent).append(if (node.isDirectory) "📁 " else "📄 ").append(node.name).append('\n')
            node.children.forEach { render(it, "$indent  ") }
        }
        nodes.forEach { render(it, "") }
        return builder.toString().trim()
    }

    companion object {
        /**
         * Reads at most [maxBytes] and returns null when the stream is larger.
         * Document providers may report a length of 0 or lie about it, so the
         * cap must be enforced while reading, not only from metadata.
         */
        internal fun readBounded(input: InputStream, maxBytes: Int): String? {
            if (maxBytes <= 0) return null
            val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
            val chunk = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                total += read
                if (total > maxBytes) return null
                output.write(chunk, 0, read)
            }
            return output.toString(Charsets.UTF_8.name())
        }

        private val TEXT_EXTENSIONS = setOf(
            "html", "htm", "css", "js", "ts", "json", "xml", "md", "txt",
            "java", "kt", "kts", "c", "h", "cpp", "cc", "hpp", "cmake",
            "gradle", "properties", "yml", "yaml", "sql", "sh", "py",
        )

        fun isTextFile(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS

        fun languageFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "html"
            "java" -> "java"
            "c", "h", "cpp", "cc", "hpp" -> "cpp"
            "js" -> "javascript"
            "ts" -> "typescript"
            "css" -> "css"
            "json" -> "json"
            "md" -> "markdown"
            "xml" -> "xml"
            "gradle", "kts" -> "kotlin"
            else -> "text"
        }

        fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "java" -> "text/x-java"
            "cpp", "cc", "h", "hpp", "c" -> "text/x-c++src"
            "md" -> "text/markdown"
            else -> "text/plain"
        }
    }
}
