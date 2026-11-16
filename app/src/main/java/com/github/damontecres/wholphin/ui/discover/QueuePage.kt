package com.github.damontecres.wholphin.ui.discover

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
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
import com.github.damontecres.wholphin.data.model.AutoCancel
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.QueueProblem
import com.github.damontecres.wholphin.data.model.QueueRow
import com.github.damontecres.wholphin.data.model.QueueStatus
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.data.model.downloadPercent
import com.github.damontecres.wholphin.data.model.formatCountdown
import com.github.damontecres.wholphin.data.model.formatSpeed
import com.github.damontecres.wholphin.data.model.keyAfterRemoval
import com.github.damontecres.wholphin.data.model.queueRefreshDelay
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.QueueResult
import com.github.damontecres.wholphin.services.QueueService
import com.github.damontecres.wholphin.services.UnavailableTitles
import com.github.damontecres.wholphin.ui.components.CircularProgress
import com.github.damontecres.wholphin.ui.components.GridTitle
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.TextButton
import com.github.damontecres.wholphin.ui.ifElse
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.DataLoadingState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * How long the remove button waits for the second press
 */
internal const val REMOVE_CONFIRM_WINDOW_MS = 4_000L

/**
 * What a row says about its problem: what it is and when it gets cancelled
 *
 * @param problem null if the service didn't say or said something new
 * @param timeLeft the argument of [countdown], if it takes one
 */
data class QueueProblemText(
    @param:StringRes val problem: Int?,
    @param:StringRes val countdown: Int,
    val timeLeft: String? = null,
)

/**
 * @param now the current time on the server's clock, in Unix seconds
 * @return null unless the row is going to be cancelled
 */
internal fun queueProblemText(
    now: Long,
    cancelAt: Long?,
    problem: String?,
    autoCancel: String?,
): QueueProblemText? {
    if (cancelAt == null) return null
    val problemRes =
        when (QueueProblem.from(problem)) {
            QueueProblem.NO_SEEDS -> R.string.queue_problem_no_seeds
            QueueProblem.STUCK -> R.string.queue_problem_stuck
            QueueProblem.NOT_FOUND -> R.string.queue_problem_not_found
            null -> null
        }
    val left = cancelAt - now
    return when {
        left > 0 -> QueueProblemText(problemRes, R.string.queue_cancels_in, formatCountdown(left))
        AutoCancel.from(autoCancel) == AutoCancel.DRY -> QueueProblemText(problemRes, R.string.queue_would_cancel)
        else -> QueueProblemText(problemRes, R.string.queue_cancelling_soon)
    }
}

/**
 * Why a removal didn't work, in words
 *
 * @param error the service's error code
 */
@StringRes
internal fun removeFailureRes(error: String?): Int =
    when (error) {
        "in_library" -> R.string.queue_remove_in_library
        "not_allowed" -> R.string.queue_remove_not_allowed
        "changed", "not_in_queue" -> R.string.queue_remove_changed
        "not_ours", "finished" -> R.string.queue_remove_not_ours
        else -> R.string.queue_remove_failed
    }

/**
 * Whether a press of the remove button is the second one, in time to remove
 *
 * @param armedAt when the first press was, null if there wasn't one
 */
internal fun removeConfirmed(
    armedAt: Long?,
    now: Long,
): Boolean = armedAt != null && now - armedAt in 0 until REMOVE_CONFIRM_WINDOW_MS

/**
 * The Discover page for a row, null for a row which isn't a movie or series
 */
internal fun queueDestination(row: QueueRow): Destination.DiscoveredItem? {
    val type = row.itemType ?: return null
    val tmdbId = row.tmdbId ?: return null
    // The page fetches the rest by the ID
    return Destination.DiscoveredItem(
        DiscoverItem(
            id = tmdbId,
            type = type,
            title = row.title,
            subtitle = null,
            overview = null,
            availability = SeerrAvailability.UNKNOWN,
            releaseDate = null,
            posterUrl = null,
            backDropUrl = null,
            logoUrl = null,
            jellyfinItemId = null,
        ),
    )
}

/**
 * @param refreshFailed the last refresh failed, the rows are what was there before
 * @param removing the keys of the rows being removed
 * @param removeFailure why the last removal didn't work, until that has been shown
 */
data class QueueState(
    val queue: DataLoadingState<QueueStatus> = DataLoadingState.Pending,
    val refreshFailed: Boolean = false,
    val removing: Set<String> = emptySet(),
    @param:StringRes val removeFailure: Int? = null,
)

@HiltViewModel
class QueueViewModel
    @Inject
    constructor(
        private val queueService: QueueService,
        private val unavailableTitles: UnavailableTitles,
        val navigationManager: NavigationManager,
    ) : ViewModel() {
        private val _state = MutableStateFlow(QueueState())
        val state: StateFlow<QueueState> = _state

        // One call at a time, so that an older queue never replaces a newer one
        private val mutex = Mutex()

        @Volatile
        private var lastRefresh: TimeMark? = null

        init {
            viewModelScope.launchIO { unavailableTitles.refresh() }
        }

        fun retry() {
            _state.update { it.copy(queue = DataLoadingState.Loading) }
            viewModelScope.launchIO { refresh() }
        }

        /**
         * Keep the queue up to date for as long as the caller stays active
         *
         * The caller scopes this to the page being visible.
         */
        suspend fun refreshWhileVisible() {
            var failures = 0
            while (true) {
                val wait = lastRefresh?.let { queueRefreshDelay(failures) - it.elapsedNow() }
                if (wait != null && wait.isPositive()) delay(wait)
                failures = if (refresh()) 0 else failures + 1
            }
        }

        private suspend fun refresh(): Boolean =
            mutex.withLock {
                val result = queueService.queue()
                lastRefresh = TimeSource.Monotonic.markNow()
                _state.update { current ->
                    when (result) {
                        is QueueResult.Success -> {
                            current.copy(queue = DataLoadingState.Success(result.value), refreshFailed = false)
                        }

                        is QueueResult.Failure -> {
                            if (current.queue is DataLoadingState.Success) {
                                current.copy(refreshFailed = true)
                            } else {
                                current.copy(queue = DataLoadingState.Error())
                            }
                        }
                    }
                }
                result is QueueResult.Success
            }

        fun open(row: QueueRow) {
            queueDestination(row)?.let(navigationManager::navigateTo)
        }

        /**
         * Remove what a row is for, the row goes as soon as that worked
         */
        fun remove(row: QueueRow) {
            var start = false
            _state.update {
                start = row.key !in it.removing
                it.copy(removing = it.removing + row.key)
            }
            if (!start) return
            viewModelScope.launchIO {
                val removed =
                    try {
                        // Holds off the refresh, which could still have the row
                        mutex.withLock {
                            when (val result = queueService.remove(row.key)) {
                                is QueueResult.Success -> {
                                    _state.update { it.withoutRow(row.key) }
                                    true
                                }

                                is QueueResult.Failure -> {
                                    _state.update { it.copy(removeFailure = removeFailureRes(result.error)) }
                                    false
                                }
                            }
                        }
                    } finally {
                        _state.update { it.copy(removing = it.removing - row.key) }
                    }
                if (removed) {
                    // A cancelled title can be one the posters mark
                    unavailableTitles.refresh(force = true)
                    refresh()
                }
            }
        }

        fun onRemoveFailureShown() {
            _state.update { it.copy(removeFailure = null) }
        }
    }

private fun QueueState.withoutRow(key: String): QueueState {
    val status = (queue as? DataLoadingState.Success)?.data ?: return this
    return copy(queue = DataLoadingState.Success(status.copy(rows = status.rows.filterNot { it.key == key })))
}

/**
 * Everything being downloaded or searched for, for every user, one line each
 */
@Composable
fun QueuePage(
    modifier: Modifier = Modifier,
    viewModel: QueueViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.removeFailure) {
        state.removeFailure?.let {
            showToast(context, context.getString(it))
            viewModel.onRemoveFailureShown()
        }
    }

    // Leaving the page or the app cancels this, so nothing is fetched unless it can be seen
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refreshWhileVisible()
        }
    }

    when (val queue = state.queue) {
        DataLoadingState.Pending,
        DataLoadingState.Loading,
        -> {
            LoadingPage(modifier)
        }

        is DataLoadingState.Error -> {
            DownloadsMessage(
                message = stringResource(R.string.queue_load_error),
                detail = null,
                onRetry = viewModel::retry,
                modifier = modifier,
            )
        }

        is DataLoadingState.Success<QueueStatus> -> {
            if (queue.data.rows.isEmpty()) {
                DownloadsMessage(
                    message = stringResource(R.string.queue_empty),
                    detail = null,
                    onRetry = viewModel::retry,
                    modifier = modifier,
                )
            } else {
                QueueList(
                    status = queue.data,
                    state = state,
                    onClickRow = viewModel::open,
                    onClickRemove = viewModel::remove,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun QueueList(
    status: QueueStatus,
    state: QueueState,
    onClickRow: (QueueRow) -> Unit,
    onClickRemove: (QueueRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = status.rows
    var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val restoredRow = remember { listState.firstVisibleItemIndex }
    val focusedRow =
        rows.indexOfFirst { it.key == focusedKey }.takeIf { it >= 0 } ?: restoredRow.coerceAtMost(rows.lastIndex)
    val focusRequester = remember { FocusRequester() }
    // Not saved, so that coming back to the page focuses the row it was left on
    var focusRestored by remember { mutableStateOf(false) }
    var previousKeys by remember { mutableStateOf(rows.map { it.key }) }
    // The times are the server's, which is what the countdowns are measured against
    val now = status.fetchedAt ?: (System.currentTimeMillis() / 1000)

    // The row with focus can go while the page is showing, a remote would have nothing focused
    LaunchedEffect(rows) {
        val keys = rows.map { it.key }
        keyAfterRemoval(previousKeys, keys, focusedKey)?.let {
            focusedKey = it
            focusRestored = false
        }
        previousKeys = keys
    }

    Column(modifier = modifier.fillMaxSize()) {
        GridTitle(
            title = stringResource(R.string.queue_title),
            modifier = Modifier.padding(top = 16.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = pluralStringResource(R.plurals.queue_count, rows.size, rows.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (AutoCancel.from(status.autoCancel) == AutoCancel.DRY) {
                Text(
                    text = stringResource(R.string.queue_auto_cancel_dry),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.refreshFailed) {
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
            itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                val restoreFocus = index == focusedRow && !focusRestored
                if (restoreFocus) {
                    LaunchedEffect(Unit) { focusRestored = focusRequester.tryRequestFocus("queue") }
                }
                QueueRowCard(
                    row = row,
                    problemText = queueProblemText(now, row.cancelAt, row.problem, status.autoCancel),
                    canRemove = status.canRemove,
                    removing = row.key in state.removing,
                    onClick = { onClickRow.invoke(row) },
                    onClickRemove = { onClickRemove.invoke(row) },
                    cardModifier = Modifier.ifElse(restoreFocus, Modifier.focusRequester(focusRequester)),
                    modifier = Modifier.onFocusChanged { if (it.hasFocus) focusedKey = row.key },
                )
            }
        }
    }
}

/**
 * A row with its removal, if the user may remove, as a second button after it. So OK on the row
 * always does the same thing and Right from the row is how to get to the button.
 *
 * Removing deletes what was downloaded, so the button takes a second press within a few seconds.
 *
 * @param cardModifier for the row itself, not the button
 * @param modifier for the row and the button together
 */
@Composable
private fun QueueRowCard(
    row: QueueRow,
    problemText: QueueProblemText?,
    canRemove: Boolean,
    removing: Boolean,
    onClick: () -> Unit,
    onClickRemove: () -> Unit,
    modifier: Modifier = Modifier,
    cardModifier: Modifier = Modifier,
) {
    // Permission can go with the button focused, which it can't take with it
    var removeFocused by remember { mutableStateOf(false) }
    var armedAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(armedAt) {
        if (armedAt != null) {
            delay(REMOVE_CONFIRM_WINDOW_MS)
            armedAt = null
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .height(QUEUE_LINE_HEIGHT),
    ) {
        QueueRowContent(
            row = row,
            problemText = problemText,
            onClick = onClick,
            modifier =
                cardModifier
                    .weight(1f)
                    .height(QUEUE_LINE_HEIGHT),
        )
        if (canRemove || removeFocused) {
            // Stays the same button while removing so that it keeps focus
            TextButton(
                onClick = {
                    if (!removing) {
                        val now = SystemClock.uptimeMillis()
                        if (removeConfirmed(armedAt, now)) {
                            armedAt = null
                            onClickRemove.invoke()
                        } else {
                            armedAt = now
                        }
                    }
                },
                modifier =
                    Modifier.onFocusChanged {
                        removeFocused = it.isFocused
                        // Only two presses in a row count, not one from an earlier visit
                        if (!it.isFocused) armedAt = null
                    },
            ) {
                when {
                    removing -> CircularProgress(Modifier.size(24.dp))
                    armedAt != null -> Text(text = stringResource(R.string.queue_remove_confirm))
                    else -> Text(text = stringResource(R.string.queue_remove))
                }
            }
        }
    }
}

@Composable
private fun QueueRowContent(
    row: QueueRow,
    problemText: QueueProblemText?,
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
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
        ) {
            // A small mark for a row that needs attention, in the same place on every row
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .ifElse(
                            row.hasProblem,
                            Modifier.background(MaterialTheme.colorScheme.error, CircleShape),
                        ),
            )
            val downloads =
                if (row.kind == "series" && row.downloads > 1) {
                    pluralStringResource(R.plurals.queue_downloads, row.downloads, row.downloads)
                } else {
                    null
                }
            QueueText(
                text = listOfNotNull(titleWithYear(row.title, row.year), downloads).joinToString("  •  "),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(0.34f),
            )
            QueueText(
                text = row.requestedBy.joinToString(", "),
                modifier = Modifier.weight(0.16f),
            )
            val status =
                listOfNotNull(
                    row.stateLabel ?: row.state,
                    row.progress?.let { stringResource(R.string.download_percent, downloadPercent(it.toFloat())) },
                    row.downloadSpeed.takeIf { it > 0 }?.let { formatSpeed(it) },
                    row.seeds?.let { stringResource(R.string.queue_seeds, it) },
                )
            QueueText(
                text = status.joinToString("  •  "),
                modifier = Modifier.weight(0.28f),
            )
            val problem =
                problemText?.let { text ->
                    listOfNotNull(
                        text.problem?.let { stringResource(it) },
                        text.timeLeft?.let { stringResource(text.countdown, it) } ?: stringResource(text.countdown),
                    ).joinToString(" · ")
                }
            QueueText(
                text = problem.orEmpty(),
                color = if (row.hasProblem) MaterialTheme.colorScheme.error else LocalContentColor.current,
                modifier = Modifier.weight(0.22f),
            )
        }
    }
}

@Composable
private fun QueueText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = LocalContentColor.current,
) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
