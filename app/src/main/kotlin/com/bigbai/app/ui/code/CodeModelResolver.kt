package com.bigbai.app.ui.code

/**
 * One installed model, reduced to the data the selection rules need.
 * Keeping this independent from the loader makes the choice testable on the JVM
 * and guarantees that the label and the loaded model come from the same call.
 */
data class CodeModelCandidate(
    val id: String,
    val label: String,
    val isCode: Boolean,
    val rank: Int,
)

sealed interface CodeModelChoice {
    val candidate: CodeModelCandidate?

    data class Selected(
        override val candidate: CodeModelCandidate,
        val source: CodeModelSource,
    ) : CodeModelChoice

    data class Unavailable(
        val message: String,
        override val candidate: CodeModelCandidate? = null,
    ) : CodeModelChoice
}

enum class CodeModelSource {
    /** The user explicitly chose this model for ITAGPT Code. */
    EXPLICIT_CODE,

    /** No explicit Code model: the best installed Code model is used. */
    AUTOMATIC_CODE,

    /** No Code model installed: the selected Chat model is used, and said so. */
    CHAT_FALLBACK,

    /** Nothing was selected: the best installed model is used. */
    BEST_INSTALLED,
}

object CodeModelResolver {

    fun resolve(
        installed: List<CodeModelCandidate>,
        activeCodeModelId: String?,
        activeChatModelId: String?,
    ): CodeModelChoice {
        if (installed.isEmpty()) {
            return CodeModelChoice.Unavailable(
                "Nessun modello installato. Apri Modelli per scaricarne uno: " +
                    "ITAGPT Code non può generare codice senza un modello.",
            )
        }

        // An explicit Code choice is a promise: never silently replace it with
        // the Chat model when the file has been removed or is incomplete.
        if (activeCodeModelId != null) {
            val explicit = installed.firstOrNull { it.id == activeCodeModelId }
            return if (explicit != null) {
                CodeModelChoice.Selected(explicit, CodeModelSource.EXPLICIT_CODE)
            } else {
                CodeModelChoice.Unavailable(
                    "Il modello Code selezionato non è più disponibile o è incompleto. " +
                        "Scegline un altro in Modelli oppure imposta «Automatico».",
                )
            }
        }

        installed.filter { it.isCode }.maxByOrNull { it.rank }?.let { bestCode ->
            return CodeModelChoice.Selected(bestCode, CodeModelSource.AUTOMATIC_CODE)
        }

        if (activeChatModelId != null) {
            installed.firstOrNull { it.id == activeChatModelId }?.let { chat ->
                return CodeModelChoice.Selected(chat, CodeModelSource.CHAT_FALLBACK)
            }
        }

        val best = installed.maxByOrNull { it.rank } ?: return CodeModelChoice.Unavailable(
            "Nessun modello utilizzabile. Apri Modelli per scaricarne uno.",
        )
        return CodeModelChoice.Selected(best, CodeModelSource.BEST_INSTALLED)
    }

    /** Label shown under the composer, always derived from the same choice. */
    fun label(choice: CodeModelChoice): String? = when (choice) {
        is CodeModelChoice.Selected -> when (choice.source) {
            CodeModelSource.EXPLICIT_CODE -> choice.candidate.label
            CodeModelSource.AUTOMATIC_CODE -> "${choice.candidate.label} (Code automatico)"
            CodeModelSource.CHAT_FALLBACK -> "${choice.candidate.label} (modello chat)"
            CodeModelSource.BEST_INSTALLED -> "${choice.candidate.label} (automatico)"
        }
        is CodeModelChoice.Unavailable -> null
    }
}
