package com.github.damontecres.wholphin.ui.discover

import com.github.damontecres.wholphin.api.seerr.MoviesApi
import com.github.damontecres.wholphin.api.seerr.RequestApi
import com.github.damontecres.wholphin.api.seerr.SeerrApiClient
import com.github.damontecres.wholphin.api.seerr.model.DownloadingItem
import com.github.damontecres.wholphin.api.seerr.model.MediaInfo
import com.github.damontecres.wholphin.api.seerr.model.MediaRequest
import com.github.damontecres.wholphin.api.seerr.model.MovieDetails
import com.github.damontecres.wholphin.api.seerr.model.PageInfo
import com.github.damontecres.wholphin.api.seerr.model.UserUserIdRequestsGet200Response
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.DownloadState
import com.github.damontecres.wholphin.data.model.RequestStatus
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SeerrServerRepository
import com.github.damontecres.wholphin.services.SeerrService
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.UUID

private const val USER_ID = 7
private const val JELLYFIN_ID = "0123456789abcdef0123456789abcdef"
private val jellyfinUuid = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private val requestApi = mockk<RequestApi>()
    private val moviesApi = mockk<MoviesApi>()
    private val seerrApiClient = mockk<SeerrApiClient>()
    private val seerrService = mockk<SeerrService>()
    private val seerrServerRepository = mockk<SeerrServerRepository>()
    private val userLibraryApi = mockk<UserLibraryApi>()
    private val api = mockk<ApiClient>()
    private val navigationManager = mockk<NavigationManager>(relaxed = true)

    private val userId = MutableStateFlow<Int?>(USER_ID)
    private val movies = mutableMapOf<Int, MediaInfo>()
    private val movieFetches = mutableListOf<Int>()
    private var movieFailure: Exception? = null

    @Before
    fun setUp() {
        WholphinDispatchers.configure(testDispatcher)
        every { seerrApiClient.requestApi } returns requestApi
        every { seerrApiClient.moviesApi } returns moviesApi
        every { seerrService.api } returns seerrApiClient
        every { seerrServerRepository.currentUserId } returns userId
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
        coEvery { seerrService.createImageUrl(any(), any(), any(), any(), any()) } returns "tmdb-poster"
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
            navigationManager = navigationManager,
        )

    private fun request(
        id: Int,
        media: MediaInfo = movies[id] ?: MediaInfo(tmdbId = id),
        status: RequestStatus = RequestStatus.APPROVED,
        is4k: Boolean = false,
    ) = MediaRequest(id = id, status = status.status, media = media.copy(tmdbId = id), is4k = is4k)

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

    private fun DownloadsViewModel.rows() = state.value.rows.successValue!!

    private fun DownloadsViewModel.rowState(index: Int = 0) = rows()[index].progress.state

    private fun TestScope.startRefreshing(viewModel: DownloadsViewModel) =
        launch(testDispatcher) { viewModel.refreshWhileVisible() }

    @Test
    fun `asks for this user's newest movie requests`() =
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
                    mediaType = RequestApi.MediaTypeRequestGet.MOVIE,
                )
            }
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
}
