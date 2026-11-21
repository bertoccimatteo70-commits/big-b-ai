package com.bigbai.code

import java.net.InetAddress
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
        val h = host.lowercase().trim().trimEnd('.').removeSurrounding("[", "]")
        if (h.isEmpty()) return true
        if (h == SANDBOX_HOST) return false
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local")) return true
        if (h == "metadata.google.internal") return true

        // Only resolve literals: passing a hostname to InetAddress would perform
        // a DNS lookup from the UI process and could block. A hostname that
        // starts with "fc" or "fd" is not an IPv6 address.
        if (!isIpLiteral(h)) return false
        val address = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return false
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        // Java does not always report IPv6 unique-local addresses as site-local.
        val bytes = address.address
        return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
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

    private fun isIpLiteral(value: String): Boolean {
        val withoutZone = value.substringBefore('%')
        if (withoutZone.isEmpty()) return false
        if (withoutZone.contains(':')) {
            return withoutZone.all { it.isDigit() || it in 'a'..'f' || it == ':' || it == '.' }
        }
        return withoutZone.all { it.isDigit() || it == '.' }
    }
}
