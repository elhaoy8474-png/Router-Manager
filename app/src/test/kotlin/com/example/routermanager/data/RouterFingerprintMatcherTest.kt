package com.example.routermanager.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class RouterFingerprintMatcherTest {

    @Test
    fun identifiesStrongZteFingerprint() {
        val match = RouterFingerprintMatcher.match("ZXHN H188A router login")
        assertNotNull(match)
        assertEquals("ZTE", match?.vendor)
        assertEquals(100, match?.confidence)
    }

    @Test
    fun rejectsWeakGenericWebPath() {
        assertNull(RouterFingerprintMatcher.match("Generic router page at /webpages/"))
    }

    @Test
    fun rejectsAmbiguousVendorEvidence() {
        assertNull(RouterFingerprintMatcher.match("tp-link d-link router login"))
    }

    @Test
    fun rejectsUnrecognizedGenericLoginPage() {
        assertNull(RouterFingerprintMatcher.match("<title>Router Login</title>"))
    }
}
