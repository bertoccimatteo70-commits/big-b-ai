package com.bigbai.code

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

data class SandboxResult(
    val success: Boolean,
    val logs: List<String>,
    val errors: List<String>,
    val durationMs: Long,
    val timedOut: Boolean,
    val blockedRequests: Int,
)

/**
 * Isolated execution environment for HTML/JS projects.
 *
 * A throw-away WebView runs the page with JavaScript enabled, storage disabled,
 * file access disabled and every network request blocked unless the project
 * explicitly allows it. There is no bridge to app data: the only interface the
 * page can call is the log collector below.
 *
 * Java and C++ are not executable here: Android ships no javac/clang for apps,
 * and the UI states that instead of pretending to compile them.
 */
class JsSandbox(private val context: Context) {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun run(
        html: String,
        timeoutMs: Long = 7000,
        allowNetwork: Boolean = false,
    ): SandboxResult = withContext(Dispatchers.Main) {
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val blocked = AtomicInteger()
        val started = System.currentTimeMillis()

        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.blockNetworkLoads = !allowNetwork
            settings.mediaPlaybackRequiresUserGesture = true
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            setBackgroundColor(0x00000000)
        }

        webView.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun log(level: String, message: String) {
                    val line = "[${level.uppercase()}] $message"
                    if (logs.size < 300) logs.add(line)
                }

                @JavascriptInterface
                fun error(message: String) {
                    if (errors.size < 100) errors.add(message)
                }
            },
            "BigBAIConsole",
        )

        val harness = """
            <script>
            (function() {
              function send(level, args) {
                try {
                  var text = Array.prototype.map.call(args, function(a) {
                    try { return typeof a === 'object' ? JSON.stringify(a) : String(a); }
                    catch (e) { return String(a); }
                  }).join(' ');
                  window.BigBAIConsole.log(level, text);
                } catch (e) {}
              }
              ['log','info','warn','error','debug'].forEach(function(level) {
                var original = console[level];
                console[level] = function() { send(level, arguments); if (original) original.apply(console, arguments); };
              });
              window.addEventListener('error', function(event) {
                try { window.BigBAIConsole.error(String(event.message) + ' @ linea ' + (event.lineno || 0)); } catch (e) {}
              });
              window.addEventListener('unhandledrejection', function(event) {
                try { window.BigBAIConsole.error('Promise rifiutata: ' + String(event.reason)); } catch (e) {}
              });
              window.BigBAI = {
                assert: function(condition, message) {
                  if (condition) { window.BigBAIConsole.log('pass', message || 'ok'); }
                  else { window.BigBAIConsole.error('ASSERT FALLITA: ' + (message || 'condizione falsa')); }
                },
                log: function(message) { window.BigBAIConsole.log('info', message); }
              };
            })();
            </script>
        """.trimIndent()

        val document = when {
            html.contains("<head>", ignoreCase = true) ->
                html.replaceFirst(Regex("<head>", RegexOption.IGNORE_CASE), "<head>$harness")
            html.contains("<html", ignoreCase = true) ->
                html.replaceFirst(Regex("(<html[^>]*>)", RegexOption.IGNORE_CASE), "$1<head>$harness</head>")
            else -> "<!DOCTYPE html><html><head>$harness</head><body>$html</body></html>"
        }

        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            var finished = false

            fun finish(timedOut: Boolean) {
                if (finished) return
                finished = true
                handler.removeCallbacksAndMessages(null)
                runCatching {
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.destroy()
                }
                if (continuation.isActive) {
                    continuation.resume(
                        SandboxResult(
                            success = errors.isEmpty() && !timedOut,
                            logs = logs.toList(),
                            errors = errors.toList(),
                            durationMs = System.currentTimeMillis() - started,
                            timedOut = timedOut,
                            blockedRequests = blocked.get(),
                        ),
                    )
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    val remote = url.startsWith("http://") || url.startsWith("https://")
                    if (remote && !allowNetwork) {
                        blocked.incrementAndGet()
                        return WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            403,
                            "Blocked",
                            mapOf("X-BigBAI-Sandbox" to "network-blocked"),
                            ByteArrayInputStream(ByteArray(0)),
                        )
                    }
                    return null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    handler.postDelayed({ finish(timedOut = false) }, 900)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        errors.add("Errore di caricamento: ${error?.description ?: "sconosciuto"}")
                    }
                }
            }

            continuation.invokeOnCancellation { finish(timedOut = false) }
            handler.postDelayed({ finish(timedOut = true) }, timeoutMs)

            runCatching {
                webView.loadDataWithBaseURL(
                    "https://sandbox.local/",
                    document,
                    "text/html",
                    "UTF-8",
                    null,
                )
            }.onFailure { error ->
                errors.add(error.message ?: "Impossibile avviare la sandbox")
                finish(timedOut = false)
            }
        }
    }
}
