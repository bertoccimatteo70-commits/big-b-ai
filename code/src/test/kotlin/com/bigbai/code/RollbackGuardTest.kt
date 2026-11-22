package com.bigbai.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RollbackGuardTest {

    @Test
    fun `fingerprint is stable and content sensitive`() {
        assertEquals(
            RollbackGuard.fingerprint("abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        )
        assertTrue(RollbackGuard.fingerprint("abc") != RollbackGuard.fingerprint("abd"))
    }

    @Test
    fun `user edits are detected on files the task wrote`() {
        val expected = mapOf("a.txt" to "v2", "b.txt" to "nuovo")
        val current = mapOf("a.txt" to "v3", "b.txt" to "nuovo")

        assertEquals(listOf("a.txt"), RollbackGuard.detectUserEdits(expected, current))
    }

    @Test
    fun `a deleted file is not a user edit`() {
        val expected = mapOf("a.txt" to "v2")

        assertTrue(RollbackGuard.detectUserEdits(expected, emptyMap()).isEmpty())
    }

    @Test
    fun `automatic rollback requires every safety condition`() {
        assertTrue(
            RollbackGuard.shouldAutoRollback(
                enabled = true,
                verificationFailed = true,
                writtenFiles = 2,
                checkpointId = "abc",
                userEdits = emptyList(),
            ),
        )
        assertFalse(
            RollbackGuard.shouldAutoRollback(true, true, 2, "abc", listOf("a.txt")),
        )
        assertFalse(RollbackGuard.shouldAutoRollback(false, true, 2, "abc", emptyList()))
        assertFalse(RollbackGuard.shouldAutoRollback(true, false, 2, "abc", emptyList()))
        assertFalse(RollbackGuard.shouldAutoRollback(true, true, 0, "abc", emptyList()))
        assertFalse(RollbackGuard.shouldAutoRollback(true, true, 2, null, emptyList()))
    }

    @Test
    fun `restore verification reports identical files`() {
        val expected = mapOf("a.txt" to "v1", "b.txt" to "b")
        val current = mapOf("a.txt" to "v1", "b.txt" to "b")

        val result = RollbackGuard.verifyRestore(expected, current)

        assertTrue(result.complete)
        assertEquals(listOf("a.txt", "b.txt"), result.verified)
        assertTrue(result.message().contains("2 file identici"))
    }

    @Test
    fun `restore verification catches a partial restore`() {
        val expected = mapOf("a.txt" to "v1", "b.txt" to "b")
        val current = mapOf("a.txt" to "v1", "b.txt" to "diverso")

        val result = RollbackGuard.verifyRestore(expected, current)

        assertFalse(result.complete)
        assertEquals(listOf("b.txt"), result.mismatched)
        assertTrue(result.message().contains("1 diversi"))
    }

    @Test
    fun `restore verification reports missing files`() {
        val result = RollbackGuard.verifyRestore(
            mapOf("a.txt" to "v1"),
            emptyMap(),
        )

        assertFalse(result.complete)
        assertEquals(listOf("a.txt"), result.missing)
    }
}
