package com.bigbai.code

import java.net.URI

/**
 * Network policy of the HTML sandbox, kept pure so it can be tested without a
 * WebView. Even when the project is allowed to use the network, loopback and
 * private addresses stay blocked: a project must not be able to probe the
 * device or the local network.
 */
object SandboxPolicy {
    const val SANDBOX_HOST = "sandbox.local"

    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h.isEmpty()) return true
        if (h == "sandbox.local") return false
        if (h == "localhost" || h == "::1" || h == "0.0.0.0") return true
        if (h.endsWith(".local") || h.endsWith(".localhost")) return true
        if (h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.") ||
            h.startsWith("169.254.")
        ) {
            return true
        }
        if (Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(h)) return true
        if (h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")) return true
        return false
    }

    fun isRemote(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    /**
     * Returns true when the request may leave the WebView. Relative resources
     * resolve to [SANDBOX_HOST] and are local, so they are always allowed.
     */
    fun allows(url: String, networkAllowed: Boolean): Boolean {
        if (!isRemote(url)) return true
        val host = runCatching { URI(url).host }.getOrNull() ?: return false
        // Relative scripts/styles are resolved against the synthetic sandbox
        // origin and are local even when the network is disabled.
        if (host.equals(SANDBOX_HOST, ignoreCase = true)) return true
        if (!networkAllowed) return false
        return !isPrivateHost(host)
    }

    fun allowsMainNavigation(url: String): Boolean {
        if (url == "about:blank" || url.startsWith("data:", ignoreCase = true)) return true
        val host = runCatching { URI(url).host }.getOrNull() ?: return false
        return host.equals(SANDBOX_HOST, ignoreCase = true)
    }
}
