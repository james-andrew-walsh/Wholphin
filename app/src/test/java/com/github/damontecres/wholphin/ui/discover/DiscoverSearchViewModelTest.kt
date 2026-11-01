package com.github.damontecres.wholphin.ui.discover

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.services.SeerrSearchResult
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.ui.search.SearchResult
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverSearchViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val seerrService = mockk<SeerrService>()

    @Before
    fun setUp() {
        WholphinDispatchers.configure(testDispatcher)
    }

    @After
    fun tearDown() {
        WholphinDispatchers.reset()
    }

    private fun item(
        id: Int,
        type: SeerrItemType,
    ) = DiscoverItem(
        id = id,
        type = type,
        title = "Item $id",
        subtitle = null,
        overview = null,
        availability = SeerrAvailability.UNKNOWN,
        releaseDate = null,
        posterUrl = null,
        backDropUrl = null,
        logoUrl = null,
        jellyfinItemId = null,
    )

    @Test
    fun `movies only is off, so series can be found and requested`() {
        assertFalse(BuildConfig.MOVIES_ONLY_DISCOVER_ENABLED)
    }

    @Test
    fun `search returns series as well as movies but not people`() =
        runTest(testDispatcher) {
            val movie = mockk<SeerrSearchResult>()
            val series = mockk<SeerrSearchResult>()
            val person = mockk<SeerrSearchResult>()
            coEvery { seerrService.search("query", any()) } returns listOf(movie, series, person)
            coEvery { seerrService.createDiscoverItem(movie) } returns item(1, SeerrItemType.MOVIE)
            coEvery { seerrService.createDiscoverItem(series) } returns item(2, SeerrItemType.TV)
            coEvery { seerrService.createDiscoverItem(person) } returns item(3, SeerrItemType.PERSON)
            val viewModel =
                DiscoverSearchViewModel(
                    navigationManager = mockk(relaxed = true),
                    seerrService = seerrService,
                    voiceInputManager = mockk(relaxed = true),
                    userPreferencesService = mockk(relaxed = true),
                )

            viewModel.search("query")
            advanceUntilIdle()

            val result = viewModel.seerrResults.value
            assertTrue("Got $result", result is SearchResult.SuccessSeerr)
            assertEquals(
                listOf(SeerrItemType.MOVIE, SeerrItemType.TV),
                (result as SearchResult.SuccessSeerr).items.map { it.type },
            )
        }
}
