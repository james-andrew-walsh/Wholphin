package com.github.damontecres.wholphin.ui.discover

import com.github.damontecres.wholphin.api.seerr.SeerrApiClient
import com.github.damontecres.wholphin.api.seerr.TvApi
import com.github.damontecres.wholphin.api.seerr.model.TvTvIdRatingsGet200Response
import com.github.damontecres.wholphin.data.filter.BrowseRow
import com.github.damontecres.wholphin.data.filter.DiscoverFilter
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.DiscoverRating
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.ui.components.Genre
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.DiscoverRequestType
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
class TvBrowseViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val seerrService = mockk<SeerrService>(relaxed = true)

    // What was requested, in the same form as requestsFor
    private val requests = mutableListOf<Any>()

    @Before
    fun setUp() {
        WholphinDispatchers.configure(testDispatcher)

        coEvery { seerrService.trendingTvWeek(any()) } coAnswers {
            requests.add(DiscoverRequestType.TRENDING_TV_WEEK)
            emptyList()
        }
        coEvery { seerrService.upcomingTv(any()) } coAnswers {
            requests.add(DiscoverRequestType.UPCOMING_TV)
            emptyList()
        }
        coEvery { seerrService.discoverTvBy(any(), any()) } coAnswers {
            requests.add(firstArg<DiscoverFilter>())
            emptyList()
        }
        coEvery { seerrService.tvGenres() } coAnswers {
            requests.add(BrowseRow.Genres)
            emptyList()
        }
    }

    @After
    fun tearDown() {
        WholphinDispatchers.reset()
    }

    private fun createViewModel() =
        TvBrowseViewModel(
            seerrService = seerrService,
            navigationManager = mockk(relaxed = true),
            backdropService = mockk(relaxed = true),
        )

    private fun requestsFor(
        viewModel: TvBrowseViewModel,
        indices: IntRange,
    ): List<Any> =
        viewModel.rows.slice(indices).mapNotNull { row ->
            when (row) {
                is BrowseRow.Items -> {
                    if (row.shelf.type == DiscoverRequestType.DISCOVER_TV) {
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
    fun `genre and decade cards open TV grids`() {
        val viewModel = createViewModel()

        assertEquals(SeerrItemType.TV, viewModel.itemType)
        assertEquals(DiscoverRequestType.DISCOVER_TV, viewModel.gridType)
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
    fun `every row can load and the decades row makes no request`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.ensureLoaded(viewModel.rows.lastIndex)
            advanceUntilIdle()

            assertEquals(13, viewModel.rows.size)
            assertEquals(12, requests.size)
            assertRequested(requestsFor(viewModel, 0..12), requests)
        }

    @Test
    fun `no row asks for movies`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.ensureLoaded(viewModel.rows.lastIndex)
            advanceUntilIdle()

            coVerify(exactly = 0) { seerrService.trendingMoviesWeek(any()) }
            coVerify(exactly = 0) { seerrService.upcomingMovies(any()) }
            coVerify(exactly = 0) { seerrService.discoverMoviesBy(any(), any()) }
            coVerify(exactly = 0) { seerrService.movieGenres() }
            assertEquals((0..12).filter { it !in 5..6 }.toSet(), viewModel.rowItems.value.keys)
            assertTrue(viewModel.genres.value is DataLoadingState.Success<*>)
        }

    @Test
    fun `genres use the TV list`() =
        runTest(testDispatcher) {
            val genres = listOf(Genre(id = 18.toUUID(), name = "Drama", imageUrl = null))
            coEvery { seerrService.tvGenres() } returns genres

            val viewModel = createViewModel()
            viewModel.ensureLoaded(3)
            advanceUntilIdle()

            assertEquals(DataLoadingState.Success(genres), viewModel.genres.value)
        }

    @Test
    fun `a failed row also loads the rows past it`() =
        runTest(testDispatcher) {
            coEvery { seerrService.discoverTvBy(any(), any()) } coAnswers {
                val filter = firstArg<DiscoverFilter>()
                requests.add(filter)
                if (filter.voteCountGte != null) throw IOException("Failed")
                emptyList()
            }

            val viewModel = createViewModel()
            viewModel.ensureLoaded(0)
            advanceUntilIdle()

            // Highly Rated is row 2 and New Series is row 3, both fail, so through row 5
            assertTrue(viewModel.rowItems.value[2] is DataLoadingState.Error)
            assertTrue(viewModel.rowItems.value[3] is DataLoadingState.Error)
            assertRequested(requestsFor(viewModel, 0..5), requests)
        }

    @Test
    fun `the focused series gets its TV rating`() =
        runTest(testDispatcher) {
            val client = mockk<SeerrApiClient>()
            val tvApi = mockk<TvApi>()
            every { seerrService.api } returns client
            every { client.tvApi } returns tvApi
            coEvery { tvApi.tvTvIdRatingsGet(5) } returns TvTvIdRatingsGet200Response(criticsScore = 90)

            val viewModel = createViewModel()
            viewModel.updateBackdrop(DiscoverFocusedItem.Item(series(5)))
            advanceUntilIdle()

            assertEquals(DiscoverRating(criticRating = 90, audienceRating = null), viewModel.rating.value[5])
        }

    private fun series(id: Int) =
        DiscoverItem(
            id = id,
            type = SeerrItemType.TV,
            title = "Series $id",
            subtitle = null,
            overview = null,
            availability = SeerrAvailability.UNKNOWN,
            releaseDate = null,
            posterUrl = null,
            backDropUrl = null,
            logoUrl = null,
            jellyfinItemId = null,
        )
}
