package com.bigbai.core.lang

/**
 * Italian-first language tutoring.
 *
 * The app does not retrain model weights on the device: that is not feasible for
 * multi-gigabyte GGUF models on a phone and would be dishonest to claim. Instead
 * the tutor works the way in-context learning actually works:
 *
 *  1. a curated Italian → target glossary is injected into the system prompt;
 *  2. the model is instructed to emit one machine-readable line per new word;
 *  3. the app stores those lines in the encrypted database, in a dedicated
 *     `Lingua:<CODE>` memory category;
 *  4. every later prompt re-injects the accumulated personal lexicon.
 *
 * The result is a model that keeps "learning" the languages the user practises,
 * starting from Italian, without touching its weights and without a network.
 */
enum class TutorLevel(val label: String, val instructions: String) {
    BASE("Base", "Usa frasi brevi, il presente e un vocabolario di uso quotidiano."),
    INTERMEDIATE("Intermedio", "Usa frasi complete, passa al passato quando serve e introduci sinonimi."),
    ADVANCED("Avanzato", "Conversa quasi interamente nella lingua di studio, con correzioni mirate."),
}

data class TutorLanguage(
    val code: String,
    val italianName: String,
    val nativeName: String,
)

data class LexiconEntry(
    val italian: String,
    val target: String,
    val pronunciation: String,
    val example: String,
)

object LanguageCoach {

    val languages: List<TutorLanguage> = listOf(
        TutorLanguage("en", "Inglese", "English"),
        TutorLanguage("es", "Spagnolo", "Español"),
        TutorLanguage("fr", "Francese", "Français"),
        TutorLanguage("de", "Tedesco", "Deutsch"),
        TutorLanguage("pt", "Portoghese", "Português"),
    )

    fun language(code: String): TutorLanguage? = languages.firstOrNull { it.code == code.lowercase() }

    fun categoryName(code: String): String = "Lingua:${code.uppercase()}"

    fun label(code: String): String {
        val language = language(code) ?: return code.uppercase()
        return "${language.italianName} (${language.nativeName})"
    }

    /** Curated starting glossary. It is a seed, not a dictionary: the personal
     *  lexicon built from real conversations is what makes it grow. */
    fun glossary(code: String, level: TutorLevel): List<LexiconEntry> {
        val base = BASE_GLOSSARY[code.lowercase()] ?: return emptyList()
        if (level == TutorLevel.BASE) return base
        val extra = EXTRA_GLOSSARY[code.lowercase()].orEmpty()
        return base + extra
    }

    fun promptSection(code: String, level: TutorLevel, learned: List<String>): String {
        val language = language(code) ?: return ""
        val builder = StringBuilder()
        builder.append("MODALITÀ TUTOR DI LINGUA (attiva)\n")
        builder.append("Lingua di studio: ${language.italianName} (${language.nativeName}).\n")
        builder.append("Livello: ${level.label}. ${level.instructions}\n")
        builder.append(
            "Regole:\n" +
                "- La conversazione e le spiegazioni restano in italiano; la lingua di studio compare " +
                "sempre con la traduzione italiana tra parentesi.\n" +
                "- Struttura tipica: una risposta breve, 3-5 parole o frasi nuove, un mini-esercizio " +
                "con la soluzione.\n" +
                "- Correggi gli errori dell'utente mostrando la forma corretta, senza umiliare.\n" +
                "- Non inventare traduzioni, pronunce o regole: se non sei sicuro, dillo.\n" +
                "- Non affermare di aver modificato i tuoi pesi o di aver studiato offline: stai " +
                "insegnando usando il lessico fornito dall'app.\n",
        )
        builder.append(
            "- Per ogni parola o frase nuova aggiungi UNA riga esattamente in questo formato:\n" +
                "Lingua:${language.code.uppercase()} — <testo in ${language.nativeName}> = " +
                "<traduzione italiana> (esempio: <frase breve>)\n",
        )
        if (learned.isNotEmpty()) {
            builder.append("\nLessico personale già appreso (riutilizzalo, non rispiegarlo da zero):\n")
            learned.take(16).forEach { builder.append("- ").append(it).append('\n') }
        }
        val seed = glossary(language.code, level).take(14)
        if (seed.isNotEmpty()) {
            builder.append("\nGlossario di partenza fornito dall'app (italiano → ${language.nativeName}):\n")
            seed.forEach { entry ->
                builder.append("IT: ${entry.italian} → ${language.nativeName}: ${entry.target}")
                if (entry.pronunciation.isNotBlank()) builder.append(" [").append(entry.pronunciation).append(']')
                builder.append('\n')
            }
        }
        return builder.toString().trim()
    }

    /** A ready-to-send first lesson, used by the chat quick action. */
    fun lessonPrompt(code: String, level: TutorLevel): String {
        val language = language(code) ?: return ""
        return "Iniziamo una lezione di ${language.italianName} (${language.nativeName}) livello " +
            "${level.label.lowercase()}. Insegnami 5 parole utili con pronuncia, traduzione e una frase " +
            "di esempio per ciascuna; poi fammi un esercizio e attendi la mia risposta. " +
            "Ricorda il formato delle righe «Lingua:${language.code.uppercase()}» per il lessico."
    }

    private val lexiconPattern = Regex(
        """(?m)^[\s\-•]*Lingua:(EN|ES|FR|DE|PT)\s*[—–\-:]\s*(.{3,220})$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Extracts the vocabulary lines the model emitted, normalised and de-duplicated.
     * Only the target language is accepted so a stray example cannot pollute the
     * wrong personal lexicon.
     */
    fun parseLexicon(code: String, reply: String, limit: Int = 8): List<String> {
        val target = code.uppercase()
        val entries = mutableListOf<String>()
        // Deduplicate by the word/phrase itself (before "="), not by the whole
        // line: the model may repeat a word with a different example, and the
        // lexicon must not grow with the same entry over and over.
        val seen = HashSet<String>()
        lexiconPattern.findAll(reply).forEach { match ->
            val language = match.groupValues[1].uppercase()
            if (language != target) return@forEach
            val body = match.groupValues[2].trim().trimEnd('.', ',', ';')
            if (body.length < 3) return@forEach
            val headword = body.substringBefore('=').trim().lowercase()
            if (!seen.add(headword)) return@forEach
            entries.add("Lingua:$language — $body")
            if (entries.size >= limit) return@forEach
        }
        return entries
    }

    // ---------------------------------------------------------------------
    // Curated glossaries (Italian → target). Kept intentionally short and
    // hand-checked; the personal lexicon grows from real use.
    // ---------------------------------------------------------------------

    private val BASE_GLOSSARY: Map<String, List<LexiconEntry>> = mapOf(
        "en" to listOf(
            LexiconEntry("ciao", "hello", "hel-oh", "Hello, how are you?"),
            LexiconEntry("buongiorno", "good morning", "gud mor-ning", "Good morning, Anna."),
            LexiconEntry("buonasera", "good evening", "gud iiv-ning", "Good evening, everyone."),
            LexiconEntry("grazie", "thank you", "thank yoo", "Thank you for your help."),
            LexiconEntry("prego", "you're welcome", "yor wel-kam", "You're welcome!"),
            LexiconEntry("per favore", "please", "pleez", "Please sit down."),
            LexiconEntry("scusa", "sorry", "sor-ee", "Sorry, I am late."),
            LexiconEntry("sì", "yes", "yes", "Yes, I agree."),
            LexiconEntry("no", "no", "noh", "No, thank you."),
            LexiconEntry("come stai?", "how are you?", "how ar yoo", "How are you today?"),
            LexiconEntry("bene, grazie", "I'm fine, thanks", "aim fain thanks", "I'm fine, thanks."),
            LexiconEntry("mi chiamo…", "my name is…", "mai neim iz", "My name is Marco."),
            LexiconEntry("piacere", "nice to meet you", "nais tu meet yoo", "Nice to meet you."),
            LexiconEntry("arrivederci", "goodbye", "gud-bai", "Goodbye, see you soon."),
            LexiconEntry("a domani", "see you tomorrow", "see yoo tu-mor-oh", "See you tomorrow!"),
            LexiconEntry("quanto costa?", "how much does it cost?", "how mach daz it kost", "How much does it cost?"),
            LexiconEntry("non capisco", "I don't understand", "ai dont an-der-stand", "Sorry, I don't understand."),
            LexiconEntry("puoi ripetere?", "can you repeat?", "kan yoo ri-peet", "Can you repeat that, please?"),
        ),
        "es" to listOf(
            LexiconEntry("ciao", "hola", "oh-lah", "¡Hola, cómo estás?"),
            LexiconEntry("buongiorno", "buenos días", "bweh-nos dee-as", "Buenos días, Ana."),
            LexiconEntry("buonasera", "buenas noches", "bweh-nas no-ches", "Buenas noches, hasta mañana."),
            LexiconEntry("grazie", "gracias", "gra-syas", "Gracias por tu ayuda."),
            LexiconEntry("prego", "de nada", "deh nah-dah", "¡De nada!"),
            LexiconEntry("per favore", "por favor", "por fah-vor", "Por favor, siéntate."),
            LexiconEntry("scusa", "perdón", "per-don", "Perdón, llego tarde."),
            LexiconEntry("sì", "sí", "see", "Sí, estoy de acuerdo."),
            LexiconEntry("no", "no", "noh", "No, gracias."),
            LexiconEntry("come stai?", "¿cómo estás?", "ko-mo es-tas", "¿Cómo estás hoy?"),
            LexiconEntry("bene, grazie", "bien, gracias", "byen gra-syas", "Bien, gracias."),
            LexiconEntry("mi chiamo…", "me llamo…", "meh ya-mo", "Me llamo Marco."),
            LexiconEntry("piacere", "mucho gusto", "moo-cho goos-toh", "Mucho gusto."),
            LexiconEntry("arrivederci", "adiós", "ah-dyos", "Adiós, hasta pronto."),
            LexiconEntry("a domani", "hasta mañana", "as-tah ma-nya-na", "¡Hasta mañana!"),
            LexiconEntry("quanto costa?", "¿cuánto cuesta?", "kwan-to kwes-ta", "¿Cuánto cuesta?"),
            LexiconEntry("non capisco", "no entiendo", "no en-tyen-do", "Perdón, no entiendo."),
            LexiconEntry("puoi ripetere?", "¿puedes repetir?", "pwe-des re-pe-tir", "¿Puedes repetir, por favor?"),
        ),
        "fr" to listOf(
            LexiconEntry("ciao", "salut", "sa-lu", "Salut, ça va ?"),
            LexiconEntry("buongiorno", "bonjour", "bon-zhoor", "Bonjour, Anna."),
            LexiconEntry("buonasera", "bonsoir", "bon-swahr", "Bonsoir à tous."),
            LexiconEntry("grazie", "merci", "mer-see", "Merci pour ton aide."),
            LexiconEntry("prego", "de rien", "duh ryan", "De rien !"),
            LexiconEntry("per favore", "s'il vous plaît", "seel voo pleh", "Asseyez-vous, s'il vous plaît."),
            LexiconEntry("scusa", "pardon", "par-don", "Pardon, je suis en retard."),
            LexiconEntry("sì", "oui", "wee", "Oui, je suis d'accord."),
            LexiconEntry("no", "non", "non", "Non, merci."),
            LexiconEntry("come stai?", "comment vas-tu ?", "ko-man va tu", "Comment vas-tu aujourd'hui ?"),
            LexiconEntry("bene, grazie", "bien, merci", "byan mer-see", "Bien, merci."),
            LexiconEntry("mi chiamo…", "je m'appelle…", "zhuh ma-pel", "Je m'appelle Marco."),
            LexiconEntry("piacere", "enchanté", "an-shan-tay", "Enchanté !"),
            LexiconEntry("arrivederci", "au revoir", "oh ruh-vwahr", "Au revoir, à bientôt."),
            LexiconEntry("a domani", "à demain", "ah duh-man", "À demain !"),
            LexiconEntry("quanto costa?", "combien ça coûte ?", "kom-byan sa koot", "Combien ça coûte ?"),
            LexiconEntry("non capisco", "je ne comprends pas", "zhuh nuh kom-pran pa", "Je ne comprends pas."),
            LexiconEntry("puoi ripetere?", "peux-tu répéter ?", "puh tu ray-pay-tay", "Peux-tu répéter, s'il te plaît ?"),
        ),
        "de" to listOf(
            LexiconEntry("ciao", "hallo", "ha-loh", "Hallo, wie geht es dir?"),
            LexiconEntry("buongiorno", "guten Morgen", "goo-ten mor-gen", "Guten Morgen, Anna."),
            LexiconEntry("buonasera", "guten Abend", "goo-ten ah-bent", "Guten Abend zusammen."),
            LexiconEntry("grazie", "danke", "dan-kuh", "Danke für deine Hilfe."),
            LexiconEntry("prego", "gern geschehen", "gern guh-shay-en", "Gern geschehen!"),
            LexiconEntry("per favore", "bitte", "bit-tuh", "Bitte setz dich."),
            LexiconEntry("scusa", "Entschuldigung", "ent-shool-dee-goong", "Entschuldigung, ich bin spät."),
            LexiconEntry("sì", "ja", "yah", "Ja, ich stimme zu."),
            LexiconEntry("no", "nein", "nine", "Nein, danke."),
            LexiconEntry("come stai?", "wie geht es dir?", "vee gayt es deer", "Wie geht es dir heute?"),
            LexiconEntry("bene, grazie", "gut, danke", "goot dan-kuh", "Gut, danke."),
            LexiconEntry("mi chiamo…", "ich heiße…", "ikh hai-suh", "Ich heiße Marco."),
            LexiconEntry("piacere", "freut mich", "froyt mikh", "Freut mich!"),
            LexiconEntry("arrivederci", "auf Wiedersehen", "owf vee-der-zayn", "Auf Wiedersehen!"),
            LexiconEntry("a domani", "bis morgen", "bis mor-gen", "Bis morgen!"),
            LexiconEntry("quanto costa?", "wie viel kostet das?", "vee feel kos-tet das", "Wie viel kostet das?"),
            LexiconEntry("non capisco", "ich verstehe nicht", "ikh fer-shtay-uh nikht", "Ich verstehe nicht."),
            LexiconEntry("puoi ripetere?", "kannst du das wiederholen?", "kanst doo das vee-der-hoh-len", "Kannst du das wiederholen?"),
        ),
        "pt" to listOf(
            LexiconEntry("ciao", "olá", "oh-lah", "Olá, como estás?"),
            LexiconEntry("buongiorno", "bom dia", "bom dee-ah", "Bom dia, Ana."),
            LexiconEntry("buonasera", "boa noite", "boh-ah noy-tuh", "Boa noite a todos."),
            LexiconEntry("grazie", "obrigado", "oh-bree-gah-doo", "Obrigado pela ajuda."),
            LexiconEntry("prego", "de nada", "duh nah-dah", "De nada!"),
            LexiconEntry("per favore", "por favor", "poor fah-vor", "Por favor, senta-te."),
            LexiconEntry("scusa", "desculpa", "desh-kool-pah", "Desculpa, estou atrasado."),
            LexiconEntry("sì", "sim", "sing", "Sim, concordo."),
            LexiconEntry("no", "não", "nowng", "Não, obrigado."),
            LexiconEntry("come stai?", "como estás?", "koh-moo esh-tash", "Como estás hoje?"),
            LexiconEntry("bene, grazie", "bem, obrigado", "beng oh-bree-gah-doo", "Bem, obrigado."),
            LexiconEntry("mi chiamo…", "chamo-me…", "shah-moo muh", "Chamo-me Marco."),
            LexiconEntry("piacere", "muito prazer", "mween-too prah-zer", "Muito prazer!"),
            LexiconEntry("arrivederci", "adeus", "ah-deh-oosh", "Adeus, até breve."),
            LexiconEntry("a domani", "até amanhã", "ah-teh ah-mah-nyang", "Até amanhã!"),
            LexiconEntry("quanto costa?", "quanto custa?", "kwan-too koosh-tah", "Quanto custa?"),
            LexiconEntry("non capisco", "não entendo", "nowng en-ten-doo", "Desculpa, não entendo."),
            LexiconEntry("puoi ripetere?", "podes repetir?", "poh-desh reh-peh-teer", "Podes repetir, por favor?"),
        ),
    )

    private val EXTRA_GLOSSARY: Map<String, List<LexiconEntry>> = mapOf(
        "en" to listOf(
            LexiconEntry("vorrei un caffè", "I would like a coffee", "ai wood laik uh kof-ee", "I would like a coffee, please."),
            LexiconEntry("posso pagare con la carta?", "can I pay by card?", "kan ai pay bai kard", "Can I pay by card?"),
            LexiconEntry("che ora è?", "what time is it?", "wot taim iz it", "What time is it?"),
            LexiconEntry("ho bisogno di aiuto", "I need help", "ai need help", "I need help with this."),
            LexiconEntry("dov'è la stazione?", "where is the station?", "wair iz thuh stay-shun", "Where is the station?"),
            LexiconEntry("parlo un po' di…", "I speak a little…", "ai speek uh lit-ul", "I speak a little English."),
            LexiconEntry("come si dice … ?", "how do you say … ?", "how doo yoo say", "How do you say 'grazie' in English?"),
            LexiconEntry("mi piace / non mi piace", "I like / I don't like", "ai laik / ai dont laik", "I like this book."),
        ),
        "es" to listOf(
            LexiconEntry("vorrei un caffè", "quisiera un café", "kee-syeh-rah oon kah-feh", "Quisiera un café, por favor."),
            LexiconEntry("posso pagare con la carta?", "¿puedo pagar con tarjeta?", "pweh-do pa-gar kon tar-heh-ta", "¿Puedo pagar con tarjeta?"),
            LexiconEntry("che ora è?", "¿qué hora es?", "keh oh-rah es", "¿Qué hora es?"),
            LexiconEntry("ho bisogno di aiuto", "necesito ayuda", "ne-se-see-to ah-yoo-da", "Necesito ayuda con esto."),
            LexiconEntry("dov'è la stazione?", "¿dónde está la estación?", "don-deh es-ta la es-tah-syon", "¿Dónde está la estación?"),
            LexiconEntry("parlo un po' di…", "hablo un poco de…", "ah-blo oon po-ko de", "Hablo un poco de español."),
            LexiconEntry("come si dice … ?", "¿cómo se dice … ?", "ko-mo se dee-se", "¿Cómo se dice 'gracias' en italiano?"),
            LexiconEntry("mi piace / non mi piace", "me gusta / no me gusta", "meh goos-ta / no meh goos-ta", "Me gusta este libro."),
        ),
        "fr" to listOf(
            LexiconEntry("vorrei un caffè", "je voudrais un café", "zhuh voo-dreh un ka-fay", "Je voudrais un café, s'il vous plaît."),
            LexiconEntry("posso pagare con la carta?", "puis-je payer par carte ?", "pweezh pay-yay par kart", "Puis-je payer par carte ?"),
            LexiconEntry("che ora è?", "quelle heure est-il ?", "kel ur ay-teel", "Quelle heure est-il ?"),
            LexiconEntry("ho bisogno di aiuto", "j'ai besoin d'aide", "zhay buh-zwan ded", "J'ai besoin d'aide."),
            LexiconEntry("dov'è la stazione?", "où est la gare ?", "oo ay la gar", "Où est la gare ?"),
            LexiconEntry("parlo un po' di…", "je parle un peu…", "zhuh parl un puh", "Je parle un peu français."),
            LexiconEntry("come si dice … ?", "comment dit-on … ?", "ko-man dee-ton", "Comment dit-on 'merci' en italien ?"),
            LexiconEntry("mi piace / non mi piace", "j'aime / je n'aime pas", "zhem / zhuh nem pa", "J'aime ce livre."),
        ),
        "de" to listOf(
            LexiconEntry("vorrei un caffè", "ich möchte einen Kaffee", "ikh mukh-tuh eye-nen ka-fay", "Ich möchte einen Kaffee, bitte."),
            LexiconEntry("posso pagare con la carta?", "kann ich mit Karte bezahlen?", "kan ikh mit kar-tuh buh-tsah-len", "Kann ich mit Karte bezahlen?"),
            LexiconEntry("che ora è?", "wie spät ist es?", "vee shpayt ist es", "Wie spät ist es?"),
            LexiconEntry("ho bisogno di aiuto", "ich brauche Hilfe", "ikh brow-khuh hil-fuh", "Ich brauche Hilfe."),
            LexiconEntry("dov'è la stazione?", "wo ist der Bahnhof?", "voh ist der bahn-hohf", "Wo ist der Bahnhof?"),
            LexiconEntry("parlo un po' di…", "ich spreche ein wenig…", "ikh shpre-khuh ine vay-nikh", "Ich spreche ein wenig Deutsch."),
            LexiconEntry("come si dice … ?", "wie sagt man … ?", "vee zahkt man", "Wie sagt man 'danke' auf Italienisch?"),
            LexiconEntry("mi piace / non mi piace", "ich mag / ich mag nicht", "ikh mahk / ikh mahk nikht", "Ich mag dieses Buch."),
        ),
        "pt" to listOf(
            LexiconEntry("vorrei un caffè", "queria um café", "keh-ree-ah oong kah-feh", "Queria um café, por favor."),
            LexiconEntry("posso pagare con la carta?", "posso pagar com cartão?", "poh-soo pah-gar kong kar-towng", "Posso pagar com cartão?"),
            LexiconEntry("che ora è?", "que horas são?", "keh oh-rahs sowng", "Que horas são?"),
            LexiconEntry("ho bisogno di aiuto", "preciso de ajuda", "preh-see-zoo duh ah-zhoo-dah", "Preciso de ajuda."),
            LexiconEntry("dov'è la stazione?", "onde fica a estação?", "on-duh fee-kah ah esh-tah-sowng", "Onde fica a estação?"),
            LexiconEntry("parlo un po' di…", "falo um pouco de…", "fah-loo oong poh-koo duh", "Falo um pouco de português."),
            LexiconEntry("come si dice … ?", "como se diz … ?", "koh-moo seh deez", "Como se diz 'obrigado' em italiano?"),
            LexiconEntry("mi piace / non mi piace", "gosto / não gosto", "gosh-too / nowng gosh-too", "Gosto deste livro."),
        ),
    )
}
