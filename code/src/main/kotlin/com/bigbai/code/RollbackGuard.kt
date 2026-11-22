package com.bigbai.code

import java.security.MessageDigest

data class RestoreVerification(
    val verified: List<String>,
    val mismatched: List<String>,
    val missing: List<String>,
) {
    val complete: Boolean get() = mismatched.isEmpty() && missing.isEmpty()

    fun message(): String = buildString {
        append("Ripristino verificato: ").append(verified.size).append(" file identici al checkpoint")
        if (mismatched.isNotEmpty()) append(", ").append(mismatched.size).append(" diversi")
        if (missing.isNotEmpty()) append(", ").append(missing.size).append(" mancanti")
        append('.')
    }
}

data class RestorePlan(
    /** Paths to write back from the checkpoint, with their original content. */
    val restore: Map<String, String> = emptyMap(),
    /** Paths created by the task that must be removed. */
    val delete: List<String> = emptyList(),
    /** Paths changed by the user after the task: never touched by a rollback. */
    val skipped: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = restore.isEmpty() && delete.isEmpty()
}

/**
 * Safety rules around rollback.
 *
 * Three promises are kept here:
 *  * a rollback never deletes work the user did after the task — [detectUserEdits]
 *    and [RollbackPlanner] make those files visible before anything is restored;
 *  * a restore is not declared successful because the write returned true —
 *    [verifyRestore] compares the real bytes with the checkpoint;
 *  * automatic rollback is opt-in and requires every safety condition.
 */
object RollbackGuard {

    fun fingerprint(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun fingerprints(files: Map<String, String>): Map<String, String> =
        files.mapValues { (_, content) -> fingerprint(content) }

    /**
     * Files the task wrote whose current content is no longer what the task
     * produced. They are the user's edits and must not be silently overwritten.
     */
    fun detectUserEdits(
        expected: Map<String, String>,
        current: Map<String, String>,
    ): List<String> = expected.keys.filter { path ->
        val now = current[path]
        now != null && fingerprint(now) != fingerprint(expected.getValue(path))
    }

    fun shouldAutoRollback(
        enabled: Boolean,
        verificationFailed: Boolean,
        writtenFiles: Int,
        checkpointId: String?,
        userEdits: List<String>,
    ): Boolean = enabled && verificationFailed && writtenFiles > 0 &&
        checkpointId != null && userEdits.isEmpty()

    fun verifyRestore(
        expected: Map<String, String>,
        current: Map<String, String>,
    ): RestoreVerification {
        val verified = mutableListOf<String>()
        val mismatched = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for ((path, content) in expected) {
            val now = current[path]
            when {
                now == null -> missing += path
                fingerprint(now) == fingerprint(content) -> verified += path
                else -> mismatched += path
            }
        }
        return RestoreVerification(verified, mismatched, missing)
    }
}

/**
 * Builds the exact list of operations a rollback will perform.
 *
 * A file is skipped when its content is no longer what the task wrote: that is
 * a legitimate user edit, and losing it would be worse than an incomplete
 * restore. The skip is returned, never silent.
 */
object RollbackPlanner {
    /**
     * Hashes are used instead of full contents so the state kept between a task
     * and a rollback stays small even for large projects.
     */
    fun plan(
        checkpoint: Map<String, String>,
        touched: Set<String>,
        currentHashes: Map<String, String>,
        expectedHashes: Map<String, String>,
    ): RestorePlan {
        val restore = linkedMapOf<String, String>()
        val delete = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for (path in touched) {
            val after = expectedHashes[path]
            val now = currentHashes[path]
            val userEdited = after != null && now != null && now != after
            if (userEdited) {
                skipped += path
                continue
            }
            val original = checkpoint[path]
            if (original != null) {
                restore[path] = original
            } else if (now != null) {
                delete += path
            }
        }
        return RestorePlan(restore, delete, skipped)
    }
}
