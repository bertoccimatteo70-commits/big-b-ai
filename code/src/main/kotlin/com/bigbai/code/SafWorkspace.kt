package com.bigbai.code

import androidx.documentfile.provider.DocumentFile

/**
 * [ProjectWorkspace] backed by the Storage Access Framework tree the user
 * granted. Every call re-resolves the path through [ProjectFiles], so no stale
 * handle can point at a file that was renamed or deleted outside the app.
 *
 * Renames across folders are implemented as copy + verified write + delete: SAF
 * providers usually refuse to move an item to another parent.
 */
class SafWorkspace(
    private val files: ProjectFiles,
    private val root: DocumentFile,
) : ProjectWorkspace {

    override suspend fun list(directory: String): List<WorkspaceEntry> {
        val safe = if (directory.isEmpty()) "" else ProjectPaths.normalize(directory) ?: return emptyList()
        val node = if (safe.isEmpty()) root else files.find(root, safe) ?: return emptyList()
        if (!node.isDirectory) return emptyList()
        return runCatching { node.listFiles() }.getOrDefault(emptyArray())
            .mapNotNull { child ->
                val name = child.name ?: return@mapNotNull null
                if (name.startsWith(".")) return@mapNotNull null
                WorkspaceEntry(
                    path = WorkspacePaths.join(safe, name),
                    isDirectory = child.isDirectory,
                    size = if (child.isDirectory) 0 else child.length(),
                )
            }
    }

    override suspend fun read(path: String): String? = files.readText(root, path)

    override suspend fun write(path: String, content: String): Boolean =
        files.writeText(root, path, content)

    override suspend fun mkdir(path: String): Boolean = files.createDirectory(root, path)

    override suspend fun delete(path: String): Boolean = files.delete(root, path)

    override suspend fun rename(from: String, to: String): Boolean {
        val fromSafe = ProjectPaths.normalize(from) ?: return false
        val toSafe = ProjectPaths.normalize(to) ?: return false
        if (toSafe.isEmpty()) return false
        if (files.find(root, toSafe) != null) return false
        if (WorkspacePaths.parent(fromSafe) == WorkspacePaths.parent(toSafe)) {
            return files.rename(root, fromSafe, WorkspacePaths.name(toSafe))
        }
        if (files.find(root, fromSafe)?.isDirectory == true) return false
        val content = files.readText(root, fromSafe) ?: return false
        if (!files.writeText(root, toSafe, content)) return false
        if (!files.delete(root, fromSafe)) {
            // Do not leave two copies behind when the move could not complete.
            files.delete(root, toSafe)
            return false
        }
        return true
    }

    override suspend fun exists(path: String): Boolean =
        if (path.isEmpty()) root.exists() else files.find(root, path) != null

    override suspend fun isDirectory(path: String): Boolean =
        if (path.isEmpty()) root.isDirectory else files.find(root, path)?.isDirectory == true
}
