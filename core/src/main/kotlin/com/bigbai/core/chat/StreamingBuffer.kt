package com.bigbai.core.chat

/**
 * Accumulates streamed tokens and rate-limits how often a full snapshot is
 * published to the UI.
 *
 * The engine emits one token per JNI call; publishing a new immutable state for
 * every token meant allocating a copy of the whole answer dozens of times per
 * second and recomposing the chat list at token frequency. A 40 ms window keeps
 * streaming visually continuous (~25 fps) while bounding both allocation and
 * recomposition. The complete text is always available through [snapshot].
 */
class StreamingBuffer(private val intervalMs: Long = 40L) {

    private val text = StringBuilder()
    private var lastEmitMs = Long.MIN_VALUE
    private var emittedLength = 0

    val length: Int get() = text.length

    fun isEmpty(): Boolean = text.isEmpty()

    /**
     * Append [token] and return a snapshot when the UI should be updated,
     * or null when the token was buffered only.
     *
     * @param nowMs monotonic time in milliseconds (SystemClock.elapsedRealtime).
     */
    fun append(token: String, nowMs: Long): String? {
        if (token.isNotEmpty()) text.append(token)
        val due = lastEmitMs == Long.MIN_VALUE || nowMs - lastEmitMs >= intervalMs
        if (!due) return null
        lastEmitMs = nowMs
        if (text.length == emittedLength) return null
        emittedLength = text.length
        return text.toString()
    }

    /** Force a snapshot, used when generation ends so the last tokens are shown. */
    fun flush(): String {
        emittedLength = text.length
        return text.toString()
    }

    fun snapshot(): String = text.toString()
}
