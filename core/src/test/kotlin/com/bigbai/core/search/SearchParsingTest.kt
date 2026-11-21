package com.bigbai.core.search

import com.bigbai.core.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchParsingTest {

    private val duckHtml = """
        <html><body>
        <div class="result results_links web-result">
          <h2 class="result__title">
            <a rel="nofollow" class="result__a"
               href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fnews%3Futm_source%3Dfeed&amp;rut=abc">
              Intelligenza &amp; lavoro: le novità
            </a>
          </h2>
          <a class="result__snippet" href="//duckduckgo.com/l/?uddg=x">
            Un <b>riassunto</b> della notizia.
          </a>
          <span class="result__timestamp">20 nov 2026</span>
        </div>
        <div class="result results_links web-result">
          <h2 class="result__title">
            <a class="result__a" href="https://second.example.org/article">Secondo risultato</a>
          </h2>
          <a class="result__snippet">Altro testo.</a>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `duckduckgo results are parsed with title, snippet, site and date`() {
        val results = SearchParsing.parseDuckDuckGo(duckHtml, 10)
        assertEquals(2, results.size)
        val first = results.first()
        assertEquals("https://example.com/news?utm_source=feed", first.url)
        assertEquals("Intelligenza & lavoro: le novità", first.title)
        assertTrue(first.snippet.contains("riassunto"))
        assertEquals("example.com", first.site)
        assertEquals("20 nov 2026", first.publishedAt)
        assertEquals("second.example.org", results[1].site)
    }

    @Test
    fun `duplicate pages are removed by canonical url`() {
        val sources = listOf(
            Source("A", "https://www.example.com/news/?utm_source=x"),
            Source("B", "https://example.com/news"),
            Source("C", "https://other.org/page"),
        )
        val unique = SearchParsing.dedupe(sources)
        assertEquals(2, unique.size)
        assertEquals("A", unique[0].title)
        assertEquals("C", unique[1].title)
    }

    @Test
    fun `non http links are rejected`() {
        assertFalse(SearchParsing.isHttpUrl("javascript:alert(1)"))
        assertFalse(SearchParsing.isHttpUrl("file:///etc/passwd"))
        assertFalse(SearchParsing.isHttpUrl("http://192.168.1.1/admin"))
        assertFalse(SearchParsing.isHttpUrl("http://127.0.0.1:8080"))
        assertTrue(SearchParsing.isHttpUrl("https://example.com"))
    }

    @Test
    fun `duckduckgo redirect is unwrapped`() {
        val target = SearchParsing.unwrapDuckDuckGo(
            "//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%3Fb%3D1&amp;rut=z",
        )
        assertEquals("https://example.com/a?b=1", target)
    }

    @Test
    fun `page metadata is read from real tags`() {
        val html = """
            <html><head>
            <title>  Titolo   della pagina </title>
            <meta name="description" content="Descrizione &amp; dettagli">
            <meta property="og:site_name" content="Test Site">
            <meta property="article:published_time" content="2026-11-20T10:00:00Z">
            <meta property="article:modified_time" content="2026-11-20T12:00:00Z">
            </head><body></body></html>
        """.trimIndent()
        val meta = SearchParsing.parsePageMeta(html)
        assertEquals("Titolo della pagina", meta.title)
        assertEquals("Descrizione & dettagli", meta.description)
        assertEquals("Test Site", meta.site)
        assertEquals("2026-11-20T10:00:00Z", meta.publishedAt)
        assertEquals("2026-11-20T12:00:00Z", meta.updatedAt)
    }

    @Test
    fun `google news rss keeps real title, source and date`() {
        val xml = """
            <rss><channel>
            <item>
              <title>Corso intelligenza artificiale per avvocati - Cassa Forense News</title>
              <link>https://news.google.com/rss/articles/ABC?oc=5</link>
              <pubDate>Fri, 20 Nov 2026 10:45:44 GMT</pubDate>
              <source url="https://www.cassaforese.it">Cassa Forense News</source>
            </item>
            <item>
              <title>Seconda notizia</title>
              <link>https://news.google.com/rss/articles/DEF?oc=5</link>
            </item>
            </channel></rss>
        """.trimIndent()
        val results = SearchParsing.parseGoogleNews(xml, 5)
        assertEquals(2, results.size)
        assertEquals("Corso intelligenza artificiale per avvocati", results[0].title)
        assertEquals("Cassa Forense News", results[0].site)
        assertEquals("Fri, 20 Nov 2026 10:45:44 GMT", results[0].publishedAt)
        assertEquals("Google News", results[0].provider)
        assertEquals("Seconda notizia", results[1].title)
    }

    @Test
    fun `news detection follows the question and the time window`() {
        assertTrue(SearchParsing.wantsNews("Cerca le ultime notizie sull'IA", "any"))
        assertTrue(SearchParsing.wantsNews("documentazione React", "week"))
        assertFalse(SearchParsing.wantsNews("spiega la fotosintesi", "any"))
    }

    @Test
    fun `missing metadata is null, never invented`() {
        val meta = SearchParsing.parsePageMeta("<html><body>solo testo</body></html>")
        assertNull(meta.title)
        assertNull(meta.description)
        assertNull(meta.site)
        assertNull(meta.publishedAt)
        assertNull(meta.updatedAt)
    }
}
