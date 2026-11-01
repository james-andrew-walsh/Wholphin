package com.github.damontecres.wholphin.ui.discover

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.api.seerr.SearchApi
import com.github.damontecres.wholphin.api.seerr.model.DiscoverMoviesGet200Response
import com.github.damontecres.wholphin.api.seerr.model.DiscoverTvGet200Response
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.filter.DiscoverFilter
import com.github.damontecres.wholphin.data.filter.DiscoverMovieYearFilter
import com.github.damontecres.wholphin.data.filter.DiscoverSort
import com.github.damontecres.wholphin.data.filter.DiscoverSortAndDirection
import com.github.damontecres.wholphin.data.filter.DiscoverTvGenreFilter
import com.github.damontecres.wholphin.data.filter.DiscoverTvYearFilter
import com.github.damontecres.wholphin.data.filter.browseMovieFilters
import com.github.damontecres.wholphin.data.filter.browseTvFilters
import com.github.damontecres.wholphin.data.filter.discoverMovieFilters
import com.github.damontecres.wholphin.data.filter.discoverTvFilters
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.services.KeyValueService
import com.github.damontecres.wholphin.services.SeerrApi
import com.github.damontecres.wholphin.util.DiscoverRequestType
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

private const val KEY = "test_filter"

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverRequestViewModelTest {
    private class InMemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private data class TvQuery(
        val sortBy: String?,
        val firstAirDateGte: String?,
        val firstAirDateLte: String?,
    )

    private data class MovieQuery(
        val genre: String?,
        val sortBy: String?,
        val primaryReleaseDateGte: String?,
        val primaryReleaseDateLte: String?,
    )

    private val testDispatcher = StandardTestDispatcher()

    private val searchApi = mockk<SearchApi>()
    private val seerrApi = mockk<SeerrApi>()
    private val serverRepository = mockk<ServerRepository>(relaxed = true)

    private val dataStore = InMemoryDataStore()
    private val keyValueService = KeyValueService(dataStore)

    private val userA = UUID.randomUUID()
    private val userB = UUID.randomUUID()

    private val queries = mutableListOf<MovieQuery>()
    private val tvQueries = mutableListOf<TvQuery>()

    private val votesSort = DiscoverSortAndDirection(DiscoverSort.TMDB_VOTE, SortOrder.ASCENDING)

    @Before
    fun setUp() {
        WholphinDispatchers.configure(testDispatcher)

        every { seerrApi.api.searchApi } returns searchApi
        signIn(userA)
        coEvery {
            searchApi.discoverMoviesGet(
                page = any(),
                language = any(),
                genre = any(),
                studio = any(),
                keywords = any(),
                excludeKeywords = any(),
                sortBy = any(),
                primaryReleaseDateGte = any(),
                primaryReleaseDateLte = any(),
                withRuntimeGte = any(),
                withRuntimeLte = any(),
                voteAverageGte = any(),
                voteAverageLte = any(),
                voteCountGte = any(),
                voteCountLte = any(),
                watchRegion = any(),
                watchProviders = any(),
                certification = any(),
                certificationGte = any(),
                certificationLte = any(),
                certificationCountry = any(),
                certificationMode = any(),
            )
        } coAnswers {
            queries.add(MovieQuery(arg(2), arg(6), arg(7), arg(8)))
            DiscoverMoviesGet200Response(totalResults = 0, results = emptyList())
        }
        coEvery {
            searchApi.discoverTvGet(
                page = any(),
                language = any(),
                genre = any(),
                network = any(),
                keywords = any(),
                excludeKeywords = any(),
                sortBy = any(),
                firstAirDateGte = any(),
                firstAirDateLte = any(),
                withRuntimeGte = any(),
                withRuntimeLte = any(),
                voteAverageGte = any(),
                voteAverageLte = any(),
                voteCountGte = any(),
                voteCountLte = any(),
                watchRegion = any(),
                watchProviders = any(),
                status = any(),
                certification = any(),
                certificationGte = any(),
                certificationLte = any(),
                certificationCountry = any(),
                certificationMode = any(),
            )
        } coAnswers {
            tvQueries.add(TvQuery(arg(6), arg(7), arg(8)))
            DiscoverTvGet200Response(totalResults = 0, results = emptyList())
        }
    }

    @After
    fun tearDown() {
        WholphinDispatchers.reset()
    }

    private fun signIn(userId: UUID) {
        val user = mockk<JellyfinUser>()
        every { user.id } returns userId
        every { serverRepository.currentUser } returns user
    }

    private fun createViewModel(
        initialFilter: DiscoverFilter = DiscoverFilter(),
        persistKey: String? = KEY,
        keyValueService: KeyValueService = this.keyValueService,
        type: DiscoverRequestType = DiscoverRequestType.DISCOVER_MOVIES,
    ) = DiscoverRequestViewModel(
        context = mockk(relaxed = true),
        seerrService = mockk(relaxed = true),
        navigationManager = mockk(relaxed = true),
        api = seerrApi,
        filterOptionCache = mockk(relaxed = true),
        keyValueService = keyValueService,
        serverRepository = serverRepository,
        type = type,
        startIndex = 0,
        initialFilter = initialFilter,
        persistKey = persistKey,
    )

    private suspend fun saved(userId: UUID = userA) = keyValueService.get<DiscoverFilter?>(userId, KEY, null).first()

    @Test
    fun `initial sort reaches the first request`() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    initialFilter = DiscoverFilter(sortBy = votesSort),
                    persistKey = null,
                )
            advanceUntilIdle()

            assertEquals("vote_average.asc", queries.single().sortBy)
            assertEquals(votesSort, viewModel.state.value.sortAndDirection)
        }

    @Test
    fun `a TV row's sort and dates reach its grid`() =
        runTest(testDispatcher) {
            val newest = DiscoverSortAndDirection(DiscoverSort.RELEASE_DATE, SortOrder.DESCENDING)
            val viewModel =
                createViewModel(
                    initialFilter =
                        DiscoverFilter(
                            sortBy = newest,
                            firstAirDateGte = "2026-05-03",
                            firstAirDateLte = "2026-10-30",
                        ),
                    persistKey = null,
                    type = DiscoverRequestType.DISCOVER_TV,
                )
            advanceUntilIdle()

            assertEquals(TvQuery("first_air_date.desc", "2026-05-03", "2026-10-30"), tvQueries.single())
            assertEquals(newest, viewModel.state.value.sortAndDirection)
        }

    @Test
    fun `a TV grid without a sort stays on the default`() =
        runTest(testDispatcher) {
            createViewModel(persistKey = null, type = DiscoverRequestType.DISCOVER_TV)
            advanceUntilIdle()

            assertEquals(TvQuery("popularity.desc", null, null), tvQueries.single())
        }

    @Test
    fun `a pinned TV decade does not offer the year filter`() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    initialFilter = DiscoverFilter(firstAirDateGte = "1990-01-01", firstAirDateLte = "1999-12-31"),
                    persistKey = null,
                    type = DiscoverRequestType.DISCOVER_TV,
                )
            advanceUntilIdle()

            assertFalse(DiscoverTvYearFilter in viewModel.state.value.filterOptions)
        }

    @Test
    fun `a TV genre grid does not offer the genre filter`() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    initialFilter = DiscoverFilter(genreIds = listOf(18)),
                    persistKey = null,
                    type = DiscoverRequestType.DISCOVER_TV,
                )
            advanceUntilIdle()

            assertEquals(
                discoverFilterOptions(
                    DiscoverRequestType.DISCOVER_TV,
                    BuildConfig.BROWSE_DISCOVER_ENABLED,
                ) - DiscoverTvGenreFilter,
                viewModel.state.value.filterOptions,
            )
        }

    @Test
    fun `saved filter is applied before the only first request`() =
        runTest(testDispatcher) {
            keyValueService.save(
                userA,
                KEY,
                DiscoverFilter(
                    genreIds = listOf(28),
                    sortBy = votesSort,
                    primaryReleaseDateGte = "1990-01-01",
                    primaryReleaseDateLte = "1999-12-31",
                ),
            )

            val viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(
                MovieQuery("28", "vote_average.asc", "1990-01-01", "1999-12-31"),
                queries.single(),
            )
            assertEquals(votesSort, viewModel.state.value.sortAndDirection)
            assertEquals(listOf(28), viewModel.state.value.filter.genreIds)
            assertNull(viewModel.state.value.filter.sortBy)
        }

    @Test
    fun `saved filter does not hide filter options`() =
        runTest(testDispatcher) {
            keyValueService.save(userA, KEY, DiscoverFilter(genreIds = listOf(28)))

            val viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(
                discoverFilterOptions(
                    DiscoverRequestType.DISCOVER_MOVIES,
                    BuildConfig.BROWSE_DISCOVER_ENABLED,
                ),
                viewModel.state.value.filterOptions,
            )
        }

    @Test
    fun `a pinned decade does not offer the year filter`() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    initialFilter = DiscoverFilter(primaryReleaseDateLte = "1949-12-31"),
                    persistKey = null,
                )
            advanceUntilIdle()

            assertFalse(DiscoverMovieYearFilter in viewModel.state.value.filterOptions)
        }

    @Test
    fun `corrupt saved value falls back to defaults`() =
        runTest(testDispatcher) {
            dataStore.edit { it[stringPreferencesKey("${userA}_$KEY")] = "not json" }

            val viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(MovieQuery(null, "popularity.desc", null, null), queries.single())
            assertEquals(DiscoverFilter(), viewModel.state.value.filter)
        }

    @Test
    fun `users do not share saved filters`() =
        runTest(testDispatcher) {
            keyValueService.save(userA, KEY, DiscoverFilter(genreIds = listOf(28)))
            signIn(userB)

            val viewModel = createViewModel()
            advanceUntilIdle()
            assertNull(queries.single().genre)

            viewModel.onFilterChange(DiscoverFilter(genreIds = listOf(35)))
            advanceUntilIdle()

            assertEquals(listOf(35), saved(userB)?.genreIds)
            assertEquals(listOf(28), saved(userA)?.genreIds)
        }

    @Test
    fun `filter and sort changes are saved together`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.onFilterChange(DiscoverFilter(genreIds = listOf(35)))
            advanceUntilIdle()
            viewModel.onSortChange(votesSort)
            advanceUntilIdle()

            assertEquals(DiscoverFilter(genreIds = listOf(35), sortBy = votesSort), saved())
        }

    @Test
    fun `a new view model restores what was saved`() =
        runTest(testDispatcher) {
            val first = createViewModel()
            advanceUntilIdle()
            first.onFilterChange(DiscoverFilter(genreIds = listOf(35)))
            advanceUntilIdle()
            first.onSortChange(votesSort)
            advanceUntilIdle()
            queries.clear()

            createViewModel()
            advanceUntilIdle()

            assertEquals(MovieQuery("35", "vote_average.asc", null, null), queries.single())
        }

    @Test
    fun `reset clears the filter and sort and saves that`() =
        runTest(testDispatcher) {
            keyValueService.save(userA, KEY, DiscoverFilter(genreIds = listOf(28), sortBy = votesSort))
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.resetFilters()
            advanceUntilIdle()

            assertEquals(DiscoverFilter(), viewModel.state.value.filter)
            assertEquals(DiscoverSortAndDirection(), viewModel.state.value.sortAndDirection)
            assertEquals(MovieQuery(null, "popularity.desc", null, null), queries.last())
            assertEquals(DiscoverFilter(sortBy = DiscoverSortAndDirection()), saved())

            // And it stays cleared after a restart
            queries.clear()
            val restarted = createViewModel()
            advanceUntilIdle()
            assertEquals(MovieQuery(null, "popularity.desc", null, null), queries.single())
            assertEquals(DiscoverFilter(), restarted.state.value.filter)
        }

    @Test
    fun `no persist key never touches saved values`() =
        runTest(testDispatcher) {
            val unusedDataStore = mockk<DataStore<Preferences>>()
            val viewModel =
                createViewModel(
                    persistKey = null,
                    keyValueService = KeyValueService(unusedDataStore),
                )
            advanceUntilIdle()

            viewModel.onFilterChange(DiscoverFilter(genreIds = listOf(35)))
            viewModel.onSortChange(votesSort)
            advanceUntilIdle()

            verify { unusedDataStore wasNot Called }
            assertEquals("35", queries.last().genre)
        }

    @Test
    fun `no user means nothing is read or saved`() =
        runTest(testDispatcher) {
            every { serverRepository.currentUser } returns null
            val unusedDataStore = mockk<DataStore<Preferences>>()
            val viewModel = createViewModel(keyValueService = KeyValueService(unusedDataStore))
            advanceUntilIdle()

            viewModel.onFilterChange(DiscoverFilter(genreIds = listOf(35)))
            advanceUntilIdle()

            verify { unusedDataStore wasNot Called }
            assertEquals(2, queries.size)
        }

    @Test
    fun `upstream sort options are unchanged`() {
        val expected =
            listOf(
                DiscoverSort.POPULARITY,
                DiscoverSort.RELEASE_DATE,
                DiscoverSort.TMDB_VOTE,
                DiscoverSort.ALPHABETICAL,
            )
        assertEquals(expected, discoverSortOptions(DiscoverRequestType.DISCOVER_MOVIES, false))
        assertEquals(expected, discoverSortOptions(DiscoverRequestType.DISCOVER_TV, false))
    }

    @Test
    fun `TV browsing sorts by the TV name`() {
        assertEquals(
            listOf(
                DiscoverSort.POPULARITY,
                DiscoverSort.RELEASE_DATE,
                DiscoverSort.TMDB_VOTE,
                DiscoverSort.ORIGINAL_NAME,
            ),
            discoverSortOptions(DiscoverRequestType.DISCOVER_TV, true),
        )
    }

    @Test
    fun `movie browsing sorts by primary release date`() {
        val options = discoverSortOptions(DiscoverRequestType.DISCOVER_MOVIES, true)

        assertEquals(
            listOf(
                DiscoverSort.POPULARITY,
                DiscoverSort.PRIMARY_RELEASE_DATE,
                DiscoverSort.TMDB_VOTE,
                DiscoverSort.ALPHABETICAL,
            ),
            options,
        )
    }

    @Test
    fun `upstream filter options are unchanged`() {
        assertEquals(discoverMovieFilters, discoverFilterOptions(DiscoverRequestType.DISCOVER_MOVIES, false))
        assertEquals(discoverTvFilters, discoverFilterOptions(DiscoverRequestType.DISCOVER_TV, false))
    }

    @Test
    fun `movie browsing adds the year filter`() {
        assertEquals(browseMovieFilters, discoverFilterOptions(DiscoverRequestType.DISCOVER_MOVIES, true))
        assertTrue(DiscoverMovieYearFilter in browseMovieFilters)
    }

    @Test
    fun `TV browsing adds the year filter`() {
        assertEquals(browseTvFilters, discoverFilterOptions(DiscoverRequestType.DISCOVER_TV, true))
        assertTrue(DiscoverTvYearFilter in browseTvFilters)
    }

    @Test
    fun `trending and upcoming grids have no options`() {
        listOf(
            DiscoverRequestType.TRENDING,
            DiscoverRequestType.TRENDING_MOVIES_WEEK,
            DiscoverRequestType.TRENDING_TV_WEEK,
            DiscoverRequestType.UPCOMING_MOVIES,
            DiscoverRequestType.UPCOMING_TV,
            DiscoverRequestType.UNKNOWN,
        ).forEach {
            assertTrue(discoverSortOptions(it, true).isEmpty())
            assertTrue(discoverFilterOptions(it, true).isEmpty())
        }
    }
}
