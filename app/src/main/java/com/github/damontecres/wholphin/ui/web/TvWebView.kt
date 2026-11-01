package com.github.damontecres.wholphin.ui.web

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import androidx.annotation.RequiresApi
import timber.log.Timber

/**
 * A WebView that is the only thing on the page and is used with a remote
 *
 * Focus stays in here, Back is left for the rest of the app, and the view is torn down properly
 *
 * @param onRendererGone called when the view is no longer usable because its renderer died
 */
abstract class TvWebView(
    context: Context,
    private val onRendererGone: () -> Unit,
) : WebView(context) {
    protected var destroyed = false
        private set

    init {
        // AndroidView defaults to wrap content, which gives pages a height of 0 for anything sized in %
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    /**
     * Call from [android.webkit.WebViewClient.onRenderProcessGone] and return true there
     */
    @RequiresApi(Build.VERSION_CODES.O)
    protected fun handleRendererGone(detail: RenderProcessGoneDetail) {
        Timber.w("WebView renderer gone, didCrash=%s", detail.didCrash())
        destroyed = true
        removeFromParent()
        destroy()
        onRendererGone.invoke()
    }

    open fun pausePage() {
        if (!destroyed) onPause()
    }

    open fun resumePage() {
        if (!destroyed) onResume()
    }

    /**
     * Destroys this view, it can't be used afterwards
     */
    fun release() {
        if (destroyed) return
        destroyed = true
        removeFromParent()
        stopLoading()
        clearHistory()
        removeAllViews()
        destroy()
    }

    private fun removeFromParent() {
        (parent as? ViewGroup)?.removeView(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { if (!destroyed) requestFocus() }
    }

    // The rest of the app handles Back, returning here so that the page never sees it
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = event.keyCode != KeyEvent.KEYCODE_BACK && super.dispatchKeyEvent(event)

    // There is nothing else on the page, so focus has nowhere to go
    override fun focusSearch(direction: Int): View = this

    override fun focusSearch(
        focused: View?,
        direction: Int,
    ): View = this
}
