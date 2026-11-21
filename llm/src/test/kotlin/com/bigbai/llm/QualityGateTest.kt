package com.bigbai.llm

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
        issues: List<String> = emptyList(),
    ) = ResponseQuality.Report(
        empty = empty,
        repetitionRatio = repetition,
        truncated = false,
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
    fun `describe returns the reported issues`() {
        assertEquals("Ripetizioni eccessive", QualityGate.describe(report(issues = listOf("Ripetizioni eccessive"))))
        assertNull(QualityGate.describe(report()))
    }
}
