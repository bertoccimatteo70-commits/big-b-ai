package com.bigbai.core.device

import com.bigbai.core.model.PerformanceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceAdvisorTest {

    @Test
    fun `a cool flagship uses all cores in performance mode`() {
        val advice = PerformanceAdvisor.recommend(
            cores = 8,
            thermalStatus = 0,
            powerSaver = false,
            mode = PerformanceMode.PERFORMANCE,
        )
        assertEquals(8, advice.threads)
        assertEquals(1f, advice.contextFactor)
        assertNull(advice.note)
    }

    @Test
    fun `a severe thermal state halves the context and caps threads`() {
        val advice = PerformanceAdvisor.recommend(
            cores = 8,
            thermalStatus = 3,
            powerSaver = false,
            mode = PerformanceMode.PERFORMANCE,
        )
        assertEquals(6, advice.threads)
        assertEquals(0.6f, advice.contextFactor)
        assertNotNull(advice.note)
    }

    @Test
    fun `critical heat never leaves fewer than one thread`() {
        val advice = PerformanceAdvisor.recommend(
            cores = 2,
            thermalStatus = 5,
            powerSaver = false,
            mode = PerformanceMode.PERFORMANCE,
        )
        assertEquals(1, advice.threads)
        assertTrue(advice.contextFactor <= 0.45f)
    }

    @Test
    fun `battery saver reduces threads and context on a balanced device`() {
        val normal = PerformanceAdvisor.recommend(8, 0, powerSaver = false)
        val saver = PerformanceAdvisor.recommend(8, 0, powerSaver = true)
        assertTrue(saver.threads <= normal.threads)
        assertTrue(saver.contextFactor < normal.contextFactor)
        assertNotNull(saver.note)
    }

    @Test
    fun `save mode is always lighter than performance mode`() {
        val saver = PerformanceAdvisor.recommend(8, 0, false, PerformanceMode.SAVER)
        val performance = PerformanceAdvisor.recommend(8, 0, false, PerformanceMode.PERFORMANCE)
        assertTrue(saver.threads < performance.threads)
        assertTrue(saver.contextFactor < performance.contextFactor)
    }
}
