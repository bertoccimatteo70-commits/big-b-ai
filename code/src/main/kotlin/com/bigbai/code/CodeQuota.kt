package com.bigbai.code

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Daily allowance for BIG B Code.
 *
 * One user task costs exactly one request: every internal step (reading files,
 * writing files, self-correction, checks) is part of that same task and is free.
 * The count is persisted by the app, so closing the process does not reset it;
 * it resets when the local calendar day changes.
 */
data class CodeQuota(
    val date: String,
    val used: Int,
    val limit: Int = LIMIT,
) {
    val remaining: Int get() = (limit - used).coerceAtLeast(0)
    val exhausted: Boolean get() = remaining == 0

    fun consume(): CodeQuota = copy(used = (used + 1).coerceAtMost(limit))

    /** Used when a task fails for a technical reason before doing any work. */
    fun refund(): CodeQuota = copy(used = (used - 1).coerceAtLeast(0))

    companion object {
        const val LIMIT = 5

        fun of(today: String, storedDate: String?, storedUsed: Int): CodeQuota =
            if (storedDate == today) {
                CodeQuota(today, storedUsed.coerceIn(0, LIMIT))
            } else {
                CodeQuota(today, 0)
            }

        fun today(nowMillis: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String =
            Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().toString()

        fun today(zone: ZoneId): String = LocalDate.now(zone).toString()
    }
}
