package com.bigbai.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class CodeQuotaTest {

    @Test
    fun `a new day resets the counter`() {
        val quota = CodeQuota.of("2026-11-21", "2026-11-20", 5)
        assertEquals(0, quota.used)
        assertEquals(5, quota.remaining)
        assertFalse(quota.exhausted)
    }

    @Test
    fun `same day keeps the stored count`() {
        val quota = CodeQuota.of("2026-11-21", "2026-11-21", 3)
        assertEquals(2, quota.remaining)
        assertFalse(quota.exhausted)
    }

    @Test
    fun `five tasks exhaust the allowance`() {
        var quota = CodeQuota.of("2026-11-21", "2026-11-21", 0)
        repeat(5) { quota = quota.consume() }
        assertEquals(0, quota.remaining)
        assertTrue(quota.exhausted)
        assertEquals(5, quota.consume().used)
    }

    @Test
    fun `a technical failure refunds the request`() {
        val consumed = CodeQuota.of("2026-11-21", "2026-11-21", 2).consume()
        assertEquals(3, consumed.used)
        assertEquals(2, consumed.refund().used)
        assertEquals(0, CodeQuota.of("2026-11-21", "2026-11-21", 0).refund().used)
    }

    @Test
    fun `stored values outside the range are clamped`() {
        assertEquals(5, CodeQuota.of("2026-11-21", "2026-11-21", 99).used)
        assertEquals(0, CodeQuota.of("2026-11-21", "2026-11-21", -4).used)
    }

    @Test
    fun `date is computed in the local calendar`() {
        val utc = ZoneId.of("UTC")
        // 2026-11-21T23:30:00Z is already 22 November in Rome.
        val instant = 1_795_303_800_000L
        assertEquals("2026-11-21", CodeQuota.today(instant, utc))
        assertEquals("2026-11-22", CodeQuota.today(instant, ZoneId.of("Europe/Rome")))
    }
}
