package com.bigbai.core.search

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.bigbai.core.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Search configuration. The default is fully automatic: ITAGPT chooses the
 * provider, the user never has to pick an endpoint or paste a key. An advanced
 * user can still force Brave or a private SearXNG instance in Settings.
 *
 * @param provider "auto", "brave" or "searxng"
 * @param timeRange "any", "day", "week", "month" or "year"
 */
data class SearchConfig(
    val provider: String = "auto",
    val searxEndpoint: String = "",
    val braveApiKey: String = "",
    val timeRange: String = "any",
)

sealed interface SearchResult {
    data class Success(
        val sources: List<Source>,
        val query: String,
        /** Human label of the provider that actually returned the results. */
        val provider: String = "",
        /** Providers that were tried and failed, for honest diagnostics. */
        val failedProviders: List<String> = emptyList(),
    ) : SearchResult

    data class Failure(
        val reason: SearchFailure,
        val failedProviders: List<String> = emptyList(),
    ) : SearchResult
}

enum class SearchFailure { OFFLINE, NOT_CONFIGURED, NETWORK_ERROR, EMPTY }

/**
 * Web search with automatic provider selection.
 *
 * The chain is: Brave (only when the user saved a key) → SearXNG (only when the
 * user saved an endpoint) → DuckDuckGo HTML → Wikipedia. The first provider that
 * returns at least one usable result wins; if it returns too few, the next one
 * is merged in. Every result is de-duplicated by canonical URL, enriched with
 * the page's own title, description and publication date when the page allows
 * it, and never invented: a failure returns a failure, not placeholder links.
 *
 * DuckDuckGo and Wikipedia need no key, so web search works on a fresh install.
 * They are general-purpose HTML/JSON endpoints, not paid APIs.
 */
class WebSearchClient(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun isOnline(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun search(
        query: String,
        config: SearchConfig = SearchConfig(),
        maxResults: Int = 6,
        enrichPages: Boolean = true,
    ): SearchResult = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isEmpty()) return@withContext SearchResult.Failure(SearchFailure.EMPTY)
        if (!isOnline()) return@withContext SearchResult.Failure(SearchFailure.OFFLINE)

        val language = detectLanguage(clean)
        val failed = mutableListOf<String>()
        val merged = mutableListOf<Source>()

        suspend fun runProvider(label: String, block: suspend () -> List<Source>) {
            runCatching { block() }
                .onSuccess { results ->
                    val before = merged.size
                    merged.addAll(results)
                    if (merged.size == before) failed.add("$label: nessun risultato")
                }
                .onFailure { error ->
                    failed.add("$label: ${error.message ?: "errore di rete"}")
                }
        }

        val requested = config.provider.lowercase()
        val braveKey = config.braveApiKey.trim()
        val searx = config.searxEndpoint.trim()

        // The user's explicit choice is respected first.
        if (requested == "brave") {
            if (braveKey.isBlank()) {
                failed.add("Brave: chiave API assente, passo al provider automatico")
            } else {
                runProvider("Brave") { searchBrave(clean, braveKey, maxResults, config.timeRange, language) }
            }
        }
        if (requested == "searxng") {
            if (searx.isBlank()) {
                failed.add("SearXNG: endpoint assente, passo al provider automatico")
            } else {
                runProvider("SearXNG") { searchSearx(clean, searx, maxResults, config.timeRange, language) }
            }
        }

        if (requested == "auto" || merged.size < maxResults) {
            if (requested == "auto" && braveKey.isNotBlank() && merged.size < maxResults) {
                runProvider("Brave") { searchBrave(clean, braveKey, maxResults, config.timeRange, language) }
            }
            if (requested == "auto" && searx.isNotBlank() && merged.size < maxResults) {
                runProvider("SearXNG") { searchSearx(clean, searx, maxResults, config.timeRange, language) }
            }
        }
        // News and "latest" questions get a dated RSS provider that needs no
        // key and works even when HTML engines rate-limit the device.
        if (merged.size < maxResults && SearchParsing.wantsNews(clean, config.timeRange)) {
            runProvider("Google News") { searchGoogleNews(clean, maxResults, language, config.timeRange) }
        }
        if (merged.size < maxResults) {
            runProvider("DuckDuckGo") { searchDuckDuckGo(clean, maxResults, language, config.timeRange) }
        }
        if (merged.size < 2) {
            runProvider("Wikipedia") { searchWikipedia(clean, maxResults, language) }
        }

        val unique = SearchParsing.dedupe(merged).take(maxResults)
        if (unique.isEmpty()) {
            return@withContext SearchResult.Failure(
                reason = if (failed.isEmpty()) SearchFailure.EMPTY else SearchFailure.NETWORK_ERROR,
                failedProviders = failed,
            )
        }
        val enriched = if (enrichPages) {
            enrichTop(unique, minOf(3, unique.size))
        } else {
            unique
        }
        SearchResult.Success(
            sources = enriched,
            query = clean,
            provider = SearchParsing.providerOf(enriched, requested, braveKey, searx),
            failedProviders = failed,
        )
    }

    /** Fetches the readable text of a page, bounded and sanitised. */
    suspend fun fetchPageText(url: String, maxChars: Int = 3000): String? = withContext(Dispatchers.IO) {
        if (!SearchParsing.isHttpUrl(url)) return@withContext null
        runCatching {
            val request = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if (contentType.isNotBlank() && !contentType.contains("text/") &&
                    !contentType.contains("xhtml") && !contentType.contains("xml")
                ) {
                    return@runCatching null
                }
                val body = response.body ?: return@runCatching null
                val bytes = body.source().readByteArray(MAX_PAGE_BYTES)
                HtmlText.extract(String(bytes, Charsets.UTF_8)).take(maxChars)
            }
        }.getOrNull()
    }

    // ---------------------------------------------------------------- providers

    private fun searchDuckDuckGo(
        query: String,
        max: Int,
        language: String,
        timeRange: String,
    ): List<Source> {
        val region = if (language == "it") "it-it" else "wt-wt"
        val freshness = when (timeRange) {
            "day" -> "&df=d"
            "week" -> "&df=w"
            "month" -> "&df=m"
            "year" -> "&df=y"
            else -> ""
        }
        val url = "https://html.duckduckgo.com/html/?q=${encode(query)}&kl=$region$freshness"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", if (language == "it") "it-IT,it;q=0.9,en;q=0.6" else "en-US,en;q=0.9")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val html = response.body?.string().orEmpty()
            SearchParsing.parseDuckDuckGo(html, max)
        }
    }

    private fun searchWikipedia(query: String, max: Int, language: String): List<Source> {
        val lang = if (language == "it") "it" else "en"
        val url = "https://$lang.wikipedia.org/w/api.php?action=query&list=search" +
            "&srsearch=${encode(query)}&srlimit=$max&srprop=snippet%7Ctimestamp&format=json&utf8=1"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            val results = root["query"]?.jsonObject?.get("search")?.jsonArray ?: JsonArray(emptyList())
            results.take(max).mapNotNull { element ->
                val obj = element.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content.orEmpty()
                if (title.isBlank()) return@mapNotNull null
                val pageUrl = "https://$lang.wikipedia.org/wiki/" +
                    URLEncoder.encode(title.replace(' ', '_'), "UTF-8")
                Source(
                    title = title,
                    url = pageUrl,
                    snippet = SearchParsing.clean(HtmlText.extract(
                        obj["snippet"]?.jsonPrimitive?.content.orEmpty(),
                    )).take(300),
                    site = "Wikipedia",
                    publishedAt = obj["timestamp"]?.jsonPrimitive?.content,
                    provider = "Wikipedia",
                )
            }
        }
    }

    /** Google News RSS: dated headlines, no key, real links. */
    private fun searchGoogleNews(
        query: String,
        max: Int,
        language: String,
        timeRange: String,
    ): List<Source> {
        val lang = if (language == "it") "it" else "en"
        val country = if (language == "it") "IT" else "US"
        val freshness = when (timeRange) {
            "day" -> " when:1d"
            "week" -> " when:7d"
            "month" -> " when:30d"
            "year" -> " when:1y"
            else -> ""
        }
        val url = "https://news.google.com/rss/search?q=${encode(query + freshness)}" +
            "&hl=$lang&gl=$country&ceid=$country:$lang"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/rss+xml,application/xml,text/xml")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            SearchParsing.parseGoogleNews(response.body?.string().orEmpty(), max)
        }
    }

    private fun searchBrave(
        query: String,
        key: String,
        max: Int,
        timeRange: String,
        language: String,
    ): List<Source> {
        val freshness = when (timeRange) {
            "day" -> "&freshness=pd"
            "week" -> "&freshness=pw"
            "month" -> "&freshness=pm"
            "year" -> "&freshness=py"
            else -> ""
        }
        val url = "https://api.search.brave.com/res/v1/web/search?q=${encode(query)}" +
            "&count=${max.coerceIn(1, 20)}&safesearch=moderate" +
            "&search_lang=${if (language == "it") "it" else "en"}$freshness"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Subscription-Token", key)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            val results = root["web"]?.jsonObject?.get("results")?.jsonArray ?: JsonArray(emptyList())
            results.take(max).mapNotNull { element ->
                val obj = element.jsonObject
                val link = obj["url"]?.jsonPrimitive?.content.orEmpty()
                if (!SearchParsing.isHttpUrl(link)) return@mapNotNull null
                val profile = obj["profile"]?.jsonObject
                Source(
                    title = obj["title"]?.jsonPrimitive?.content
                        ?.let(SearchParsing::clean)
                        ?.ifBlank { SearchParsing.siteOf(link) }
                        ?: SearchParsing.siteOf(link),
                    url = link,
                    snippet = SearchParsing.clean(
                        obj["description"]?.jsonPrimitive?.content.orEmpty(),
                    ).take(400),
                    site = profile?.get("long_name")?.jsonPrimitive?.content
                        ?: SearchParsing.siteOf(link),
                    publishedAt = obj["page_age"]?.jsonPrimitive?.content
                        ?: obj["age"]?.jsonPrimitive?.content,
                    provider = "Brave",
                )
            }
        }
    }

    private fun searchSearx(
        query: String,
        endpoint: String,
        max: Int,
        timeRange: String,
        language: String,
    ): List<Source> {
        val base = endpoint.trimEnd('/').removeSuffix("/search")
        if (!SearchParsing.isHttpUrl(base)) return emptyList()
        val time = when (timeRange) {
            "day" -> "&time_range=day"
            "week" -> "&time_range=week"
            "month" -> "&time_range=month"
            "year" -> "&time_range=year"
            else -> ""
        }
        val url = "$base/search?q=${encode(query)}&format=json&safesearch=1" +
            "&language=${if (language == "it") "it" else "en"}$time"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            val results = root["results"]?.jsonArray ?: JsonArray(emptyList())
            results.take(max).mapNotNull { element ->
                val obj = element.jsonObject
                val link = obj["url"]?.jsonPrimitive?.content.orEmpty()
                if (!SearchParsing.isHttpUrl(link)) return@mapNotNull null
                val engine = obj["engine"]?.jsonPrimitive?.content
                Source(
                    title = obj["title"]?.jsonPrimitive?.content
                        ?.let(SearchParsing::clean)
                        ?.ifBlank { SearchParsing.siteOf(link) }
                        ?: SearchParsing.siteOf(link),
                    url = link,
                    snippet = SearchParsing.clean(
                        obj["content"]?.jsonPrimitive?.content.orEmpty(),
                    ).take(400),
                    site = SearchParsing.siteOf(link),
                    publishedAt = obj["publishedDate"]?.jsonPrimitive?.content,
                    updatedAt = obj["updatedDate"]?.jsonPrimitive?.content,
                    provider = "SearXNG",
                ).let { source ->
                    if (engine.isNullOrBlank()) source else source
                }
            }
        }
    }

    // ------------------------------------------------------------- enrichment

    /**
     * Reads the first [count] result pages and replaces thin snippets with the
     * page's own description. This is best-effort: a page that blocks bots,
     * times out or is a binary file keeps the search-engine snippet.
     */
    private suspend fun enrichTop(sources: List<Source>, count: Int): List<Source> {
        if (count <= 0) return sources
        val enriched = coroutineScope {
            sources.mapIndexed { index, source ->
                if (index >= count) return@mapIndexed null
                async(Dispatchers.IO) { index to enrich(source) }
            }.mapNotNull { deferred -> deferred?.await() }.toMap()
        }
        return sources.mapIndexed { index, source -> enriched[index] ?: source }
    }

    private fun enrich(source: Source): Source? = runCatching {
        // Google News links are redirects; fetching them would replace the real
        // headline with Google's own page title. Keep the RSS metadata as-is.
        if (source.provider == "Google News") return@runCatching null
        if (!SearchParsing.isHttpUrl(source.url)) return@runCatching null
        val request = Request.Builder().url(source.url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val type = response.header("Content-Type").orEmpty().lowercase()
            if (type.isNotBlank() && !type.contains("text/html") && !type.contains("xhtml")) {
                return@runCatching null
            }
            val html = String(response.body?.source()?.readByteArray(MAX_PAGE_BYTES) ?: ByteArray(0), Charsets.UTF_8)
            if (html.isBlank()) return@runCatching null
            val meta = SearchParsing.parsePageMeta(html)
            val text = HtmlText.extract(html)
            source.copy(
                title = meta.title?.takeIf { it.length > 3 } ?: source.title,
                snippet = when {
                    !meta.description.isNullOrBlank() -> meta.description.take(400)
                    source.snippet.length >= 80 -> source.snippet
                    else -> text.take(360)
                },
                site = meta.site ?: source.site.ifBlank { SearchParsing.siteOf(source.url) },
                publishedAt = source.publishedAt ?: meta.publishedAt,
                updatedAt = source.updatedAt ?: meta.updatedAt,
            )
        }
    }.getOrNull()

    private fun detectLanguage(query: String): String {
        val italian = Regex(
            "\\b(il|lo|la|gli|le|un|una|di|che|per|con|come|dove|quando|ultime|notizie|oggi|prezzo|meteo)\\b",
            RegexOption.IGNORE_CASE,
        )
        return if (italian.containsMatchIn(query)) "it" else "en"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val MAX_PAGE_BYTES = 512L * 1024L
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0 Mobile Safari/537.36 ITAGPT/2.1 (local assistant)"
    }
}

/**
 * Pure parsing and de-duplication, separated from Android so it can be tested on
 * the JVM. Every function returns only what is actually present in the input:
 * no titles, URLs or dates are synthesised.
 */
object SearchParsing {

    private val RESULT_ANCHOR = Regex(
        """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val SNIPPET = Regex(
        """class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val TIMESTAMP = Regex(
        """class="[^"]*result__timestamp[^"]*"[^>]*>(.*?)</(?:span|div)>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val TITLE_TAG = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val META_DESCRIPTION = Regex(
        """<meta[^>]+name=["']description["'][^>]+content=["']([^"']*)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val META_DESCRIPTION_REVERSED = Regex(
        """<meta[^>]+content=["']([^"']*)["'][^>]+name=["']description["']""",
        RegexOption.IGNORE_CASE,
    )
    private val META_PUBLISHED = Regex(
        """<meta[^>]+(?:property|name)=["'](?:article:published_time|datePublished|pubdate|publish-date)["'][^>]+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val META_UPDATED = Regex(
        """<meta[^>]+(?:property|name)=["'](?:article:modified_time|dateModified|last-modified)["'][^>]+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val SITE_NAME = Regex(
        """<meta[^>]+(?:property|name)=["'](?:og:site_name|application-name)["'][^>]+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )

    fun parseDuckDuckGo(html: String, max: Int): List<Source> {
        if (html.isBlank()) return emptyList()
        val anchors = RESULT_ANCHOR.findAll(html).toList()
        if (anchors.isEmpty()) return emptyList()
        val results = mutableListOf<Source>()
        for ((index, anchor) in anchors.withIndex()) {
            if (results.size >= max) break
            val rawHref = decodeEntities(anchor.groupValues[1])
            val url = unwrapDuckDuckGo(rawHref)
            if (!isHttpUrl(url)) continue
            val title = clean(stripTags(anchor.groupValues[2]))
            if (title.isBlank()) continue
            val end = anchors.getOrNull(index + 1)?.range?.first ?: html.length
            val block = html.substring(anchor.range.last + 1, end)
            val snippet = SNIPPET.find(block)?.groupValues?.get(1)
                ?.let { clean(stripTags(it)) }
                .orEmpty()
            val timestamp = TIMESTAMP.find(block)?.groupValues?.get(1)
                ?.let { clean(stripTags(it)) }
                ?.takeIf { it.isNotBlank() }
            results.add(
                Source(
                    title = title,
                    url = url,
                    snippet = snippet.take(400),
                    site = siteOf(url),
                    publishedAt = timestamp,
                    provider = "DuckDuckGo",
                ),
            )
        }
        return results
    }

    data class PageMeta(
        val title: String?,
        val description: String?,
        val site: String?,
        val publishedAt: String?,
        val updatedAt: String?,
    )

    fun parsePageMeta(html: String): PageMeta {
        val title = TITLE_TAG.find(html)?.groupValues?.get(1)?.let { clean(stripTags(it)) }?.takeIf { it.isNotBlank() }
        val description = (META_DESCRIPTION.find(html) ?: META_DESCRIPTION_REVERSED.find(html))
            ?.groupValues?.get(1)?.let { clean(decodeEntities(it)) }?.takeIf { it.isNotBlank() }
        return PageMeta(
            title = title?.take(200),
            description = description?.take(400),
            site = SITE_NAME.find(html)?.groupValues?.get(1)?.let { clean(decodeEntities(it)) }?.takeIf { it.isNotBlank() },
            publishedAt = META_PUBLISHED.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() },
            updatedAt = META_UPDATED.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() },
        )
    }

    /** Removes duplicate pages, keeping the first (highest-priority) result. */
    fun dedupe(sources: List<Source>): List<Source> {
        val seen = HashSet<String>()
        return sources.filter { source ->
            if (!isHttpUrl(source.url)) return@filter false
            val key = canonicalKey(source.url)
            key.isNotBlank() && seen.add(key)
        }
    }

    fun canonicalKey(url: String): String = runCatching {
        val uri = URI(url)
        val host = uri.host.orEmpty().lowercase().removePrefix("www.")
        val path = uri.path.orEmpty().trimEnd('/').ifBlank { "/" }
        val kept = uri.query.orEmpty()
            .split('&')
            .filter { it.isNotBlank() }
            .filterNot { pair ->
                val name = pair.substringBefore('=').lowercase()
                name.startsWith("utm_") || name in setOf("fbclid", "gclid", "ref", "source", "campaign", "s")
            }
            .sorted()
            .joinToString("&")
        if (host.isBlank()) "" else "$host$path" + if (kept.isBlank()) "" else "?$kept"
    }.getOrDefault("")

    fun siteOf(url: String): String = runCatching {
        URI(url).host.orEmpty().lowercase().removePrefix("www.")
    }.getOrDefault("")

    fun isHttpUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host.orEmpty().lowercase()
        if (uri.scheme != "http" && uri.scheme != "https") return@runCatching false
        if (host.isBlank()) return@runCatching false
        // Never fetch loopback or private-network addresses from search results:
        // a malicious result must not turn the assistant into an SSRF probe.
        if (host == "localhost" || host == "::1" || host.endsWith(".local")) return@runCatching false
        if (host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") ||
            host.startsWith("169.254.") || Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host)
        ) {
            return@runCatching false
        }
        true
    }.getOrDefault(false)

    fun providerOf(sources: List<Source>, requested: String, braveKey: String, searx: String): String {
        val used = sources.map { it.provider }.filter { it.isNotBlank() }.distinct()
        if (used.isNotEmpty()) return used.joinToString(" / ")
        if (requested == "brave" && braveKey.isNotBlank()) return "Brave Search"
        if (requested == "searxng" && searx.isNotBlank()) return "SearXNG"
        return "Ricerca automatica"
    }

    /** True when the question is about news or a narrow time window. */
    fun wantsNews(query: String, timeRange: String): Boolean {
        if (timeRange != "any") return true
        val lower = query.lowercase()
        return listOf(
            "notizie", "notizia", "ultime", "ultimissim", "oggi", "adesso", "attual",
            "news", "latest", "today", "breaking", "current events",
        ).any { it in lower }
    }

    /** RSS items: title, link, publication date and source name, nothing invented. */
    fun parseGoogleNews(xml: String, max: Int): List<Source> {
        if (xml.isBlank()) return emptyList()
        val items = Regex("<item>(.*?)</item>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(xml)
        val results = mutableListOf<Source>()
        for (item in items) {
            if (results.size >= max) break
            val body = item.groupValues[1]
            val rawTitle = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)
                ?.let { clean(decodeEntities(it.removePrefix("<![CDATA[").removeSuffix("]]>").trim())) }
                ?: continue
            val link = Regex("<link>(.*?)</link>", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)
                ?.let { decodeEntities(it.removePrefix("<![CDATA[").removeSuffix("]]>").trim()) }
                ?: continue
            if (!isHttpUrl(link)) continue
            val sourceName = Regex("<source[^>]*>(.*?)</source>", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)
                ?.let { clean(decodeEntities(it)) }
                ?.takeIf { it.isNotBlank() }
            val title = sourceName?.let { name ->
                rawTitle.removeSuffix(" - $name").trim()
            } ?: rawTitle
            val published = Regex("<pubDate>(.*?)</pubDate>", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)?.trim()
            results.add(
                Source(
                    title = title,
                    url = link,
                    snippet = "",
                    site = sourceName ?: siteOf(link),
                    publishedAt = published,
                    provider = "Google News",
                ),
            )
        }
        return results
    }

    /** Extracts the real target from DuckDuckGo's `/l/?uddg=` redirect. */
    fun unwrapDuckDuckGo(href: String): String {
        val decoded = decodeEntities(href)
        val match = Regex("""[?&]uddg=([^&]+)""").find(decoded)
        if (match != null) {
            return runCatching { URLDecoder.decode(match.groupValues[1], "UTF-8") }.getOrDefault(decoded)
        }
        return when {
            decoded.startsWith("//") -> "https:$decoded"
            else -> decoded
        }
    }

    fun stripTags(html: String): String = html.replace(Regex("<[^>]+>"), " ")

    fun decodeEntities(text: String): String {
        var value = text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
        val numeric = Regex("""&#(x?[0-9a-fA-F]+);""")
        value = numeric.replace(value) { match ->
            val token = match.groupValues[1]
            runCatching {
                val code = if (token.startsWith("x") || token.startsWith("X")) {
                    token.drop(1).toInt(16)
                } else {
                    token.toInt()
                }
                String(Character.toChars(code))
            }.getOrDefault(match.value)
        }
        return value
    }

    fun clean(text: String): String = decodeEntities(text)
        .replace(Regex("\\s+"), " ")
        .trim()
}

/** Minimal, dependency-free HTML to text conversion with script/style removal. */
object HtmlText {
    fun extract(html: String): String {
        var text = html
        text = text.replace(Regex("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>"), " ")
        text = text.replace(Regex("(?is)<!--.*?-->"), " ")
        text = text.replace(Regex("(?i)<br\\s*/?>"), "\n")
        text = text.replace(Regex("(?i)</(p|div|li|h[1-6]|tr)>"), "\n")
        text = text.replace(Regex("<[^>]+>"), " ")
        text = text.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        text = text.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        text = text.replace(Regex("\\n\\s*\\n+"), "\n\n")
        return text.trim()
    }
}
