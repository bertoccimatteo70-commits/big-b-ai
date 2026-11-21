package com.bigbai.code

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectContextBuilderTest {

    private val files = linkedMapOf(
        "aaa-unrelated.txt" to "Lorem ipsum dolor sit amet",
        "src/login.kt" to "fun login(user: String) { authenticate(user) }",
        "src/auth.kt" to "fun authenticate(user: String) { /* verifica password */ }",
        "index.html" to "<html><body>home</body></html>",
        "README.md" to "Documentazione del progetto",
    )

    @Test
    fun `the file named by the request is included before unrelated files`() {
        val context = ProjectContextBuilder.build(files, "Correggi il login che non funziona", 4_000)
        assertTrue(context.contains("src/login.kt"))
        assertTrue(context.indexOf("src/login.kt") < context.indexOf("aaa-unrelated.txt"))
    }

    @Test
    fun `imported files receive a relevance boost`() {
        val withImport = files + ("main.js" to "import { login } from './login.kt'\nconsole.log('avvio')")
        val context = ProjectContextBuilder.build(withImport, "Sistema il punto di ingresso", 4_000)
        assertTrue(context.contains("main.js"))
        assertTrue(context.contains("src/login.kt") || context.contains("login"))
    }

    @Test
    fun `the context never exceeds the character budget`() {
        val big = (1..50).associate { "src/file$it.kt" to "x".repeat(5_000) }
        val context = ProjectContextBuilder.build(big, "correggi tutto", maxChars = 2_000, perFileChars = 900)
        assertTrue(context.length <= 2_000)
    }

    @Test
    fun `no request still yields a useful structural excerpt`() {
        val context = ProjectContextBuilder.build(files, "", 3_000)
        assertTrue(context.contains("index.html"))
        assertTrue(context.contains("src/login.kt") || context.contains("README.md"))
    }

    @Test
    fun `tokenizer removes stop words and duplicates`() {
        val tokens = ProjectContextBuilder.tokens("Correggi il file login, poi login di nuovo")
        assertTrue(tokens.contains("login"))
        assertFalse(tokens.contains("correggi"))
        assertFalse(tokens.contains("il"))
        assertFalse(tokens.contains("file"))
        assertTrue(tokens.count { it == "login" } == 1)
    }
}
