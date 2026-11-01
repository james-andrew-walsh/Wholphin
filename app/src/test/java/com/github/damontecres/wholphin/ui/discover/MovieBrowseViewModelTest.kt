package com.github.damontecres.wholphin.ui.discover

import com.github.damontecres.wholphin.data.filter.DiscoverFilter
import com.github.damontecres.wholphin.data.filter.BrowseRow
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.DiscoverRequestType
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class MovieBrowseViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val seerrService = mockk<SeerrService>(relaxed = true)

    // What was requested, in the same form as requestsFor
    private val requests = mutableListOf<Any>()

    @Before
    fun setUp() {
        WholphinDispatchers.configure(testDispatcher)

        coEvery { seerrService.trendingMoviesWeek(any()) } coAnswers {
            requests.add(DiscoverRequestType.TRENDING_MOVIES_WEEK)
            emptyList()
        }
        coEvery { seerrService.upcomingMovies(any()) } coAnswers {
            requests.add(DiscoverRequestType.UPCOMING_MOVIES)
            emptyList()
        }
        coEvery { seerrService.discoverMoviesBy(any(), any()) } coAnswers {
            requests.add(firstArg<DiscoverFilter>())
            emptyList()
        }
        coEvery { seerrService.movieGenres() } coAnswers {
            requests.add(BrowseRow.Genres)
            emptyList()
        }
    }

    @After
    fun tearDown() {
        WholphinDispatchers.reset()
    }

    private fun createViewModel() =
        MovieBrowseViewModel(
            seerrService = seerrService,
            navigationManager = mockk(relaxed = true),
            backdropService = mockk(relaxed = true),
        )

    private fun requestsFor(
        viewModel: MovieBrowseViewModel,
        indices: IntRange,
    ): List<Any> =
        viewModel.rows.slice(indices).mapNotNull { row ->
            when (row) {
                is BrowseRow.Items -> {
                    if (row.shelf.type == DiscoverRequestType.DISCOVER_MOVIES) {
                        row.shelf.filter
                    } else {
                        row.shelf.type
                    }
                }

                BrowseRow.Genres -> {
                    row
                }

                BrowseRow.Decades -> {
                    null
                }
            }
        }

    private fun assertRequested(
        expected: List<Any>,
        actual: List<Any>,
    ) {
        assertEquals(expected.size, actual.size)
        assertTrue("Expected $expected but got $actual", actual.containsAll(expected))
    }

    @Test
    fun `nothing is requested until a row is asked for`() =
        runTest(testDispatcher) {
            createViewModel()
            advanceUntilIdle()

            assertEquals(emptyList<Any>(), requests)
        }

    @Test
    fun `opening the page only requests the first three rows`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.ensureLoaded(0)
            advanceUntilIdle()

            assertRequested(requestsFor(viewModel, 0..2), requests)
        }

    @Test
    fun `moving down adds rows without requesting earlier ones again`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.ensureLoaded(0)
            advanceUntilIdle()
            viewModel.ensureLoaded(5)
            advanceUntilIdle()

            // 8 rows, but the decades row makes no request
            assertEquals(7, requests.size)
            assertRequested(requestsFor(viewModel, 0..7), requests)
        }

    @Test
    fun `moving back up requests nothing`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.ensureLoaded(3)
            advanceUntilIdle()
            val count = requests.size
            viewModel.ensureLoaded(1)
            viewModel.ensureLoaded(3)
            advanceUntilIdle()

            assertEquals(count, requests.size)
        }

    @Test
    fun `the last rows stay within bounds`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.ensureLoaded(viewModel.rows.lastIndex)
            advanceUntilIdle()

            assertEquals(13, viewModel.rows.size)
            assertEquals(12, requests.size)
        }

    @Test
    fun `at most two requests run at once`() =
        runTest(testDispatcher) {
            var inFlight = 0
            var maxInFlight = 0
            val gate = CompletableDeferred<Unit>()
            val gated: suspend () -> List<DiscoverItem> = {
                inFlight++
                maxInFlight = maxOf(maxInFlight, inFlight)
                gate.await()
                inFlight--
                emptyList()
            }
            coEvery { seerrService.trendingMoviesWeek(any()) } coAnswers { gated() }
            coEvery { seerrService.upcomingMovies(any()) } coAnswers { gated() }
            coEvery { seerrService.discoverMoviesBy(any(), any()) } coAnswers { gated() }

            val viewModel = createViewModel()
            viewModel.ensureLoaded(2)
            advanceUntilIdle()
            assertEquals(2, inFlight)

            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(0, inFlight)
            assertEquals(2, maxInFlight)
            assertEquals(5, (0..4).count { viewModel.rowItems.value[it] is DataLoadingState.Success<*> })
        }

    @Test
    fun `a failed row also loads the rows past it`() =
        runTest(testDispatcher) {
            coEvery { seerrService.discoverMoviesBy(any(), any()) } coAnswers {
                val filter = firstArg<DiscoverFilter>()
                requests.add(filter)
                if (filter.voteCountGte != null) throw IOException("Failed")
                emptyList()
            }

            val viewModel = createViewModel()
            viewModel.ensureLoaded(0)
            advanceUntilIdle()

            // Highly Rated is row 2 and New Releases is row 3, both fail, so through row 5
            assertTrue(viewModel.rowItems.value[2] is DataLoadingState.Error)
            assertTrue(viewModel.rowItems.value[3] is DataLoadingState.Error)
            assertRequested(requestsFor(viewModel, 0..5), requests)
        }

    @Test
    fun `a successful page does not slide the window`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.ensureLoaded(0)
            advanceUntilIdle()

            assertEquals(setOf(0, 1, 2), viewModel.rowItems.value.keys)
            assertEquals(DataLoadingState.Pending, viewModel.genres.value)
        }

    @Test
    fun `the row loads with its filter`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.ensureLoaded(0)
            advanceUntilIdle()

            val highlyRated = (viewModel.rows[2] as BrowseRow.Items).shelf
            assertTrue(highlyRated.filter in requests)
        }

    @Test
    fun `a genre failure shows on the genre row`() =
        runTest(testDispatcher) {
            coEvery { seerrService.movieGenres() } throws IOException("Failed")

            val viewModel = createViewModel()
            viewModel.ensureLoaded(3)
            advanceUntilIdle()

            assertTrue(viewModel.genres.value is DataLoadingState.Error)
        }
}
