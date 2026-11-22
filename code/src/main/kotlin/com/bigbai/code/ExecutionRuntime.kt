package com.bigbai.code

/**
 * A request to execute a project file in the platform runtime.
 *
 * Only languages Android can genuinely run are accepted: HTML and JavaScript in
 * the isolated WebView. Everything else is refused with an explicit reason
 * instead of being presented as "executed".
 */
data class ExecutionRequest(
    val path: String,
    val content: String,
    val language: String,
    val timeoutMs: Long = 8_000,
    val allowNetwork: Boolean = false,
)

data class ExecutionResult(
    val success: Boolean,
    val logs: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val durationMs: Long = 0,
    val timedOut: Boolean = false,
    val blockedRequests: Int = 0,
    /** Set when the platform cannot run this language at all. */
    val unsupportedReason: String? = null,
) {
    fun outputText(maxLines: Int = 60): String = buildString {
        if (unsupportedReason != null) {
            appendLine("Non eseguibile: $unsupportedReason")
        } else {
            appendLine(
                if (timedOut) "Esecuzione interrotta per timeout"
                else if (success) "Esecuzione completata"
                else "Esecuzione terminata con errori",
            )
            appendLine("Durata: ${durationMs} ms · richieste di rete bloccate: $blockedRequests")
            errors.take(maxLines).forEach { appendLine("ERRORE: $it") }
            logs.take(maxLines).forEach { appendLine(it) }
            if (logs.size > maxLines) appendLine("… altre ${logs.size - maxLines} righe")
        }
    }.trim()
}

/** Runtime capable of actually running a project file. */
interface ExecutionRuntime {
    suspend fun execute(request: ExecutionRequest): ExecutionResult
}

/**
 * Language support matrix, stated once and used by the UI, the terminal and the
 * verifier. It is the honest answer to "what can ITAGPT Code actually run?".
 */
object LanguageSupport {

    enum class Level(val label: String) {
        EXECUTABLE("eseguibile"),
        VALIDATED("validabile"),
        STATIC_ONLY("solo analisi statica"),
        UNSUPPORTED("non supportato"),
    }

    data class Info(
        val language: String,
        val extensions: List<String>,
        val level: Level,
        val reason: String,
    )

    private val entries = listOf(
        Info(
            "HTML",
            listOf("html", "htm"),
            Level.EXECUTABLE,
            "Eseguito in una WebView isolata, con rete bloccata salvo autorizzazione.",
        ),
        Info(
            "JavaScript",
            listOf("js", "mjs"),
            Level.EXECUTABLE,
            "Eseguito nella WebView isolata; gli errori di sintassi e le eccezioni vengono riportati.",
        ),
        Info(
            "JSON",
            listOf("json"),
            Level.VALIDATED,
            "Validato con un parser reale; un errore riporta riga e motivo.",
        ),
        Info(
            "XML",
            listOf("xml", "svg", "plist"),
            Level.VALIDATED,
            "Verificata la correttezza formale (annidamento, attributi, radice).",
        ),
        Info(
            "CSV",
            listOf("csv", "tsv"),
            Level.VALIDATED,
            "Verificate righe, colonne e virgolette.",
        ),
        Info(
            "Java",
            listOf("java"),
            Level.STATIC_ONLY,
            "Android non include javac: compilazione ed esecuzione non sono possibili. Restano " +
                "analisi statica, controllo della struttura e delle parentesi.",
        ),
        Info(
            "Kotlin",
            listOf("kt", "kts"),
            Level.STATIC_ONLY,
            "Android non include kotlinc: compilazione ed esecuzione non sono possibili. Restano " +
                "analisi statica e controllo della struttura.",
        ),
        Info(
            "C/C++",
            listOf("c", "h", "cpp", "cc", "hpp"),
            Level.STATIC_ONLY,
            "Android non include clang per le app: compilazione ed esecuzione non sono possibili. " +
                "Vengono controllati parentesi, include locali e guardie degli header.",
        ),
        Info(
            "Python",
            listOf("py"),
            Level.STATIC_ONLY,
            "L'interprete Python non è incluso nell'app: nessuna esecuzione.",
        ),
    )

    fun forPath(path: String): Info? {
        val extension = path.substringAfterLast('.', "").lowercase()
        return entries.firstOrNull { extension in it.extensions }
    }

    fun all(): List<Info> = entries

    fun matrix(): String = buildString {
        appendLine("Linguaggio · stato · motivo")
        for (entry in entries) {
            appendLine("${entry.language} (${entry.extensions.joinToString(", ")}) · ${entry.level.label} · ${entry.reason}")
        }
    }.trim()
}
