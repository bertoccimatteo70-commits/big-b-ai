package com.bigbai.code

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.ByteArrayOutputStream
import java.io.File

data class GitCommitInfo(
    val id: String,
    val shortId: String,
    val message: String,
    val author: String,
    val timestamp: Long,
)

/**
 * Versioning for ITAGPT Code.
 *
 * The Storage Access Framework does not expose a filesystem path, so JGit cannot
 * operate directly on the user's folder. Instead every commit snapshots the
 * project into a private Git repository (a real, standard `.git`), which gives
 * commit / diff / history / restore / branch / compare. Restoring a file copies
 * the chosen revision back into the authorised folder through SAF.
 */
class GitManager(private val context: Context) {

    private val root = File(context.filesDir, "git")

    fun mirrorDirectory(projectId: Long): File = File(root, "project-$projectId")

    private fun open(projectId: Long): Git? = runCatching {
        val directory = mirrorDirectory(projectId)
        if (File(directory, ".git").isDirectory) {
            Git.open(directory)
        } else {
            directory.mkdirs()
            Git.init().setDirectory(directory).setInitialBranch("main").call()
        }
    }.getOrNull()

    suspend fun commit(
        projectId: Long,
        files: Map<String, String>,
        message: String,
    ): GitCommitInfo? = withContext(Dispatchers.IO) {
        val git = open(projectId) ?: return@withContext null
        runCatching {
            git.use {
                val directory = mirrorDirectory(projectId)
                directory.listFiles()?.filter { child -> child.name != ".git" }?.forEach { it.deleteRecursively() }
                for ((path, content) in files) {
                    // A checkpoint must never write outside the mirror because of a
                    // malformed path coming from a provider or an old record.
                    val safe = ProjectPaths.normalize(path) ?: continue
                    val target = File(directory, safe)
                    target.parentFile?.mkdirs()
                    target.writeText(content)
                }
                it.add().addFilepattern(".").call()
                it.add().setUpdate(true).addFilepattern(".").call()
                val identity = PersonIdent("ITAGPT", "local@bigbai.app")
                val commit = it.commit()
                    .setMessage(message.ifBlank { "Snapshot del progetto" })
                    .setAuthor(identity)
                    .setCommitter(identity)
                    // An empty project still needs a baseline checkpoint.
                    .setAllowEmpty(true)
                    .call()
                commit.toInfo()
            }
        }.getOrNull()
    }

    /** Every file present in a commit, or null when the commit cannot be read. */
    suspend fun pathsAt(projectId: Long, commitId: String): Set<String>? = withContext(Dispatchers.IO) {
        val git = open(projectId) ?: return@withContext null
        git.use {
            runCatching {
                val repository = it.repository
                val commit = repository.parseCommit(ObjectId.fromString(commitId))
                val walk = TreeWalk(repository)
                walk.addTree(commit.tree)
                walk.isRecursive = true
                val paths = LinkedHashSet<String>()
                while (walk.next()) {
                    if (!walk.isSubtree) paths += walk.pathString
                }
                walk.close()
                paths
            }.getOrNull()
        }
    }

    suspend fun log(projectId: Long, limit: Int = 50): List<GitCommitInfo> = withContext(Dispatchers.IO) {
        val git = open(projectId) ?: return@withContext emptyList()
        git.use {
            runCatching {
                it.log().setMaxCount(limit).call().map { commit -> commit.toInfo() }
            }.getOrDefault(emptyList())
        }
    }

    suspend fun diff(projectId: Long, commitId: String): String = withContext(Dispatchers.IO) {
        val git = open(projectId) ?: return@withContext ""
        git.use {
            runCatching {
                val repository = it.repository
                val commit = repository.parseCommit(ObjectId.fromString(commitId))
                val parent = commit.parents.firstOrNull()
                val output = ByteArrayOutputStream()
                DiffFormatter(output).use { formatter ->
                    formatter.setRepository(repository)
                    formatter.setContext(3)
                    val newTree = treeParser(repository, commit)
                    val oldTree = parent?.let { p -> treeParser(repository, p) } ?: CanonicalTreeParser()
                    formatter.scan(oldTree, newTree)
                }
                output.toString("UTF-8")
            }.getOrDefault("")
        }
    }

    suspend fun readFileAt(projectId: Long, commitId: String, path: String): String? =
        withContext(Dispatchers.IO) {
            val git = open(projectId) ?: return@withContext null
            git.use {
                runCatching {
                    val repository = it.repository
                    val commit = repository.parseCommit(ObjectId.fromString(commitId))
                    val walk = TreeWalk.forPath(repository, path, commit.tree) ?: return@runCatching null
                    val loader = repository.open(walk.getObjectId(0))
                    String(loader.bytes, Charsets.UTF_8)
                }.getOrNull()
            }
        }

    suspend fun branches(projectId: Long): List<String> = withContext(Dispatchers.IO) {
        val git = open(projectId) ?: return@withContext emptyList()
        git.use {
            runCatching {
                it.branchList().call().map { ref -> Repository.shortenRefName(ref.name) }
            }.getOrDefault(emptyList())
        }
    }

    suspend fun currentBranch(projectId: Long): String? = withContext(Dispatchers.IO) {
        val git = open(projectId) ?: return@withContext null
        git.use { runCatching { it.repository.branch }.getOrNull() }
    }

    suspend fun createBranch(projectId: Long, name: String): Boolean = withContext(Dispatchers.IO) {
        if (!name.matches(Regex("[A-Za-z0-9._/-]{1,60}"))) return@withContext false
        val git = open(projectId) ?: return@withContext false
        git.use { runCatching { it.branchCreate().setName(name).call(); true }.getOrDefault(false) }
    }

    suspend fun checkout(projectId: Long, name: String): Boolean = withContext(Dispatchers.IO) {
        val git = open(projectId) ?: return@withContext false
        git.use { runCatching { it.checkout().setName(name).call(); true }.getOrDefault(false) }
    }

    suspend fun restoreCommit(projectId: Long, commitId: String): Boolean = withContext(Dispatchers.IO) {
        val git = open(projectId) ?: return@withContext false
        git.use {
            runCatching {
                it.reset().setMode(ResetCommand.ResetType.HARD).setRef(commitId).call()
                true
            }.getOrDefault(false)
        }
    }

    suspend fun head(projectId: Long): String? = withContext(Dispatchers.IO) {
        val git = open(projectId) ?: return@withContext null
        git.use { runCatching { it.repository.resolve(Constants.HEAD)?.name }.getOrNull() }
    }

    suspend fun status(projectId: Long): List<String> = withContext(Dispatchers.IO) {
        val git = open(projectId) ?: return@withContext emptyList()
        git.use {
            runCatching {
                val status = it.status().call()
                (status.added + status.modified + status.untracked + status.removed).sorted()
            }.getOrDefault(emptyList())
        }
    }

    private fun treeParser(repository: Repository, commit: org.eclipse.jgit.revwalk.RevCommit): CanonicalTreeParser {
        val parser = CanonicalTreeParser()
        repository.newObjectReader().use { reader -> parser.reset(reader, commit.tree) }
        return parser
    }

    private fun org.eclipse.jgit.revwalk.RevCommit.toInfo(): GitCommitInfo = GitCommitInfo(
        id = name,
        shortId = name.take(7),
        message = shortMessage,
        author = authorIdent?.name ?: "ITAGPT",
        timestamp = commitTime.toLong() * 1000L,
    )
}
