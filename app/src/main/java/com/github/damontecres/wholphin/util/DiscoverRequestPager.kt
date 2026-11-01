package com.github.damontecres.wholphin.util

import androidx.annotation.StringRes
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.seerr.SearchApi
import com.github.damontecres.wholphin.api.seerr.model.MovieResult
import com.github.damontecres.wholphin.api.seerr.model.TvResult
import com.github.damontecres.wholphin.data.filter.DiscoverFilter
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.services.SeerrApi
import com.github.damontecres.wholphin.services.SeerrSearchResult
import com.github.damontecres.wholphin.ui.discover.discoverGenreTitle
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.ui.util.StringProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable

/**
 * A [RequestPager] for Seerr server queries
 */
class DiscoverRequestPager<T>(
    private val api: SeerrApi,
    val requestHandler: DiscoverRequestHandler<T>,
    val transform: suspend (T) -> DiscoverItem,
    scope: CoroutineScope,
    pageSize: Int = SEERR_PAGE_SIZE,
    cacheSize: Long = 16,
) : RequestPager<DiscoverItem>(scope, pageSize, cacheSize) {
    override suspend fun init(initialPosition: Int): DiscoverRequestPager<T> = super.init(initialPosition) as DiscoverRequestPager<T>

    override suspend fun fetchPage(
        pageNumber: Int,
        includeTotalCount: Boolean,
    ): QueryResult<DiscoverItem> {
        val result = requestHandler.execute(api, pageNumber + 1) // Seerr pages are 1-indexed
        val transformed = result.items.map { transform.invoke(it) }
        return QueryResult(transformed, capSeerrTotal(result.totalCount))
    }
}

const val SEERR_PAGE_SIZE = 20

/** TMDB serves 500 pages at most, and Seerr returns an error for any page past that */
const val SEERR_MAX_RESULTS = 500 * SEERR_PAGE_SIZE

/**
 * Limit a reported total to the results that can actually be fetched, so that a grid ends cleanly
 * instead of requesting a page that fails. TMDB also caps the total it reports at 20,001.
 */
fun capSeerrTotal(
    total: Int?,
    enabled: Boolean = BuildConfig.BROWSE_DISCOVER_ENABLED,
): Int? = if (enabled) total?.coerceAtMost(SEERR_MAX_RESULTS) else total

enum class DiscoverRequestType(
    @param:StringRes val stringRes: Int,
) {
    DISCOVER_TV(R.string.discover_tv),
    DISCOVER_MOVIES(R.string.discover_movies),
    TRENDING(R.string.trending),
    UPCOMING_TV(R.string.upcoming_tv),
    UPCOMING_MOVIES(R.string.upcoming_movies),
    TRENDING_MOVIES_WEEK(R.string.popular_this_week),
    TRENDING_TV_WEEK(R.string.popular_this_week),
    UNKNOWN(R.string.unknown),
}

@Serializable
sealed interface DiscoverPagerType {
    val title: StringProvider

    @Serializable
    data class RequestType(
        val type: DiscoverRequestType,
    ) : DiscoverPagerType {
        override val title: StringProvider = ResStringProvider(type.stringRes)
    }

    @Serializable
    data class Genre(
        val genreId: Int,
        val name: String,
        val type: SeerrItemType,
    ) : DiscoverPagerType {
        override val title: StringProvider = discoverGenreTitle(name, type)
    }
}

/**
 * Specifies how a [RequestPager] should prepare and execute API calls
 */
interface DiscoverRequestHandler<T> {
    suspend fun execute(
        api: SeerrApi,
        pageNumber: Int,
    ): QueryResult<T>
}

class DiscoverTvRequestHandler(
    val filter: DiscoverFilter = DiscoverFilter(),
) : DiscoverRequestHandler<TvResult> {
    override suspend fun execute(
        api: SeerrApi,
        pageNumber: Int,
    ): QueryResult<TvResult> =
        filter.discoverTv(api.api.searchApi, pageNumber).let {
            QueryResult(it.results.orEmpty(), it.totalResults ?: 0)
        }
}

class DiscoverMovieRequestHandler(
    val filter: DiscoverFilter = DiscoverFilter(),
) : DiscoverRequestHandler<MovieResult> {
    override suspend fun execute(
        api: SeerrApi,
        pageNumber: Int,
    ): QueryResult<MovieResult> =
        filter.discoverMovies(api.api.searchApi, pageNumber).let {
            QueryResult(it.results.orEmpty(), it.totalResults ?: 0)
        }
}

val TrendingRequestHandler =
    object : DiscoverRequestHandler<SeerrSearchResult> {
        override suspend fun execute(
            api: SeerrApi,
            pageNumber: Int,
        ): QueryResult<SeerrSearchResult> =
            api.api.searchApi.discoverTrendingGet(page = pageNumber).let {
                QueryResult(it.results.orEmpty(), it.totalResults ?: 0)
            }
    }

private fun trendingWeekHandler(mediaType: SearchApi.MediaTypeDiscoverTrendingGet) =
    object : DiscoverRequestHandler<SeerrSearchResult> {
        override suspend fun execute(
            api: SeerrApi,
            pageNumber: Int,
        ): QueryResult<SeerrSearchResult> =
            api.api.searchApi
                .discoverTrendingGet(
                    page = pageNumber,
                    mediaType = mediaType,
                    timeWindow = SearchApi.TimeWindowDiscoverTrendingGet.WEEK,
                ).let {
                    QueryResult(it.results.orEmpty(), it.totalResults ?: 0)
                }
    }

val TrendingMoviesWeekRequestHandler = trendingWeekHandler(SearchApi.MediaTypeDiscoverTrendingGet.MOVIE)

val TrendingTvWeekRequestHandler = trendingWeekHandler(SearchApi.MediaTypeDiscoverTrendingGet.TV)

val UpcomingTvRequestHandler =
    object : DiscoverRequestHandler<TvResult> {
        override suspend fun execute(
            api: SeerrApi,
            pageNumber: Int,
        ): QueryResult<TvResult> =
            api.api.searchApi.discoverTvUpcomingGet(page = pageNumber).let {
                QueryResult(it.results.orEmpty(), it.totalResults ?: 0)
            }
    }

val UpcomingMovieRequestHandler =
    object : DiscoverRequestHandler<MovieResult> {
        override suspend fun execute(
            api: SeerrApi,
            pageNumber: Int,
        ): QueryResult<MovieResult> =
            api.api.searchApi.discoverMoviesUpcomingGet(page = pageNumber).let {
                QueryResult(it.results.orEmpty(), it.totalResults ?: 0)
            }
    }
