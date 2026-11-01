package com.github.damontecres.wholphin.ui.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPageRulesTest {
    @Test
    fun `Rating pages stay on their own site`() {
        assertEquals("imdb.com", ratingSiteDomain("https://www.imdb.com/title/tt0078748"))
        assertEquals("rottentomatoes.com", ratingSiteDomain("https://www.rottentomatoes.com/m/alien"))
    }

    @Test
    fun `Anything else has no site`() {
        assertNull(ratingSiteDomain("https://evil.example/"))
        assertNull(ratingSiteDomain("http://www.imdb.com/"))
    }

    @Test
    fun `The main frame can go anywhere on the site`() {
        listOf(
            "https://www.imdb.com/title/tt0078748/fullcredits",
            "https://m.imdb.com/title/tt0078748",
            "https://imdb.com/",
            "https://WWW.IMDB.COM/",
            "https://www.imdb.com/find?q=a|b#c",
        ).forEach {
            assertTrue(it, isAllowedWebPageNavigation(it, true, "imdb.com"))
        }
    }

    @Test
    fun `The main frame can't leave the site`() {
        listOf(
            "https://www.rottentomatoes.com/m/alien",
            "https://notimdb.com/",
            "https://imdb.com.evil.example/",
            "https://evil.example/imdb.com",
            "https://evil.example?imdb.com",
            "https://evil.example#imdb.com",
            "https://www.imdb.com@evil.example/",
            "https://www.imdb.com:8443/",
            "http://www.imdb.com/",
            "about:blank",
            "data:text/html,hi",
            "intent://www.imdb.com/#Intent;scheme=https;end",
            "imdb:///title/tt0078748",
            "market://details?id=com.imdb.mobile",
            "javascript:alert(1)",
            "",
        ).forEach {
            assertFalse(it, isAllowedWebPageNavigation(it, true, "imdb.com"))
        }
    }

    @Test
    fun `Frames can load the web and what pages build themselves`() {
        listOf(
            "https://ads.example/frame",
            "about:blank",
            "about:srcdoc",
            "blob:https://www.imdb.com/1234",
            "data:text/html,hi",
        ).forEach {
            assertTrue(it, isAllowedWebPageNavigation(it, false, "imdb.com"))
        }
    }

    @Test
    fun `Frames can't load anything else`() {
        listOf(
            "http://ads.example/frame",
            "intent://ads.example/#Intent;end",
            "market://details?id=com.example",
            "tel:5551234",
            "javascript:alert(1)",
        ).forEach {
            assertFalse(it, isAllowedWebPageNavigation(it, false, "imdb.com"))
        }
    }
}
