package com.github.damontecres.wholphin.ui.trailer

import org.junit.Assert
import org.junit.Test

class TrailerPlayerHtmlTest {
    private val videoId = "dQw4w9WgXcQ"
    private val origin = trailerOrigin("app.ourflix")

    @Test
    fun `Page has the video and identifies the app`() {
        val html = trailerPlayerHtml(videoId, origin)
        Assert.assertTrue(html.contains("videoId:\"$videoId\""))
        Assert.assertTrue(html.contains("origin:\"https://app.ourflix\""))
        Assert.assertTrue(html.contains("<meta name=\"referrer\" content=\"strict-origin-when-cross-origin\">"))
        Assert.assertTrue(html.contains("<script src=\"https://www.youtube.com/iframe_api\"></script>"))
        Assert.assertTrue(html.contains("playerVars:{autoplay:1,controls:1,enablejsapi:1,fs:0,iv_load_policy:3,rel:0,"))
        Assert.assertTrue(html.contains("ourflix.postMessage("))
    }

    @Test
    fun `Captions are unloaded when they load and when playing starts`() {
        val html = trailerPlayerHtml(videoId, origin)
        Assert.assertTrue(html.contains("p.unloadModule('captions')"))
        Assert.assertTrue(html.contains("onApiChange:function(e){noCaptions(e.target,false)}"))
        Assert.assertTrue(html.contains("if(e.data===1){noCaptions(e.target,true)}"))
    }

    @Test
    fun `Debug builds use their own origin`() {
        Assert.assertEquals("https://app.ourflix.debug", trailerOrigin("app.ourflix.debug"))
    }

    @Test
    fun `Video IDs are validated`() {
        listOf("", "short", "$videoId!", "dQw4w9WgXc\"", "</script>xx", "dQw4w9Wg XcQ").forEach {
            Assert.assertThrows(it, IllegalArgumentException::class.java) {
                trailerPlayerHtml(it, origin)
            }
            Assert.assertThrows(it, IllegalArgumentException::class.java) {
                trailerLoadScript(it)
            }
        }
    }

    @Test
    fun `The origin is escaped`() {
        val html = trailerPlayerHtml(videoId, "\"</script><script>alert(1)//\\")
        Assert.assertFalse(html.contains("</script><script>alert"))
        Assert.assertTrue(html.contains("origin:\"\\\"\\u003c/script\\u003e\\u003cscript\\u003ealert\\u00281\\u0029//\\\\\""))
    }

    @Test
    fun `Loading a video goes through the API`() {
        Assert.assertTrue(trailerLoadScript(videoId).contains("player.loadVideoById(\"$videoId\")"))
    }

    @Test
    fun `Seeking is relative to where the video is`() {
        Assert.assertTrue(trailerSeekScript(-10).contains("player.seekTo(player.getCurrentTime()+(-10),true)"))
    }

    @Test
    fun `The page stays on its origin`() {
        Assert.assertTrue(isAllowedTrailerNavigation("https://app.ourflix/", true, origin))
        Assert.assertTrue(isAllowedTrailerNavigation("https://app.ourflix/page?a=b", true, origin))
        listOf(
            "https://www.youtube.com/watch?v=$videoId",
            "http://app.ourflix/",
            "https://app.ourflix.evil.com/",
            "https://app.ourflix@evil.com/",
            "https://app.ourflix:444/",
            "about:blank",
            "data:text/html,hello",
            "intent://www.youtube.com/#Intent;end",
            "vnd.youtube:$videoId",
            "market://details?id=com.google.android.youtube",
            "not a url",
            "",
        ).forEach {
            Assert.assertFalse(it, isAllowedTrailerNavigation(it, true, origin))
        }
    }

    @Test
    fun `Frames can load the web but not leave it`() {
        listOf(
            "https://www.youtube.com/embed/$videoId",
            "http://example.com",
            "about:blank",
            "blob:https://www.youtube.com/abc",
            "data:text/html,<b>hello</b>",
        ).forEach {
            Assert.assertTrue(it, isAllowedTrailerNavigation(it, false, origin))
        }
        listOf(
            "intent://www.youtube.com/#Intent;end",
            "vnd.youtube:$videoId",
            "market://details?id=com.google.android.youtube",
            "file:///sdcard/secret",
            "content://secret",
            "javascript:alert(1)",
            "",
        ).forEach {
            Assert.assertFalse(it, isAllowedTrailerNavigation(it, false, origin))
        }
    }
}
