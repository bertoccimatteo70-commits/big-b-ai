package com.bigbai.llm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaluationSuiteTest {

    private val case = EvalCase(
        id = "sample",
        category = "Test",
        prompt = "Spiega TCP e UDP.",
        minWords = 20,
        mustContain = listOf("TCP", "UDP"),
    )

    @Test
    fun `a complete answer passes`() {
        val answer = "TCP è orientato alla connessione e garantisce l'ordine dei dati. " +
            "UDP non apre una connessione, è più veloce e tollera la perdita di pacchetti. " +
            "TCP si usa per il web, UDP per streaming e giochi."
        val result = EvaluationSuite.score(case, answer)
        assertTrue(result.passed)
        assertTrue(result.score >= 80)
    }

    @Test
    fun `a short answer loses points`() {
        val result = EvaluationSuite.score(case, "TCP e UDP sono protocolli.")
        assertFalse(result.passed)
        assertTrue(result.notes.any { it.contains("Troppo breve") })
    }

    @Test
    fun `missing required terms fail the case`() {
        val answer = "I due protocolli di trasporto hanno caratteristiche diverse e vengono " +
            "usati in scenari diversi, come il web, lo streaming e i videogiochi online."
        val result = EvaluationSuite.score(case, answer)
        assertFalse(result.passed)
        assertTrue(result.notes.any { it.contains("Mancano") })
    }

    @Test
    fun `empty answer scores zero`() {
        val result = EvaluationSuite.score(case, "")
        assertEquals(0, result.score)
        assertFalse(result.passed)
    }

    @Test
    fun `the suite runs with a supplied generator`() = runBlocking {
        val results = EvaluationSuite.run { prompt ->
            "Risposta di prova per: $prompt. Contiene TCP e UDP e abbastanza parole per superare " +
                "il controllo minimo della suite di valutazione."
        }
        assertEquals(EvaluationSuite.cases.size, results.size)
        assertTrue(results.all { it.score >= 0 })
    }
}
