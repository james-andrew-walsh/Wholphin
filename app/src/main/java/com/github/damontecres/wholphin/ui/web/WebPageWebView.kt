package com.github.damontecres.wholphin.ui.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi

/**
 * A WebView showing a page of a site as that site provides it
 *
 * Nothing here opens another app: there are no intents or downloads, and leaving [siteDomain] is
 * blocked.
 *
 * @param siteDomain see [isAllowedWebPageNavigation]
 * @param onLoaded called when a page has finished loading
 * @param onFailed called when the page in the main frame couldn't be loaded
 * @param onClose called when there is nothing left to show
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class WebPageWebView(
    context: Context,
    private val siteDomain: String,
    private val onLoaded: () -> Unit,
    private val onFailed: () -> Unit,
    private val onClose: () -> Unit,
) : TvWebView(context, onClose) {
    init {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // mediaPlaybackRequiresUserGesture keeps its default, pages can't start playing sound
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webChromeClient =
            object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    request.deny()
                }
            }
        webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = !isAllowedWebPageNavigation(request.url.toString(), request.isForMainFrame, siteDomain)

                // Catches what shouldOverrideUrlLoading isn't told about
                override fun doUpdateVisitedHistory(
                    view: WebView,
                    url: String,
                    isReload: Boolean,
                ) {
                    if (!isAllowedWebPageNavigation(url, true, siteDomain) && !goBackInHistory()) {
                        onClose.invoke()
                    }
                }

                override fun onPageFinished(
                    view: WebView,
                    url: String,
                ) {
                    onLoaded.invoke()
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                        onFailed.invoke()
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) {
                        onFailed.invoke()
                    }
                }

                @RequiresApi(Build.VERSION_CODES.O_MR1)
                override fun onSafeBrowsingHit(
                    view: WebView,
                    request: WebResourceRequest,
                    threatType: Int,
                    callback: SafeBrowsingResponse,
                ) {
                    callback.backToSafety(true)
                }

                @RequiresApi(Build.VERSION_CODES.O)
                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    handleRendererGone(detail)
                    return true
                }
            }
    }

    /**
     * Goes to the previous page in here
     *
     * @return false if there isn't one
     */
    fun goBackInHistory(): Boolean {
        if (destroyed || !canGoBack()) return false
        goBack()
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (event.action == KeyEvent.ACTION_DOWN && !destroyed) pageDown(false)
                true
            }

            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (event.action == KeyEvent.ACTION_DOWN && !destroyed) pageUp(false)
                true
            }

            else -> {
                super.dispatchKeyEvent(event)
            }
        }
}
