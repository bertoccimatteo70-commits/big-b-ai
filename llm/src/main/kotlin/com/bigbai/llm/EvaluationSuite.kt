package com.bigbai.llm

/**
 * Repeatable evaluation of model answers.
 *
 * The cases cover the tasks the app promises: simple and technical
 * explanations, summaries, general knowledge, multi-turn conversation,
 * programming and document reasoning. Scoring is deterministic so two runs with
 * the same answer produce the same result; running it against a real model is a
 * device task (the app has no model in unit tests).
 */
data class EvalCase(
    val id: String,
    val category: String,
    val prompt: String,
    val minWords: Int,
    val mustContain: List<String> = emptyList(),
    val mustNotContain: List<String> = emptyList(),
)

data class EvalResult(
    val caseId: String,
    val passed: Boolean,
    val score: Int,
    val notes: List<String>,
)

object EvaluationSuite {

    val cases: List<EvalCase> = listOf(
        EvalCase(
            id = "simple-explanation",
            category = "Spiegazione semplice",
            prompt = "Spiega cos'è la fotosintesi come se avessi dieci anni.",
            minWords = 40,
            mustContain = listOf("piante", "luce"),
        ),
        EvalCase(
            id = "technical-explanation",
            category = "Spiegazione tecnica",
            prompt = "Spiega la differenza tra TCP e UDP, con esempi d'uso.",
            minWords = 60,
            mustContain = listOf("TCP", "UDP"),
        ),
        EvalCase(
            id = "summary",
            category = "Riassunto",
            prompt = "Riassumi in cinque punti i vantaggi dei modelli linguistici locali.",
            minWords = 40,
        ),
        EvalCase(
            id = "general-knowledge",
            category = "Conoscenza generale",
            prompt = "Qual è la capitale dell'Australia e perché non è Sydney?",
            minWords = 25,
            mustContain = listOf("Canberra"),
        ),
        EvalCase(
            id = "multi-turn",
            category = "Conversazione",
            prompt = "Prima dimmi cos'è una funzione, poi fai un esempio in Python.",
            minWords = 40,
            mustContain = listOf("def"),
        ),
        EvalCase(
            id = "programming",
            category = "Programmazione",
            prompt = "Scrivi una funzione Python che verifica se una stringa è palindroma.",
            minWords = 30,
            mustContain = listOf("def"),
        ),
        EvalCase(
            id = "document",
            category = "Documento",
            prompt = "Usa solo il documento allegato: quante persone partecipano al progetto?",
            minWords = 10,
        ),
    )

    /** Scores an answer against a case, without any model or network access. */
    fun score(case: EvalCase, answer: String): EvalResult {
        val quality = ResponseQuality.inspect(answer)
        val lower = answer.lowercase()
        val notes = mutableListOf<String>()
        var score = 100

        if (quality.empty) {
            return EvalResult(case.id, false, 0, listOf("Risposta vuota"))
        }
        if (quality.words < case.minWords) {
            score -= 45
            notes.add("Troppo breve: ${quality.words} parole, attese ${case.minWords}")
        }
        if (quality.repetitionRatio > 0.45) {
            score -= 30
            notes.add("Ripetizioni eccessive (${"%.2f".format(quality.repetitionRatio)})")
        }
        if (quality.truncated) {
            score -= 15
            notes.add("Risposta troncata")
        }
        if (quality.unclosedFence) {
            score -= 15
            notes.add("Codice non chiuso")
        }
        val missing = case.mustContain.filterNot { lower.contains(it.lowercase()) }
        if (missing.isNotEmpty()) {
            score -= 25
            notes.add("Mancano: ${missing.joinToString()}")
        }
        val forbidden = case.mustNotContain.filter { lower.contains(it.lowercase()) }
        if (forbidden.isNotEmpty()) {
            score -= 40
            notes.add("Contiene: ${forbidden.joinToString()}")
        }
        // A missing required term is a factual failure, not a style penalty.
        val passed = score >= 60 && missing.isEmpty()
        return EvalResult(case.id, passed, score.coerceIn(0, 100), notes)
    }

    /** Runs the whole suite with a supplied generator (a model on device, a fake in tests). */
    suspend fun run(generate: suspend (String) -> String): List<EvalResult> =
        cases.map { case -> score(case, generate(case.prompt)) }
}
