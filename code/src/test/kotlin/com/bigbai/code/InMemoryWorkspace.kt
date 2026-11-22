package com.bigbai.code

/**
 * Test double for [ProjectWorkspace]. It applies the same path policy as the
 * real adapter and can simulate provider failures, so tests exercise the real
 * command and agent logic without Android.
 */
class InMemoryWorkspace(initial: Map<String, String> = emptyMap()) : ProjectWorkspace {

    private val files = linkedMapOf<String, String>()
    private val directories = linkedSetOf<String>()

    /** Simulates a provider that accepts the call but cannot write. */
    var failWrites: Boolean = false

    /** Simulates a provider that cannot delete or rename. */
    var failDeletes: Boolean = false

    var writeCount: Int = 0
        private set

    init {
        for ((path, content) in initial) {
            val safe = ProjectPaths.normalize(path) ?: continue
            files[safe] = content
            addParents(safe)
        }
    }

    fun snapshot(): Map<String, String> = LinkedHashMap(files)

    fun fileNames(): Set<String> = files.keys.toSet()

    fun directoryNames(): Set<String> = directories.toSet()

    override suspend fun list(directory: String): List<WorkspaceEntry> {
        val safe = if (directory.isEmpty()) "" else ProjectPaths.normalize(directory) ?: return emptyList()
        if (safe.isNotEmpty() && safe !in directories) return emptyList()
        val prefix = if (safe.isEmpty()) "" else "$safe/"
        val entries = linkedMapOf<String, WorkspaceEntry>()
        for ((path, content) in files) {
            if (!path.startsWith(prefix)) continue
            val rest = path.removePrefix(prefix)
            if (rest.contains('/')) continue
            entries[path] = WorkspaceEntry(path, false, content.toByteArray().size.toLong())
        }
        for (path in directories) {
            if (!path.startsWith(prefix)) continue
            val rest = path.removePrefix(prefix)
            if (rest.isEmpty() || rest.contains('/')) continue
            entries[path] = WorkspaceEntry(path, true)
        }
        return entries.values.sortedBy { it.path }
    }

    override suspend fun read(path: String): String? {
        val safe = ProjectPaths.normalize(path) ?: return null
        return files[safe]
    }

    override suspend fun write(path: String, content: String): Boolean {
        val safe = ProjectPaths.normalize(path) ?: return false
        if (failWrites) return false
        addParents(safe)
        files[safe] = content
        writeCount++
        return true
    }

    override suspend fun mkdir(path: String): Boolean {
        val safe = ProjectPaths.normalize(path) ?: return false
        if (failWrites) return false
        directories += safe
        addParents(safe)
        return true
    }

    override suspend fun delete(path: String): Boolean {
        val safe = ProjectPaths.normalize(path) ?: return false
        if (failDeletes) return false
        var changed = files.remove(safe) != null
        changed = directories.remove(safe) || changed
        val prefix = "$safe/"
        val nestedFiles = files.keys.filter { it.startsWith(prefix) }
        nestedFiles.forEach(files::remove)
        val nestedDirectories = directories.filter { it.startsWith(prefix) }
        nestedDirectories.forEach(directories::remove)
        return changed || nestedFiles.isNotEmpty() || nestedDirectories.isNotEmpty()
    }

    override suspend fun rename(from: String, to: String): Boolean {
        val source = ProjectPaths.normalize(from) ?: return false
        val target = ProjectPaths.normalize(to) ?: return false
        if (failDeletes) return false
        if (source.isEmpty() || target.isEmpty()) return false
        if (exists(target)) return false
        val content = files.remove(source)
        if (content != null) {
            addParents(target)
            files[target] = content
            return true
        }
        if (source !in directories) return false
        addParents(target)
        val sourcePrefix = "$source/"
        val targetPrefix = "$target/"
        val movedFiles = files.filterKeys { it.startsWith(sourcePrefix) }
        movedFiles.forEach { (path, value) ->
            files.remove(path)
            files[targetPrefix + path.removePrefix(sourcePrefix)] = value
        }
        val movedDirectories = directories.filter { it == source || it.startsWith(sourcePrefix) }
        movedDirectories.forEach { path ->
            directories.remove(path)
            directories += targetPrefix + path.removePrefix(sourcePrefix).removePrefix("/")
        }
        directories += target
        return true
    }

    override suspend fun exists(path: String): Boolean {
        val safe = if (path.isEmpty()) "" else ProjectPaths.normalize(path) ?: return false
        return safe.isEmpty() || safe in files || safe in directories
    }

    override suspend fun isDirectory(path: String): Boolean {
        val safe = if (path.isEmpty()) "" else ProjectPaths.normalize(path) ?: return false
        return safe.isEmpty() || safe in directories
    }

    private fun addParents(path: String) {
        var parent = WorkspacePaths.parent(path)
        while (parent.isNotEmpty()) {
            directories += parent
            parent = WorkspacePaths.parent(parent)
        }
    }
}
