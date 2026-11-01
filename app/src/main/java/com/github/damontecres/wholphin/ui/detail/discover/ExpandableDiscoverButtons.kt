package com.github.damontecres.wholphin.ui.detail.discover

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.data.model.Trailer
import com.github.damontecres.wholphin.services.TrailerService
import com.github.damontecres.wholphin.ui.components.ExpandableFaButton
import com.github.damontecres.wholphin.ui.components.ExpandablePlayButton
import com.github.damontecres.wholphin.ui.components.TrailerButton
import com.github.damontecres.wholphin.ui.ifElse
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlin.time.Duration

/**
 * @param canRemove offer removing the request and starting over
 * @param removing that is running, the button stays meanwhile so that it keeps focus
 * @param waitingForRelease a requested movie isn't out, which is why nothing is happening
 */
@Composable
fun ExpandableDiscoverButtons(
    canRequest: Boolean,
    canCancel: Boolean,
    availability: SeerrAvailability,
    trailers: List<Trailer>?,
    requestOnClick: () -> Unit,
    cancelOnClick: () -> Unit,
    goToOnClick: () -> Unit,
    moreOnClick: () -> Unit,
    trailerOnClick: (Trailer) -> Unit,
    buttonOnFocusChanged: (FocusState) -> Unit,
    modifier: Modifier = Modifier,
    pendingOnClick: () -> Unit = {},
    trailerFocusRequester: FocusRequester? = null,
    canRemove: Boolean = false,
    removing: Boolean = false,
    removeOnClick: () -> Unit = {},
    waitingForRelease: Boolean = false,
) {
    val firstFocus = remember { FocusRequester() }
    // The button goes once it has worked and takes focus with it, a remote would have nothing focused
    var wasRemoving by remember { mutableStateOf(false) }
    LaunchedEffect(removing) {
        if (wasRemoving && !removing && !canRemove) firstFocus.tryRequestFocus()
        wasRemoving = removing
    }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(8.dp),
        modifier =
            modifier
                .focusGroup()
                .focusRestorer(firstFocus),
    ) {
        val text =
            when (availability) {
                SeerrAvailability.UNKNOWN -> R.string.request

                SeerrAvailability.PENDING,
                SeerrAvailability.PROCESSING,
                -> if (waitingForRelease) R.string.download_state_waiting_release else R.string.pending

                SeerrAvailability.PARTIALLY_AVAILABLE,
                SeerrAvailability.AVAILABLE,
                -> R.string.go_to

                // TODO
                SeerrAvailability.DELETED -> R.string.delete

                // TODO Blocklist
                SeerrAvailability.BLOCKLISTED -> R.string.unavailable
            }
        val icon =
            when (availability) {
                SeerrAvailability.UNKNOWN -> R.string.fa_download

                SeerrAvailability.PENDING,
                SeerrAvailability.PROCESSING,
                -> R.string.fa_clock

                SeerrAvailability.PARTIALLY_AVAILABLE,
                SeerrAvailability.AVAILABLE,
                -> R.string.fa_play

                SeerrAvailability.DELETED -> R.string.fa_video

                // TODO
                SeerrAvailability.BLOCKLISTED -> R.string.fa_xmark
            }
        item("first") {
            ExpandableFaButton(
                title = text,
                iconStringRes = icon,
                enabled =
                    when (availability) {
                        SeerrAvailability.UNKNOWN -> canRequest

                        SeerrAvailability.PENDING,
                        SeerrAvailability.PROCESSING,
                        SeerrAvailability.PARTIALLY_AVAILABLE,
                        SeerrAvailability.AVAILABLE,
                        -> true

                        SeerrAvailability.BLOCKLISTED,
                        SeerrAvailability.DELETED,
                        -> false
                    },
                onClick = {
                    when (availability) {
                        SeerrAvailability.UNKNOWN -> {
                            requestOnClick.invoke()
                        }

                        SeerrAvailability.PENDING,
                        SeerrAvailability.PROCESSING,
                        -> {
                            pendingOnClick.invoke()
                        }

                        SeerrAvailability.PARTIALLY_AVAILABLE,
                        SeerrAvailability.AVAILABLE,
                        -> {
                            goToOnClick.invoke()
                        }

                        SeerrAvailability.DELETED,
                        SeerrAvailability.BLOCKLISTED,
                        -> {
                            // TODO
                        }
                    }
                },
                modifier =
                    Modifier
                        .focusRequester(firstFocus)
                        .onFocusChanged(buttonOnFocusChanged),
            )
        }
        if (availability == SeerrAvailability.PARTIALLY_AVAILABLE) {
            item("request_partial") {
                ExpandableFaButton(
                    title = R.string.request,
                    iconStringRes = R.string.fa_download,
                    onClick = {
                        requestOnClick.invoke()
                    },
                    enabled = canRequest,
                    modifier =
                        Modifier
                            .onFocusChanged(buttonOnFocusChanged),
                )
            }
        }

        if (canCancel) {
            item("cancel") {
                ExpandablePlayButton(
                    title = R.string.cancel,
                    icon = Icons.Default.Delete,
                    onClick = {
                        firstFocus.tryRequestFocus()
                        cancelOnClick.invoke()
                    },
                    resume = Duration.ZERO,
                    enabled = canCancel,
                    modifier =
                        Modifier
                            .onFocusChanged(buttonOnFocusChanged),
                )
            }
        }

        if (canRemove) {
            item("remove") {
                ExpandablePlayButton(
                    title = R.string.remove_request,
                    icon = Icons.Default.Refresh,
                    onClick = { if (!removing) removeOnClick.invoke() },
                    resume = Duration.ZERO,
                    modifier =
                        Modifier
                            .onFocusChanged(buttonOnFocusChanged),
                )
            }
        }

        if (trailers != null && TrailerService.showButton(trailers)) {
            item("trailers") {
                TrailerButton(
                    trailers = trailers,
                    trailerOnClick = trailerOnClick,
                    modifier =
                        Modifier
                            .onFocusChanged(buttonOnFocusChanged)
                            .ifElse(trailerFocusRequester != null, { Modifier.focusRequester(trailerFocusRequester!!) }),
                )
            }
        }

        // More button
        // No functionality yet
//        item("more") {
//            ExpandablePlayButton(
//                R.string.more,
//                Duration.ZERO,
//                Icons.Default.MoreVert,
//                { moreOnClick.invoke() },
//                Modifier
//                    .onFocusChanged(buttonOnFocusChanged),
//            )
//        }
    }
}
