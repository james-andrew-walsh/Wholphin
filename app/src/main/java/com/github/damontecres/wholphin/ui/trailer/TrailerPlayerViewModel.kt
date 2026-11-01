package com.github.damontecres.wholphin.ui.trailer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.webkit.WebViewFeature
import com.github.damontecres.wholphin.services.MusicService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.ScreensaverService
import com.github.damontecres.wholphin.services.TrailerService
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

private val START_TIMEOUT = 30.seconds

/**
 * @param videoId the video that should be playing
 * @param loadCount increases for each load, including trying the same video again
 * @param ready the player in the page is up
 */
data class TrailerPlayerState(
    val videoId: String? = null,
    val loadCount: Int = 0,
    val ready: Boolean = false,
    val failure: TrailerFailure? = null,
)

@HiltViewModel(assistedFactory = TrailerPlayerViewModel.Factory::class)
class TrailerPlayerViewModel
    @AssistedInject
    constructor(
        private val navigationManager: NavigationManager,
        private val screensaverService: ScreensaverService,
        private val musicService: MusicService,
        @Assisted private val destination: Destination.TrailerPlayer,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(destination: Destination.TrailerPlayer): TrailerPlayerViewModel
        }

        private val _state = MutableStateFlow(TrailerPlayerState())
        val state: StateFlow<TrailerPlayerState> = _state

        private val queue = TrailerQueue(destination.videoIds.filter { TrailerService.isValidVideoId(it) })
        private var startTimeout: Job? = null
        private var playing = false

        val isCurrent: Boolean get() = navigationManager.isCurrent(destination)

        init {
            addCloseable { screensaverService.keepScreenOn(false) }
            viewModelScope.launchIO { musicService.stop() }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                perform(queue.start())
            } else {
                perform(TrailerAction.Fail(TrailerFailure.UNSUPPORTED))
            }
        }

        fun onEvent(event: TrailerEvent) {
            when (event) {
                TrailerEvent.Ready -> {
                    _state.update { it.copy(ready = true) }
                }

                is TrailerEvent.StateChanged -> {
                    setPlaying(event.isPlaying)
                }

                is TrailerEvent.Error -> {
                    Timber.w("Trailer player error %s", event.code)
                }

                TrailerEvent.AutoplayBlocked -> {
                    Timber.w("Trailer autoplay was blocked")
                }
            }
            perform(queue.onEvent(event))
        }

        /**
         * A new page is going in the WebView, so there's no player until it says so
         */
        fun onPageLoading() {
            _state.update { it.copy(ready = false) }
        }

        fun close() {
            navigationManager.goBackFrom(destination)
        }

        private fun perform(action: TrailerAction) {
            when (action) {
                TrailerAction.None -> {}

                is TrailerAction.Load -> {
                    setPlaying(false)
                    _state.update { it.copy(videoId = action.videoId, loadCount = it.loadCount + 1) }
                    startTimeout?.cancel()
                    startTimeout =
                        viewModelScope.launch {
                            delay(START_TIMEOUT)
                            perform(queue.onTimeout())
                        }
                }

                TrailerAction.Close -> {
                    close()
                }

                is TrailerAction.Fail -> {
                    if (action.failure == TrailerFailure.APP_IDENTITY) {
                        Timber.e("YouTube rejected the app's identity")
                    }
                    setPlaying(false)
                    startTimeout?.cancel()
                    _state.update { it.copy(failure = action.failure) }
                }
            }
        }

        private fun setPlaying(playing: Boolean) {
            if (this.playing != playing) {
                this.playing = playing
                screensaverService.keepScreenOn(playing)
            }
        }
    }
