package com.github.damontecres.wholphin.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.QueueRemoval
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.QueueResult
import com.github.damontecres.wholphin.services.QueueService
import com.github.damontecres.wholphin.ui.components.GridTitle
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.ifElse
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.DataLoadingState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * The Discover page for a removal, null if the service didn't say what it was
 */
internal fun triedDestination(removal: QueueRemoval): Destination.DiscoveredItem? =
    discoveredItemDestination(removal.itemType, removal.tmdbId, removal.title)

/**
 * @param refreshFailed the last refresh failed, the list is what was there before
 */
data class TriedState(
    val removals: DataLoadingState<List<QueueRemoval>> = DataLoadingState.Pending,
    val refreshFailed: Boolean = false,
)

@HiltViewModel
class TriedViewModel
    @Inject
    constructor(
        private val queueService: QueueService,
        val navigationManager: NavigationManager,
    ) : ViewModel() {
        private val _state = MutableStateFlow(TriedState())
        val state: StateFlow<TriedState> = _state

        // One call at a time, so that an older list never replaces a newer one
        private val mutex = Mutex()

        fun retry() {
            _state.update { it.copy(removals = DataLoadingState.Loading) }
            viewModelScope.launchIO { refresh() }
        }

        /**
         * Fetch the history again, which the page does each time it shows
         */
        suspend fun refresh() {
            mutex.withLock {
                val result = queueService.removalHistory()
                _state.update { current ->
                    when (result) {
                        is QueueResult.Success -> {
                            TriedState(DataLoadingState.Success(result.value.removals))
                        }

                        is QueueResult.Failure -> {
                            if (current.removals is DataLoadingState.Success) {
                                current.copy(refreshFailed = true)
                            } else {
                                current.copy(removals = DataLoadingState.Error())
                            }
                        }
                    }
                }
            }
        }

        fun open(removal: QueueRemoval) {
            triedDestination(removal)?.let(navigationManager::navigateTo)
        }
    }

/**
 * Everything that was tried and removed from the queue, newest first, one line each. A line opens
 * the title's Discover page.
 */
@Composable
fun TriedPage(
    modifier: Modifier = Modifier,
    viewModel: TriedViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    // Each time the page shows, so a removal made elsewhere meanwhile is there
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refresh()
        }
    }

    when (val removals = state.removals) {
        DataLoadingState.Pending,
        DataLoadingState.Loading,
        -> {
            LoadingPage(modifier)
        }

        is DataLoadingState.Error -> {
            DownloadsMessage(
                message = stringResource(R.string.tried_load_error),
                detail = null,
                onRetry = viewModel::retry,
                modifier = modifier,
            )
        }

        is DataLoadingState.Success<List<QueueRemoval>> -> {
            if (removals.data.isEmpty()) {
                DownloadsMessage(
                    message = stringResource(R.string.tried_empty),
                    detail = null,
                    onRetry = viewModel::retry,
                    modifier = modifier,
                )
            } else {
                TriedList(
                    removals = removals.data,
                    refreshFailed = state.refreshFailed,
                    onClick = viewModel::open,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun TriedList(
    removals: List<QueueRemoval>,
    refreshFailed: Boolean,
    onClick: (QueueRemoval) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focusedId by rememberSaveable { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()
    val restoredRow = remember { listState.firstVisibleItemIndex }
    val focusedRow =
        removals.indexOfFirst { it.id == focusedId }.takeIf { it >= 0 }
            ?: restoredRow.coerceAtMost(removals.lastIndex)
    val focusRequester = remember { FocusRequester() }
    // Not saved, so that coming back from a title focuses the line it was opened from
    var focusRestored by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        GridTitle(
            title = stringResource(R.string.tried_title),
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = pluralStringResource(R.plurals.tried_count, removals.size, removals.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (refreshFailed) {
            Text(
                text = stringResource(R.string.downloads_refresh_error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 40.dp),
            modifier =
                Modifier
                    .focusRestorer()
                    .weight(1f)
                    .fillMaxWidth(),
        ) {
            itemsIndexed(removals, key = { _, removal -> removal.id }) { index, removal ->
                val restoreFocus = index == focusedRow && !focusRestored
                if (restoreFocus) {
                    LaunchedEffect(Unit) { focusRestored = focusRequester.tryRequestFocus("tried") }
                }
                TriedLine(
                    removal = removal,
                    onClick = { onClick.invoke(removal) },
                    modifier =
                        Modifier
                            .ifElse(restoreFocus, Modifier.focusRequester(focusRequester))
                            .onFocusChanged { if (it.hasFocus) focusedId = removal.id },
                )
            }
        }
    }
}

@Composable
private fun TriedLine(
    removal: QueueRemoval,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                focusedContainerColor = MaterialTheme.colorScheme.onSurface,
                focusedContentColor = MaterialTheme.colorScheme.inverseOnSurface,
                pressedContainerColor = MaterialTheme.colorScheme.onSurface,
                pressedContentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ),
        modifier =
            modifier
                .fillMaxWidth()
                .height(QUEUE_LINE_HEIGHT),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
        ) {
            TriedText(
                text = titleWithYear(removal.title, removal.year),
                bold = true,
                modifier = Modifier.weight(0.38f),
            )
            TriedText(
                text = removal.requestedBy.joinToString(", "),
                modifier = Modifier.weight(0.14f),
            )
            TriedText(
                text = removalDescription(removal.reason, removal.partial, removal.removedBy),
                modifier = Modifier.weight(0.38f),
            )
            TriedText(
                text = removal.at?.let { formatNoticeDate(it) }.orEmpty(),
                modifier = Modifier.weight(0.1f),
            )
        }
    }
}

@Composable
private fun TriedText(
    text: String,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
) {
    Text(
        text = text,
        style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
        color = LocalContentColor.current,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
