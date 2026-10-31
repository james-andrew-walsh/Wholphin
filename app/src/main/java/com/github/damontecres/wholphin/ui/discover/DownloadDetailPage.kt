package com.github.damontecres.wholphin.ui.discover

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.DownloadProgress
import com.github.damontecres.wholphin.data.model.DownloadStatus
import com.github.damontecres.wholphin.data.model.RadarrStatus
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.data.model.TrackerSummary
import com.github.damontecres.wholphin.data.model.bandwidthGraph
import com.github.damontecres.wholphin.data.model.downloadDetailRefreshDelay
import com.github.damontecres.wholphin.data.model.downloadPercent
import com.github.damontecres.wholphin.data.model.formatCount
import com.github.damontecres.wholphin.data.model.formatDecimal
import com.github.damontecres.wholphin.data.model.formatPeerClients
import com.github.damontecres.wholphin.data.model.formatSize
import com.github.damontecres.wholphin.data.model.formatSpeed
import com.github.damontecres.wholphin.data.model.formatTimeLeft
import com.github.damontecres.wholphin.data.model.fraction
import com.github.damontecres.wholphin.data.model.leftQueue
import com.github.damontecres.wholphin.data.model.queueProgress
import com.github.damontecres.wholphin.services.DownloadStatusError
import com.github.damontecres.wholphin.services.DownloadStatusResult
import com.github.damontecres.wholphin.services.DownloadStatusService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.cards.ItemCardImage
import com.github.damontecres.wholphin.ui.components.CircularProgress
import com.github.damontecres.wholphin.ui.components.TextButton
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val DETAIL_SEPARATOR = "  •  "
private const val LABEL_ALPHA = 0.7f
private const val AXIS_ALPHA = 0.4f
private const val GRID_ALPHA = 0.15f
private val POSTER_WIDTH = 168.dp
private val PROGRESS_BAR_HEIGHT = 16.dp
private val Y_AXIS_WIDTH = 84.dp
private val Y_AXIS_GAP = 8.dp
private val LINE_WIDTH = 2.dp

/**
 * @param seerrProgress what Seerr said last, for when there are no live details
 * @param status the live details, null until they arrive and whenever the last attempt failed so
 * that old speeds never look current
 * @param finished the download left the queue, nothing more will change
 */
data class DownloadDetailState(
    val seerrProgress: DownloadProgress,
    val status: DownloadStatus? = null,
    val error: DownloadStatusError? = null,
    val finished: Boolean = false,
)

@HiltViewModel(assistedFactory = DownloadDetailViewModel.Factory::class)
class DownloadDetailViewModel
    @AssistedInject
    constructor(
        private val statusService: DownloadStatusService,
        private val seerrService: SeerrService,
        private val navigationManager: NavigationManager,
        @Assisted private val destination: Destination.DownloadDetail,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(destination: Destination.DownloadDetail): DownloadDetailViewModel
        }

        private val _state = MutableStateFlow(DownloadDetailState(destination.progress))
        val state: StateFlow<DownloadDetailState> = _state

        private val retries = Channel<Unit>(Channel.CONFLATED)
        private var failures = 0
        private var lastRefresh: TimeMark? = null

        /**
         * Keep the details up to date for as long as the caller stays active
         *
         * The caller scopes this to the page being visible. Returns once the download has finished.
         */
        suspend fun refreshWhileVisible() {
            while (!_state.value.finished) {
                val wait = lastRefresh?.let { downloadDetailRefreshDelay(failures) - it.elapsedNow() }
                if (wait != null && wait.isPositive()) {
                    if (withTimeoutOrNull(wait) { retries.receive() } != null) failures = 0
                }
                refresh()
                lastRefresh = TimeSource.Monotonic.markNow()
            }
        }

        /**
         * Try again now instead of waiting for the next attempt
         */
        fun retry() {
            retries.trySend(Unit)
        }

        private suspend fun refresh() {
            when (val result = statusService.fetch(destination.downloadId)) {
                is DownloadStatusResult.Success -> {
                    failures = 0
                    _state.update {
                        it.copy(status = result.status, error = null, finished = result.status.leftQueue)
                    }
                }

                DownloadStatusResult.Gone -> {
                    finish()
                }

                is DownloadStatusResult.Failure -> {
                    failures++
                    _state.update { it.copy(status = null, error = result.error) }
                    refreshFromSeerr()
                }
            }
        }

        private fun finish() {
            _state.update { it.copy(status = null, error = null, finished = true) }
        }

        /**
         * Without the status service, Seerr is the fallback for the progress and for noticing the end
         *
         * Being missing from Seerr's queue proves nothing since it only reads the start of the queue
         * and has nothing while Radarr is down, so it also has to say that the movie is there.
         */
        private suspend fun refreshFromSeerr() {
            val media =
                try {
                    seerrService.api.moviesApi
                        .movieMovieIdGet(movieId = destination.tmdbId)
                        .mediaInfo
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.w(ex, "Error fetching movie %s", destination.tmdbId)
                    return
                }
            val queue = if (destination.is4k) media?.downloadStatus4k else media?.downloadStatus
            val availability = SeerrAvailability.from(if (destination.is4k) media?.status4k else media?.status)
            val entry = queue?.firstOrNull { it.downloadId.equals(destination.downloadId, ignoreCase = true) }
            if (entry != null) {
                _state.update { it.copy(seerrProgress = queueProgress(entry, Instant.now())) }
            } else if (availability == SeerrAvailability.AVAILABLE || availability == SeerrAvailability.PARTIALLY_AVAILABLE) {
                finish()
            }
        }

        fun openMovie() {
            navigationManager.navigateTo(Destination.DiscoveredItem(destination.item))
        }

        fun goBack() {
            navigationManager.goBack()
        }
    }

/**
 * Everything known about a download while it is in the queue
 *
 * This is read-only on purpose, there are no controls for the download here. There's nothing to
 * scroll to with a remote either, so it all has to fit on one screen.
 */
@Composable
fun DownloadDetailPage(
    destination: Destination.DownloadDetail,
    modifier: Modifier = Modifier,
    viewModel: DownloadDetailViewModel =
        hiltViewModel<DownloadDetailViewModel, DownloadDetailViewModel.Factory>(
            creationCallback = { it.create(destination) },
        ),
) {
    val state by viewModel.state.collectAsState()

    // Leaving the page or the app cancels this, so nothing is fetched unless it can be seen
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refreshWhileVisible()
        }
    }

    val focusRequester = remember { FocusRequester() }
    val showRetry = state.error != null
    // The retry button takes focus with it when it goes away, a remote would have nothing focused
    LaunchedEffect(showRetry) { focusRequester.tryRequestFocus("download detail") }

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp, vertical = 24.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.width(POSTER_WIDTH),
            ) {
                ItemCardImage(
                    imageUrl = destination.item.posterUrl,
                    name = destination.item.title,
                    showOverlay = false,
                    favorite = false,
                    watched = false,
                    unwatchedCount = 0,
                    watchedPercent = null,
                    numberOfVersions = -1,
                    useFallbackText = false,
                    contentScale = ContentScale.FillBounds,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(AspectRatios.TALL),
                )
                TextButton(
                    stringRes = R.string.download_detail_movie_page,
                    onClick = viewModel::openMovie,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                )
                if (showRetry) {
                    TextButton(
                        stringRes = R.string.retry,
                        onClick = viewModel::retry,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.finished) {
                    TextButton(
                        stringRes = R.string.download_detail_back,
                        onClick = viewModel::goBack,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
            ) {
                Text(
                    text = destination.item.title ?: stringResource(R.string.unknown),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val status = state.status
                when {
                    state.finished -> {
                        Text(
                            text = stringResource(R.string.download_detail_finished),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }

                    status != null -> {
                        LiveDetails(status)
                    }

                    else -> {
                        SeerrDetails(state.seerrProgress, state.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun SeerrDetails(
    progress: DownloadProgress,
    error: DownloadStatusError?,
) {
    Text(
        text = stringResource(progress.state.stringRes),
        style = MaterialTheme.typography.titleMedium,
    )
    progress.fraction?.let { DownloadProgressBar(it, height = PROGRESS_BAR_HEIGHT) }
    DownloadProgressText(progress)
    if (error != null) {
        Text(
            text = stringResource(error.stringRes),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = stringResource(R.string.download_detail_retry_hint),
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 16.dp),
        ) {
            CircularProgress(Modifier.size(24.dp))
            Text(
                text = stringResource(R.string.download_detail_loading),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ColumnScope.LiveDetails(status: DownloadStatus) {
    val dash = stringResource(R.string.download_detail_no_value)
    Text(
        text = status.stateLabel ?: dash,
        style = MaterialTheme.typography.titleMedium,
    )
    val fraction = status.fraction
    fraction?.let { DownloadProgressBar(it, height = PROGRESS_BAR_HEIGHT) }
    Text(
        text =
            listOfNotNull(
                fraction?.let { stringResource(R.string.download_percent, downloadPercent(it)) } ?: dash,
                stringResource(R.string.download_detail_size, formatSize(status.size) ?: dash),
                formatTimeLeft(status.etaSeconds)?.let { stringResource(R.string.download_time_left, it) },
            ).joinToString(DETAIL_SEPARATOR),
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Text(
            text = stringResource(R.string.download_detail_speed_down, formatSpeed(status.downloadSpeed) ?: dash),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.download_detail_speed_up, formatSpeed(status.uploadSpeed) ?: dash),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    BandwidthChart(
        status = status,
        modifier =
            Modifier
                .weight(1f)
                .fillMaxWidth(),
    )
    Row(modifier = Modifier.fillMaxWidth()) {
        DetailText(
            label = stringResource(R.string.download_detail_seeds),
            value = connectedOfSwarm(status.seedsConnected, status.seedsSwarm, dash),
            modifier = Modifier.weight(1f),
        )
        DetailText(
            label = stringResource(R.string.download_detail_leeches),
            value = connectedOfSwarm(status.leechesConnected, status.leechesSwarm, dash),
            modifier = Modifier.weight(1f),
        )
        DetailText(
            label = stringResource(R.string.download_detail_availability),
            value = formatDecimal(status.availability) ?: dash,
            modifier = Modifier.weight(1f),
        )
        DetailText(
            label = stringResource(R.string.download_detail_connections),
            value = formatCount(status.connections) ?: dash,
            modifier = Modifier.weight(1f),
        )
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        DetailText(
            label = stringResource(R.string.download_detail_ratio),
            value = formatDecimal(status.ratio) ?: dash,
            modifier = Modifier.weight(1f),
        )
        DetailText(
            label = stringResource(R.string.download_detail_downloaded),
            value = formatSize(status.downloaded) ?: dash,
            modifier = Modifier.weight(1f),
        )
        DetailText(
            label = stringResource(R.string.download_detail_uploaded),
            value = formatSize(status.uploaded) ?: dash,
            modifier = Modifier.weight(1f),
        )
        DetailText(
            label = stringResource(R.string.download_detail_files),
            value = formatCount(status.fileCount) ?: dash,
            modifier = Modifier.weight(1f),
        )
    }
    DetailText(
        label = stringResource(R.string.download_detail_trackers),
        value = trackersText(status.trackers, dash),
    )
    DetailText(
        label = stringResource(R.string.download_detail_peer_clients),
        value = formatPeerClients(status.peerClients) ?: dash,
    )
    DetailText(
        label = stringResource(R.string.download_detail_radarr),
        value = radarrText(status.radarr, dash),
    )
    if (status.radarr.messages.isNotEmpty()) {
        Text(
            text = status.radarr.messages.joinToString(DETAIL_SEPARATOR),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DetailText(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current.copy(alpha = LABEL_ALPHA),
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun connectedOfSwarm(
    connected: Int?,
    swarm: Int?,
    dash: String,
): String {
    val connectedText = formatCount(connected)
    val swarmText = formatCount(swarm)
    return if (connectedText == null && swarmText == null) {
        dash
    } else {
        stringResource(R.string.download_detail_of, connectedText ?: dash, swarmText ?: dash)
    }
}

@Composable
private fun trackersText(
    trackers: TrackerSummary?,
    dash: String,
): String {
    val total = trackers?.total
    val working = trackers?.working
    val health =
        when {
            total == null || working == null -> dash
            total == 0 -> stringResource(R.string.download_detail_trackers_none)
            else -> stringResource(R.string.download_detail_trackers_working, working, total)
        }
    return listOfNotNull(health, trackers?.message?.takeIf { it.isNotBlank() })
        .joinToString(DETAIL_SEPARATOR)
}

@Composable
private fun radarrText(
    radarr: RadarrStatus,
    dash: String,
): String {
    if (!radarr.available) return stringResource(R.string.download_detail_radarr_unavailable)
    if (radarr.tracked != true) return stringResource(R.string.download_detail_radarr_not_tracked)
    val state =
        radarrStateRes(radarr.trackedState)?.let { stringResource(it) }
            ?: radarr.trackedState
            ?: radarr.status
            ?: dash
    return when (radarr.trackedStatus?.lowercase()) {
        "warning", "error" -> stringResource(R.string.download_detail_radarr_attention, state)
        else -> state
    }
}

@StringRes
private fun radarrStateRes(trackedState: String?): Int? =
    when (trackedState?.lowercase()) {
        "downloading" -> R.string.download_detail_radarr_downloading
        "importblocked" -> R.string.download_detail_radarr_import_blocked
        "importpending" -> R.string.download_detail_radarr_import_pending
        "importing" -> R.string.download_detail_radarr_importing
        "imported" -> R.string.download_detail_radarr_imported
        "failed", "failedpending" -> R.string.download_detail_radarr_failed
        "ignored" -> R.string.download_detail_radarr_ignored
        else -> null
    }

@get:StringRes
private val DownloadStatusError.stringRes: Int
    get() =
        when (this) {
            DownloadStatusError.SESSION_REFUSED -> R.string.download_detail_session_refused
            DownloadStatusError.AUTH_UNAVAILABLE -> R.string.download_detail_auth_unavailable
            DownloadStatusError.QBITTORRENT_UNAVAILABLE -> R.string.download_detail_client_unavailable
            DownloadStatusError.SERVICE_UNAVAILABLE -> R.string.download_detail_service_unavailable
        }

/**
 * Download and upload speeds over the last hour
 *
 * The lines differ in more than colour, download is solid and upload is dashed.
 */
@Composable
private fun BandwidthChart(
    status: DownloadStatus,
    modifier: Modifier = Modifier,
) {
    val graph = remember(status.history, status.fetchedAt) { bandwidthGraph(status.history, status.fetchedAt) }
    val downloadColor = MaterialTheme.colorScheme.onBackground
    val uploadColor = MaterialTheme.colorScheme.border
    val dash = stringResource(R.string.download_detail_no_value)
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.download_detail_graph_title),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            LegendKey(stringResource(R.string.download_detail_graph_download), downloadColor, dashed = false)
            LegendKey(stringResource(R.string.download_detail_graph_upload), uploadColor, dashed = true)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Y_AXIS_GAP),
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier =
                    Modifier
                        .width(Y_AXIS_WIDTH)
                        .fillMaxHeight(),
            ) {
                AxisLabel(formatSpeed(graph.ceiling) ?: dash)
                AxisLabel(formatSpeed(graph.ceiling / 2) ?: dash)
                AxisLabel(formatSpeed(0) ?: dash)
            }
            Canvas(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
            ) {
                drawAxes(downloadColor)
                drawSegments(graph.upload, uploadColor, dashEffect())
                drawSegments(graph.download, downloadColor, null)
            }
        }
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = Y_AXIS_WIDTH + Y_AXIS_GAP),
        ) {
            AxisLabel(stringResource(R.string.download_detail_graph_start))
            AxisLabel(stringResource(R.string.download_detail_graph_middle))
            AxisLabel(stringResource(R.string.download_detail_graph_end))
        }
    }
}

@Composable
private fun AxisLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
    )
}

@Composable
private fun LegendKey(
    label: String,
    color: Color,
    dashed: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.size(width = 32.dp, height = 8.dp)) {
            drawLine(
                color = color,
                start = Offset(0f, center.y),
                end = Offset(size.width, center.y),
                strokeWidth = LINE_WIDTH.toPx(),
                pathEffect = if (dashed) dashEffect() else null,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun Density.dashEffect(): PathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))

private fun DrawScope.drawAxes(color: Color) {
    val strokeWidth = 1.dp.toPx()
    val axis = color.copy(alpha = AXIS_ALPHA)
    val grid = color.copy(alpha = GRID_ALPHA)
    drawLine(grid, Offset.Zero, Offset(size.width, 0f), strokeWidth)
    drawLine(grid, Offset(0f, center.y), Offset(size.width, center.y), strokeWidth)
    drawLine(axis, Offset.Zero, Offset(0f, size.height), strokeWidth)
    drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth)
}

private fun DrawScope.drawSegments(
    segments: List<FloatArray>,
    color: Color,
    pathEffect: PathEffect?,
) {
    val stroke = Stroke(width = LINE_WIDTH.toPx(), join = StrokeJoin.Round, pathEffect = pathEffect)
    val path = Path()
    segments.forEach { points ->
        val start = graphPoint(points, 0)
        if (points.size == 2) {
            // A sample between two gaps has no line to be part of
            drawCircle(color, stroke.width, start)
        } else {
            path.moveTo(start.x, start.y)
            for (index in 2 until points.size step 2) {
                val point = graphPoint(points, index)
                path.lineTo(point.x, point.y)
            }
        }
    }
    drawPath(path, color, style = stroke)
}

private fun DrawScope.graphPoint(
    points: FloatArray,
    index: Int,
): Offset = Offset(points[index] * size.width, (1 - points[index + 1]) * size.height)
