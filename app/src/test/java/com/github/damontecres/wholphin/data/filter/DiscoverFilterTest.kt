package com.github.damontecres.wholphin.data.filter

import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.services.KeyValueService
import com.github.damontecres.wholphin.services.SeerrApi
import com.github.damontecres.wholphin.ui.discover.discoverGenreTitle
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.util.DiscoverRequestType
import com.github.damontecres.wholphin.util.TrendingMoviesWeekRequestHandler
import com.github.damontecres.wholphin.util.TrendingRequestHandler
import com.github.damontecres.wholphin.util.TrendingTvWeekRequestHandler
import com.github.damontecres.wholphin.util.requestSerializersModule
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jellyfin.sdk.model.api.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Sends real requests through the generated client so these check the query that leaves the app
 */
class DiscoverFilterTest {
    private val urls = CopyOnWriteArrayList<HttpUrl>()

    private val client =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                urls.add(chain.request().url)
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/json")
                    .body("""{"results":[]}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

    private val seerrApi = SeerrApi(client).apply { update("http://seerr.test/", null) }

    private val searchApi get() = seerrApi.api.searchApi

    private val keyValueJson = KeyValueService(mockk(relaxed = true)).json

    // The same as MainActivity uses for the back stack
    private val backStackJson =
        Json {
            classDiscriminator = "_type"
            serializersModule = requestSerializersModule
        }

    @Test
    fun `discoverMovies sends the dates, sort and vote count`() =
        runTest {
            DiscoverFilter(
                sortBy = DiscoverSortAndDirection(DiscoverSort.PRIMARY_RELEASE_DATE, SortOrder.DESCENDING),
                primaryReleaseDateGte = "2026-05-03",
                primaryReleaseDateLte = "2026-10-30",
                voteCountGte = 20,
            ).discoverMovies(searchApi, 1)

            val url = urls.single()
            assertEquals("primary_release_date.desc", url.queryParameter("sortBy"))
            assertEquals("2026-05-03", url.queryParameter("primaryReleaseDateGte"))
            assertEquals("2026-10-30", url.queryParameter("primaryReleaseDateLte"))
            assertEquals("20", url.queryParameter("voteCountGte"))
        }

    @Test
    fun `discoverMovies with a default filter only sends the page`() =
        runTest {
            DiscoverFilter().discoverMovies(searchApi, 2)

            val url = urls.single()
            assertEquals(setOf("page"), url.queryParameterNames)
            assertEquals("2", url.queryParameter("page"))
        }

    @Test
    fun `an open ended date range only sends one side`() =
        runTest {
            DiscoverMovieYearFilter
                .set(DiscoverYearRange(null, "1949-12-31"), DiscoverFilter())
                .discoverMovies(searchApi, 1)

            assertEquals(setOf("page", "primaryReleaseDateLte"), urls.single().queryParameterNames)
        }

    @Test
    fun `discoverTv does not send the movie dates`() =
        runTest {
            DiscoverFilter(
                primaryReleaseDateGte = "2026-05-03",
                primaryReleaseDateLte = "2026-10-30",
            ).discoverTv(searchApi, 1)

            assertEquals(setOf("page"), urls.single().queryParameterNames)
        }

    @Test
    fun `discoverTv sends the first air dates, sort and vote count`() =
        runTest {
            DiscoverFilter(
                sortBy = DiscoverSortAndDirection(DiscoverSort.RELEASE_DATE, SortOrder.DESCENDING),
                firstAirDateGte = "2026-05-03",
                firstAirDateLte = "2026-10-30",
                voteCountGte = 10,
            ).discoverTv(searchApi, 1)

            val url = urls.single()
            assertEquals("/api/v1/discover/tv", url.encodedPath)
            assertEquals("first_air_date.desc", url.queryParameter("sortBy"))
            assertEquals("2026-05-03", url.queryParameter("firstAirDateGte"))
            assertEquals("2026-10-30", url.queryParameter("firstAirDateLte"))
            assertEquals("10", url.queryParameter("voteCountGte"))
        }

    @Test
    fun `discoverMovies does not send the first air dates`() =
        runTest {
            DiscoverFilter(
                firstAirDateGte = "2026-05-03",
                firstAirDateLte = "2026-10-30",
            ).discoverMovies(searchApi, 1)

            assertEquals(setOf("page"), urls.single().queryParameterNames)
        }

    @Test
    fun `discoverTv with a default filter only sends the page`() =
        runTest {
            DiscoverFilter().discoverTv(searchApi, 2)

            assertEquals(setOf("page"), urls.single().queryParameterNames)
        }

    @Test
    fun `the TV browse rows only send names Seerr knows`() =
        runTest {
            tvBrowseRows(LocalDate.of(2026, 10, 30))
                .filterIsInstance<BrowseRow.Items>()
                .filter { it.shelf.type == DiscoverRequestType.DISCOVER_TV }
                .forEach { it.shelf.filter.discoverTv(searchApi, 1) }

            val sent = urls.flatMap { it.queryParameterNames }.toSet()
            assertEquals(
                setOf("page", "genre", "sortBy", "firstAirDateGte", "firstAirDateLte", "voteCountGte"),
                sent,
            )
        }

    @Test
    fun `sort by name uses the TV name, the shared one is not valid for TV`() {
        val sort = DiscoverSortAndDirection(DiscoverSort.ORIGINAL_NAME, SortOrder.ASCENDING)
        assertEquals("original_name.asc", sort.key(true))
    }

    @Test
    fun `tv still uses first_air_date for the new sort`() {
        val sort = DiscoverSortAndDirection(DiscoverSort.PRIMARY_RELEASE_DATE, SortOrder.DESCENDING)
        assertEquals("first_air_date.desc", sort.key(true))
    }

    @Test
    fun `the upstream trending request is unchanged`() =
        runTest {
            TrendingRequestHandler.execute(seerrApi, 1)

            val url = urls.single()
            assertEquals("/api/v1/discover/trending", url.encodedPath)
            assertEquals(setOf("page"), url.queryParameterNames)
        }

    @Test
    fun `popular this week asks for a week of movies`() =
        runTest {
            TrendingMoviesWeekRequestHandler.execute(seerrApi, 3)

            val url = urls.single()
            assertEquals("/api/v1/discover/trending", url.encodedPath)
            assertEquals(setOf("page", "mediaType", "timeWindow"), url.queryParameterNames)
            assertEquals("3", url.queryParameter("page"))
            assertEquals("movie", url.queryParameter("mediaType"))
            assertEquals("week", url.queryParameter("timeWindow"))
        }

    @Test
    fun `popular this week asks for a week of series`() =
        runTest {
            TrendingTvWeekRequestHandler.execute(seerrApi, 3)

            val url = urls.single()
            assertEquals("/api/v1/discover/trending", url.encodedPath)
            assertEquals(setOf("page", "mediaType", "timeWindow"), url.queryParameterNames)
            assertEquals("3", url.queryParameter("page"))
            assertEquals("tv", url.queryParameter("mediaType"))
            assertEquals("week", url.queryParameter("timeWindow"))
        }

    @Test
    fun `a filter round trips through the saved format`() {
        val filter =
            DiscoverFilter(
                genreIds = listOf(28, 35),
                sortBy = DiscoverSortAndDirection(DiscoverSort.PRIMARY_RELEASE_DATE, SortOrder.ASCENDING),
                primaryReleaseDateGte = "1990-01-01",
                primaryReleaseDateLte = "1999-12-31",
                voteAverageGte = 7,
                certification = listOf("PG"),
                networkIds = listOf(8),
                firstAirDateGte = "2000-01-01",
                firstAirDateLte = "2009-12-31",
            )
        val encoded = keyValueJson.encodeToString(DiscoverFilter.serializer(), filter)

        assertEquals(filter, keyValueJson.decodeFromString(DiscoverFilter.serializer(), encoded))
    }

    @Test
    fun `a filter saved before the date fields still decodes`() {
        assertEquals(
            DiscoverFilter(genreIds = listOf(28)),
            keyValueJson.decodeFromString(DiscoverFilter.serializer(), """{"genreIds":[28]}"""),
        )
    }

    @Test
    fun `the back stack round trips the new destinations`() {
        val destinations =
            listOf<Destination>(
                Destination.DiscoverMoreResult(
                    type = DiscoverRequestType.DISCOVER_MOVIES,
                    startIndex = 0,
                    initialFilter =
                        DiscoverFilter(
                            sortBy = DiscoverSortAndDirection(DiscoverSort.PRIMARY_RELEASE_DATE, SortOrder.DESCENDING),
                            primaryReleaseDateGte = "1990-01-01",
                            primaryReleaseDateLte = "1999-12-31",
                        ),
                    titleOverride = discoverGenreTitle("1990s", SeerrItemType.MOVIE),
                ),
                Destination.DiscoverMoreResult(
                    type = DiscoverRequestType.TRENDING_MOVIES_WEEK,
                    titleOverride = ResStringProvider(R.string.popular_this_week),
                ),
                Destination.DiscoverMoreResult(
                    type = DiscoverRequestType.DISCOVER_TV,
                    startIndex = 0,
                    initialFilter =
                        DiscoverFilter(
                            firstAirDateGte = "1990-01-01",
                            firstAirDateLte = "1999-12-31",
                        ),
                    titleOverride = discoverGenreTitle("1990s", SeerrItemType.TV),
                ),
                Destination.DiscoverMoreResult(
                    type = DiscoverRequestType.TRENDING_TV_WEEK,
                    titleOverride = ResStringProvider(R.string.popular_this_week),
                ),
            )
        val serializer = ListSerializer(Destination.serializer())
        val encoded = backStackJson.encodeToString(serializer, destinations)

        assertEquals(destinations, backStackJson.decodeFromString(serializer, encoded))
    }
}
