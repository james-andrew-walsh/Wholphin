package com.github.damontecres.wholphin.services.hilt

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert
import org.junit.Test

class IsJellyfinServerUrlTest {
    private val serverUrl = "http://10.0.0.2:8096"

    private fun assertMatches(
        requestUrl: String,
        serverUrl: String?,
    ) = Assert.assertTrue(isJellyfinServerUrl(requestUrl.toHttpUrl(), serverUrl))

    private fun assertNoMatch(
        requestUrl: String,
        serverUrl: String?,
    ) = Assert.assertFalse(isJellyfinServerUrl(requestUrl.toHttpUrl(), serverUrl))

    @Test
    fun `Test same host and port`() {
        assertMatches("http://10.0.0.2:8096/Videos/abc/stream?static=true", serverUrl)
        assertMatches("http://10.0.0.2:8096/Items/abc/Images/Primary", serverUrl)
        assertMatches("http://10.0.0.2:8096/Audio/abc/universal", serverUrl)
    }

    @Test
    fun `Test other host`() {
        assertNoMatch("https://image.tmdb.org/t/p/original/poster.jpg", serverUrl)
    }

    @Test
    fun `Test Seerr on different port of same host`() {
        assertNoMatch("http://10.0.0.2:5055/api/v1/status", serverUrl)
    }

    @Test
    fun `Test null server`() {
        assertNoMatch("http://10.0.0.2:8096/Items", null)
    }

    @Test
    fun `Test unparseable server`() {
        assertNoMatch("http://10.0.0.2:8096/Items", "")
        assertNoMatch("http://10.0.0.2:8096/Items", "not a url")
        assertNoMatch("http://10.0.0.2:8096/Items", "10.0.0.2:8096")
    }

    @Test
    fun `Test host case is ignored`() {
        assertMatches("http://JELLYFIN.example.com:8096/Items", "http://jellyfin.EXAMPLE.com:8096")
    }

    @Test
    fun `Test default ports`() {
        assertMatches("http://jellyfin.example.com/Items", "http://jellyfin.example.com:80")
        assertMatches("http://jellyfin.example.com:80/Items", "http://jellyfin.example.com")
        assertMatches("https://jellyfin.example.com/Items", "https://jellyfin.example.com:443")
        assertNoMatch("http://jellyfin.example.com/Items", "http://jellyfin.example.com:8096")
    }

    @Test
    fun `Test scheme is ignored`() {
        assertMatches("https://10.0.0.2:8096/Items", serverUrl)
    }

    @Test
    fun `Test server with path`() {
        assertMatches("https://example.com/jellyfin/Items", "https://example.com/jellyfin")
    }
}
