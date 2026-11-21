package com.bigbai.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeAgentTest {

    @Test
    fun `write file block is parsed with its full content`() {
        val response = """
            Creo la pagina.
            <tool name="write_file" path="index.html">
            <!doctype html>
            <h1>Ciao</h1>
            </tool>
        """.trimIndent()
        val actions = CodeAgent.parse(response)
        assertEquals(1, actions.size)
        val write = actions.single() as AgentAction.WriteFile
        assertEquals("index.html", write.path)
        assertTrue(write.content.contains("<h1>Ciao</h1>"))
    }

    @Test
    fun `multiple tools in one answer are parsed in order`() {
        val response = """
            <tool name="read_file" path="app.js"/>
            <tool name="write_file" path="src/app.js">
            console.log("ok")
            </tool>
            <tool name="finish">Fatto</tool>
        """.trimIndent()
        val actions = CodeAgent.parse(response)
        assertEquals(3, actions.size)
        assertEquals(AgentAction.ReadFile("app.js"), actions[0])
        assertEquals("src/app.js", (actions[1] as AgentAction.WriteFile).path)
        assertEquals(AgentAction.Finish("Fatto"), actions[2])
    }

    @Test
    fun `legacy patch blocks still work`() {
        val response = """
            <<<<<<< FILE: style.css
            body { color: black; }
            >>>>>>> END
        """.trimIndent()
        val write = CodeAgent.parse(response).single() as AgentAction.WriteFile
        assertEquals("style.css", write.path)
        assertTrue(write.content.contains("body"))
    }

    @Test
    fun `path traversal is rejected`() {
        val actions = CodeAgent.parse(
            """<tool name="write_file" path="../segreto.txt">x</tool>""",
        )
        assertTrue(actions.single() is AgentAction.Invalid)
        assertEquals(null, CodeAgent.sanitizePath("../segreto.txt"))
        assertEquals(null, CodeAgent.sanitizePath(".git/config"))
        assertEquals(null, CodeAgent.sanitizePath("/etc/passwd"))
        assertEquals("src/app.js", CodeAgent.sanitizePath("src/app.js"))
    }

    @Test
    fun `unclosed tool block never becomes a write`() {
        val actions = CodeAgent.parse("""<tool name="write_file" path="index.html">metà""")
        assertTrue(actions.single() is AgentAction.Invalid)
    }

    @Test
    fun `markdown fence around file content is removed`() {
        val response = """
            <tool name="write_file" path="main.py">
            ```python
            print("ciao")
            ```
            </tool>
        """.trimIndent()
        val write = CodeAgent.parse(response).single() as AgentAction.WriteFile
        assertEquals("print(\"ciao\")", write.content)
    }

    @Test
    fun `delete is the only destructive action`() {
        val delete = CodeAgent.parse("""<tool name="delete_file" path="old.txt"/>""").single()
        val write = CodeAgent.parse("""<tool name="write_file" path="new.txt">x</tool>""").single()
        assertTrue(CodeAgent.isDestructive(delete))
        assertFalse(CodeAgent.isDestructive(write))
        assertTrue(delete is AgentAction.DeleteFile)
    }

    @Test
    fun `actions per step are capped`() {
        val response = (1..20).joinToString("\n") {
            """<tool name="write_file" path="f$it.txt">$it</tool>"""
        }
        assertEquals(CodeAgent.MAX_ACTIONS_PER_STEP, CodeAgent.parse(response).size)
    }

    @Test
    fun `oversized file is refused`() {
        val big = "x".repeat(CodeAgent.MAX_FILE_BYTES + 1)
        val actions = CodeAgent.parse("""<tool name="write_file" path="big.txt">$big</tool>""")
        assertTrue(actions.single() is AgentAction.Invalid)
    }

    @Test
    fun `plain prose is not treated as a tool call`() {
        assertTrue(CodeAgent.parse("Certo, posso aiutarti a creare il progetto.").isEmpty())
    }

    @Test
    fun `the step budget allows a real multi-file task but stays bounded`() {
        assertTrue(CodeAgent.MAX_STEPS >= 10)
        assertTrue(CodeAgent.MAX_STEPS <= 20)
        assertTrue(CodeAgent.MAX_FILES_PER_TASK <= 50)
    }
}
