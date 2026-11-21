package com.bigbai.llm

import com.bigbai.core.model.FinishReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityGateTest {

    private fun report(
        repetition: Double = 0.1,
        unclosedFence: Boolean = false,
        empty: Boolean = false,
        truncated: Boolean = false,
        issues: List<String> = emptyList(),
    ) = ResponseQuality.Report(
        empty = empty,
        repetitionRatio = repetition,
        truncated = truncated,
        unclosedFence = unclosedFence,
        words = 100,
        issues = issues,
    )

    @Test
    fun `a looping answer is rewritten while there is time`() {
        assertTrue(QualityGate.shouldRetry(report(repetition = 0.6), elapsedMs = 10_000))
        assertFalse(QualityGate.shouldRetry(report(repetition = 0.6), elapsedMs = 120_000))
    }

    @Test
    fun `an unclosed code block is rewritten`() {
        assertTrue(QualityGate.shouldRetry(report(unclosedFence = true), elapsedMs = 5_000))
    }

    @Test
    fun `a normal answer is never rewritten`() {
        assertFalse(QualityGate.shouldRetry(report(repetition = 0.2), elapsedMs = 5_000))
    }

    @Test
    fun `empty answers are not retried because a rewrite cannot help`() {
        assertFalse(QualityGate.shouldRetry(report(empty = true), elapsedMs = 1_000))
    }

    @Test
    fun `a token-limit cut is retried even when the text looks complete`() {
        val cut = report(truncated = false, repetition = 0.0)
        assertTrue(QualityGate.shouldRetry(cut, elapsedMs = 10_000, finishReason = FinishReason.LENGTH))
    }

    @Test
    fun `a heuristic truncation with a clean EOS is not retried`() {
        val finished = report(truncated = true, repetition = 0.0)
        assertFalse(QualityGate.shouldRetry(finished, elapsedMs = 10_000, finishReason = FinishReason.EOS))
        assertFalse(QualityGate.shouldRetry(finished, elapsedMs = 10_000, finishReason = FinishReason.UNKNOWN))
    }

    @Test
    fun `cancelled, timed out and failed generations are never retried automatically`() {
        val looping = report(repetition = 0.8)
        assertFalse(QualityGate.shouldRetry(looping, 10_000, FinishReason.CANCELLED))
        assertFalse(QualityGate.shouldRetry(looping, 10_000, FinishReason.TIMEOUT))
        assertFalse(QualityGate.shouldRetry(looping, 10_000, FinishReason.ERROR))
    }

    @Test
    fun `only one rewrite is allowed`() {
        val looping = report(repetition = 0.8)
        assertTrue(QualityGate.shouldRetry(looping, 10_000, FinishReason.EOS, attempt = 0))
        assertFalse(QualityGate.shouldRetry(looping, 10_000, FinishReason.EOS, attempt = 1))
    }

    @Test
    fun `the retry token budget grows but stays bounded`() {
        assertEquals(1_024, QualityGate.retryMaxTokens(512))
        assertEquals(4_096, QualityGate.retryMaxTokens(4_000))
    }

    @Test
    fun `a complete retry replaces a truncated first answer`() {
        assertTrue(QualityGate.isAcceptableRetry(report(truncated = true), report(truncated = false)))
    }

    @Test
    fun `a truncated retry never replaces a complete first answer`() {
        assertFalse(QualityGate.isAcceptableRetry(report(truncated = false), report(truncated = true)))
    }

    @Test
    fun `a much more repetitive retry is rejected`() {
        assertFalse(QualityGate.isAcceptableRetry(report(repetition = 0.2), report(repetition = 0.6)))
        assertTrue(QualityGate.isAcceptableRetry(report(repetition = 0.5), report(repetition = 0.4)))
    }

    @Test
    fun `a retry that opens a code fence is rejected`() {
        assertFalse(QualityGate.isAcceptableRetry(report(unclosedFence = false), report(unclosedFence = true)))
        assertTrue(QualityGate.isAcceptableRetry(report(unclosedFence = true), report(unclosedFence = false)))
    }

    @Test
    fun `describe names a token-limit cut`() {
        val text = QualityGate.describe(report(), FinishReason.LENGTH)
        assertTrue(text!!.contains("limite di token"))
        assertNull(QualityGate.describe(report()))
    }

    @Test
    fun `describe returns the reported issues`() {
        assertEquals("Ripetizioni eccessive", QualityGate.describe(report(issues = listOf("Ripetizioni eccessive"))))
        assertNull(QualityGate.describe(report()))
    }
}
