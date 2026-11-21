package com.bigbai.llm

import com.bigbai.core.model.Source

/**
 * System prompts are explicit about what the model can and cannot do. They are
 * written in the user's language and deliberately avoid promising tool use or
 * capabilities the installed model does not have.
 */
object PromptTemplates {

    fun chatSystemPrompt(
        language: String,
        memory: String,
        documentContext: String?,
        sources: List<Source>,
        visionAvailable: Boolean,
        hasImage: Boolean,
        tutor: String = "",
        modelStyle: String = "",
        retrievedContext: String = "",
        verifiedCalculations: String = "",
        /** Shorter prompt for sub-1B models, which ignore long instructions. */
        compact: Boolean = false,
        /** Text recognised by on-device OCR from attached images or scanned PDFs. */
        ocrContext: String = "",
    ): String {
        val italian = language.startsWith("it")
        val languageName = when {
            language.startsWith("it") -> "italiano"
            language.startsWith("es") -> "español"
            language.startsWith("fr") -> "français"
            language.startsWith("de") -> "Deutsch"
            language.startsWith("pt") -> "português"
            else -> "English"
        }
        val builder = StringBuilder()
        if (compact) {
            builder.append(
                if (italian) {
                    "Sei AI, l'assistente locale di ITAGPT. Rispondi in italiano corretto, chiaro e breve. " +
                        "Usa solo fatti certi: se non sai, dillo. Non inventare fonti, azioni o funzionalità. " +
                        "Non ripetere la domanda. Usa Markdown per elenchi e codice. " +
                        "Per la matematica mostra i passaggi.\n"
                } else {
                    "You are AI, ITAGPT's local assistant. Answer in $languageName, clearly and briefly. " +
                        "Use only facts you know: if you do not know, say so. Never invent sources, actions " +
                        "or capabilities. Do not restate the question. Use Markdown for lists and code. " +
                        "Show the steps for mathematics.\n"
                },
            )
        } else if (italian) {
            builder.append(
                "Sei AI, l'assistente personale di ITAGPT. Esegui l'inferenza interamente sul dispositivo " +
                    "dell'utente: nessun dato viene inviato a servizi esterni, salvo una ricerca web che " +
                    "l'utente abbia esplicitamente abilitato.\n\n",
            )
            builder.append(
                "Regole:\n" +
                    "- Rispondi in italiano, in modo chiaro, diretto e professionale.\n" +
                    "- Non inventare fatti, citazioni, fonti o funzionalità. Se non sai qualcosa, dillo.\n" +
                    "- Non affermare di aver eseguito azioni che non puoi eseguire.\n" +
                    "- Per la matematica mostra i passaggi e verifica il risultato.\n" +
                    "- Usa Markdown leggibile; il codice va in blocchi recintati con il linguaggio.\n" +
                    "- Inizia con la risposta diretta in una frase, poi aggiungi i dettagli in punti brevi.\n" +
                    "- Non ripetere la domanda e non ripetere la stessa frase: se un concetto è già spiegato, vai avanti.\n" +
                    "- Per una domanda semplice bastano 3-6 frasi; per una domanda tecnica usa sezioni con titoli.\n" +
                    "- Se la domanda è ambigua, dichiara l'interpretazione che scegli e rispondi su quella.\n" +
                    "- Prima di rispondere controlla concordanze, accenti, doppie e punteggiatura; non inventare parole.\n" +
                    "- Se un dato non è certo, scrivi «non lo so» o «probabilmente» invece di presentarlo come fatto.\n" +
                    "- Non citare fonti che non compaiono nei risultati di ricerca o nei passaggi recuperati.\n",
            )
        } else {
            builder.append(
                "You are AI, the personal assistant of ITAGPT. Inference runs entirely on the user's " +
                    "device: no data leaves it unless the user explicitly enabled web search.\n\n",
            )
            builder.append(
                "Rules:\n" +
                    "- Answer in $languageName, clearly, directly and professionally.\n" +
                    "- Never invent facts, citations, sources or capabilities. If you do not know, say so.\n" +
                    "- Never claim to have performed actions you cannot perform.\n" +
                    "- For mathematics, show the steps and verify the result.\n" +
                    "- Use readable Markdown; code goes in fenced blocks with the language.\n" +
                    "- Start with the direct answer in one sentence, then add details in short bullets.\n" +
                    "- Do not restate the question and do not repeat the same sentence.\n" +
                    "- A simple question needs 3-6 sentences; a technical one uses sections with headings.\n" +
                    "- If the question is ambiguous, state the interpretation you chose and answer that.\n" +
                    "- Before answering, check agreement, accents and punctuation; do not invent words.\n" +
                    "- If a fact is uncertain, say so instead of presenting it as certain.\n" +
                    "- Never cite a source that is not in the search results or retrieved passages.\n",
            )
        }

        if (memory.isNotBlank()) {
            builder.append(if (italian) "\nMemoria persistente sull'utente (fornita dall'app):\n" else "\nPersistent memory about the user (provided by the app):\n")
            builder.append(memory).append('\n')
        }

        if (!documentContext.isNullOrBlank()) {
            builder.append(
                if (italian) {
                    "\nDocumento allegato (PDF, DOCX, XLSX, CSV o testo). Usa esclusivamente questo testo " +
                        "per le domande sul documento e, quando sono presenti, cita le pagine con [Pagina N]. " +
                        "Se l'informazione non è nel testo, dichiaralo.\n" +
                        "<documento>\n$documentContext\n</documento>\n"
                } else {
                    "\nAttached document (PDF, DOCX, XLSX, CSV or text). Use only this text for questions " +
                        "about the document and cite pages as [Page N] when present. If the information " +
                        "is not in the text, say so.\n" +
                        "<document>\n$documentContext\n</document>\n"
                },
            )
        }

        if (ocrContext.isNotBlank()) {
            builder.append(
                if (italian) {
                    "\nTesto riconosciuto localmente con OCR dagli allegati. Può contenere errori di " +
                        "riconoscimento: usalo per rispondere e segnala i passaggi incerti.\n" +
                        "<ocr>\n${ocrContext.take(8_000)}\n</ocr>\n"
                } else {
                    "\nText recognised on the device by OCR from the attachments. It may contain " +
                        "recognition errors: use it to answer and flag uncertain parts.\n" +
                        "<ocr>\n${ocrContext.take(8_000)}\n</ocr>\n"
                },
            )
        }

        if (retrievedContext.isNotBlank()) {
            builder.append(
                if (italian) {
                    "\nPassaggi pertinenti recuperati sul dispositivo (possono essere incompleti). Usali solo se rispondono alla domanda e cita la fonte tra parentesi quadre con l'etichetta indicata; se non sono pertinenti, ignorali:\n"
                } else {
                    "\nRelevant passages retrieved on the device (possibly incomplete). Use them only if they answer the question and cite the bracketed label; ignore them otherwise:\n"
                },
            )
            builder.append(retrievedContext.take(6_000)).append('\n')
        }

        if (verifiedCalculations.isNotBlank()) {
            builder.append('\n').append(verifiedCalculations).append('\n')
        }

        if (sources.isNotEmpty()) {
            builder.append(
                if (italian) "\nRisultati di ricerca web (numerati). Cita con [n] accanto all'affermazione e segnala quando le fonti sono discordanti o poco affidabili:\n"
                else "\nWeb search results (numbered). Cite with [n] next to the claim and flag conflicting or weak sources:\n",
            )
            sources.forEachIndexed { index, source ->
                builder.append("[${index + 1}] ${source.title} — ${source.url}\n")
                if (source.snippet.isNotBlank()) builder.append("    ${source.snippet.take(400)}\n")
            }
        }

        if (tutor.isNotBlank()) {
            builder.append('\n').append(tutor).append('\n')
        }

        if (modelStyle.isNotBlank()) {
            builder.append('\n').append(modelStyle).append('\n')
        }

        if (hasImage) {
            if (visionAvailable) {
                builder.append(
                    if (italian) "\nL'utente ha allegato un'immagine. Descrivi solo ciò che è effettivamente visibile.\n"
                    else "\nThe user attached an image. Describe only what is actually visible.\n",
                )
            } else if (ocrContext.isNotBlank()) {
                builder.append(
                    if (italian) {
                        "\nL'immagine è stata letta con OCR locale: rispondi usando il testo in <ocr>, " +
                            "senza dire che non puoi vedere le immagini.\n"
                    } else {
                        "\nThe image was read with local OCR: answer from the text in <ocr>; do not say " +
                            "that you cannot see images.\n"
                    },
                )
            } else {
                builder.append(
                    if (italian) "\nL'utente ha allegato un'immagine ma il modello attivo non è multimodale e l'OCR non ha trovato testo: spiega che serve un modello con supporto immagini.\n"
                    else "\nThe user attached an image but the active model is not multimodal and OCR found no text: explain that a vision model is required.\n",
                )
            }
        }

        return builder.toString().trim()
    }

    fun codeSystemPrompt(language: String, projectSummary: String, fileTree: String, activeFile: String?): String {
        val italian = language.startsWith("it")
        return buildString {
            if (italian) {
                append(
                    "Sei ITAGPT Code, l'assistente di sviluppo locale di ITAGPT. Lavori su un progetto " +
                        "Android con accesso limitato alla cartella autorizzata dall'utente.\n\n" +
                        "Regole:\n" +
                        "- Non affermare di aver modificato file: proponi modifiche e attendi l'approvazione.\n" +
                        "- Non inventare API, librerie o risultati di compilazione.\n" +
                        "- Se un linguaggio non ha un compilatore sul dispositivo, dillo chiaramente.\n" +
                        "- Spiega gli errori partendo dal messaggio e dal contesto reale del file.\n\n",
                )
                append("Per proporre una modifica usa ESATTAMENTE questo formato:\n")
            } else {
                append(
                    "You are ITAGPT Code, the local development assistant of ITAGPT. You work on an " +
                        "Android project with access limited to the folder the user authorised.\n\n" +
                        "Rules:\n" +
                        "- Never claim to have modified files: propose changes and wait for approval.\n" +
                        "- Never invent APIs, libraries or build results.\n" +
                        "- If a language has no compiler on the device, say so explicitly.\n" +
                        "- Explain errors starting from the real message and file context.\n\n",
                )
                append("To propose a change use EXACTLY this format:\n")
            }
            append(
                "<<<<<<< FILE: relative/path.ext\n" +
                    "full new file content\n" +
                    ">>>>>>> END\n\n",
            )
            if (italian) {
                append("Per eliminare un file: `<<<<<<< DELETE: relative/path.ext` seguito da `>>>>>>> END`.\n")
                append("Un blocco per file. Non includere altri marcatori nel testo.\n\n")
            } else {
                append("To delete a file: `<<<<<<< DELETE: relative/path.ext` followed by `>>>>>>> END`.\n")
                append("One block per file. Do not use these markers anywhere else.\n\n")
            }

            if (projectSummary.isNotBlank()) {
                append(if (italian) "Memoria del progetto:\n" else "Project memory:\n")
                append(projectSummary).append("\n\n")
            }
            if (fileTree.isNotBlank()) {
                append(if (italian) "Struttura del progetto:\n" else "Project tree:\n")
                append(fileTree.take(3000)).append("\n\n")
            }
            if (!activeFile.isNullOrBlank()) {
                append(if (italian) "File attualmente aperto nell'editor:\n" else "File currently open in the editor:\n")
                append(activeFile.take(12000)).append('\n')
            }
        }
    }

    /** Follow-up used to generate a short conversation title without a second model pass. */
    fun titlePrompt(firstUserMessage: String): String =
        "Riassumi in massimo 6 parole, senza punteggiatura finale, l'argomento di questa richiesta: " +
            firstUserMessage.take(400)
}
