package com.github.damontecres.wholphin.ui.discover

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.seerr.RequestApi
import com.github.damontecres.wholphin.api.seerr.model.MediaInfo
import com.github.damontecres.wholphin.api.seerr.model.MediaRequest
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.DownloadProgress
import com.github.damontecres.wholphin.data.model.DownloadState
import com.github.damontecres.wholphin.data.model.DownloadStatus
import com.github.damontecres.wholphin.data.model.RequestStatus
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.data.model.canRemoveRequest
import com.github.damontecres.wholphin.data.model.downloadFailedOrStuck
import com.github.damontecres.wholphin.data.model.downloadPercent
import com.github.damontecres.wholphin.data.model.downloadProgress
import com.github.damontecres.wholphin.data.model.downloadRefreshDelay
import com.github.damontecres.wholphin.data.model.hasMoreDownloads
import com.github.damontecres.wholphin.data.model.isDownloadHash
import com.github.damontecres.wholphin.data.model.isMovieReleased
import com.github.damontecres.wholphin.data.model.keepsRefreshing
import com.github.damontecres.wholphin.data.model.nextRefreshBatch
import com.github.damontecres.wholphin.data.model.parseSeerrTime
import com.github.damontecres.wholphin.data.model.requestedQueue
import com.github.damontecres.wholphin.data.model.requestedSeasonsInLibrary
import com.github.damontecres.wholphin.data.model.seenInQueue
import com.github.damontecres.wholphin.services.DownloadStatusResult
import com.github.damontecres.wholphin.services.DownloadStatusService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.RemoveRequestResult
import com.github.damontecres.wholphin.services.RemovedMedia
import com.github.damontecres.wholphin.services.SeerrServerRepository
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.services.SeerrUserConfig
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.cards.ItemCardImage
import com.github.damontecres.wholphin.ui.components.CircularProgress
import com.github.damontecres.wholphin.ui.components.GridTitle
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.TextButton
import com.github.damontecres.wholphin.ui.detail.discover.RemoveRequestDialog
import com.github.damontecres.wholphin.ui.detail.discover.stringRes
import com.github.damontecres.wholphin.ui.formatDuration
import com.github.damontecres.wholphin.ui.ifElse
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.successValue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import kotlin.time.TimeMark
import kotlin.time.TimeSource

// A page costs one GET /request plus one GET /movie or /tv for each row, the TV is on weak Wi-Fi
internal const val DOWNLOADS_PAGE_SIZE = 20
internal const val MAX_ROWS_PER_REFRESH = 10
private const val MAX_CONCURRENT_REQUESTS = 3
private const val ROWS_LOADED_AHEAD = 5
private val ROW_HEIGHT = 128.dp

/**
 * A movie or series the user requested
 *
 * @param type the type of [tmdbId], the same ID can be both a movie and a different series
 * @param seasons the requested seasons of a series
 * @param item the details, null until they have been fetched
 * @param jellyfinItemId only set once the user's Jellyfin has returned the item
 * @param seenInQueue whether a download was seen, which tells searching apart from importing
 * @param released false if a movie isn't out, which tells searching apart from having to wait
 * @param activeAt the last time there was a download, or else when the request last changed
 * @param removable it has failed or is stuck and the user can remove it, which shows on the row
 */
data class DownloadRow(
    val requestId: Int,
    val tmdbId: Int,
    val is4k: Boolean,
    val requestStatus: RequestStatus,
    val type: SeerrItemType = SeerrItemType.MOVIE,
    val seasons: List<Int> = emptyList(),
    val requestExists: Boolean = true,
    val item: DiscoverItem? = null,
    val jellyfinItemId: UUID? = null,
    val seenInQueue: Boolean = false,
    val released: Boolean = true,
    val activeAt: Instant? = null,
    val removable: Boolean = false,
    val progress: DownloadProgress = DownloadProgress(DownloadState.SEARCHING),
) {
    // A row without its details needs another fetch, even if its state is final
    val refreshing: Boolean get() = item == null || keepsRefreshing(progress.state, requestStatus)
}

/**
 * @param loadMoreFailed the last attempt at the next page failed, stays set during a retry so that
 * the retry button doesn't go away until it worked
 * @param removedRequests the loaded rows whose request is gone and was taken off [fetched]
 * @param removing the rows being removed
 * @param removeFailure why the last removal didn't work, until that has been shown
 */
data class DownloadsState(
    val rows: DataLoadingState<List<DownloadRow>> = DataLoadingState.Pending,
    val fetched: Int = 0,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreFailed: Boolean = false,
    val refreshFailed: Boolean = false,
    val removedRequests: Set<Int> = emptySet(),
    val removing: Set<Int> = emptySet(),
    val removeFailure: RemoveRequestResult? = null,
) {
    val refreshing: Boolean get() = rows.successValue?.any { it.refreshing } == true
}

/**
 * The rows whose request is gone, going by a first page sorted the same way as [rows]
 *
 * Only rows above the oldest one on [page] can be told, the others may just be further on.
 *
 * @param page the request IDs on the first page
 */
internal fun removedRequests(
    rows: List<DownloadRow>,
    page: Set<Int>,
): Set<Int> =
    rows
        .take(rows.indexOfLast { it.requestId in page } + 1)
        .map { it.requestId }
        .filterNot { it in page }
        .toSet()

/**
 * Which row focus belongs on
 *
 * The rows aren't saved but the list's position is. After the process was killed the row that had
 * focus might not be loaded yet, so this falls back to a row the list has composed.
 *
 * @param restoredRow the first row the list was showing when it was composed
 */
internal fun focusedRowIndex(
    rows: List<DownloadRow>,
    focusedRequestId: Int?,
    restoredRow: Int,
): Int {
    val index = rows.indexOfFirst { it.requestId == focusedRequestId }
    return if (index >= 0) index else restoredRow.coerceAtMost(rows.lastIndex)
}

/**
 * Which row focus belongs on once the row that had it has gone, null if it hasn't gone
 *
 * The next row which is still there, or the one before if it was the last. A focused row which was
 * never in [previous] hasn't gone, it can just be that it hasn't loaded yet.
 *
 * @param previous the rows before they changed
 */
internal fun rowAfterRemoval(
    previous: List<DownloadRow>,
    rows: List<DownloadRow>,
    focusedRequestId: Int?,
): Int? {
    val index = previous.indexOfFirst { it.requestId == focusedRequestId }
    if (index < 0 || rows.any { it.requestId == focusedRequestId }) return null
    val remaining = rows.map { it.requestId }.toSet()
    val after = previous.drop(index + 1).firstOrNull { it.requestId in remaining }
    return (after ?: previous.take(index).lastOrNull { it.requestId in remaining })?.requestId
}

/**
 * Drop the rows of what was removed, every request for it goes along with Seerr's record
 */
internal fun DownloadsState.withoutRemoved(removed: RemovedMedia): DownloadsState {
    val rows = rows.successValue ?: return this
    val gone =
        rows
            .filter { it.type == removed.type && it.tmdbId == removed.tmdbId }
            .map { it.requestId }
            .toSet()
    if (gone.isEmpty()) return this
    return copy(
        rows = DataLoadingState.Success(rows.filterNot { it.requestId in gone }),
        fetched = fetched - (gone - removedRequests).size,
        removedRequests = removedRequests - gone,
    )
}

/**
 * The detail page for a row, or null if it has no live download to show
 *
 * A row can miss out because Seerr only reads the start of the queue, so it has no ID for the rest.
 * Radarr's and Sonarr's ID is only a hash for torrents, and a row without its details has no page.
 */
internal fun detailDestination(
    row: DownloadRow,
    enabled: Boolean = BuildConfig.DOWNLOADS_PAGE_ENABLED,
): Destination.DownloadDetail? {
    val downloadId = row.progress.downloadId
    val item = row.item
    if (!enabled || !row.progress.state.active || item == null) return null
    if (downloadId == null || !isDownloadHash(downloadId)) return null
    return Destination.DownloadDetail(
        downloadId = downloadId,
        tmdbId = row.tmdbId,
        is4k = row.is4k,
        progress = row.progress,
        item = item,
    )
}

private data class DownloadsResult(
    val rows: List<DownloadRow>,
    val fetched: Int,
    val hasMore: Boolean,
)

/**
 * What a row needs from Seerr's details of a movie or series
 */
private class RequestedMedia(
    val mediaInfo: MediaInfo?,
    val released: Boolean = true,
    val createItem: suspend () -> DiscoverItem,
)

@HiltViewModel
class DownloadsViewModel
    @Inject
    constructor(
        private val api: ApiClient,
        private val seerrService: SeerrService,
        private val seerrServerRepository: SeerrServerRepository,
        private val statusService: DownloadStatusService,
        val navigationManager: NavigationManager,
    ) : ViewModel() {
        private val _state = MutableStateFlow(DownloadsState())
        val state: StateFlow<DownloadsState> = _state

        private val limiter = Semaphore(MAX_CONCURRENT_REQUESTS)
        private val reloads = MutableStateFlow(0)
        private var loadMoreJob: Job? = null
        private var newRequestsJob: Job? = null
        private var refreshStart = 0
        private var resumed = false

        @Volatile
        private var userId: Int? = null

        @Volatile
        private var userConfig: SeerrUserConfig? = null

        @Volatile
        private var lastRefresh: TimeMark = TimeSource.Monotonic.markNow()

        init {
            viewModelScope.launchIO {
                combine(
                    seerrServerRepository.currentUserId.distinctUntilChanged(),
                    reloads,
                ) { userId, _ -> userId }
                    .collectLatest { load(it) }
            }
            // This view model stays alive under the Discover pages, which can remove a request too
            viewModelScope.launchIO {
                seerrService.removedMedia.collect { removed ->
                    _state.update { it.withoutRemoved(removed) }
                }
            }
        }

        fun retry() {
            reloads.update { it + 1 }
        }

        private suspend fun load(userId: Int?) {
            loadMoreJob?.cancel()
            newRequestsJob?.cancel()
            this.userId = userId
            _state.value = DownloadsState(rows = DataLoadingState.Loading)
            if (userId == null) {
                _state.value = DownloadsState(rows = DataLoadingState.Error())
                return
            }
            _state.value =
                try {
                    userConfig = seerrServerRepository.current.first()?.config
                    val page = fetchPage(userId, 0)
                    lastRefresh = TimeSource.Monotonic.markNow()
                    DownloadsState(
                        rows = DataLoadingState.Success(page.rows),
                        fetched = page.fetched,
                        hasMore = page.hasMore,
                    )
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(ex, "Error fetching downloads")
                    DownloadsState(rows = DataLoadingState.Error(ex))
                }
        }

        /**
         * Load the next page, if there is one and the last attempt didn't fail
         */
        fun loadMore() {
            startLoadMore(retry = false)
        }

        fun retryLoadMore() {
            startLoadMore(retry = true)
        }

        private fun startLoadMore(retry: Boolean) {
            val userId = userId ?: return
            var start = false
            var skip = 0
            _state.update {
                start = it.hasMore && !it.loadingMore && (retry || !it.loadMoreFailed)
                skip = it.fetched
                if (start) it.copy(loadingMore = true) else it
            }
            if (!start) return
            loadMoreJob =
                viewModelScope.launchIO {
                    try {
                        val page = fetchPage(userId, skip)
                        _state.update { current ->
                            val rows = current.rows.successValue ?: return@update current
                            current.copy(
                                // Newer requests can push ones already seen onto the next page
                                rows = DataLoadingState.Success((rows + page.rows).distinctBy { it.requestId }),
                                fetched = page.fetched,
                                hasMore = page.hasMore,
                                loadingMore = false,
                                loadMoreFailed = false,
                            )
                        }
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.e(ex, "Error fetching more downloads")
                        _state.update { it.copy(loadingMore = false, loadMoreFailed = true) }
                    }
                }
        }

        /**
         * Pick up requests made since the page was last showing
         *
         * This view model stays alive under pages opened from the list, so nothing else fetches
         * them. The first resume is the page opening, which [load] already covers.
         */
        fun onResume() {
            val firstResume = !resumed
            resumed = true
            val userId = userId
            if (firstResume || userId == null || newRequestsJob?.isActive == true) return
            if (_state.value.rows !is DataLoadingState.Success) return
            newRequestsJob =
                viewModelScope.launchIO {
                    try {
                        addNewRequests(userId)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.w(ex, "Error fetching new downloads")
                    }
                }
        }

        /**
         * The list only keeps its place through new rows while it is on screen, what it saves when
         * it isn't is just an index. So these wait for the next [onResume] instead.
         */
        fun onPause() {
            newRequestsJob?.cancel()
        }

        private suspend fun addNewRequests(userId: Int) {
            val known = _state.value.rows.successValue.orEmpty().map { it.requestId }.toSet()
            val page = fetchRequests(userId, 0).results.orEmpty()
            // Newest first, so anything new is ahead of every known request. An unknown one further
            // down is an old request which moved up because a loaded one was removed
            val newRows = createRows(page.takeWhile { it.id !in known })
            val pageIds = page.map { it.id }.toSet()
            _state.update { current ->
                val rows = current.rows.successValue ?: return@update current
                val added = newRows.filter { new -> rows.none { it.requestId == new.requestId } }
                // Their rows stay in the list, so they are found again every time
                val removed = removedRequests(rows, pageIds) - current.removedRequests
                if (added.isEmpty() && removed.isEmpty()) return@update current
                current.copy(
                    rows = DataLoadingState.Success(added + rows),
                    // These moved the rows already fetched along by as many places
                    fetched = current.fetched + added.size - removed.size,
                    removedRequests = current.removedRequests + removed,
                )
            }
        }

        /**
         * Keep the rows up to date for as long as the caller stays active
         *
         * The caller scopes this to the page being visible. Returns if there is nothing to refresh.
         */
        suspend fun refreshWhileVisible() {
            var failures = 0
            while (true) {
                val wait = downloadRefreshDelay(failures) - lastRefresh.elapsedNow()
                if (wait.isPositive()) delay(wait)
                val success = refreshRows() ?: return
                failures = if (success) 0 else failures + 1
                _state.update { it.copy(refreshFailed = failures > 0) }
            }
        }

        /**
         * Refresh the rows which can still change or are missing their details
         *
         * @return whether any were refreshed or null if none need it
         */
        private suspend fun refreshRows(): Boolean? {
            val rows = _state.value.rows.successValue.orEmpty().filter { it.refreshing }
            if (rows.isEmpty()) return null
            val batch = nextRefreshBatch(rows, refreshStart, MAX_ROWS_PER_REFRESH)
            refreshStart = (refreshStart + batch.size) % rows.size
            val updated =
                withContext(WholphinDispatchers.IO) {
                    batch
                        .map { row ->
                            async {
                                limiter.withPermit {
                                    fetchMedia(row.type, row.tmdbId)?.let { updateRow(row, it.mediaInfo, it) }
                                }
                            }
                        }.awaitAll()
                }.filterNotNull().associateBy { it.requestId }
            lastRefresh = TimeSource.Monotonic.markNow()
            _state.update { current ->
                val latest = current.rows.successValue ?: return@update current
                current.copy(
                    rows = DataLoadingState.Success(latest.map { updated[it.requestId] ?: it }),
                )
            }
            return updated.isNotEmpty()
        }

        private suspend fun fetchPage(
            userId: Int,
            skip: Int,
        ): DownloadsResult {
            val response = fetchRequests(userId, skip)
            val requests = response.results.orEmpty()
            val fetched = skip + requests.size
            return DownloadsResult(
                rows = createRows(requests),
                fetched = fetched,
                hasMore =
                    hasMoreDownloads(
                        fetched = fetched,
                        total = response.pageInfo?.results,
                        lastPageSize = requests.size,
                        pageSize = DOWNLOADS_PAGE_SIZE,
                    ),
            )
        }

        private suspend fun fetchRequests(
            userId: Int,
            skip: Int,
        ) = seerrService.api.requestApi.requestGet(
            take = DOWNLOADS_PAGE_SIZE,
            skip = skip,
            sort = RequestApi.SortRequestGet.ADDED,
            sortDirection = RequestApi.SortDirectionRequestGet.DESC,
            requestedBy = userId,
            mediaType = RequestApi.MediaTypeRequestGet.ALL,
        )

        private suspend fun createRows(requests: List<MediaRequest>): List<DownloadRow> =
            coroutineScope {
                requests
                    .map { request -> async { limiter.withPermit { createRow(request) } } }
                    .awaitAll()
            }.filterNotNull()

        private suspend fun createRow(request: MediaRequest): DownloadRow? {
            val tmdbId = request.media?.tmdbId
            if (tmdbId == null) {
                Timber.v("No TMDB ID for request %s", request.id)
                return null
            }
            val type = SeerrItemType.fromString(request.type, SeerrItemType.MOVIE)
            val details = fetchMedia(type, tmdbId)
            return updateRow(
                row =
                    DownloadRow(
                        requestId = request.id,
                        tmdbId = tmdbId,
                        is4k = request.is4k == true,
                        requestStatus = RequestStatus.from(request.status),
                        type = type,
                        seasons = request.seasons?.mapNotNull { it.seasonNumber }.orEmpty().sorted(),
                        activeAt = parseSeerrTime(request.updatedAt ?: request.createdAt),
                    ),
                // Without the details the row still gets its state, only the title & poster are missing
                media = details?.mediaInfo ?: request.media,
                details = details,
            )
        }

        private suspend fun fetchMedia(
            type: SeerrItemType,
            tmdbId: Int,
        ): RequestedMedia? =
            try {
                if (type == SeerrItemType.TV) {
                    val series = seerrService.api.tvApi.tvTvIdGet(tvId = tmdbId)
                    RequestedMedia(series.mediaInfo) {
                        createItem(seerrService.createDiscoverItem(series), series.posterPath)
                    }
                } else {
                    val movie = seerrService.api.moviesApi.movieMovieIdGet(movieId = tmdbId)
                    val released = isMovieReleased(movie.status, movie.releaseDate, movie.releases, LocalDate.now())
                    RequestedMedia(movie.mediaInfo, released) {
                        createItem(seerrService.createDiscoverItem(movie), movie.posterPath)
                    }
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.w(ex, "Error fetching %s %s", type, tmdbId)
                null
            }

        /**
         * @param media the latest media info, null if Seerr no longer has any
         * @param details what that came from, or null if it could not be fetched
         */
        private suspend fun updateRow(
            row: DownloadRow,
            media: MediaInfo?,
            details: RequestedMedia?,
        ): DownloadRow {
            val request = media?.requests?.firstOrNull { it.id == row.requestId }
            val requestRemoved = details != null && (media == null || (media.requests != null && request == null))
            val requestExists = row.requestExists && !requestRemoved
            val requestStatus = request?.let { RequestStatus.from(it.status) } ?: row.requestStatus
            val queue = requestedQueue(if (row.is4k) media?.downloadStatus4k else media?.downloadStatus, row.seasons)
            val seen = seenInQueue(row.seenInQueue, queue)
            val jellyfinId =
                (if (row.is4k) media?.jellyfinMediaId4k else media?.jellyfinMediaId)
                    ?.toUUIDOrNull()
                    ?.takeIf { isInJellyfin(it) }
            val mediaStatus = SeerrAvailability.from(if (row.is4k) media?.status4k else media?.status)
            val seriesAvailable =
                mediaStatus == SeerrAvailability.AVAILABLE || mediaStatus == SeerrAvailability.PARTIALLY_AVAILABLE
            val seasonsInLibrary = requestedSeasonsInLibrary(row.seasons, media?.seasons, row.is4k, queue)
            val now = Instant.now()
            val released = details?.released ?: row.released
            val activeAt =
                if (queue.isNullOrEmpty()) {
                    listOfNotNull(row.activeAt, parseSeerrTime(request?.updatedAt)).maxOrNull()
                } else {
                    now
                }
            val progress =
                downloadProgress(
                    requestStatus = requestStatus,
                    requestExists = requestExists,
                    mediaStatus = if (seriesAvailable && seasonsInLibrary == false) SeerrAvailability.PROCESSING else mediaStatus,
                    queue = queue,
                    inJellyfin = jellyfinId != null && seasonsInLibrary == true,
                    seenInQueue = seen,
                    now = now,
                    released = released,
                )
            return row.copy(
                requestStatus = requestStatus,
                requestExists = requestExists,
                item = row.item ?: details?.createItem?.invoke(),
                jellyfinItemId = jellyfinId,
                seenInQueue = seen,
                released = released,
                activeAt = activeAt,
                // Without the details the seasons aren't known, and a partial series can't be offered
                removable =
                    details != null &&
                        requestExists &&
                        canRemoveRequest(userConfig, media) &&
                        downloadFailedOrStuck(
                            state = progress.state,
                            mediaStatus = mediaStatus,
                            queue = queue,
                            status = if (queue.isNullOrEmpty()) null else fetchStatus(progress),
                            activeAt = activeAt,
                            now = now,
                        ),
                progress = progress,
            )
        }

        private suspend fun fetchStatus(progress: DownloadProgress): DownloadStatus? {
            val hash = progress.downloadId?.takeIf { isDownloadHash(it) } ?: return null
            return (statusService.fetch(hash) as? DownloadStatusResult.Success)?.status
        }

        // Seerr's id can be ahead of Jellyfin or stale, only the user's own lookup proves it's playable
        private suspend fun isInJellyfin(itemId: UUID): Boolean =
            try {
                api.userLibraryApi.getItem(itemId)
                true
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.d(ex, "Item %s not in Jellyfin", itemId)
                false
            }

        // The poster can't come from Jellyfin here, it may not have the item yet
        private suspend fun createItem(
            item: DiscoverItem,
            posterPath: String?,
        ): DiscoverItem {
            val posterUrl = seerrService.createImageUrl(ImageType.PRIMARY, posterPath, null)
            return item.copy(posterUrl = posterUrl ?: item.posterUrl)
        }

        /**
         * A live download goes to its details, a row that is ready goes to the Jellyfin item and any
         * other to its Discover page
         */
        fun open(row: DownloadRow) {
            val destination =
                detailDestination(row)
                    ?: row.jellyfinItemId?.let {
                        Destination.MediaItem(itemId = it, type = row.type.baseItemKind ?: BaseItemKind.MOVIE)
                    }
                    ?: row.item?.let { Destination.DiscoveredItem(it) }
            destination?.let(navigationManager::navigateTo)
        }

        /**
         * Remove what a row is for, the row itself goes once [SeerrService.removedMedia] says so
         */
        fun remove(row: DownloadRow) {
            var start = false
            _state.update {
                start = row.requestId !in it.removing
                it.copy(removing = it.removing + row.requestId)
            }
            if (!start) return
            viewModelScope.launchIO {
                try {
                    val result = seerrService.removeRequest(row.type, row.tmdbId)
                    if (result != RemoveRequestResult.Removed) {
                        _state.update { it.copy(removeFailure = result) }
                    }
                } finally {
                    _state.update { it.copy(removing = it.removing - row.requestId) }
                }
            }
        }

        fun onRemoveFailureShown() {
            _state.update { it.copy(removeFailure = null) }
        }
    }

/**
 * The current user's requested movies and series and how far along each one is
 */
@Composable
fun DownloadsPage(
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var rowToRemove by remember { mutableStateOf<DownloadRow?>(null) }

    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose { viewModel.onPause() }
    }

    LaunchedEffect(state.removeFailure) {
        state.removeFailure?.let {
            showToast(context, context.getString(it.stringRes))
            viewModel.onRemoveFailureShown()
        }
    }

    // Leaving the page or the app cancels this, so nothing is fetched unless it can be seen
    val refreshing = state.refreshing
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, refreshing) {
        if (refreshing) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.refreshWhileVisible()
            }
        }
    }

    when (val rows = state.rows) {
        DataLoadingState.Pending,
        DataLoadingState.Loading,
        -> {
            LoadingPage(modifier)
        }

        is DataLoadingState.Error -> {
            DownloadsMessage(
                message = stringResource(R.string.downloads_load_error),
                detail = rows.exception?.localizedMessage,
                onRetry = viewModel::retry,
                modifier = modifier,
            )
        }

        is DataLoadingState.Success<List<DownloadRow>> -> {
            if (rows.data.isEmpty()) {
                DownloadsMessage(
                    message = stringResource(R.string.downloads_empty),
                    detail = null,
                    onRetry = viewModel::retry,
                    modifier = modifier,
                )
            } else {
                DownloadsList(
                    rows = rows.data,
                    state = state,
                    onClickRow = viewModel::open,
                    onClickRemove = { rowToRemove = it },
                    onLoadMore = viewModel::loadMore,
                    onRetryLoadMore = viewModel::retryLoadMore,
                    modifier = modifier,
                )
            }
        }
    }
    rowToRemove?.let { row ->
        RemoveRequestDialog(
            title = row.item?.title,
            onCancel = { rowToRemove = null },
            onConfirm = {
                rowToRemove = null
                viewModel.remove(row)
            },
        )
    }
}

@Composable
private fun DownloadsMessage(
    message: String,
    detail: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        modifier =
            modifier
                .fillMaxSize()
                .padding(32.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
        }
        TextButton(
            stringRes = R.string.retry,
            onClick = onRetry,
            modifier = Modifier.focusRequester(focusRequester),
        )
    }
}

@Composable
private fun DownloadsList(
    rows: List<DownloadRow>,
    state: DownloadsState,
    onClickRow: (DownloadRow) -> Unit,
    onClickRemove: (DownloadRow) -> Unit,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focusedRequestId by rememberSaveable { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()
    val restoredRow = remember { listState.firstVisibleItemIndex }
    val focusedRow = focusedRowIndex(rows, focusedRequestId, restoredRow)
    val focusRequester = remember { FocusRequester() }
    // Not saved, so that coming back to the page focuses the row it was left on
    var focusRestored by remember { mutableStateOf(false) }
    // The last row while the retry button has focus, otherwise null
    var rowBeforeRetry by remember { mutableStateOf<Int?>(null) }
    var previousRows by remember { mutableStateOf(rows) }

    // The row with focus can go while the page is showing, a remote would have nothing focused
    LaunchedEffect(rows) {
        rowAfterRemoval(previousRows, rows, focusedRequestId)?.let {
            focusedRequestId = it
            focusRestored = false
        }
        previousRows = rows
    }
    LaunchedEffect(focusedRow, rows.size) {
        if (focusedRow >= rows.size - ROWS_LOADED_AHEAD) onLoadMore.invoke()
    }
    // The button stays until a row has taken focus from it, or a remote would have nothing focused
    LaunchedEffect(state.loadMoreFailed) {
        val lastRow = rowBeforeRetry
        if (!state.loadMoreFailed && lastRow != null) {
            val firstNewRow = rows.getOrNull(rows.indexOfFirst { it.requestId == lastRow } + 1)
            focusedRequestId = (firstNewRow ?: rows.last()).requestId
            focusRestored = false
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        GridTitle(
            title = stringResource(R.string.downloads_title),
            modifier = Modifier.padding(top = 16.dp),
        )
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 40.dp),
            modifier =
                Modifier
                    .focusRestorer()
                    .weight(1f)
                    .fillMaxWidth(),
        ) {
            itemsIndexed(rows, key = { _, row -> row.requestId }) { index, row ->
                val restoreFocus = index == focusedRow && !focusRestored
                if (restoreFocus) {
                    LaunchedEffect(Unit) { focusRestored = focusRequester.tryRequestFocus("downloads") }
                }
                DownloadRowCard(
                    row = row,
                    removing = row.requestId in state.removing,
                    onClick = { onClickRow.invoke(row) },
                    onClickRemove = { onClickRemove.invoke(row) },
                    cardModifier = Modifier.ifElse(restoreFocus, Modifier.focusRequester(focusRequester)),
                    modifier = Modifier.onFocusChanged { if (it.hasFocus) focusedRequestId = row.requestId },
                )
            }
            val showRetry = state.loadMoreFailed || rowBeforeRetry != null
            if (state.loadingMore && !showRetry) {
                item(key = "loading") {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                        CircularProgress(Modifier.size(32.dp))
                    }
                }
            }
            if (showRetry) {
                item(key = "retry") {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                        // Stays the same button while loading so that it keeps focus
                        TextButton(
                            onClick = onRetryLoadMore,
                            modifier =
                                Modifier.onFocusChanged {
                                    rowBeforeRetry = if (it.hasFocus) rows.last().requestId else null
                                },
                        ) {
                            if (state.loadMoreFailed && !state.loadingMore) {
                                Text(text = stringResource(R.string.retry))
                            } else {
                                CircularProgress(Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A row with its removal, if any, as a second button after it. So OK on the row always does the
 * same thing and Right from the row is how to get to the button.
 *
 * @param cardModifier for the row itself, not the button
 * @param modifier for the row and the button together
 */
@Composable
private fun DownloadRowCard(
    row: DownloadRow,
    removing: Boolean,
    onClick: () -> Unit,
    onClickRemove: () -> Unit,
    modifier: Modifier = Modifier,
    cardModifier: Modifier = Modifier,
) {
    // A download can recover with the button focused, which it can't take with it
    var removeFocused by remember { mutableStateOf(false) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .height(ROW_HEIGHT),
    ) {
        DownloadRowContent(
            row = row,
            onClick = onClick,
            modifier =
                cardModifier
                    .weight(1f)
                    .fillMaxHeight(),
        )
        if (row.removable || removeFocused) {
            // Stays the same button while removing so that it keeps focus
            TextButton(
                onClick = { if (!removing) onClickRemove.invoke() },
                modifier = Modifier.onFocusChanged { removeFocused = it.isFocused },
            ) {
                if (removing) {
                    CircularProgress(Modifier.size(24.dp))
                } else {
                    Text(text = stringResource(R.string.remove_request))
                }
            }
        }
    }
}

@Composable
private fun DownloadRowContent(
    row: DownloadRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = row.progress
    val title = row.item?.title ?: stringResource(R.string.unknown)
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
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(8.dp),
        ) {
            ItemCardImage(
                imageUrl = row.item?.posterUrl,
                name = title,
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
                        .fillMaxHeight()
                        .aspectRatio(AspectRatios.TALL),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val seasons =
                    row.seasons.takeIf { it.isNotEmpty() }?.let {
                        pluralStringResource(R.plurals.download_seasons, it.size, it.joinToString(", "))
                    }
                listOfNotNull(row.item?.releaseDate?.year?.toString(), seasons)
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        Text(
                            text = it.joinToString("  •  "),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                Text(
                    text = stringResource(progress.state.stringRes),
                    style = MaterialTheme.typography.bodyMedium,
                )
                progress.fraction?.let { DownloadProgressBar(it) }
                DownloadProgressText(progress)
            }
            if (progress.state == DownloadState.READY) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                    )
                    Text(
                        text = stringResource(R.string.play),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

@Composable
internal fun DownloadProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
) {
    val color = LocalContentColor.current
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(height / 2))
                .background(color.copy(alpha = 0.25f)),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(color),
        )
    }
}

@Composable
internal fun DownloadProgressText(
    progress: DownloadProgress,
    modifier: Modifier = Modifier,
) {
    val percent =
        progress.fraction?.let {
            stringResource(R.string.download_percent, downloadPercent(it))
        }
    val timeLeft =
        progress.timeLeft?.let {
            stringResource(R.string.download_time_left, LocalResources.current.formatDuration(it))
        }
    val text = listOfNotNull(percent, timeLeft).joinToString("  •  ")
    if (text.isNotEmpty()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier,
        )
    }
}

@get:StringRes
internal val DownloadState.stringRes: Int
    get() =
        when (this) {
            DownloadState.PENDING_APPROVAL -> R.string.download_state_pending
            DownloadState.SEARCHING -> R.string.download_state_searching
            DownloadState.WAITING_FOR_RELEASE -> R.string.download_state_waiting_release
            DownloadState.QUEUED -> R.string.download_state_queued
            DownloadState.DOWNLOADING -> R.string.download_state_downloading
            DownloadState.PREPARING -> R.string.download_state_preparing
            DownloadState.READY -> R.string.download_state_ready
            DownloadState.FAILED -> R.string.download_state_failed
            DownloadState.DECLINED -> R.string.download_state_declined
            DownloadState.UNAVAILABLE -> R.string.download_state_unavailable
        }
