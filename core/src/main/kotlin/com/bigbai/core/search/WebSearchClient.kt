package com.bigbai.core.search

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.bigbai.core.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class SearchConfig(
    val provider: String,          // "searxng" | "brave"
    val searxEndpoint: String,
    val braveApiKey: String,
)

sealed interface SearchResult {
    data class Success(val sources: List<Source>, val query: String) : SearchResult
    data class Failure(val reason: SearchFailure) : SearchResult
}

enum class SearchFailure { OFFLINE, NOT_CONFIGURED, NETWORK_ERROR, EMPTY }

/**
 * Optional web search. It is disabled by default and only runs when the user
 * configures a provider. No query is ever sent anywhere without an explicit
 * setting and an active network.
 */
class WebSearchClient(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun isOnline(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun search(query: String, config: SearchConfig, maxResults: Int = 5): SearchResult =
        withContext(Dispatchers.IO) {
            if (!isOnline()) return@withContext SearchResult.Failure(SearchFailure.OFFLINE)
            val raw = when (config.provider) {
                "brave" -> searchBrave(query, config.braveApiKey, maxResults)
                "searxng" -> searchSearx(query, config.searxEndpoint, maxResults)
                else -> null
            } ?: return@withContext SearchResult.Failure(
                if (config.provider == "brave" && config.braveApiKey.isBlank() ||
                    config.provider == "searxng" && config.searxEndpoint.isBlank()
                ) {
                    SearchFailure.NOT_CONFIGURED
                } else {
                    SearchFailure.NETWORK_ERROR
                },
            )
            if (raw.isEmpty()) return@withContext SearchResult.Failure(SearchFailure.EMPTY)
            SearchResult.Success(raw, query)
        }

    private fun searchSearx(query: String, endpoint: String, max: Int): List<Source>? {
        if (endpoint.isBlank()) return null
        val base = endpoint.trimEnd('/')
        val url = "$base/search?q=${encode(query)}&format=json&safesearch=1"
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val root = json.parseToJsonElement(body).jsonObject
                val results = root["results"]?.jsonArray ?: JsonArray(emptyList())
                results.take(max).mapNotNull { element ->
                    val obj = element.jsonObject
                    val title = obj["title"]?.jsonPrimitive?.contentOrEmpty().orEmpty()
                    val link = obj["url"]?.jsonPrimitive?.contentOrEmpty().orEmpty()
                    val snippet = obj["content"]?.jsonPrimitive?.contentOrEmpty().orEmpty()
                    if (link.isBlank()) null else Source(title.ifBlank { link }, link, snippet)
                }
            }
        }.getOrNull()
    }

    private fun searchBrave(query: String, key: String, max: Int): List<Source>? {
        if (key.isBlank()) return null
        val url = "https://api.search.brave.com/res/v1/web/search?q=${encode(query)}&count=$max&safesearch=moderate"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Subscription-Token", key)
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val root = json.parseToJsonElement(body).jsonObject
                val results = root["web"]?.jsonObject?.get("results")?.jsonArray ?: JsonArray(emptyList())
                results.take(max).mapNotNull { element ->
                    val obj = element.jsonObject
                    val title = obj["title"]?.jsonPrimitive?.contentOrEmpty().orEmpty()
                    val link = obj["url"]?.jsonPrimitive?.contentOrEmpty().orEmpty()
                    val snippet = obj["description"]?.jsonPrimitive?.contentOrEmpty().orEmpty()
                    if (link.isBlank()) null else Source(title.ifBlank { link }, link, snippet)
                }
            }
        }.getOrNull()
    }

    /** Fetches the readable text of a page, bounded and sanitised. */
    suspend fun fetchPageText(url: String, maxChars: Int = 3000): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url)
                .header("User-Agent", "BigBAI/1.0 (local assistant; +https://github.com/bertoccimatteo70-commits/big-b-ai)")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    null
                } else {
                    response.body?.string()?.let { html -> HtmlText.extract(html).take(maxChars) }
                }
            }
        }.getOrNull()
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    private fun JsonObject.text(key: String): String =
        this[key]?.jsonPrimitive?.contentOrEmpty().orEmpty()

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrEmpty(): String =
        runCatching { content }.getOrDefault("")
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
