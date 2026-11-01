package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.api.seerr.MediaApi
import com.github.damontecres.wholphin.api.seerr.MoviesApi
import com.github.damontecres.wholphin.api.seerr.TvApi
import com.github.damontecres.wholphin.api.seerr.infrastructure.ClientException
import com.github.damontecres.wholphin.api.seerr.infrastructure.ServerException
import com.github.damontecres.wholphin.api.seerr.model.ExternalIds
import com.github.damontecres.wholphin.api.seerr.model.MediaInfo
import com.github.damontecres.wholphin.api.seerr.model.MovieDetails
import com.github.damontecres.wholphin.api.seerr.model.Season
import com.github.damontecres.wholphin.api.seerr.model.TvDetails
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.ui.successQueryResult
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.operations.ItemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.UUID

private const val TMDB_ID = 348
private const val MEDIA_ID = "5"
private const val JELLYFIN_ID = "0123456789abcdef0123456789abcdef"

class SeerrServiceRemoveRequestTest {
    private val moviesApi = mockk<MoviesApi>()
    private val tvApi = mockk<TvApi>()
    private val mediaApi = mockk<MediaApi>()
    private val itemsApi = mockk<ItemsApi>()
    private val jellyfinApi = mockk<ApiClient>()
    private val seerrApi = mockk<SeerrApi>()
    private val service =
        SeerrService(
            seerApi = seerrApi,
            seerrServerRepository = mockk(),
            imageUrlService = mockk(),
            jellyfinApi = jellyfinApi,
        )

    private val requested =
        MediaInfo(
            id = 5,
            status = SeerrAvailability.PROCESSING.status,
            serviceId = 0,
            externalServiceId = 12,
        )

    @Before
    fun setUp() {
        every { seerrApi.api.moviesApi } returns moviesApi
        every { seerrApi.api.tvApi } returns tvApi
        every { seerrApi.api.mediaApi } returns mediaApi
        every { jellyfinApi.itemsApi } returns itemsApi
        coEvery { itemsApi.getItems(any<GetItemsRequest>()) } returns successQueryResult()
        coEvery { mediaApi.mediaMediaIdFileDelete(any(), any()) } just runs
        coEvery { mediaApi.mediaMediaIdDelete(any()) } just runs
        movie(requested)
    }

    private fun movie(media: MediaInfo?) {
        coEvery { moviesApi.movieMovieIdGet(TMDB_ID) } returns
            MovieDetails(id = TMDB_ID, imdbId = "tt0078748", mediaInfo = media)
    }

    private fun series(media: MediaInfo?) {
        coEvery { tvApi.tvTvIdGet(TMDB_ID) } returns
            TvDetails(id = TMDB_ID, externalIds = ExternalIds(imdbId = "tt0944947"), mediaInfo = media)
    }

    private fun library(vararg items: BaseItemDto) {
        coEvery { itemsApi.getItems(any<GetItemsRequest>()) } returns successQueryResult(items.toList())
    }

    private fun item(
        kind: BaseItemKind = BaseItemKind.MOVIE,
        id: UUID = UUID.randomUUID(),
        providerIds: Map<String, String> = emptyMap(),
    ) = BaseItemDto(id = id, type = kind, providerIds = providerIds)

    private suspend fun removeMovie() = service.removeRequest(SeerrItemType.MOVIE, TMDB_ID)

    private suspend fun removeSeries() = service.removeRequest(SeerrItemType.TV, TMDB_ID)

    private fun verifyNothingRemoved() {
        coVerify { mediaApi wasNot Called }
    }

    @Test
    fun `Radarr goes first, then Seerr's record`() =
        runTest {
            assertEquals(RemoveRequestResult.Removed, removeMovie())
            coVerifyOrder {
                moviesApi.movieMovieIdGet(TMDB_ID)
                itemsApi.getItems(any<GetItemsRequest>())
                mediaApi.mediaMediaIdFileDelete(MEDIA_ID, false)
                mediaApi.mediaMediaIdDelete(MEDIA_ID)
            }
        }

    @Test
    fun `A series is looked for as a series and removed the same way`() =
        runTest {
            series(requested)
            assertEquals(RemoveRequestResult.Removed, removeSeries())
            coVerifyOrder {
                tvApi.tvTvIdGet(TMDB_ID)
                itemsApi.getItems(match<GetItemsRequest> { it.includeItemTypes == listOf(BaseItemKind.SERIES) })
                mediaApi.mediaMediaIdFileDelete(MEDIA_ID, false)
                mediaApi.mediaMediaIdDelete(MEDIA_ID)
            }
        }

    @Test
    fun `Never having reached Radarr only needs Seerr's record gone`() =
        runTest {
            movie(MediaInfo(id = 5, status = SeerrAvailability.PENDING.status))
            assertEquals(RemoveRequestResult.Removed, removeMovie())
            coVerify(exactly = 0) { mediaApi.mediaMediaIdFileDelete(any(), any()) }
            coVerify(exactly = 1) { mediaApi.mediaMediaIdDelete(MEDIA_ID) }
        }

    @Test
    fun `Both versions leave Radarr, the record is shared`() =
        runTest {
            movie(requested.copy(serviceId4k = 1))
            assertEquals(RemoveRequestResult.Removed, removeMovie())
            coVerifyOrder {
                mediaApi.mediaMediaIdFileDelete(MEDIA_ID, false)
                mediaApi.mediaMediaIdFileDelete(MEDIA_ID, true)
                mediaApi.mediaMediaIdDelete(MEDIA_ID)
            }
        }

    @Test
    fun `A Radarr failure stops, dropping the record would orphan the movie`() =
        runTest {
            coEvery { mediaApi.mediaMediaIdFileDelete(any(), any()) } throws ServerException("Oops", 500)
            assertEquals(RemoveRequestResult.ServiceFailed, removeMovie())
            coVerify(exactly = 0) { mediaApi.mediaMediaIdDelete(any()) }
        }

    @Test
    fun `No Radarr server configured stops too`() =
        runTest {
            coEvery { mediaApi.mediaMediaIdFileDelete(any(), any()) } throws ClientException("Conflict", 409)
            assertEquals(RemoveRequestResult.ServiceFailed, removeMovie())
            coVerify(exactly = 0) { mediaApi.mediaMediaIdDelete(any()) }
        }

    @Test
    fun `Failing on the second version stops as well`() =
        runTest {
            movie(requested.copy(serviceId4k = 1))
            coEvery { mediaApi.mediaMediaIdFileDelete(MEDIA_ID, true) } throws IOException("offline")
            assertEquals(RemoveRequestResult.ServiceFailed, removeMovie())
            coVerify(exactly = 0) { mediaApi.mediaMediaIdDelete(any()) }
        }

    @Test
    fun `A movie that's not found there is tolerated`() =
        runTest {
            coEvery { mediaApi.mediaMediaIdFileDelete(any(), any()) } throws ClientException("Not found", 404)
            assertEquals(RemoveRequestResult.Removed, removeMovie())
            coVerify(exactly = 1) { mediaApi.mediaMediaIdDelete(MEDIA_ID) }
        }

    @Test
    fun `The record failing is reported as such`() =
        runTest {
            coEvery { mediaApi.mediaMediaIdDelete(any()) } throws ServerException("Oops", 500)
            assertEquals(RemoveRequestResult.RecordFailed, removeMovie())
        }

    @Test
    fun `Seerr saying it's available removes nothing`() =
        runTest {
            movie(requested.copy(status = SeerrAvailability.AVAILABLE.status))
            assertEquals(RemoveRequestResult.InLibrary, removeMovie())
            verifyNothingRemoved()
        }

    @Test
    fun `A partially available series is never removed`() =
        runTest {
            series(requested.copy(status = SeerrAvailability.PARTIALLY_AVAILABLE.status))
            assertEquals(RemoveRequestResult.InLibrary, removeSeries())
            verifyNothingRemoved()
        }

    @Test
    fun `Neither is one with a season in while another is processing`() =
        runTest {
            series(
                requested.copy(
                    seasons = listOf(Season(seasonNumber = 1, status = SeerrAvailability.AVAILABLE.status)),
                ),
            )
            assertEquals(RemoveRequestResult.InLibrary, removeSeries())
            verifyNothingRemoved()
        }

    @Test
    fun `Having been imported just now removes nothing`() =
        runTest {
            library(item(providerIds = mapOf("Tmdb" to TMDB_ID.toString())))
            assertEquals(RemoveRequestResult.InLibrary, removeMovie())
            verifyNothingRemoved()
        }

    @Test
    fun `Any ID can identify it`() =
        runTest {
            library(item(providerIds = mapOf("Imdb" to "tt0078748")))
            assertEquals(RemoveRequestResult.InLibrary, removeMovie())

            series(requested.copy(tvdbId = 121361))
            library(item(BaseItemKind.SERIES, providerIds = mapOf("Tvdb" to "121361")))
            assertEquals(RemoveRequestResult.InLibrary, removeSeries())

            movie(requested.copy(jellyfinMediaId = JELLYFIN_ID))
            library(item(id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")))
            assertEquals(RemoveRequestResult.InLibrary, removeMovie())
            verifyNothingRemoved()
        }

    @Test
    fun `Another movie in the library doesn't matter`() =
        runTest {
            library(item(providerIds = mapOf("Tmdb" to "1", "Imdb" to "tt1")))
            assertEquals(RemoveRequestResult.Removed, removeMovie())
        }

    @Test
    fun `All of the library gets looked at`() =
        runTest {
            coEvery { itemsApi.getItems(match<GetItemsRequest> { it.startIndex == 0 }) } returns
                successQueryResult(listOf(item()), totalRecordCount = 2)
            coEvery { itemsApi.getItems(match<GetItemsRequest> { it.startIndex == 1 }) } returns
                successQueryResult(listOf(item(providerIds = mapOf("Tmdb" to TMDB_ID.toString()))), totalRecordCount = 2)
            assertEquals(RemoveRequestResult.InLibrary, removeMovie())
            verifyNothingRemoved()
        }

    @Test
    fun `A library ending early isn't taken as not having it`() =
        runTest {
            coEvery { itemsApi.getItems(any<GetItemsRequest>()) } returns
                successQueryResult(emptyList(), totalRecordCount = 2)
            assertEquals(RemoveRequestResult.CheckFailed, removeMovie())
            verifyNothingRemoved()
        }

    @Test
    fun `Failing to ask Jellyfin removes nothing`() =
        runTest {
            coEvery { itemsApi.getItems(any<GetItemsRequest>()) } throws IOException("offline")
            assertEquals(RemoveRequestResult.CheckFailed, removeMovie())
            verifyNothingRemoved()
        }

    @Test
    fun `Failing to fetch the record removes nothing`() =
        runTest {
            coEvery { moviesApi.movieMovieIdGet(TMDB_ID) } throws IOException("offline")
            assertEquals(RemoveRequestResult.CheckFailed, removeMovie())
            verifyNothingRemoved()
            coVerify { itemsApi wasNot Called }
        }

    @Test
    fun `A record without an ID can't be removed`() =
        runTest {
            movie(requested.copy(id = null))
            assertEquals(RemoveRequestResult.CheckFailed, removeMovie())
            verifyNothingRemoved()
        }

    @Test
    fun `Seerr no longer having it is the same as having removed it`() =
        runTest {
            movie(null)
            assertEquals(RemoveRequestResult.Removed, removeMovie())
            verifyNothingRemoved()
        }

    @Test
    fun `Other pages hear about it`() =
        runTest(UnconfinedTestDispatcher()) {
            var removed: RemovedMedia? = null
            val job = launch { removed = service.removedMedia.first() }
            removeMovie()
            job.join()
            assertEquals(RemovedMedia(SeerrItemType.MOVIE, TMDB_ID), removed)
        }

    @Test
    fun `Leaving the page isn't a failure to report`() {
        coEvery { mediaApi.mediaMediaIdFileDelete(any(), any()) } throws CancellationException("Left the page")
        assertThrows(CancellationException::class.java) {
            runTest { removeMovie() }
        }
    }
}
