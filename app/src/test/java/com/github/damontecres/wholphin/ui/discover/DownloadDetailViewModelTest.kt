package com.github.damontecres.wholphin.ui.discover

import com.github.damontecres.wholphin.api.seerr.MoviesApi
import com.github.damontecres.wholphin.api.seerr.SeerrApiClient
import com.github.damontecres.wholphin.api.seerr.TvApi
import com.github.damontecres.wholphin.api.seerr.model.DownloadingItem
import com.github.damontecres.wholphin.api.seerr.model.MediaInfo
import com.github.damontecres.wholphin.api.seerr.model.MovieDetails
import com.github.damontecres.wholphin.api.seerr.model.TvDetails
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.DownloadProgress
import com.github.damontecres.wholphin.data.model.DownloadState
import com.github.damontecres.wholphin.data.model.DownloadStatus
import com.github.damontecres.wholphin.data.model.RadarrStatus
import com.github.damontecres.wholphin.data.model.RequestStatus
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.services.DownloadStatusError
import com.github.damontecres.wholphin.services.DownloadStatusResult
import com.github.damontecres.wholphin.services.DownloadStatusService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.ui.nav.Destination
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

private const val TMDB_ID = 42
private val HASH = "abcdef0123".repeat(4)

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadDetailViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private val statusService = mockk<DownloadStatusService>()
    private val seerrService = mockk<SeerrService>()
    private val seerrApiClient = mockk<SeerrApiClient>()
    private val moviesApi = mockk<MoviesApi>()
    private val tvApi = mockk<TvApi>()
    private val navigationManager = mockk<NavigationManager>(relaxed = true)

    private val item =
        DiscoverItem(
            id = TMDB_ID,
            type = SeerrItemType.MOVIE,
            title = "Made Up Movie",
            subtitle = null,
            overview = null,
            availability = SeerrAvailability.PROCESSING,
            releaseDate = null,
            posterUrl = null,
            backDropUrl = null,
            logoUrl = null,
            jellyfinItemId = null,
        )
    private val seerrProgress = DownloadProgress(DownloadState.DOWNLOADING, fraction = .25f, downloadId = HASH)
    private val destination =
        Destination.DownloadDetail(
            downloadId = HASH,
            tmdbId = TMDB_ID,
            is4k = false,
            progress = seerrProgress,
            item = item,
        )
    private val seriesItem = item.copy(type = SeerrItemType.TV, title = "Made Up Series")
    private val seriesDestination = destination.copy(item = seriesItem)

    private val liveStatus =
        DownloadStatus(
            stateLabel = "Downloading",
            progress = .5,
            radarr = RadarrStatus(available = true, tracked = true),
        )

    // What the status service sends for any finished torrent that isn't in Radarr's queue
    private val untrackedFinished =
        liveStatus.copy(progress = 1.0, radarr = RadarrStatus(available = true, tracked = false))

    private var result: DownloadStatusResult = DownloadStatusResult.Success(liveStatus)
    private var fetches = 0
    private var media: MediaInfo? = null
    private var movieFailure: Exception? = null

    @Before
    fun setUp() {
        every { seerrService.api } returns seerrApiClient
        every { seerrApiClient.moviesApi } returns moviesApi
        every { seerrApiClient.tvApi } returns tvApi
        coEvery { statusService.fetch(HASH) } coAnswers {
            fetches++
            result
        }
        coEvery { moviesApi.movieMovieIdGet(any(), any()) } coAnswers {
            movieFailure?.let { throw it }
            MovieDetails(id = TMDB_ID, mediaInfo = media)
        }
        coEvery { tvApi.tvTvIdGet(any(), any()) } coAnswers {
            movieFailure?.let { throw it }
            TvDetails(id = TMDB_ID, mediaInfo = media)
        }
    }

    private fun createViewModel(destination: Destination.DownloadDetail = this.destination) =
        DownloadDetailViewModel(
            statusService = statusService,
            seerrService = seerrService,
            navigationManager = navigationManager,
            destination = destination,
        )

    private fun TestScope.startRefreshing(viewModel: DownloadDetailViewModel): Job {
        val job = launch { viewModel.refreshWhileVisible() }
        runCurrent()
        return job
    }

    private fun failure(error: DownloadStatusError = DownloadStatusError.SERVICE_UNAVAILABLE) = DownloadStatusResult.Failure(error)

    @Test
    fun `shows Seerr's progress until the live details arrive`() {
        val state = createViewModel().state.value

        assertEquals(seerrProgress, state.seerrProgress)
        assertNull(state.status)
        assertNull(state.error)
        assertFalse(state.finished)
    }

    @Test
    fun `refreshes every 5 seconds until the caller stops`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            val job = startRefreshing(viewModel)

            assertEquals(1, fetches)
            assertEquals(liveStatus, viewModel.state.value.status)

            advanceTimeBy(4_000)
            assertEquals(1, fetches)
            advanceTimeBy(1_100)
            assertEquals(2, fetches)
            advanceTimeBy(5_000)
            assertEquals(3, fetches)

            job.cancelAndJoin()
            advanceTimeBy(60_000)
            assertEquals(3, fetches)
        }

    @Test
    fun `failures back off to 60 seconds and a success goes back to 5`() =
        runTest(testDispatcher) {
            result = failure()
            val job = startRefreshing(createViewModel())

            assertEquals(1, fetches)
            advanceTimeBy(9_000)
            assertEquals(1, fetches)
            advanceTimeBy(1_100)
            assertEquals(2, fetches)
            advanceTimeBy(20_000)
            assertEquals(3, fetches)
            advanceTimeBy(40_000)
            assertEquals(4, fetches)
            advanceTimeBy(59_000)
            assertEquals(4, fetches)
            advanceTimeBy(1_000)
            assertEquals(5, fetches)
            advanceTimeBy(60_000)
            assertEquals(6, fetches)

            result = DownloadStatusResult.Success(liveStatus)
            advanceTimeBy(60_000)
            assertEquals(7, fetches)
            advanceTimeBy(5_100)
            assertEquals(8, fetches)
            job.cancelAndJoin()
        }

    @Test
    fun `a failure drops the live numbers and falls back to Seerr`() =
        runTest(testDispatcher) {
            media =
                MediaInfo(
                    status = SeerrAvailability.PROCESSING.status,
                    downloadStatus =
                        listOf(
                            DownloadingItem(
                                propertySize = 1000.0,
                                sizeLeft = 250.0,
                                status = "downloading",
                                downloadId = HASH.uppercase(),
                            ),
                        ),
                )
            val viewModel = createViewModel()
            val job = startRefreshing(viewModel)
            assertNotNull(viewModel.state.value.status)

            result = failure(DownloadStatusError.QBITTORRENT_UNAVAILABLE)
            advanceTimeBy(5_100)

            val state = viewModel.state.value
            assertNull(state.status)
            assertEquals(DownloadStatusError.QBITTORRENT_UNAVAILABLE, state.error)
            assertEquals(.75f, state.seerrProgress.fraction)
            assertFalse(state.finished)

            result = DownloadStatusResult.Success(liveStatus)
            advanceTimeBy(10_100)
            assertEquals(liveStatus, viewModel.state.value.status)
            assertNull(viewModel.state.value.error)
            job.cancelAndJoin()
        }

    @Test
    fun `Seerr failing as well keeps what it said before`() =
        runTest(testDispatcher) {
            result = failure()
            movieFailure = IOException("Made up failure")
            val viewModel = createViewModel()
            val job = startRefreshing(viewModel)

            assertEquals(seerrProgress, viewModel.state.value.seerrProgress)
            assertEquals(DownloadStatusError.SERVICE_UNAVAILABLE, viewModel.state.value.error)
            job.cancelAndJoin()
        }

    @Test
    fun `a refused session is reported and keeps trying without signing out`() =
        runTest(testDispatcher) {
            result = failure(DownloadStatusError.SESSION_REFUSED)
            val viewModel = createViewModel()
            val job = startRefreshing(viewModel)

            assertEquals(DownloadStatusError.SESSION_REFUSED, viewModel.state.value.error)
            assertFalse(viewModel.state.value.finished)
            advanceTimeBy(10_100)
            assertEquals(2, fetches)
            job.cancelAndJoin()

            verify(exactly = 0) { navigationManager.goBack() }
            verify(exactly = 0) { navigationManager.goToHome() }
            verify(exactly = 0) { navigationManager.navigateTo(any()) }
        }

    @Test
    fun `not being able to check the session is not a refusal`() =
        runTest(testDispatcher) {
            result = failure(DownloadStatusError.AUTH_UNAVAILABLE)
            val viewModel = createViewModel()
            val job = startRefreshing(viewModel)

            assertEquals(DownloadStatusError.AUTH_UNAVAILABLE, viewModel.state.value.error)
            job.cancelAndJoin()
        }

    @Test
    fun `stops when qBittorrent no longer has the download`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            val job = startRefreshing(viewModel)

            result = DownloadStatusResult.Gone
            advanceTimeBy(5_100)

            val state = viewModel.state.value
            assertTrue(state.finished)
            assertNull(state.status)
            assertNull(state.error)
            assertTrue(job.isCompleted)
            advanceTimeBy(60_000)
            assertEquals(2, fetches)
        }

    @Test
    fun `stops when a complete download has left Radarr's queue`() =
        runTest(testDispatcher) {
            result =
                DownloadStatusResult.Success(
                    liveStatus.copy(
                        progress = 1.0,
                        radarr = RadarrStatus(available = true, tracked = false),
                    ),
                )
            val viewModel = createViewModel()
            val job = startRefreshing(viewModel)

            assertTrue(viewModel.state.value.finished)
            assertTrue(job.isCompleted)
        }

    @Test
    fun `keeps going while Radarr doesn't know an unfinished download`() =
        runTest(testDispatcher) {
            result =
                DownloadStatusResult.Success(
                    liveStatus.copy(radarr = RadarrStatus(available = true, tracked = false)),
                )
            val viewModel = createViewModel()
            val job = startRefreshing(viewModel)

            assertFalse(viewModel.state.value.finished)
            job.cancelAndJoin()
        }

    @Test
    fun `keeps going when Radarr can't be reached`() =
        runTest(testDispatcher) {
            result =
                DownloadStatusResult.Success(
                    liveStatus.copy(progress = 1.0, radarr = RadarrStatus(available = false)),
                )
            val viewModel = createViewModel()
            val job = startRefreshing(viewModel)

            assertFalse(viewModel.state.value.finished)
            job.cancelAndJoin()
        }

    @Test
    fun `without the service stops once Seerr has the movie and no download`() =
        runTest(testDispatcher) {
            result = failure()
            media = MediaInfo(status = SeerrAvailability.AVAILABLE.status)
            val viewModel = createViewModel()
            val job = startRefreshing(viewModel)

            assertTrue(viewModel.state.value.finished)
            assertNull(viewModel.state.value.error)
            assertTrue(job.isCompleted)
        }

    @Test
    fun `missing from Seerr's queue alone doesn't mean it finished`() =
        runTest(testDispatcher) {
            result = failure()
            media = MediaInfo(status = SeerrAvailability.PROCESSING.status)
            val viewModel = createViewModel()
            val job = startRefreshing(viewModel)

            assertFalse(viewModel.state.value.finished)
            assertEquals(seerrProgress, viewModel.state.value.seerrProgress)
            job.cancelAndJoin()
        }

    @Test
    fun `without the service a series reads Seerr's series`() =
        runTest(testDispatcher) {
            result = failure()
            media =
                MediaInfo(
                    status = SeerrAvailability.PROCESSING.status,
                    downloadStatus =
                        listOf(
                            DownloadingItem(
                                propertySize = 1000.0,
                                sizeLeft = 250.0,
                                status = "downloading",
                                downloadId = HASH,
                            ),
                        ),
                )
            val viewModel = createViewModel(seriesDestination)
            val job = startRefreshing(viewModel)

            assertEquals(.75f, viewModel.state.value.seerrProgress.fraction)
            coVerify(exactly = 0) { moviesApi.movieMovieIdGet(any(), any()) }
            job.cancelAndJoin()
        }

    @Test
    fun `an earlier season being in doesn't finish a series download`() =
        runTest(testDispatcher) {
            result = failure()
            media = MediaInfo(status = SeerrAvailability.PARTIALLY_AVAILABLE.status)
            val viewModel = createViewModel(seriesDestination)
            val job = startRefreshing(viewModel)

            assertFalse(viewModel.state.value.finished)
            job.cancelAndJoin()
        }

    @Test
    fun `without the service stops once the whole series is in`() =
        runTest(testDispatcher) {
            result = failure()
            media = MediaInfo(status = SeerrAvailability.AVAILABLE.status)
            val viewModel = createViewModel(seriesDestination)
            val job = startRefreshing(viewModel)

            assertTrue(viewModel.state.value.finished)
            assertTrue(job.isCompleted)
        }

    @Test
    fun `a series download keeps going although Radarr doesn't know it`() =
        runTest(testDispatcher) {
            result =
                DownloadStatusResult.Success(
                    liveStatus.copy(radarr = RadarrStatus(available = true, tracked = false)),
                )
            val viewModel = createViewModel(seriesDestination)
            val job = startRefreshing(viewModel)

            assertFalse(viewModel.state.value.finished)
            assertNull(viewModel.state.value.error)
            job.cancelAndJoin()
        }

    @Test
    fun `a finished torrent doesn't finish a series download`() =
        runTest(testDispatcher) {
            result = DownloadStatusResult.Success(untrackedFinished)
            media = MediaInfo(status = SeerrAvailability.PARTIALLY_AVAILABLE.status)
            val viewModel = createViewModel(seriesDestination)
            val job = startRefreshing(viewModel)

            assertFalse(viewModel.state.value.finished)
            assertEquals(untrackedFinished, viewModel.state.value.status)

            advanceTimeBy(5_100)
            assertEquals(2, fetches)
            job.cancelAndJoin()
        }

    @Test
    fun `a series download still in Seerr's queue goes on`() =
        runTest(testDispatcher) {
            result = DownloadStatusResult.Success(untrackedFinished)
            media =
                MediaInfo(
                    status = SeerrAvailability.AVAILABLE.status,
                    downloadStatus = listOf(DownloadingItem(status = "completed", downloadId = HASH)),
                )
            val viewModel = createViewModel(seriesDestination)
            val job = startRefreshing(viewModel)

            assertFalse(viewModel.state.value.finished)
            job.cancelAndJoin()
        }

    @Test
    fun `a seeding torrent ends once the whole series is in`() =
        runTest(testDispatcher) {
            result = DownloadStatusResult.Success(untrackedFinished)
            media = MediaInfo(status = SeerrAvailability.AVAILABLE.status)
            val viewModel = createViewModel(seriesDestination)
            val job = startRefreshing(viewModel)

            assertTrue(viewModel.state.value.finished)
            assertTrue(job.isCompleted)
        }

    @Test
    fun `an unfinished series torrent doesn't ask Seerr`() =
        runTest(testDispatcher) {
            result = DownloadStatusResult.Success(liveStatus)
            val viewModel = createViewModel(seriesDestination)
            val job = startRefreshing(viewModel)

            coVerify(exactly = 0) { tvApi.tvTvIdGet(any(), any()) }
            job.cancelAndJoin()
        }

    @Test
    fun `a Radarr download that left the queue doesn't need Seerr`() =
        runTest(testDispatcher) {
            result = DownloadStatusResult.Success(untrackedFinished)
            val viewModel = createViewModel()
            val job = startRefreshing(viewModel)

            assertTrue(viewModel.state.value.finished)
            assertTrue(job.isCompleted)
            coVerify(exactly = 0) { moviesApi.movieMovieIdGet(any(), any()) }
        }

    @Test
    fun `the series page is the Discover page of the series`() {
        createViewModel(seriesDestination).openMovie()

        verify { navigationManager.navigateTo(Destination.DiscoveredItem(seriesItem)) }
    }

    @Test
    fun `retry goes straight away and restarts the backoff`() =
        runTest(testDispatcher) {
            result = failure()
            val viewModel = createViewModel()
            val job = startRefreshing(viewModel)
            advanceTimeBy(10_100)
            advanceTimeBy(1_000)
            assertEquals(2, fetches)

            viewModel.retry()
            runCurrent()
            assertEquals(3, fetches)

            advanceTimeBy(9_000)
            assertEquals(3, fetches)
            advanceTimeBy(1_100)
            assertEquals(4, fetches)
            job.cancelAndJoin()
        }

    @Test
    fun `the movie page is the Discover page of the movie`() {
        createViewModel().openMovie()

        verify { navigationManager.navigateTo(Destination.DiscoveredItem(item)) }
    }

    private fun row(
        progress: DownloadProgress = seerrProgress,
        item: DiscoverItem? = this.item,
    ) = DownloadRow(
        requestId = 1,
        tmdbId = TMDB_ID,
        is4k = false,
        requestStatus = RequestStatus.APPROVED,
        item = item,
        progress = progress,
    )

    @Test
    fun `a row with a live download opens the detail page`() {
        assertEquals(destination, detailDestination(row(), enabled = true))
    }

    @Test
    fun `a series row with a live download opens the detail page too`() {
        assertEquals(seriesDestination, detailDestination(row(item = seriesItem), enabled = true))
    }

    @Test
    fun `a queued or importing download has details too`() {
        listOf(DownloadState.QUEUED, DownloadState.PREPARING).forEach {
            assertNotNull(detailDestination(row(DownloadProgress(it, downloadId = HASH)), enabled = true))
        }
    }

    @Test
    fun `a 64 character hash is accepted`() {
        val id = "ABCDEF0123".repeat(6) + "ABCD"

        assertEquals(
            id,
            detailDestination(row(DownloadProgress(DownloadState.DOWNLOADING, downloadId = id)), enabled = true)?.downloadId,
        )
    }

    @Test
    fun `the feature flag turns the detail page off`() {
        assertNull(detailDestination(row(), enabled = false))
    }

    @Test
    fun `other rows keep their behaviour`() {
        assertNull(detailDestination(row(DownloadProgress(DownloadState.DOWNLOADING)), enabled = true))
        assertNull(detailDestination(row(DownloadProgress(DownloadState.READY, downloadId = HASH)), enabled = true))
        assertNull(detailDestination(row(DownloadProgress(DownloadState.FAILED, downloadId = HASH)), enabled = true))
        assertNull(detailDestination(row(item = null), enabled = true))
    }

    @Test
    fun `an ID which isn't a hash can't be looked up`() {
        listOf("SABnzbd_nzo_abc", "abc", "z".repeat(40), HASH + "a").forEach {
            assertNull(
                detailDestination(row(DownloadProgress(DownloadState.DOWNLOADING, downloadId = it)), enabled = true),
            )
        }
    }

    @Test
    fun `the logs don't say what is being downloaded`() {
        assertFalse(destination.toString().contains(HASH))
        assertFalse(destination.toString().contains("Made Up Movie"))
    }
}
