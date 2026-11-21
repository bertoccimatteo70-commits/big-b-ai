package com.bigbai.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationTimingTest {

    @Test
    fun `uses llama perf counters for prompt and decode speed`() {
        val timing = GenerationTiming.compute(
            promptStartedMs = 1_000,
            firstTokenAtMs = 1_400,
            finishedMs = 3_600,
            streamedTokens = 40,
            perf = longArrayOf(250, 2_000, 50, 40),
        )

        assertEquals(50, timing.promptTokens)
        assertEquals(40, timing.completionTokens)
        assertEquals(250, timing.promptMs)
        assertEquals(2_000, timing.generationMs)
        assertEquals(400, timing.firstTokenMs)
        assertEquals(20.0, timing.tokensPerSecond, 0.0001)
    }

    @Test
    fun `falls back to wall clock when native perf is unavailable`() {
        val timing = GenerationTiming.compute(
            promptStartedMs = 10_000,
            firstTokenAtMs = 11_500,
            finishedMs = 13_000,
            streamedTokens = 3,
            perf = null,
        )

        assertEquals(0, timing.promptTokens)
        assertEquals(3, timing.completionTokens)
        assertEquals(1_500, timing.promptMs)
        assertEquals(1_500, timing.generationMs)
        assertEquals(1_500, timing.firstTokenMs)
        assertEquals(2.0, timing.tokensPerSecond, 0.0001)
    }

    @Test
    fun `reports zero speed when no token was produced`() {
        val timing = GenerationTiming.compute(
            promptStartedMs = 500,
            firstTokenAtMs = 0,
            finishedMs = 500,
            streamedTokens = 0,
            perf = null,
        )

        assertEquals(0, timing.completionTokens)
        assertEquals(0, timing.firstTokenMs)
        assertEquals(0.0, timing.tokensPerSecond, 0.0)
    }

    @Test
    fun `ignores zero perf counters instead of dividing by zero`() {
        val timing = GenerationTiming.compute(
            promptStartedMs = 0,
            firstTokenAtMs = 300,
            finishedMs = 1_300,
            streamedTokens = 5,
            perf = longArrayOf(0, 0, 0, 0),
        )

        assertEquals(5, timing.completionTokens)
        assertEquals(300, timing.promptMs)
        assertEquals(1_000, timing.generationMs)
        assertEquals(5.0, timing.tokensPerSecond, 0.0001)
    }

    @Test
    fun `stopped generation before first token has no negative durations`() {
        val timing = GenerationTiming.compute(
            promptStartedMs = 2_000,
            firstTokenAtMs = 0,
            finishedMs = 1_500,
            streamedTokens = 0,
            perf = null,
        )

        assertEquals(0, timing.firstTokenMs)
        assertEquals(0, timing.generationMs)
        assertEquals(0.0, timing.tokensPerSecond, 0.0)
    }
}
