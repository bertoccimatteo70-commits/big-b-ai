package com.bigbai.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditBudgetTest {

    @Test
    fun `forty unique files are allowed and the forty first is refused`() {
        val budget = EditBudget()
        repeat(CodeAgent.MAX_FILES_PER_TASK) { index ->
            assertTrue(budget.beginOperation())
            assertTrue(budget.recordChange("file$index.txt"))
        }
        assertEquals(CodeAgent.MAX_FILES_PER_TASK, budget.fileCount)
        assertTrue(budget.exhausted)
        assertTrue(budget.beginOperation())
        assertFalse(budget.canChange("file41.txt"))
        assertFalse(budget.recordChange("file41.txt"))
        assertTrue(budget.blockReason().contains("40 file"))
    }

    @Test
    fun `rewriting the same file does not consume another slot`() {
        val budget = EditBudget()
        repeat(10) {
            assertTrue(budget.beginOperation())
            assertTrue(budget.recordChange("index.html"))
        }
        assertEquals(1, budget.fileCount)
        assertEquals(CodeAgent.MAX_FILES_PER_TASK - 1, budget.remainingFiles)
    }

    @Test
    fun `operation cap stops a task even when it touches few files`() {
        val budget = EditBudget(maxFiles = 40, maxOperations = 3)
        repeat(3) { assertTrue(budget.beginOperation()) }
        assertFalse(budget.beginOperation())
        assertTrue(budget.exhausted)
        assertTrue(budget.blockReason().contains("3 operazioni"))
    }

    @Test
    fun `splitting writes over many steps cannot pass the forty file cap`() {
        val budget = EditBudget()
        var written = 0
        // 12 agent steps with 6 writes each: 72 attempts, only 40 may land.
        for (step in 0 until CodeAgent.MAX_STEPS) {
            var action = 0
            while (action < CodeAgent.MAX_ACTIONS_PER_STEP && budget.beginOperation()) {
                val path = "file${written + 1}.txt"
                if (budget.canChange(path) && budget.recordChange(path)) written += 1
                action += 1
            }
        }
        assertEquals(CodeAgent.MAX_FILES_PER_TASK, written)
        assertTrue(budget.exhausted)
    }

    @Test
    fun `deleting the same file twice counts once`() {
        val budget = EditBudget()
        assertTrue(budget.recordChange("old.txt"))
        assertTrue(budget.recordChange("old.txt"))
        assertEquals(1, budget.fileCount)
    }
}
