package com.bigbai.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseQualityTest {

    @Test
    fun `empty answer is rejected`() {
        val report = ResponseQuality.inspect("   \n ")
        assertTrue(report.empty)
        assertFalse(report.acceptable)
    }

    @Test
    fun `a normal answer is acceptable`() {
        val answer = "La fotosintesi è il processo con cui le piante trasformano la luce in energia. " +
            "Avviene nelle foglie, grazie alla clorofilla, e produce zuccheri e ossigeno."
        val report = ResponseQuality.inspect(answer)
        assertTrue(report.acceptable)
        assertTrue(report.words > 20)
    }

    @Test
    fun `a looping answer is detected`() {
        val loop = List(40) { "Questa è una frase ripetuta sempre uguale." }.joinToString(" ")
        val report = ResponseQuality.inspect(loop)
        assertTrue(report.repetitionRatio > 0.45)
        assertFalse(report.acceptable)
    }

    @Test
    fun `an unclosed code fence is reported`() {
        val answer = "Ecco il codice:\n```python\nprint('ciao')\n"
        val report = ResponseQuality.inspect(answer)
        assertTrue(report.unclosedFence)
        assertFalse(report.acceptable)
    }

    @Test
    fun `a cut-off sentence is reported`() {
        val answer = "Questa risposta si interrompe a metà mentre stava spiegan"
        val report = ResponseQuality.inspect(answer)
        assertTrue(report.truncated)
    }

    @Test
    fun `echoing the question is detected`() {
        assertTrue(ResponseQuality.isEcho("Cos'è la fotosintesi?", "Cos'è la fotosintesi?"))
        assertFalse(
            ResponseQuality.isEcho(
                "Cos'è la fotosintesi?",
                "È il processo con cui le piante convertono la luce in energia chimica.",
            ),
        )
    }
}
