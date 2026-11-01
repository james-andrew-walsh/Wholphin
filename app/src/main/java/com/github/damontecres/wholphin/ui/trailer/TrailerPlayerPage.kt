package com.github.damontecres.wholphin.ui.trailer

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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.components.CircularProgress
import com.github.damontecres.wholphin.ui.nav.Destination

/**
 * Plays YouTube trailers inside the app using YouTube's own player
 *
 * Once the player is ready nothing is drawn over it, its controls and everything else on it stay
 * as YouTube shows them.
 */
@Composable
fun TrailerPlayerPage(
    destination: Destination.TrailerPlayer,
    modifier: Modifier = Modifier,
    viewModel: TrailerPlayerViewModel =
        hiltViewModel<TrailerPlayerViewModel, TrailerPlayerViewModel.Factory>(
            creationCallback = { it.create(destination) },
        ),
) {
    val state by viewModel.state.collectAsState()
    var webView by remember { mutableStateOf<TrailerWebView?>(null) }
    // Once left, this stays composed for the transition while the page below takes over
    val isCurrent = viewModel.isCurrent

    BackHandler(enabled = isCurrent) {
        webView?.closing = true
        viewModel.close()
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

    LaunchedEffect(webView, state.loadCount) {
        val view = webView ?: return@LaunchedEffect
        val videoId = state.videoId ?: return@LaunchedEffect
        if (view.hasPage && state.ready) {
            view.loadVideo(videoId)
        } else {
            viewModel.onPageLoading()
            view.loadPage(videoId)
        }
    }

    // Opening the same trailer during the transition brings this page back, with focus elsewhere
    LaunchedEffect(webView, state.ready, isCurrent) {
        webView?.closing = !isCurrent
        if (isCurrent) webView?.requestFocus()
    }

    Box(
        modifier = modifier.fillMaxSize().background(Color.Black),
    ) {
        val failure = state.failure
        if (failure != null) {
            TrailerFailureMessage(
                title = destination.title,
                failure = failure,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            // No onReset, so this is never reused for something else
            AndroidView(
                factory = { context ->
                    TrailerWebView(
                        context = context,
                        onEvent = viewModel::onEvent,
                        onRendererGone = viewModel::close,
                    ).also { webView = it }
                },
                onRelease = {
                    it.release()
                    webView = null
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (!state.ready) {
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
private fun TrailerFailureMessage(
    title: String?,
    failure: TrailerFailure,
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
            text =
                stringResource(
                    when (failure) {
                        TrailerFailure.UNAVAILABLE -> R.string.trailer_unavailable
                        TrailerFailure.APP_IDENTITY -> R.string.trailer_app_identity
                        TrailerFailure.UNSUPPORTED -> R.string.trailer_unsupported
                    },
                ),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
