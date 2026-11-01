package com.github.damontecres.wholphin.ui.detail.discover

import android.content.Context
import com.github.damontecres.wholphin.api.seerr.SeerrApiClient
import com.github.damontecres.wholphin.api.seerr.TvApi
import com.github.damontecres.wholphin.api.seerr.model.MediaInfo
import com.github.damontecres.wholphin.api.seerr.model.MediaRequest
import com.github.damontecres.wholphin.api.seerr.model.PublicSettings
import com.github.damontecres.wholphin.api.seerr.model.Season
import com.github.damontecres.wholphin.api.seerr.model.TvDetails
import com.github.damontecres.wholphin.api.seerr.model.User
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.RequestStatus
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.data.model.SeerrServer
import com.github.damontecres.wholphin.data.model.SeerrUser
import com.github.damontecres.wholphin.services.CurrentSeerr
import com.github.damontecres.wholphin.services.SeerrServerRepository
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val SERIES_ID = 5
private const val USER_ID = 7
private const val OTHER_USER_ID = 8

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverSeriesSeasonsTest {
    private val testDispatcher = StandardTestDispatcher()

    private val tvApi = mockk<TvApi>(relaxed = true)
    private val seerrApiClient = mockk<SeerrApiClient>()
    private val seerrService = mockk<SeerrService>(relaxed = true)
    private val seerrServerRepository = mockk<SeerrServerRepository>()

    private val item =
        DiscoverItem(
            id = SERIES_ID,
            type = SeerrItemType.TV,
            title = "Made Up Series",
            subtitle = null,
            overview = null,
            availability = SeerrAvailability.UNKNOWN,
            releaseDate = null,
            posterUrl = null,
            backDropUrl = null,
            logoUrl = null,
            jellyfinItemId = null,
        )

    private var details = TvDetails(id = SERIES_ID)
    private val current = MutableStateFlow<CurrentSeerr?>(null)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        WholphinDispatchers.configure(testDispatcher)
        every { seerrService.api } returns seerrApiClient
        every { seerrApiClient.tvApi } returns tvApi
        every { seerrServerRepository.current } returns current
        every { seerrServerRepository.currentUserId } returns flowOf(USER_ID)
        coEvery { tvApi.tvTvIdGet(any(), any()) } coAnswers { details }
        setSpecialEpisodes(false)
    }

    @After
    fun tearDown() {
        WholphinDispatchers.reset()
        Dispatchers.resetMain()
    }

    private fun setSpecialEpisodes(enabled: Boolean) {
        current.value =
            CurrentSeerr(
                server = mockk<SeerrServer>(relaxed = true),
                user = mockk<SeerrUser>(relaxed = true),
                config = mockk(relaxed = true),
                serverConfig = PublicSettings(enableSpecialEpisodes = enabled),
            )
    }

    private fun season(
        number: Int,
        episodes: Int? = 10,
    ) = Season(seasonNumber = number, episodeCount = episodes)

    private fun request(
        season: Int,
        status: RequestStatus,
        userId: Int = USER_ID,
    ) = MediaRequest(
        id = season,
        status = status.status,
        requestedBy = User(id = userId),
        is4k = false,
        seasons = listOf(Season(seasonNumber = season, status = status.status)),
    )

    private fun series(
        seasons: List<Season>,
        requests: List<MediaRequest> = emptyList(),
        mediaSeasons: Map<Int, SeerrAvailability> = emptyMap(),
    ) {
        details =
            TvDetails(
                id = SERIES_ID,
                seasons = seasons,
                mediaInfo =
                    MediaInfo(
                        requests = requests,
                        seasons =
                            mediaSeasons.map { (number, status) ->
                                Season(seasonNumber = number, status = status.status)
                            },
                    ),
            )
    }

    private fun TestScope.loadSeasons(): Map<Int, RequestSeason> {
        val viewModel =
            DiscoverSeriesViewModel(
                api = mockk(relaxed = true),
                context = mockk<Context>(relaxed = true),
                navigationManager = mockk(relaxed = true),
                backdropService = mockk(relaxed = true),
                serverRepository = mockk(relaxed = true),
                seerrService = seerrService,
                seerrServerRepository = seerrServerRepository,
                item = item,
            )
        advanceUntilIdle()
        return viewModel.state.value.seasons
            .associateBy { it.season.seasonNumber!! }
    }

    @Test
    fun `a season never requested can be`() =
        runTest(testDispatcher) {
            series(listOf(season(1)))

            assertTrue(loadSeasons().getValue(1).editable)
        }

    @Test
    fun `a declined season can be requested again`() =
        runTest(testDispatcher) {
            series(listOf(season(1)), listOf(request(1, RequestStatus.DECLINED, OTHER_USER_ID)))

            assertTrue(loadSeasons().getValue(1).editable)
        }

    @Test
    fun `a completed season that was removed can be requested again`() =
        runTest(testDispatcher) {
            series(
                listOf(season(1), season(2)),
                listOf(request(1, RequestStatus.COMPLETED), request(2, RequestStatus.COMPLETED)),
                mapOf(1 to SeerrAvailability.DELETED, 2 to SeerrAvailability.UNKNOWN),
            )

            val seasons = loadSeasons()

            assertTrue(seasons.getValue(1).editable)
            assertTrue(seasons.getValue(2).editable)
        }

    @Test
    fun `a completed season still in the library can't`() =
        runTest(testDispatcher) {
            series(
                listOf(season(1)),
                listOf(request(1, RequestStatus.COMPLETED)),
                mapOf(1 to SeerrAvailability.AVAILABLE),
            )

            assertFalse(loadSeasons().getValue(1).editable)
        }

    @Test
    fun `a declined season that Seerr has anyway can't`() =
        runTest(testDispatcher) {
            series(
                listOf(season(1)),
                listOf(request(1, RequestStatus.DECLINED)),
                mapOf(1 to SeerrAvailability.AVAILABLE),
            )

            assertFalse(loadSeasons().getValue(1).editable)
        }

    @Test
    fun `an active request still locks its season`() =
        runTest(testDispatcher) {
            series(
                listOf(season(1), season(2), season(3)),
                listOf(
                    request(1, RequestStatus.APPROVED),
                    request(2, RequestStatus.PENDING, OTHER_USER_ID),
                    request(3, RequestStatus.FAILURE),
                ),
            )

            val seasons = loadSeasons()

            assertFalse(seasons.getValue(1).editable)
            assertFalse(seasons.getValue(2).editable)
            assertFalse(seasons.getValue(3).editable)
        }

    @Test
    fun `your own pending request stays editable`() =
        runTest(testDispatcher) {
            series(listOf(season(1)), listOf(request(1, RequestStatus.PENDING)))

            assertTrue(loadSeasons().getValue(1).editable)
        }

    @Test
    fun `Specials and empty seasons aren't offered`() =
        runTest(testDispatcher) {
            series(listOf(season(0), season(1), season(2, episodes = 0), season(3, episodes = null)))

            assertEquals(setOf(1, 3), loadSeasons().keys)
        }

    @Test
    fun `Specials are offered if Seerr has them turned on`() =
        runTest(testDispatcher) {
            setSpecialEpisodes(true)
            series(listOf(season(0), season(1)))

            assertEquals(setOf(0, 1), loadSeasons().keys)
        }

    @Test
    fun `a deleted series gets the button of one never requested`() {
        assertEquals(SeerrAvailability.UNKNOWN, seriesButtonAvailability(SeerrAvailability.DELETED.status))
        assertEquals(SeerrAvailability.UNKNOWN, seriesButtonAvailability(null))
        assertEquals(SeerrAvailability.AVAILABLE, seriesButtonAvailability(SeerrAvailability.AVAILABLE.status))
    }
}
