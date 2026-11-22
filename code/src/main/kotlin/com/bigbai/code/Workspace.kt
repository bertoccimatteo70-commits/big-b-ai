package com.bigbai.code

/** One direct child of a project directory. */
data class WorkspaceEntry(
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
)

/**
 * Everything ITAGPT Code can do to a project goes through this interface: the
 * agent, the terminal, the verifier and the rollback logic. It is deliberately
 * small and suspend-only so the same logic runs against the Storage Access
 * Framework on Android and against an in-memory tree in JVM tests.
 *
 * Implementations must reject paths outside the project: [ProjectPaths] is the
 * single policy and every adapter validates before touching a provider.
 */
interface ProjectWorkspace {
    /** Direct children of [directory] ("" is the project root). */
    suspend fun list(directory: String): List<WorkspaceEntry>

    suspend fun read(path: String): String?

    suspend fun write(path: String, content: String): Boolean

    suspend fun mkdir(path: String): Boolean

    suspend fun delete(path: String): Boolean

    suspend fun rename(from: String, to: String): Boolean

    suspend fun exists(path: String): Boolean

    suspend fun isDirectory(path: String): Boolean
}

/**
 * Path arithmetic for the terminal and the tools.
 *
 * The project root is the empty string. A leading `/` means "from the project
 * root", never the Android filesystem. `..` is refused instead of being
 * resolved, so a command cannot walk out of the folder the user granted.
 */
object WorkspacePaths {

    fun resolve(cwd: String, raw: String): String? {
        val trimmed = raw.trim()
        val combined = when {
            trimmed.isEmpty() -> cwd
            trimmed == "/" -> ""
            trimmed.startsWith("/") -> trimmed.trimStart('/')
            cwd.isEmpty() -> trimmed
            else -> "$cwd/$trimmed"
        }
        if (combined.isEmpty()) return ""
        val segments = mutableListOf<String>()
        for (segment in combined.replace('\\', '/').split('/')) {
            when (segment) {
                "", "." -> continue
                ".." -> return null
                else -> segments += segment
            }
        }
        if (segments.isEmpty()) return ""
        return ProjectPaths.normalize(segments.joinToString("/"))
    }

    fun parent(path: String): String = path.substringBeforeLast('/', "")

    fun name(path: String): String = path.substringAfterLast('/')

    fun join(directory: String, name: String): String =
        if (directory.isEmpty()) name else "$directory/$name"

    fun depth(path: String): Int = if (path.isEmpty()) 0 else path.count { it == '/' } + 1

    /** A short, stable label for the prompt bar. */
    fun display(cwd: String): String = if (cwd.isEmpty()) "/" else "/$cwd"
}
