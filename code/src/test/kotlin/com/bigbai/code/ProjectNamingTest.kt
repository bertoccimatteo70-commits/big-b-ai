package com.bigbai.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ProjectNamingTest {

    @Test
    fun `folder name is deterministic and filesystem safe`() {
        val name = ProjectNaming.folderName(1_700_000_000_000L, TimeZone.getTimeZone("UTC"))
        assertEquals("progetto-20231114-221320", name)
        assertTrue(name.none { it.isWhitespace() || it == ':' || it == '/' })
    }

    @Test
    fun `starter html is a complete document`() {
        val html = ProjectNaming.starterHtml("Il mio progetto")
        assertTrue(html.startsWith("<!doctype html>"))
        assertTrue(html.contains("<title>Il mio progetto</title>"))
        assertTrue(html.contains(ProjectNaming.ENTRY_FILE))
        assertTrue(html.trimEnd().endsWith("</html>"))
    }

    @Test
    fun `starter html escapes markup in the title`() {
        val html = ProjectNaming.starterHtml("<script>alert(1)</script>")
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(!html.contains("<script>alert(1)</script>"))
    }

    @Test
    fun `blank title falls back to a readable name`() {
        assertTrue(ProjectNaming.starterHtml("   ").contains("<title>Nuovo progetto</title>"))
    }
}
