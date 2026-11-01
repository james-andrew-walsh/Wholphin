package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.api.seerr.MoviesApi
import com.github.damontecres.wholphin.api.seerr.infrastructure.ClientException
import com.github.damontecres.wholphin.api.seerr.infrastructure.Serializer
import com.github.damontecres.wholphin.api.seerr.infrastructure.ServerException
import com.github.damontecres.wholphin.api.seerr.model.MovieMovieIdRatingsGet200Response
import com.github.damontecres.wholphin.api.seerr.model.MovieMovieIdRatingscombinedGet200Response
import com.github.damontecres.wholphin.api.seerr.model.MovieMovieIdRatingscombinedGet200ResponseImdb
import com.github.damontecres.wholphin.data.model.MovieRatings
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

private const val TMDB_ID = 348
private const val IMDB_ID = "tt0078748"
private const val IMDB_URL = "https://www.imdb.com/title/tt0078748"
private const val RT_URL = "https://www.rottentomatoes.com/m/alien"

class SeerrServiceRatingsTest {
    private val moviesApi = mockk<MoviesApi>()
    private val seerrApi = mockk<SeerrApi>()
    private val seerrServerRepository = mockk<SeerrServerRepository>()
    private val service =
        SeerrService(
            seerApi = seerrApi,
            seerrServerRepository = seerrServerRepository,
            imageUrlService = mockk(),
        )

    private val rottenTomatoes =
        MovieMovieIdRatingsGet200Response(
            url = RT_URL,
            criticsScore = 93,
            audienceScore = 94,
        )

    @Before
    fun setUp() {
        every { seerrApi.api.moviesApi } returns moviesApi
        every { seerrServerRepository.active } returns flowOf(true)
    }

    @Test
    fun `Inactive Seerr still gives the IMDb page`() =
        runTest {
            every { seerrServerRepository.active } returns flowOf(false)
            assertEquals(MovieRatings(imdbUrl = IMDB_URL), service.movieRatings(TMDB_ID, IMDB_ID))
            coVerify { moviesApi wasNot Called }
        }

    @Test
    fun `A failure finding out if Seerr is active still gives the IMDb page`() =
        runTest {
            every { seerrServerRepository.active } returns flow { throw IllegalStateException("Nope") }
            assertEquals(MovieRatings(imdbUrl = IMDB_URL), service.movieRatings(TMDB_ID, IMDB_ID))
        }

    @Test
    fun `No TMDB ID makes no request`() =
        runTest {
            assertEquals(MovieRatings(imdbUrl = IMDB_URL), service.movieRatings(null, IMDB_ID))
            coVerify { moviesApi wasNot Called }
        }

    @Test
    fun `No IDs at all gives nothing`() =
        runTest {
            assertEquals(MovieRatings(), service.movieRatings(null, null))
        }

    @Test
    fun `Combined ratings have both sites`() =
        runTest {
            coEvery { moviesApi.movieMovieIdRatingscombinedGet(TMDB_ID) } returns
                MovieMovieIdRatingscombinedGet200Response(
                    rt = rottenTomatoes,
                    imdb =
                        MovieMovieIdRatingscombinedGet200ResponseImdb(
                            url = IMDB_URL,
                            criticsScore = "8.4".toBigDecimal(),
                        ),
                )
            assertEquals(
                MovieRatings(
                    imdbScore = 8.4f,
                    imdbUrl = IMDB_URL,
                    rottenTomatoesCritics = 93,
                    rottenTomatoesAudience = 94,
                    rottenTomatoesUrl = RT_URL,
                ),
                service.movieRatings(TMDB_ID, IMDB_ID),
            )
        }

    @Test
    fun `Seerr can supply the IMDb page when the item has no ID for it`() =
        runTest {
            coEvery { moviesApi.movieMovieIdRatingscombinedGet(TMDB_ID) } returns
                MovieMovieIdRatingscombinedGet200Response(
                    imdb = MovieMovieIdRatingscombinedGet200ResponseImdb(url = IMDB_URL),
                )
            assertEquals(MovieRatings(imdbUrl = IMDB_URL), service.movieRatings(TMDB_ID, null))
        }

    @Test
    fun `Combined failing falls back to Rotten Tomatoes`() =
        runTest {
            coEvery { moviesApi.movieMovieIdRatingscombinedGet(TMDB_ID) } throws ServerException("Oops", 500)
            coEvery { moviesApi.movieMovieIdRatingsGet(TMDB_ID) } returns rottenTomatoes
            assertEquals(
                MovieRatings(
                    imdbUrl = IMDB_URL,
                    rottenTomatoesCritics = 93,
                    rottenTomatoesAudience = 94,
                    rottenTomatoesUrl = RT_URL,
                ),
                service.movieRatings(TMDB_ID, IMDB_ID),
            )
        }

    @Test
    fun `Both failing still gives the IMDb page`() =
        runTest {
            coEvery { moviesApi.movieMovieIdRatingscombinedGet(TMDB_ID) } throws ServerException("Oops", 500)
            coEvery { moviesApi.movieMovieIdRatingsGet(TMDB_ID) } throws ClientException("Not found", 404)
            assertEquals(MovieRatings(imdbUrl = IMDB_URL), service.movieRatings(TMDB_ID, IMDB_ID))
        }

    @Test
    fun `URLs outside the allowlist are dropped but scores are kept`() =
        runTest {
            coEvery { moviesApi.movieMovieIdRatingscombinedGet(TMDB_ID) } returns
                MovieMovieIdRatingscombinedGet200Response(
                    rt = rottenTomatoes.copy(url = "http://www.rottentomatoes.com/m/alien"),
                    imdb =
                        MovieMovieIdRatingscombinedGet200ResponseImdb(
                            url = "https://evil.example/title/tt0078748",
                            criticsScore = "8.4".toBigDecimal(),
                        ),
                )
            assertEquals(
                MovieRatings(
                    imdbScore = 8.4f,
                    rottenTomatoesCritics = 93,
                    rottenTomatoesAudience = 94,
                ),
                service.movieRatings(TMDB_ID, null),
            )
        }

    @Test
    fun `Cancellation is not swallowed`() {
        coEvery { moviesApi.movieMovieIdRatingscombinedGet(TMDB_ID) } throws CancellationException("Left the page")
        assertThrows(CancellationException::class.java) {
            runTest { service.movieRatings(TMDB_ID, IMDB_ID) }
        }
    }

    @Test
    fun `The combined response decodes with a fractional IMDb score`() {
        val json =
            """
            {"rt":{"title":"Alien","url":"https://www.rottentomatoes.com/m/alien","criticsRating":"Certified Fresh",
            "criticsScore":93,"audienceRating":"Upright","audienceScore":94,"year":1979},
            "imdb":{"title":"Alien","url":"https://www.imdb.com/title/tt0078748","criticsScore":8.4,
            "criticsScoreCount":1081370}}
            """.trimIndent()
        val result = Serializer.kotlinxSerializationJson.decodeFromString<MovieMovieIdRatingscombinedGet200Response>(json)
        assertEquals(8.4f, result.imdb?.criticsScore?.toFloat())
        assertEquals(IMDB_URL, result.imdb?.url)
        assertEquals(93, result.rt?.criticsScore)
    }
}
