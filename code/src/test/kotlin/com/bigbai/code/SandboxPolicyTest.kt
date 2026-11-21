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
    fun `main frame cannot navigate away from the sandbox`() {
        assertTrue(SandboxPolicy.allowsMainNavigation("https://sandbox.local/index.html"))
        assertTrue(SandboxPolicy.allowsMainNavigation("about:blank"))
        assertFalse(SandboxPolicy.allowsMainNavigation("https://example.com/phish"))
        assertFalse(SandboxPolicy.allowsMainNavigation("http://127.0.0.1/"))
    }
}
