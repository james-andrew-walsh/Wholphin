package com.github.damontecres.wholphin.ui.trailer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.view.KeyEvent
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import androidx.webkit.WebViewCompat
import com.github.damontecres.wholphin.ui.web.TvWebView

/**
 * A WebView holding YouTube's player, controlled with a remote
 *
 * Nothing here opens another app: there are no intents, and navigating away is blocked. Check
 * [androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER] before creating this.
 *
 * @param onEvent called on the main thread with what the player reports
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class TrailerWebView(
    context: Context,
    private val onEvent: (TrailerEvent) -> Unit,
    onRendererGone: () -> Unit,
) : TvWebView(context, onRendererGone) {
    private val origin = trailerOrigin(context.packageName)

    /**
     * Whether a page has been loaded into this view
     */
    var hasPage = false
        private set

    /**
     * The page is being left and stays around only for the transition, so it should stay silent
     */
    var closing = false
        set(value) {
            field = value
            if (value) pausePlayback()
        }

    private var inBackground = false

    init {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        webChromeClient =
            object : WebChromeClient() {
                override fun getDefaultVideoPoster(): Bitmap =
                    Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.BLACK)
                    }
            }
        webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = !isAllowedTrailerNavigation(request.url.toString(), request.isForMainFrame, origin)

                @RequiresApi(Build.VERSION_CODES.O)
                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    handleRendererGone(detail)
                    return true
                }
            }
        // Only the page can see this, unlike a JavascriptInterface which YouTube's frames would get too
        WebViewCompat.addWebMessageListener(this, TRAILER_BRIDGE, setOf(origin)) { _, message, _, isMainFrame, _ ->
            if (isMainFrame) {
                TrailerEvent.parse(message.data)?.let(::onPlayerEvent)
            }
        }
    }

    private fun onPlayerEvent(event: TrailerEvent) {
        // Pausing does nothing until there's a player and autoplay starts it, so catch that here
        if ((closing || inBackground) && event.startsPlayback) pausePlayback()
        onEvent.invoke(event)
    }

    fun loadPage(videoId: String) {
        if (destroyed) return
        hasPage = true
        loadDataWithBaseURL("$origin/", trailerPlayerHtml(videoId, origin), "text/html", "utf-8", null)
    }

    fun loadVideo(videoId: String) {
        if (!destroyed) evaluateJavascript(trailerLoadScript(videoId), null)
    }

    fun pausePlayback() {
        if (!destroyed) evaluateJavascript(TRAILER_PAUSE_SCRIPT, null)
    }

    /**
     * Pauses the video as well, [onPause] doesn't stop audio on its own
     */
    override fun pausePage() {
        inBackground = true
        pausePlayback()
        super.pausePage()
    }

    override fun resumePage() {
        inBackground = false
        super.resumePage()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !destroyed) {
                    evaluateJavascript(TRAILER_TOGGLE_SCRIPT, null)
                }
                true
            }

            // Left to the page, these would move between the player's buttons instead
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            -> {
                seek(event, -SEEK_SECONDS)
            }

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            -> {
                seek(event, SEEK_SECONDS)
            }

            // Up and down bring up YouTube's own controls
            else -> {
                super.dispatchKeyEvent(event)
            }
        }

    private fun seek(
        event: KeyEvent,
        seconds: Int,
    ): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !destroyed) {
            evaluateJavascript(trailerSeekScript(seconds), null)
        }
        return true
    }

    private companion object {
        const val SEEK_SECONDS = 10
    }
}
