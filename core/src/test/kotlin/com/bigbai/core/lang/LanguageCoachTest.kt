package com.bigbai.core.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageCoachTest {

    @Test
    fun everyTutorLanguageHasAGlossaryForEveryLevel() {
        TutorLevel.entries.forEach { level ->
            LanguageCoach.languages.forEach { language ->
                assertTrue(
                    "${language.code}/${level.name} glossary is empty",
                    LanguageCoach.glossary(language.code, level).size >= 18,
                )
            }
        }
    }

    @Test
    fun advancedLevelContainsBaseAndExtraEntries() {
        val base = LanguageCoach.glossary("es", TutorLevel.BASE).size
        val intermediate = LanguageCoach.glossary("es", TutorLevel.INTERMEDIATE).size
        assertTrue(intermediate > base)
    }

    @Test
    fun promptSectionDeclaresTheTargetLanguageAndFormat() {
        val prompt = LanguageCoach.promptSection("fr", TutorLevel.INTERMEDIATE, listOf("Lingua:FR — bonjour = ciao"))
        assertTrue(prompt.contains("Francese"))
        assertTrue(prompt.contains("Lingua:FR"))
        assertTrue(prompt.contains("bonjour"))
        assertTrue(prompt.contains("italiano"))
    }

    @Test
    fun parseLexiconKeepsOnlyTheTargetLanguageAndDeduplicates() {
        val reply = """
            Ecco una parola nuova.
            Lingua:ES — gracias = grazie (esempio: gracias por tu ayuda)
            Lingua:FR — merci = grazie (esempio: merci beaucoup)
            Lingua:ES — gracias = grazie (esempio: duplicato)
            Lingua:ES — por favor = per favore (esempio: por favor, siéntate)
        """.trimIndent()

        val entries = LanguageCoach.parseLexicon("es", reply)
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.startsWith("Lingua:ES") })
    }

    @Test
    fun parseLexiconAcceptsBulletPrefix() {
        val entries = LanguageCoach.parseLexicon(
            "de",
            "- Lingua:DE — danke = grazie (esempio: danke für deine Hilfe)",
        )
        assertEquals(1, entries.size)
        assertTrue(entries.first().contains("danke"))
    }

    @Test
    fun lessonPromptUsesTheSelectedLanguage() {
        val prompt = LanguageCoach.lessonPrompt("pt", TutorLevel.BASE)
        assertTrue(prompt.contains("Portoghese"))
        assertTrue(prompt.contains("Lingua:PT"))
    }

    @Test
    fun languageLookupIsCaseInsensitive() {
        assertNotNull(LanguageCoach.language("EN"))
        assertEquals("Lingua:ES", LanguageCoach.categoryName("es"))
    }
}
