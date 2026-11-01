package com.github.damontecres.wholphin.ui.web

import com.github.damontecres.wholphin.data.model.MovieRatings
import java.net.URI
import java.net.URISyntaxException

private val FRAME_SCHEMES = setOf("https", "about", "blob", "data")

// Without the query or fragment, which are full of characters that URI refuses but browsers don't
private fun hostOf(url: String): String? =
    try {
        URI(url.substringBefore('#').substringBefore('?'))
            .takeIf { it.rawUserInfo == null && it.port == -1 }
            ?.host
            ?.lowercase()
    } catch (_: URISyntaxException) {
        null
    }

/**
 * The domain that a rating page must stay on, or null if [url] isn't a rating page
 */
fun ratingSiteDomain(url: String): String? = if (MovieRatings.isAllowedRatingPage(url)) hostOf(url)?.removePrefix("www.") else null

/**
 * Whether the WebView may go to [url]. The page itself stays on [siteDomain], and frames can load
 * the web but nothing that could hand off to another app.
 */
fun isAllowedWebPageNavigation(
    url: String,
    isMainFrame: Boolean,
    siteDomain: String,
): Boolean {
    val scheme = url.substringBefore(':', "").lowercase()
    if (!isMainFrame) return scheme in FRAME_SCHEMES
    if (scheme != "https") return false
    val host = hostOf(url) ?: return false
    return host == siteDomain || host.endsWith(".$siteDomain")
}
