package com.github.damontecres.wholphin.ui.web

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.services.NavigationManager
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
import kotlin.time.Duration.Companion.seconds

private val LOAD_TIMEOUT = 30.seconds

/**
 * @param loaded a page has finished loading
 * @param failed there is nothing to show, only a message
 */
data class WebPageState(
    val loaded: Boolean = false,
    val failed: Boolean = false,
)

@HiltViewModel(assistedFactory = WebPageViewModel.Factory::class)
class WebPageViewModel
    @AssistedInject
    constructor(
        private val navigationManager: NavigationManager,
        @Assisted private val destination: Destination.WebPage,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(destination: Destination.WebPage): WebPageViewModel
        }

        private val _state = MutableStateFlow(WebPageState())
        val state: StateFlow<WebPageState> = _state

        val isCurrent: Boolean get() = navigationManager.isCurrent(destination)

        private val loadTimeout: Job =
            viewModelScope.launchIO {
                delay(LOAD_TIMEOUT)
                onFailed()
            }

        fun onLoaded() {
            loadTimeout.cancel()
            _state.update { it.copy(loaded = true) }
        }

        fun onFailed() {
            loadTimeout.cancel()
            _state.update { it.copy(failed = true) }
        }

        fun close() {
            navigationManager.goBackFrom(destination)
        }
    }
