package com.bigbai.code

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxPolicyTest {

    @Test
    fun `network is blocked when the project is not authorised`() {
        assertFalse(SandboxPolicy.allows("https://example.com/api", networkAllowed = false))
        assertFalse(SandboxPolicy.allows("http://example.com", networkAllowed = false))
        // Local project assets must still load with the network disabled.
        assertTrue(SandboxPolicy.allows("https://sandbox.local/app.js", networkAllowed = false))
    }

    @Test
    fun `authorised projects can reach public hosts`() {
        assertTrue(SandboxPolicy.allows("https://example.com/api", networkAllowed = true))
        assertTrue(SandboxPolicy.allows("https://sandbox.local/app.js", networkAllowed = true))
    }

    @Test
    fun `loopback and private networks stay blocked even when authorised`() {
        listOf(
            "http://127.0.0.1:8080/admin",
            "http://localhost/",
            "http://192.168.1.1/",
            "http://10.0.0.2/",
            "http://172.16.0.1/",
            "http://169.254.169.254/latest/meta-data",
            "http://router.local/",
            "http://[::1]/",
        ).forEach { url ->
            assertFalse("deve bloccare $url", SandboxPolicy.allows(url, networkAllowed = true))
        }
    }

    @Test
    fun `public hosts starting with fc or fd are not mistaken for IPv6`() {
        assertFalse(SandboxPolicy.isPrivateHost("fcm.googleapis.com"))
        assertFalse(SandboxPolicy.isPrivateHost("fdic.gov"))
        assertTrue(SandboxPolicy.allows("https://fcm.googleapis.com/fcm/send", networkAllowed = true))
    }

    @Test
    fun `IPv6 loopback, mapped IPv4 and unique local addresses are blocked`() {
        listOf(
            "::1",
            "0:0:0:0:0:0:0:1",
            "::ffff:127.0.0.1",
            "::ffff:192.168.1.10",
            "fd00::1",
            "[fd12:3456:789a::1]",
            "fe80::1",
        ).forEach { host ->
            assertTrue("deve bloccare $host", SandboxPolicy.isPrivateHost(host))
        }
    }

    @Test
    fun `main frame cannot navigate away from the sandbox`() {
        assertTrue(SandboxPolicy.allowsMainNavigation("https://sandbox.local/index.html"))
        assertTrue(SandboxPolicy.allowsMainNavigation("about:blank"))
        assertFalse(SandboxPolicy.allowsMainNavigation("https://example.com/phish"))
        assertFalse(SandboxPolicy.allowsMainNavigation("http://127.0.0.1/"))
    }
}
