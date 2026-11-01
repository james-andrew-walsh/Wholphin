package com.github.damontecres.wholphin.ui.web

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.components.CircularProgress
import com.github.damontecres.wholphin.ui.nav.Destination

/**
 * Shows a rating site's page for a movie inside the app
 *
 * @param destination must be for a URL that [ratingSiteDomain] accepts
 */
@Composable
fun WebPagePage(
    destination: Destination.WebPage,
    viewModel: WebPageViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    var webView by remember { mutableStateOf<WebPageWebView?>(null) }
    // Once left, this stays composed for the transition while the page below takes over
    val isCurrent = viewModel.isCurrent

    BackHandler(enabled = isCurrent) {
        if (webView?.goBackInHistory() != true) {
            viewModel.close()
        }
    }

    // Opening the same page during the transition brings this back, with focus elsewhere
    LaunchedEffect(webView, isCurrent) {
        if (isCurrent) webView?.requestFocus()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, webView) {
        val view = webView
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> view?.pausePage()
                    Lifecycle.Event.ON_RESUME -> view?.resumePage()
                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = modifier.fillMaxSize().background(Color.Black),
    ) {
        if (state.failed) {
            WebPageFailureMessage(
                title = destination.title,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            // No onReset, so this is never reused for something else
            AndroidView(
                factory = { context ->
                    WebPageWebView(
                        context = context,
                        siteDomain = ratingSiteDomain(destination.url).orEmpty(),
                        onLoaded = viewModel::onLoaded,
                        onFailed = viewModel::onFailed,
                        onClose = viewModel::close,
                    ).also {
                        webView = it
                        it.loadUrl(destination.url)
                    }
                },
                onRelease = {
                    it.release()
                    webView = null
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (!state.loaded) {
                CircularProgress(
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .size(64.dp),
                )
            }
        }
    }
}

@Composable
private fun WebPageFailureMessage(
    title: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(32.dp),
    ) {
        title?.let {
            Text(
                text = it,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Text(
            text = stringResource(R.string.web_page_unavailable),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
