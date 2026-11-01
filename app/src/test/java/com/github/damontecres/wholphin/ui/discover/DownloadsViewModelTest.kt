package com.github.damontecres.wholphin.ui.discover

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.api.seerr.MoviesApi
import com.github.damontecres.wholphin.api.seerr.RequestApi
import com.github.damontecres.wholphin.api.seerr.SeerrApiClient
import com.github.damontecres.wholphin.api.seerr.TvApi
import com.github.damontecres.wholphin.api.seerr.model.DownloadingEpisode
import com.github.damontecres.wholphin.api.seerr.model.DownloadingItem
import com.github.damontecres.wholphin.api.seerr.model.MediaInfo
import com.github.damontecres.wholphin.api.seerr.model.MediaRequest
import com.github.damontecres.wholphin.api.seerr.model.MovieDetails
import com.github.damontecres.wholphin.api.seerr.model.PageInfo
import com.github.damontecres.wholphin.api.seerr.model.Season
import com.github.damontecres.wholphin.api.seerr.model.TvDetails
import com.github.damontecres.wholphin.api.seerr.model.UserUserIdRequestsGet200Response
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.DownloadState
import com.github.damontecres.wholphin.data.model.DownloadStatus
import com.github.damontecres.wholphin.data.model.RequestStatus
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.services.CurrentSeerr
import com.github.damontecres.wholphin.services.DownloadStatusError
import com.github.damontecres.wholphin.services.DownloadStatusResult
import com.github.damontecres.wholphin.services.DownloadStatusService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.RemoveRequestResult
import com.github.damontecres.wholphin.services.RemovedMedia
import com.github.damontecres.wholphin.services.SeerrServerRepository
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.services.SeerrUserConfig
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.successResponse
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import com.github.damontecres.wholphin.util.successValue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.UUID

private const val USER_ID = 7
private const val JELLYFIN_ID = "0123456789abcdef0123456789abcdef"
private val HASH = "abcdef0123".repeat(4)
private val jellyfinUuid = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private val requestApi = mockk<RequestApi>()
    private val moviesApi = mockk<MoviesApi>()
    private val tvApi = mockk<TvApi>()
    private val seerrApiClient = mockk<SeerrApiClient>()
    private val seerrService = mockk<SeerrService>()
    private val seerrServerRepository = mockk<SeerrServerRepository>()
    private val userLibraryApi = mockk<UserLibraryApi>()
    private val api = mockk<ApiClient>()
    private val navigationManager = mockk<NavigationManager>(relaxed = true)
    private val statusService = mockk<DownloadStatusService>()

    private val userId = MutableStateFlow<Int?>(USER_ID)
    private val currentSeerr = MutableStateFlow<CurrentSeerr?>(null)
    private val removedMedia = MutableSharedFlow<RemovedMedia>()
    private val movies = mutableMapOf<Int, MediaInfo>()
    private val movieFetches = mutableListOf<Int>()
    private var movieFailure: Exception? = null
    private val series = mutableMapOf<Int, MediaInfo>()
    private val seriesFetches = mutableListOf<Int>()

    @Before
    fun setUp() {
        WholphinDispatchers.configure(testDispatcher)
        every { seerrApiClient.requestApi } returns requestApi
        every { seerrApiClient.moviesApi } returns moviesApi
        every { seerrApiClient.tvApi } returns tvApi
        every { seerrService.api } returns seerrApiClient
        every { seerrServerRepository.currentUserId } returns userId
        every { seerrServerRepository.current } returns currentSeerr
        every { seerrService.removedMedia } returns removedMedia
        every { api.userLibraryApi } returns userLibraryApi

        coEvery { seerrService.createDiscoverItem(any<MovieDetails>()) } coAnswers {
            val movie = firstArg<MovieDetails>()
            DiscoverItem(
                id = movie.id!!,
                type = SeerrItemType.MOVIE,
                title = movie.title,
                subtitle = null,
                overview = null,
                availability = SeerrAvailability.UNKNOWN,
                releaseDate = null,
                posterUrl = "jellyfin-poster",
                backDropUrl = null,
                logoUrl = null,
                jellyfinItemId = null,
            )
        }
        coEvery { seerrService.createDiscoverItem(any<TvDetails>()) } coAnswers {
            val tv = firstArg<TvDetails>()
            DiscoverItem(
                id = tv.id!!,
                type = SeerrItemType.TV,
                title = tv.name,
                subtitle = null,
                overview = null,
                availability = SeerrAvailability.UNKNOWN,
                releaseDate = null,
                posterUrl = "jellyfin-poster",
                backDropUrl = null,
                logoUrl = null,
                jellyfinItemId = null,
            )
        }
        coEvery { seerrService.createImageUrl(any(), any(), any(), any(), any()) } returns "tmdb-poster"
        coEvery { tvApi.tvTvIdGet(any(), any()) } coAnswers {
            val id = firstArg<Int>()
            seriesFetches.add(id)
            movieFailure?.let { throw it }
            TvDetails(id = id, name = "Series $id", mediaInfo = series[id])
        }
        coEvery { moviesApi.movieMovieIdGet(any(), any()) } coAnswers {
            val id = firstArg<Int>()
            movieFetches.add(id)
            movieFailure?.let { throw it }
            MovieDetails(id = id, title = "Movie $id", mediaInfo = movies[id])
        }
    }

    @After
    fun tearDown() {
        WholphinDispatchers.reset()
    }

    private fun createViewModel() =
        DownloadsViewModel(
            api = api,
            seerrService = seerrService,
            seerrServerRepository = seerrServerRepository,
            statusService = statusService,
            navigationManager = navigationManager,
        )

    private fun request(
        id: Int,
        media: MediaInfo = movies[id] ?: MediaInfo(tmdbId = id),
        status: RequestStatus = RequestStatus.APPROVED,
        is4k: Boolean = false,
    ) = MediaRequest(id = id, status = status.status, media = media.copy(tmdbId = id), is4k = is4k)

    private fun seriesRequest(
        id: Int,
        seasons: List<Int> = listOf(1),
        status: RequestStatus = RequestStatus.APPROVED,
    ) = MediaRequest(
        id = id,
        status = status.status,
        type = "tv",
        media = (series[id] ?: MediaInfo()).copy(tmdbId = id),
        seasons = seasons.map { Season(seasonNumber = it) },
    )

    private fun seriesMedia(
        id: Int,
        availability: SeerrAvailability,
        seasons: Map<Int, SeerrAvailability> = emptyMap(),
        downloadStatus: List<DownloadingItem> = emptyList(),
        jellyfinMediaId: String? = null,
    ) {
        series[id] =
            MediaInfo(
                tmdbId = id,
                status = availability.status,
                seasons = seasons.map { (number, status) -> Season(seasonNumber = number, status = status.status) },
                downloadStatus = downloadStatus,
                jellyfinMediaId = jellyfinMediaId,
                requests = listOf(MediaRequest(id = id, status = RequestStatus.APPROVED.status)),
            )
    }

    private fun media(
        id: Int,
        status: RequestStatus = RequestStatus.APPROVED,
        availability: SeerrAvailability = SeerrAvailability.PROCESSING,
        downloadStatus: List<DownloadingItem> = emptyList(),
        jellyfinMediaId: String? = null,
    ): MediaInfo =
        MediaInfo(
            tmdbId = id,
            status = availability.status,
            downloadStatus = downloadStatus,
            jellyfinMediaId = jellyfinMediaId,
            requests = listOf(MediaRequest(id = id, status = status.status)),
        ).also { movies[id] = it }

    private fun requests(
        vararg requests: MediaRequest,
        total: Int = requests.size,
    ) {
        coEvery {
            requestApi.requestGet(any(), any(), any(), any(), any(), any(), any())
        } returns
            UserUserIdRequestsGet200Response(
                pageInfo = PageInfo(results = total),
                results = requests.toList(),
            )
    }

    private fun downloadRows(ids: Iterable<Int>) =
        ids.map {
            DownloadRow(
                requestId = it,
                tmdbId = it,
                is4k = false,
                requestStatus = RequestStatus.APPROVED,
            )
        }

    private fun DownloadsViewModel.rows() = state.value.rows.successValue!!

    private fun DownloadsViewModel.rowState(index: Int = 0) = rows()[index].progress.state

    private fun TestScope.startRefreshing(viewModel: DownloadsViewModel) =
        launch(testDispatcher) { viewModel.refreshWhileVisible() }

    @Test
    fun `asks for this user's newest requests of any type`() =
        runTest(testDispatcher) {
            requests()
            createViewModel()
            advanceUntilIdle()

            coVerify(exactly = 1) {
                requestApi.requestGet(
                    take = DOWNLOADS_PAGE_SIZE,
                    skip = 0,
                    filter = null,
                    sort = RequestApi.SortRequestGet.ADDED,
                    sortDirection = RequestApi.SortDirectionRequestGet.DESC,
                    requestedBy = USER_ID,
                    mediaType = RequestApi.MediaTypeRequestGet.ALL,
                )
            }
        }

    @Test
    fun `a series request reads the series, a movie can have the same ID`() =
        runTest(testDispatcher) {
            seriesMedia(1, SeerrAvailability.PROCESSING)
            requests(seriesRequest(1, seasons = listOf(2, 1)))
            val viewModel = createViewModel()
            advanceUntilIdle()

            val row = viewModel.rows().single()
            assertEquals(SeerrItemType.TV, row.type)
            assertEquals(listOf(1, 2), row.seasons)
            assertEquals("Series 1", row.item?.title)
            assertEquals(SeerrItemType.TV, row.item?.type)
            assertEquals("tmdb-poster", row.item?.posterUrl)
            assertEquals(emptyList<Int>(), movieFetches)
            assertEquals(listOf(1), seriesFetches)
        }

    @Test
    fun `a movie request has no seasons`() =
        runTest(testDispatcher) {
            requests(request(1))
            val viewModel = createViewModel()
            advanceUntilIdle()

            val row = viewModel.rows().single()
            assertEquals(SeerrItemType.MOVIE, row.type)
            assertEquals(emptyList<Int>(), row.seasons)
            assertEquals(emptyList<Int>(), seriesFetches)
        }

    @Test
    fun `a series that can't be fetched still gets its state`() =
        runTest(testDispatcher) {
            movieFailure = IOException("offline")
            seriesMedia(1, SeerrAvailability.PROCESSING)
            requests(seriesRequest(1))
            val viewModel = createViewModel()
            advanceUntilIdle()

            val row = viewModel.rows().single()
            assertEquals(null, row.item)
            assertEquals(DownloadState.SEARCHING, row.progress.state)
        }

    @Test
    fun `a refresh reads the series too`() =
        runTest(testDispatcher) {
            seriesMedia(1, SeerrAvailability.PROCESSING)
            requests(seriesRequest(1))
            val viewModel = createViewModel()
            advanceUntilIdle()
            assertEquals(DownloadState.SEARCHING, viewModel.rowState())

            seriesMedia(
                1,
                SeerrAvailability.PROCESSING,
                downloadStatus = listOf(DownloadingItem(status = "downloading")),
            )
            val job = startRefreshing(viewModel)
            advanceTimeBy(15_001)
            job.cancelAndJoin()

            assertEquals(DownloadState.DOWNLOADING, viewModel.rowState())
            assertEquals(emptyList<Int>(), movieFetches)
        }

    @Test
    fun `a series in Jellyfin opens as a series`() =
        runTest(testDispatcher) {
            seriesMedia(
                1,
                SeerrAvailability.AVAILABLE,
                seasons = mapOf(1 to SeerrAvailability.AVAILABLE),
                jellyfinMediaId = JELLYFIN_ID,
            )
            coEvery { userLibraryApi.getItem(jellyfinUuid) } returns
                successResponse(mockk<BaseItemDto>())
            requests(seriesRequest(1))
            val viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(DownloadState.READY, viewModel.rowState())
            viewModel.open(viewModel.rows().single())
            verify {
                navigationManager.navigateTo(Destination.MediaItem(jellyfinUuid, BaseItemKind.SERIES))
            }
        }

    @Test
    fun `an airing season counts as ready once it is partly in`() =
        runTest(testDispatcher) {
            seriesMedia(
                1,
                SeerrAvailability.PARTIALLY_AVAILABLE,
                seasons = mapOf(1 to SeerrAvailability.PARTIALLY_AVAILABLE),
                jellyfinMediaId = JELLYFIN_ID,
            )
            coEvery { userLibraryApi.getItem(jellyfinUuid) } returns
                successResponse(mockk<BaseItemDto>())
            requests(seriesRequest(1))
            val viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(DownloadState.READY, viewModel.rowState())
        }

    @Test
    fun `a partly imported season keeps downloading until its queue is empty`() =
        runTest(testDispatcher) {
            coEvery { userLibraryApi.getItem(jellyfinUuid) } returns
                successResponse(mockk<BaseItemDto>())
            val partial = mapOf(1 to SeerrAvailability.AVAILABLE, 2 to SeerrAvailability.PARTIALLY_AVAILABLE)
            seriesMedia(
                1,
                SeerrAvailability.PARTIALLY_AVAILABLE,
                seasons = partial,
                downloadStatus =
                    listOf(
                        DownloadingItem(status = "downloading", downloadId = HASH, episode = DownloadingEpisode(seasonNumber = 2)),
                    ),
                jellyfinMediaId = JELLYFIN_ID,
            )
            requests(seriesRequest(1, seasons = listOf(2)))
            val viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(DownloadState.DOWNLOADING, viewModel.rowState())
            assertTrue(viewModel.state.value.refreshing)
            assertEquals(HASH, detailDestination(viewModel.rows().single(), enabled = true)?.downloadId)

            seriesMedia(1, SeerrAvailability.PARTIALLY_AVAILABLE, seasons = partial, jellyfinMediaId = JELLYFIN_ID)
            val job = startRefreshing(viewModel)
            advanceTimeBy(15_001)
            job.cancelAndJoin()

            assertEquals(DownloadState.READY, viewModel.rowState())
        }

    @Test
    fun `a request doesn't show another season's download`() =
        runTest(testDispatcher) {
            seriesMedia(
                1,
                SeerrAvailability.PROCESSING,
                downloadStatus =
                    listOf(
                        DownloadingItem(status = "downloading", downloadId = HASH, episode = DownloadingEpisode(seasonNumber = 1)),
                    ),
            )
            requests(seriesRequest(1, seasons = listOf(2)))
            val viewModel = createViewModel()
            advanceUntilIdle()

            val row = viewModel.rows().single()
            assertEquals(DownloadState.SEARCHING, row.progress.state)
            assertEquals(null, row.progress.downloadId)
            assertFalse(row.seenInQueue)
        }

    @Test
    fun `a series isn't ready until its seasons can be read`() =
        runTest(testDispatcher) {
            coEvery { userLibraryApi.getItem(jellyfinUuid) } returns
                successResponse(mockk<BaseItemDto>())
            // The media on a request comes without its seasons
            series[1] =
                MediaInfo(status = SeerrAvailability.PARTIALLY_AVAILABLE.status, jellyfinMediaId = JELLYFIN_ID)
            requests(seriesRequest(1, seasons = listOf(2)))
            movieFailure = IOException("offline")
            val viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(DownloadState.PREPARING, viewModel.rowState())
            assertTrue(viewModel.state.value.refreshing)

            movieFailure = null
            seriesMedia(
                1,
                SeerrAvailability.PARTIALLY_AVAILABLE,
                seasons = mapOf(1 to SeerrAvailability.AVAILABLE, 2 to SeerrAvailability.PROCESSING),
                jellyfinMediaId = JELLYFIN_ID,
            )
            val job = startRefreshing(viewModel)
            advanceTimeBy(15_001)
            job.cancelAndJoin()

            assertEquals(DownloadState.SEARCHING, viewModel.rowState())
        }

    @Test
    fun `a new season of a series already in Jellyfin isn't ready until that season is`() =
        runTest(testDispatcher) {
            coEvery { userLibraryApi.getItem(jellyfinUuid) } returns
                successResponse(mockk<BaseItemDto>())
            seriesMedia(
                1,
                SeerrAvailability.PARTIALLY_AVAILABLE,
                seasons = mapOf(1 to SeerrAvailability.AVAILABLE, 2 to SeerrAvailability.PROCESSING),
                jellyfinMediaId = JELLYFIN_ID,
            )
            requests(seriesRequest(1, seasons = listOf(2)))
            val viewModel = createViewModel()
            advanceUntilIdle()
            assertEquals(DownloadState.SEARCHING, viewModel.rowState())
            assertTrue(viewModel.state.value.refreshing)

            seriesMedia(
                1,
                SeerrAvailability.PARTIALLY_AVAILABLE,
                seasons = mapOf(1 to SeerrAvailability.AVAILABLE, 2 to SeerrAvailability.PROCESSING),
                downloadStatus = listOf(DownloadingItem(status = "downloading")),
                jellyfinMediaId = JELLYFIN_ID,
            )
            val job = startRefreshing(viewModel)
            advanceTimeBy(15_001)
            assertEquals(DownloadState.DOWNLOADING, viewModel.rowState())

            seriesMedia(
                1,
                SeerrAvailability.AVAILABLE,
                seasons = mapOf(1 to SeerrAvailability.AVAILABLE, 2 to SeerrAvailability.AVAILABLE),
                jellyfinMediaId = JELLYFIN_ID,
            )
            advanceTimeBy(15_001)
            job.cancelAndJoin()
            assertEquals(DownloadState.READY, viewModel.rowState())
        }

    @Test
    fun `blocklisted series stay that way while a season is missing`() =
        runTest(testDispatcher) {
            seriesMedia(1, SeerrAvailability.BLOCKLISTED, seasons = mapOf(1 to SeerrAvailability.UNKNOWN))
            requests(seriesRequest(1))
            val viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(DownloadState.UNAVAILABLE, viewModel.rowState())
        }

    @Test
    fun `no requests is an empty list`() =
        runTest(testDispatcher) {
            requests()
            val viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(emptyList<DownloadRow>(), viewModel.rows())
            assertFalse(viewModel.state.value.refreshing)
        }

    @Test
    fun `a failed load can be retried`() =
        runTest(testDispatcher) {
            coEvery {
                requestApi.requestGet(any(), any(), any(), any(), any(), any(), any())
            } throws IOException("offline")
            val viewModel = createViewModel()
            advanceUntilIdle()
            assertTrue(viewModel.state.value.rows is DataLoadingState.Error)

            requests(request(1))
            viewModel.retry()
            advanceUntilIdle()

            assertEquals(1, viewModel.rows().size)
        }

    @Test
    fun `no Seerr user is an error, not an endless spinner`() =
        runTest(testDispatcher) {
            userId.value = null
            val viewModel = createViewModel()
            advanceUntilIdle()

            assertTrue(viewModel.state.value.rows is DataLoadingState.Error)
            coVerify(exactly = 0) {
                requestApi.requestGet(any(), any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `a movie that can't be fetched still gets its state`() =
        runTest(testDispatcher) {
            movieFailure = IOException("offline")
            requests(request(1, MediaInfo(status = SeerrAvailability.PROCESSING.status)))
            val viewModel = createViewModel()
            advanceUntilIdle()

            val row = viewModel.rows().single()
            assertEquals(null, row.item)
            assertEquals(DownloadState.SEARCHING, row.progress.state)
        }

    @Test
    fun `the poster doesn't rely on Jellyfin`() =
        runTest(testDispatcher) {
            requests(request(1))
            val viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals("tmdb-poster", viewModel.rows().single().item?.posterUrl)
            coVerify { seerrService.createImageUrl(ImageType.PRIMARY, any(), null, any(), any()) }
        }

    @Test
    fun `ready needs the item from the user's Jellyfin`() =
        runTest(testDispatcher) {
            media(1, RequestStatus.COMPLETED, SeerrAvailability.AVAILABLE, jellyfinMediaId = JELLYFIN_ID)
            coEvery { userLibraryApi.getItem(jellyfinUuid) } throws ApiClientException("Not found")
            requests(request(1))
            val viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(DownloadState.PREPARING, viewModel.rowState())
            viewModel.open(viewModel.rows().single())
            verify { navigationManager.navigateTo(match { it is Destination.DiscoveredItem }) }

            coEvery { userLibraryApi.getItem(jellyfinUuid) } returns
                successResponse(mockk<BaseItemDto>())
            val job = startRefreshing(viewModel)
            advanceTimeBy(15_001)
            job.cancelAndJoin()

            assertEquals(DownloadState.READY, viewModel.rowState())
            viewModel.open(viewModel.rows().single())
            verify {
                navigationManager.navigateTo(Destination.MediaItem(jellyfinUuid, BaseItemKind.MOVIE))
            }
        }

    @Test
    fun `a request for the regular version ignores the 4K id`() =
        runTest(testDispatcher) {
            movies[1] =
                MediaInfo(
                    tmdbId = 1,
                    status = SeerrAvailability.PROCESSING.status,
                    jellyfinMediaId4k = JELLYFIN_ID,
                    requests = listOf(MediaRequest(id = 1, status = RequestStatus.APPROVED.status)),
                )
            coEvery { userLibraryApi.getItem(jellyfinUuid) } returns
                successResponse(mockk<BaseItemDto>())
            requests(request(1))
            val viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(DownloadState.SEARCHING, viewModel.rowState())
            coVerify(exactly = 0) { userLibraryApi.getItem(any()) }
        }

    @Test
    fun `a 4K request reads the 4K fields`() =
        runTest(testDispatcher) {
            movies[1] =
                MediaInfo(
                    tmdbId = 1,
                    status = SeerrAvailability.AVAILABLE.status,
                    status4k = SeerrAvailability.PROCESSING.status,
                    jellyfinMediaId = JELLYFIN_ID,
                    downloadStatus4k = listOf(DownloadingItem(status = "downloading")),
                    requests = listOf(MediaRequest(id = 1, status = RequestStatus.APPROVED.status)),
                )
            requests(request(1, is4k = true))
            val viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(DownloadState.DOWNLOADING, viewModel.rowState())
        }

    @Test
    fun `only rows which can change are refreshed`() =
        runTest(testDispatcher) {
            media(1)
            media(2, RequestStatus.DECLINED)
            requests(request(1), request(2, status = RequestStatus.DECLINED))
            val viewModel = createViewModel()
            advanceUntilIdle()
            movieFetches.clear()

            val job = startRefreshing(viewModel)
            advanceTimeBy(15_001)
            job.cancelAndJoin()

            assertEquals(listOf(1), movieFetches)
        }

    @Test
    fun `a finished row is refreshed until it has its details`() =
        runTest(testDispatcher) {
            media(1, RequestStatus.DECLINED)
            requests(request(1, status = RequestStatus.DECLINED))
            movieFailure = IOException("offline")
            val viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(null, viewModel.rows().single().item)
            assertEquals(DownloadState.DECLINED, viewModel.rowState())
            assertTrue(viewModel.state.value.refreshing)
            movieFetches.clear()

            val job = startRefreshing(viewModel)
            advanceTimeBy(15_001)
            assertEquals(1, movieFetches.size)
            assertEquals(null, viewModel.rows().single().item)
            assertTrue(viewModel.state.value.refreshing)
            assertTrue(viewModel.state.value.refreshFailed)

            // Failing still backs off, the next try is 30 seconds later instead of 15
            movieFailure = null
            advanceTimeBy(20_000)
            assertEquals(1, movieFetches.size)
            advanceTimeBy(10_001)
            assertEquals(2, movieFetches.size)

            assertEquals("Movie 1", viewModel.rows().single().item?.title)
            assertEquals(DownloadState.DECLINED, viewModel.rowState())
            assertFalse(viewModel.state.value.refreshing)
            assertFalse(viewModel.state.value.refreshFailed)

            viewModel.open(viewModel.rows().single())
            verify { navigationManager.navigateTo(match { it is Destination.DiscoveredItem }) }

            // Nothing is left to fetch, so the timer ends
            advanceUntilIdle()
            assertTrue(job.isCompleted)
            assertEquals(2, movieFetches.size)
        }

    @Test
    fun `refresh updates a row from GET movie, never GET request`() =
        runTest(testDispatcher) {
            media(1)
            requests(request(1))
            val viewModel = createViewModel()
            advanceUntilIdle()
            assertEquals(DownloadState.SEARCHING, viewModel.rowState())

            media(1, downloadStatus = listOf(DownloadingItem(status = "downloading")))
            val job = startRefreshing(viewModel)
            advanceTimeBy(15_001)
            job.cancelAndJoin()

            assertEquals(DownloadState.DOWNLOADING, viewModel.rowState())
            coVerify(exactly = 1) {
                requestApi.requestGet(any(), any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `refresh stops once nothing can change`() =
        runTest(testDispatcher) {
            media(1)
            requests(request(1))
            val viewModel = createViewModel()
            advanceUntilIdle()

            media(1, RequestStatus.DECLINED)
            val job = startRefreshing(viewModel)
            advanceTimeBy(15_001)
            assertEquals(DownloadState.DECLINED, viewModel.rowState())
            movieFetches.clear()

            advanceUntilIdle()

            assertTrue(job.isCompleted)
            assertEquals(emptyList<Int>(), movieFetches)
        }

    @Test
    fun `cancelling stops refreshing, as leaving the page does`() =
        runTest(testDispatcher) {
            media(1)
            requests(request(1))
            val viewModel = createViewModel()
            advanceUntilIdle()
            movieFetches.clear()

            val job = startRefreshing(viewModel)
            advanceTimeBy(5_000)
            job.cancelAndJoin()
            advanceTimeBy(60_000)

            assertEquals(emptyList<Int>(), movieFetches)
        }

    @Test
    fun `refresh backs off after errors and recovers`() =
        runTest(testDispatcher) {
            media(1)
            requests(request(1))
            val viewModel = createViewModel()
            advanceUntilIdle()
            movieFetches.clear()
            movieFailure = IOException("offline")

            val job = startRefreshing(viewModel)
            advanceTimeBy(15_001)
            assertEquals(1, movieFetches.size)
            assertTrue(viewModel.state.value.refreshFailed)

            // The next try is 30 seconds later instead of 15
            advanceTimeBy(20_000)
            assertEquals(1, movieFetches.size)
            advanceTimeBy(10_001)
            assertEquals(2, movieFetches.size)

            movieFailure = null
            advanceTimeBy(60_001)
            assertEquals(3, movieFetches.size)
            assertFalse(viewModel.state.value.refreshFailed)

            // Back to every 15 seconds
            advanceTimeBy(15_001)
            job.cancelAndJoin()
            assertEquals(4, movieFetches.size)
        }

    @Test
    fun `a refresh only touches a limited number of rows`() =
        runTest(testDispatcher) {
            val ids = (1..DOWNLOADS_PAGE_SIZE).toList()
            ids.forEach { media(it) }
            requests(*ids.map { request(it) }.toTypedArray())
            val viewModel = createViewModel()
            advanceUntilIdle()
            movieFetches.clear()

            val job = startRefreshing(viewModel)
            advanceTimeBy(15_001)
            assertEquals(MAX_ROWS_PER_REFRESH, movieFetches.size)

            // The rest get their turn next
            advanceTimeBy(15_001)
            job.cancelAndJoin()
            assertEquals(ids.toSet(), movieFetches.toSet())
        }

    @Test
    fun `a removed request becomes unavailable and stops refreshing`() =
        runTest(testDispatcher) {
            media(1)
            requests(request(1))
            val viewModel = createViewModel()
            advanceUntilIdle()

            movies[1] = movies[1]!!.copy(requests = emptyList())
            val job = startRefreshing(viewModel)
            advanceTimeBy(15_001)
            job.cancelAndJoin()

            assertEquals(DownloadState.UNAVAILABLE, viewModel.rowState())
            assertFalse(viewModel.state.value.refreshing)
        }

    @Test
    fun `deleted media follows its download all the way to ready`() =
        runTest(testDispatcher) {
            media(
                1,
                RequestStatus.COMPLETED,
                SeerrAvailability.DELETED,
                downloadStatus = listOf(DownloadingItem(status = "downloading")),
            )
            coEvery { userLibraryApi.getItem(jellyfinUuid) } returns
                successResponse(mockk<BaseItemDto>())
            requests(request(1, status = RequestStatus.COMPLETED))
            val viewModel = createViewModel()
            advanceUntilIdle()
            assertEquals(DownloadState.DOWNLOADING, viewModel.rowState())

            // Imported, but Seerr doesn't know until a scan
            media(1, RequestStatus.COMPLETED, SeerrAvailability.DELETED)
            val job = startRefreshing(viewModel)
            advanceTimeBy(15_001)
            assertEquals(DownloadState.PREPARING, viewModel.rowState())
            assertTrue(viewModel.state.value.refreshing)

            media(1, RequestStatus.COMPLETED, SeerrAvailability.AVAILABLE, jellyfinMediaId = JELLYFIN_ID)
            advanceTimeBy(15_001)
            job.cancelAndJoin()
            assertEquals(DownloadState.READY, viewModel.rowState())
        }

    @Test
    fun `a failed grab of deleted media is followed by the next one`() =
        runTest(testDispatcher) {
            media(
                1,
                availability = SeerrAvailability.DELETED,
                downloadStatus = listOf(DownloadingItem(status = "downloading")),
            )
            requests(request(1))
            val viewModel = createViewModel()
            advanceUntilIdle()

            media(
                1,
                availability = SeerrAvailability.DELETED,
                downloadStatus = listOf(DownloadingItem(status = "failed")),
            )
            val job = startRefreshing(viewModel)
            advanceTimeBy(15_001)
            assertEquals(DownloadState.FAILED, viewModel.rowState())

            media(
                1,
                availability = SeerrAvailability.DELETED,
                downloadStatus = listOf(DownloadingItem(status = "downloading")),
            )
            advanceTimeBy(15_001)
            job.cancelAndJoin()
            assertEquals(DownloadState.DOWNLOADING, viewModel.rowState())
        }

    @Test
    fun `more pages load from where the last one ended`() =
        runTest(testDispatcher) {
            val firstPage = (1..DOWNLOADS_PAGE_SIZE).map { request(it) }.toTypedArray()
            requests(*firstPage, total = DOWNLOADS_PAGE_SIZE + 1)
            val viewModel = createViewModel()
            advanceUntilIdle()
            assertTrue(viewModel.state.value.hasMore)

            // Repeats a row, as when a new request has moved the rows along
            requests(request(DOWNLOADS_PAGE_SIZE), request(100), total = DOWNLOADS_PAGE_SIZE + 2)
            viewModel.loadMore()
            advanceUntilIdle()

            coVerify {
                requestApi.requestGet(
                    take = DOWNLOADS_PAGE_SIZE,
                    skip = DOWNLOADS_PAGE_SIZE,
                    filter = any(),
                    sort = any(),
                    sortDirection = any(),
                    requestedBy = USER_ID,
                    mediaType = any(),
                )
            }
            assertEquals(DOWNLOADS_PAGE_SIZE + 1, viewModel.rows().size)
            assertFalse(viewModel.state.value.hasMore)
        }

    @Test
    fun `a failed page waits for retry`() =
        runTest(testDispatcher) {
            val firstPage = (1..DOWNLOADS_PAGE_SIZE).map { request(it) }.toTypedArray()
            requests(*firstPage, total = DOWNLOADS_PAGE_SIZE + 1)
            val viewModel = createViewModel()
            advanceUntilIdle()

            coEvery {
                requestApi.requestGet(any(), any(), any(), any(), any(), any(), any())
            } throws IOException("offline")
            viewModel.loadMore()
            advanceUntilIdle()
            assertTrue(viewModel.state.value.loadMoreFailed)
            assertEquals(DOWNLOADS_PAGE_SIZE, viewModel.rows().size)

            // Focus moving must not hammer a failing server
            viewModel.loadMore()
            advanceUntilIdle()
            coVerify(exactly = 2) {
                requestApi.requestGet(any(), any(), any(), any(), any(), any(), any())
            }

            requests(request(100), total = DOWNLOADS_PAGE_SIZE + 1)
            viewModel.retryLoadMore()
            advanceUntilIdle()

            assertFalse(viewModel.state.value.loadMoreFailed)
            assertEquals(DOWNLOADS_PAGE_SIZE + 1, viewModel.rows().size)
        }

    @Test
    fun `the retry button stays until a retry worked`() =
        runTest(testDispatcher) {
            val firstPage = (1..DOWNLOADS_PAGE_SIZE).map { request(it) }.toTypedArray()
            requests(*firstPage, total = DOWNLOADS_PAGE_SIZE + 1)
            val viewModel = createViewModel()
            advanceUntilIdle()

            coEvery {
                requestApi.requestGet(any(), any(), any(), any(), any(), any(), any())
            } throws IOException("offline")
            viewModel.loadMore()
            advanceUntilIdle()
            assertTrue(viewModel.state.value.loadMoreFailed)
            assertFalse(viewModel.state.value.loadingMore)

            // Failing again leaves it there
            viewModel.retryLoadMore()
            assertTrue(viewModel.state.value.loadMoreFailed)
            assertTrue(viewModel.state.value.loadingMore)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.loadMoreFailed)
            assertFalse(viewModel.state.value.loadingMore)

            requests(request(100), total = DOWNLOADS_PAGE_SIZE + 1)
            viewModel.retryLoadMore()
            // Pressing it again while loading does nothing
            viewModel.retryLoadMore()
            assertTrue(viewModel.state.value.loadMoreFailed)
            assertTrue(viewModel.state.value.loadingMore)
            advanceUntilIdle()

            assertFalse(viewModel.state.value.loadMoreFailed)
            assertFalse(viewModel.state.value.loadingMore)
            coVerify(exactly = 4) {
                requestApi.requestGet(any(), any(), any(), any(), any(), any(), any())
            }
        }

    private fun TestScope.openedPage(vararg requests: MediaRequest): DownloadsViewModel {
        requests(*requests)
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.onResume()
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `opening the page doesn't fetch the requests twice`() =
        runTest(testDispatcher) {
            openedPage(request(1))

            coVerify(exactly = 1) {
                requestApi.requestGet(any(), any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `coming back adds new requests to the top`() =
        runTest(testDispatcher) {
            media(1)
            media(2)
            val viewModel = openedPage(request(2), request(1))
            val existing = viewModel.rows()
            movieFetches.clear()

            media(3)
            requests(request(3), request(2), request(1))
            viewModel.onResume()
            advanceUntilIdle()

            assertEquals(listOf(3, 2, 1), viewModel.rows().map { it.requestId })
            assertEquals(existing, viewModel.rows().drop(1))
            assertEquals(3, viewModel.state.value.fetched)
            // A page of rows costs a lot on weak Wi-Fi, only the new one is fetched
            assertEquals(listOf(3), movieFetches)
            coVerify(exactly = 2) {
                requestApi.requestGet(
                    take = DOWNLOADS_PAGE_SIZE,
                    skip = 0,
                    filter = any(),
                    sort = any(),
                    sortDirection = any(),
                    requestedBy = USER_ID,
                    mediaType = any(),
                )
            }
        }

    @Test
    fun `a removed request doesn't pull an old one to the top or skip one`() =
        runTest(testDispatcher) {
            val firstPage = (50 downTo 31).toList()
            requests(*firstPage.map { request(it) }.toTypedArray(), total = 50)
            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.onResume()
            advanceUntilIdle()
            movieFetches.clear()

            val shifted = firstPage.filter { it != 45 } + 30
            requests(*shifted.map { request(it) }.toTypedArray(), total = 49)
            viewModel.onResume()
            advanceUntilIdle()

            assertEquals(firstPage, viewModel.rows().map { it.requestId })
            assertEquals(emptyList<Int>(), movieFetches)
            assertEquals(19, viewModel.state.value.fetched)

            // Its row stays, which must not count as another removal
            viewModel.onResume()
            advanceUntilIdle()
            assertEquals(19, viewModel.state.value.fetched)

            requests(request(30), request(29), total = 49)
            viewModel.loadMore()
            advanceUntilIdle()

            coVerify {
                requestApi.requestGet(
                    take = DOWNLOADS_PAGE_SIZE,
                    skip = 19,
                    filter = any(),
                    sort = any(),
                    sortDirection = any(),
                    requestedBy = USER_ID,
                    mediaType = any(),
                )
            }
            assertEquals(firstPage + listOf(30, 29), viewModel.rows().map { it.requestId })
        }

    @Test
    fun `a new request and a removed one on the same page`() =
        runTest(testDispatcher) {
            val viewModel = openedPage(request(3), request(2), request(1))

            requests(request(4), request(3), request(1))
            viewModel.onResume()
            advanceUntilIdle()

            assertEquals(listOf(4, 3, 2, 1), viewModel.rows().map { it.requestId })
            assertEquals(3, viewModel.state.value.fetched)
        }

    @Test
    fun `only rows above the oldest one on the page can be told to be removed`() {
        val rows = downloadRows(5 downTo 1)

        assertEquals(setOf(4), removedRequests(rows, setOf(5, 3)))
        assertEquals(emptySet<Int>(), removedRequests(rows, setOf(6, 5, 4)))
        assertEquals(emptySet<Int>(), removedRequests(rows, setOf(9, 8)))
        assertEquals(emptySet<Int>(), removedRequests(rows, emptySet()))
    }

    @Test
    fun `leaving the page drops the new requests being fetched`() =
        runTest(testDispatcher) {
            media(1)
            val viewModel = openedPage(request(1))
            media(2)
            coEvery {
                requestApi.requestGet(any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                delay(1_000)
                UserUserIdRequestsGet200Response(
                    pageInfo = PageInfo(results = 2),
                    results = listOf(request(2), request(1)),
                )
            }

            viewModel.onResume()
            advanceTimeBy(500)
            viewModel.onPause()
            advanceUntilIdle()
            assertEquals(listOf(1), viewModel.rows().map { it.requestId })

            // Coming back fetches them again
            viewModel.onResume()
            advanceUntilIdle()
            assertEquals(listOf(2, 1), viewModel.rows().map { it.requestId })
        }

    @Test
    fun `focus stays on a row the list has composed`() {
        val rows = downloadRows(1..20)

        // Nothing focused yet
        assertEquals(0, focusedRowIndex(rows, focusedRequestId = null, restoredRow = 0))
        // Follows the request, wherever it has moved to
        assertEquals(4, focusedRowIndex(rows, focusedRequestId = 5, restoredRow = 15))
        // The process was killed with focus on a row of a later page
        assertEquals(19, focusedRowIndex(rows, focusedRequestId = 99, restoredRow = 23))
        // Or on a row which has since been removed
        assertEquals(3, focusedRowIndex(rows, focusedRequestId = 99, restoredRow = 3))
    }

    @Test
    fun `coming back with nothing new changes nothing`() =
        runTest(testDispatcher) {
            media(1)
            val viewModel = openedPage(request(1))
            val before = viewModel.state.value
            movieFetches.clear()

            viewModel.onResume()
            advanceUntilIdle()

            assertSame(before, viewModel.state.value)
            assertEquals(emptyList<Int>(), movieFetches)
            coVerify(exactly = 2) {
                requestApi.requestGet(any(), any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `failing to fetch on coming back keeps the rows`() =
        runTest(testDispatcher) {
            media(1)
            val viewModel = openedPage(request(1))
            val before = viewModel.state.value

            coEvery {
                requestApi.requestGet(any(), any(), any(), any(), any(), any(), any())
            } throws IOException("offline")
            viewModel.onResume()
            advanceUntilIdle()

            assertSame(before, viewModel.state.value)
        }

    @Test
    fun `resuming again while fetching only fetches once`() =
        runTest(testDispatcher) {
            media(1)
            val viewModel = openedPage(request(1))

            viewModel.onResume()
            viewModel.onResume()
            advanceUntilIdle()

            coVerify(exactly = 2) {
                requestApi.requestGet(any(), any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `coming back to a failed load leaves the retry to the user`() =
        runTest(testDispatcher) {
            coEvery {
                requestApi.requestGet(any(), any(), any(), any(), any(), any(), any())
            } throws IOException("offline")
            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.onResume()
            viewModel.onResume()
            advanceUntilIdle()

            assertTrue(viewModel.state.value.rows is DataLoadingState.Error)
            coVerify(exactly = 1) {
                requestApi.requestGet(any(), any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `the timer never fetches new requests`() =
        runTest(testDispatcher) {
            media(1)
            val viewModel = openedPage(request(1))

            media(2)
            requests(request(2), request(1))
            val job = startRefreshing(viewModel)
            advanceTimeBy(60_001)
            job.cancelAndJoin()

            assertEquals(listOf(1), viewModel.rows().map { it.requestId })
            coVerify(exactly = 1) {
                requestApi.requestGet(any(), any(), any(), any(), any(), any(), any())
            }
        }

    private fun canManageRequests() {
        currentSeerr.value = mockk { every { config } returns SeerrUserConfig(id = USER_ID, permissions = 16) }
    }

    private fun failedMedia(id: Int) =
        media(id, status = RequestStatus.FAILURE, availability = SeerrAvailability.PENDING)
            .copy(id = id)
            .also { movies[id] = it }

    private fun downloadingMedia(id: Int) =
        media(id, downloadStatus = listOf(DownloadingItem(status = "downloading", downloadId = HASH)))
            .copy(id = id)
            .also { movies[id] = it }

    @Test
    fun `a failed row is offered removal`() =
        runTest(testDispatcher) {
            assumeTrue(BuildConfig.REMOVE_REQUEST_ENABLED)
            canManageRequests()
            failedMedia(1)
            assertTrue(openedPage(request(1)).rows().single().removable)
        }

    @Test
    fun `not to a user who can't manage requests`() =
        runTest(testDispatcher) {
            failedMedia(1)
            assertFalse(openedPage(request(1)).rows().single().removable)
        }

    @Test
    fun `nor a healthy row`() =
        runTest(testDispatcher) {
            canManageRequests()
            movies[1] = media(1).copy(id = 1)
            assertFalse(openedPage(request(1)).rows().single().removable)
        }

    @Test
    fun `the status service finds what Seerr doesn't say`() =
        runTest(testDispatcher) {
            assumeTrue(BuildConfig.REMOVE_REQUEST_ENABLED)
            canManageRequests()
            downloadingMedia(1)
            coEvery { statusService.fetch(HASH) } returns
                DownloadStatusResult.Success(DownloadStatus(state = "stalledDL"))
            assertTrue(openedPage(request(1)).rows().single().removable)
        }

    @Test
    fun `the status service failing says nothing about the download`() =
        runTest(testDispatcher) {
            assumeTrue(BuildConfig.REMOVE_REQUEST_ENABLED)
            canManageRequests()
            downloadingMedia(1)
            coEvery { statusService.fetch(HASH) } returns
                DownloadStatusResult.Failure(DownloadStatusError.SERVICE_UNAVAILABLE)
            assertFalse(openedPage(request(1)).rows().single().removable)
        }

    @Test
    fun `the status service isn't bothered for a user who can't remove anything`() =
        runTest(testDispatcher) {
            downloadingMedia(1)
            openedPage(request(1))
            coVerify(exactly = 0) { statusService.fetch(any()) }
        }

    @Test
    fun `a movie that isn't out is waiting for its release`() =
        runTest(testDispatcher) {
            media(1)
            coEvery { moviesApi.movieMovieIdGet(1, any()) } returns
                MovieDetails(id = 1, title = "Movie", status = "Planned", mediaInfo = movies[1])
            val viewModel = openedPage(request(1))
            assertEquals(DownloadState.WAITING_FOR_RELEASE, viewModel.rowState())
        }

    @Test
    fun `removing asks the service once however often it's pressed`() =
        runTest(testDispatcher) {
            media(1)
            coEvery { seerrService.removeRequest(any(), any()) } returns RemoveRequestResult.Removed
            val viewModel = openedPage(request(1))
            val row = viewModel.rows().single()
            viewModel.remove(row)
            viewModel.remove(row)
            assertEquals(setOf(1), viewModel.state.value.removing)
            advanceUntilIdle()

            assertEquals(emptySet<Int>(), viewModel.state.value.removing)
            coVerify(exactly = 1) { seerrService.removeRequest(SeerrItemType.MOVIE, 1) }
        }

    @Test
    fun `a removal that didn't work keeps the row and says why`() =
        runTest(testDispatcher) {
            media(1)
            coEvery { seerrService.removeRequest(any(), any()) } returns RemoveRequestResult.InLibrary
            val viewModel = openedPage(request(1))
            viewModel.remove(viewModel.rows().single())
            advanceUntilIdle()

            assertEquals(listOf(1), viewModel.rows().map { it.requestId })
            assertEquals(RemoveRequestResult.InLibrary, viewModel.state.value.removeFailure)
            viewModel.onRemoveFailureShown()
            assertEquals(null, viewModel.state.value.removeFailure)
        }

    @Test
    fun `a removal from another page drops the rows and what they counted for`() =
        runTest(testDispatcher) {
            media(1)
            media(2)
            val viewModel = openedPage(request(1), request(2))
            removedMedia.emit(RemovedMedia(SeerrItemType.MOVIE, 1))
            advanceUntilIdle()

            assertEquals(listOf(2), viewModel.rows().map { it.requestId })
            assertEquals(1, viewModel.state.value.fetched)
        }

    @Test
    fun `a series with the same ID as a movie isn't dropped with it`() {
        val state =
            DownloadsState(
                rows = DataLoadingState.Success(downloadRows(listOf(1))),
                fetched = 1,
            )
        assertSame(state, state.withoutRemoved(RemovedMedia(SeerrItemType.TV, 1)))
    }

    @Test
    fun `every request for what was removed goes with it`() {
        val rows = downloadRows(listOf(1, 2)).map { it.copy(tmdbId = 9) } + downloadRows(listOf(3))
        val state = DownloadsState(rows = DataLoadingState.Success(rows), fetched = 3)

        val result = state.withoutRemoved(RemovedMedia(SeerrItemType.MOVIE, 9))

        assertEquals(listOf(3), result.rows.successValue?.map { it.requestId })
        assertEquals(1, result.fetched)
    }

    @Test
    fun `a row already taken off what was fetched isn't taken off twice`() {
        val state =
            DownloadsState(
                rows = DataLoadingState.Success(downloadRows(listOf(1, 2))),
                fetched = 1,
                removedRequests = setOf(1),
            )

        val result = state.withoutRemoved(RemovedMedia(SeerrItemType.MOVIE, 1))

        assertEquals(1, result.fetched)
        assertEquals(emptySet<Int>(), result.removedRequests)
    }

    @Test
    fun `focus moves to the next row when the focused one goes`() {
        val rows = downloadRows(1..3)
        assertEquals(3, rowAfterRemoval(rows, rows.filterNot { it.requestId == 2 }, 2))
    }

    @Test
    fun `or the one before if it was the last`() {
        val rows = downloadRows(1..3)
        assertEquals(2, rowAfterRemoval(rows, rows.filterNot { it.requestId == 3 }, 3))
    }

    @Test
    fun `past other rows that went with it`() {
        val rows = downloadRows(1..4)
        assertEquals(4, rowAfterRemoval(rows, downloadRows(listOf(1, 4)), 2))
    }

    @Test
    fun `focus has nowhere to go without any rows`() {
        assertEquals(null, rowAfterRemoval(downloadRows(1..1), emptyList(), 1))
    }

    @Test
    fun `focus stays when another row goes or the focused row hasn't loaded yet`() {
        val rows = downloadRows(1..3)
        assertEquals(null, rowAfterRemoval(rows, rows.filterNot { it.requestId == 3 }, 1))
        assertEquals(null, rowAfterRemoval(rows, rows, 99))
        assertEquals(null, rowAfterRemoval(rows, rows, null))
    }
}
