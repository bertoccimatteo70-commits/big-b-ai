package com.bigbai.llm

/**
 * Removes chat-template control tokens from model output and stops generation
 * as soon as one appears.
 *
 * Small models sometimes keep writing after the end-of-turn marker instead of
 * emitting a real EOG token, and a few GGUFs do not declare `<|im_end|>` as an
 * end-of-generation token at all. The previous version displayed those markers
 * verbatim and let the model continue into a second, invented turn. This class
 * cuts the stream at the first marker, holds back a suffix that could still be
 * the beginning of a marker (so a partial `<|im_` never flashes in the UI) and
 * is pure Kotlin, therefore unit-testable without a device.
 */
class AnswerSanitizer(
    private val markers: List<String> = DEFAULT_MARKERS,
) {
    private val pending = StringBuilder()

    /** True once a stop marker was found: no further text will be emitted. */
    var finished: Boolean = false
        private set

    /** Consumes a decoded chunk and returns the text that is safe to display. */
    fun feed(chunk: String): String {
        if (finished) return ""
        if (chunk.isEmpty()) return ""
        pending.append(chunk)

        val stopAt = markers
            .map { marker -> pending.indexOf(marker) }
            .filter { it >= 0 }
            .minOrNull()
        if (stopAt != null) {
            val visible = pending.substring(0, stopAt)
            pending.setLength(0)
            finished = true
            return visible
        }

        // Hold back the longest suffix that is a prefix of a marker.
        var hold = 0
        for (marker in markers) {
            val max = minOf(marker.length - 1, pending.length)
            for (length in max downTo 1) {
                if (pending.regionMatches(pending.length - length, marker, 0, length)) {
                    hold = maxOf(hold, length)
                    break
                }
            }
        }
        val cut = pending.length - hold
        if (cut <= 0) return ""
        val visible = pending.substring(0, cut)
        pending.delete(0, cut)
        return visible
    }

    /** Text still held back at the end of the stream (never a stop marker). */
    fun flush(): String {
        if (finished) return ""
        val rest = pending.toString()
        pending.setLength(0)
        return rest
    }

    companion object {
        val DEFAULT_MARKERS: List<String> = listOf(
            "<|im_end|>",
            "<|im_start|>",
            "<|endoftext|>",
            "<|eot_id|>",
            "<|end_of_text|>",
            "<|end|>",
            "<end_of_turn>",
            "<start_of_turn>",
            "</s>",
        )

        /** Non-streaming helper used for code answers and imported text. */
        fun clean(text: String): String {
            if (text.isEmpty()) return ""
            val sanitizer = AnswerSanitizer()
            val visible = StringBuilder(sanitizer.feed(text))
            visible.append(sanitizer.flush())
            return visible.toString().trimEnd()
        }
    }
}
