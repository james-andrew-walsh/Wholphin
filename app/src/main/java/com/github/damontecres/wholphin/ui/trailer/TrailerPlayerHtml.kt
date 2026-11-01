package com.github.damontecres.wholphin.ui.trailer

import com.github.damontecres.wholphin.services.TrailerService
import java.net.URI
import java.net.URISyntaxException

/**
 * The name of the object the page uses to post messages
 */
const val TRAILER_BRIDGE = "ourflix"

const val TRAILER_PAUSE_SCRIPT = "if(window.player&&player.pauseVideo){player.pauseVideo()}"

const val TRAILER_TOGGLE_SCRIPT =
    "if(window.player&&player.getPlayerState){" +
        "var s=player.getPlayerState();" +
        "if(s===1||s===3){player.pauseVideo()}else{player.playVideo()}" +
        "}"

/**
 * Moves the video by [seconds], which can be negative
 */
fun trailerSeekScript(seconds: Int): String =
    "if(window.player&&player.seekTo&&player.getCurrentTime){player.seekTo(player.getCurrentTime()+($seconds),true)}"

private val WEB_SCHEMES = setOf("http", "https", "about", "blob", "data")

/**
 * The origin of the page. YouTube identifies an embedding app by this, it is never fetched.
 */
fun trailerOrigin(packageName: String): String = "https://$packageName"

private fun jsString(value: String): String =
    buildString {
        append('"')
        value.forEach {
            when {
                it == '"' || it == '\\' -> append('\\').append(it)
                it.isLetterOrDigit() || it in "-_.:/" -> append(it)
                else -> append("\\u").append(it.code.toString(16).padStart(4, '0'))
            }
        }
        append('"')
    }

private fun checkedVideoId(videoId: String): String {
    require(TrailerService.isValidVideoId(videoId)) { "Invalid video ID" }
    return jsString(videoId)
}

fun trailerLoadScript(videoId: String): String =
    "if(window.player&&player.loadVideoById){player.loadVideoById(${checkedVideoId(videoId)})}"

/**
 * The page that hosts YouTube's player for [videoId]
 *
 * The player has to be left as YouTube provides it, this only reports what it does.
 *
 * @throws IllegalArgumentException if [videoId] isn't a valid ID
 */
fun trailerPlayerHtml(
    videoId: String,
    origin: String,
): String =
    """
    <!doctype html><html><head><meta charset="utf-8">
    <meta name="referrer" content="strict-origin-when-cross-origin">
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <style>html,body,#player{margin:0;width:100%;height:100%;background:#000;overflow:hidden}</style>
    </head><body><div id="player"></div>
    <script>
    var player;
    function post(m){$TRAILER_BRIDGE.postMessage(JSON.stringify(m))}
    function onYouTubeIframeAPIReady(){
      player=new YT.Player('player',{
        width:'100%',height:'100%',videoId:${checkedVideoId(videoId)},
        playerVars:{autoplay:1,controls:1,enablejsapi:1,fs:0,iv_load_policy:3,rel:0,origin:${jsString(origin)}},
        events:{
          onReady:function(e){post({t:'ready'});e.target.getIframe().focus()},
          onStateChange:function(e){post({t:'state',v:e.data})},
          onError:function(e){post({t:'error',v:e.data})},
          onAutoplayBlocked:function(){post({t:'blocked'})}
        }
      })
    }
    </script>
    <script src="https://www.youtube.com/iframe_api"></script>
    </body></html>
    """.trimIndent()

/**
 * Whether the WebView may go to [url]. The page itself stays on [origin], and frames can load the
 * web but nothing that could hand off to another app.
 */
fun isAllowedTrailerNavigation(
    url: String,
    isMainFrame: Boolean,
    origin: String,
): Boolean {
    val scheme = url.substringBefore(':', "").lowercase()
    if (scheme !in WEB_SCHEMES) return false
    if (!isMainFrame) return true
    return try {
        val uri = URI(url)
        val base = URI(origin)
        scheme == base.scheme && uri.host == base.host && uri.port == base.port
    } catch (_: URISyntaxException) {
        false
    }
}
