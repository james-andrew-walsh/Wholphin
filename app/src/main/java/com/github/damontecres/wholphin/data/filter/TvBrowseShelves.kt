package com.github.damontecres.wholphin.data.filter

import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.ui.discover.discoverGenreTitle
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.util.DiscoverRequestType
import org.jellyfin.sdk.model.api.SortOrder
import java.time.LocalDate

// Judgment values like the movie ones, series get far fewer votes on TMDB than movies do
const val TV_HIGHLY_RATED_MIN_VOTES = 500
const val TV_NEW_SERIES_MIN_VOTES = 10
const val TV_NEW_SERIES_WINDOW_DAYS = 180L

private const val TV_EARLIEST_LISTED_YEAR = 2000

// TMDB's TV genres, some differ from the movie ones. Sci-Fi & Fantasy is a single TV genre.
val TOP_TV_GENRES =
    listOf(
        BrowseGenre(18, "Drama"),
        BrowseGenre(35, "Comedy"),
        BrowseGenre(80, "Crime"),
        BrowseGenre(10765, "Sci-Fi & Fantasy"),
        BrowseGenre(10759, "Action & Adventure"),
        BrowseGenre(16, "Animation"),
    )

/**
 * The rows of the TV browse page in display order
 *
 * TMDB's discover has no filter for what is airing now that Seerr passes on, so what is close to it
 * here is the series which first aired recently.
 */
fun tvBrowseRows(today: LocalDate): List<BrowseRow> =
    buildList {
        add(shelf(ResStringProvider(R.string.popular_this_week), DiscoverRequestType.TRENDING_TV_WEEK))
        add(shelf(ResStringProvider(R.string.popular), DiscoverRequestType.DISCOVER_TV))
        add(
            shelf(
                ResStringProvider(R.string.highly_rated),
                DiscoverRequestType.DISCOVER_TV,
                DiscoverFilter(
                    sortBy = DiscoverSortAndDirection(DiscoverSort.TMDB_VOTE, SortOrder.DESCENDING),
                    voteCountGte = TV_HIGHLY_RATED_MIN_VOTES,
                ),
            ),
        )
        add(
            shelf(
                ResStringProvider(R.string.new_series),
                DiscoverRequestType.DISCOVER_TV,
                DiscoverFilter(
                    sortBy = DiscoverSortAndDirection(DiscoverSort.RELEASE_DATE, SortOrder.DESCENDING),
                    firstAirDateGte = today.minusDays(TV_NEW_SERIES_WINDOW_DAYS).toString(),
                    firstAirDateLte = today.toString(),
                    voteCountGte = TV_NEW_SERIES_MIN_VOTES,
                ),
            ),
        )
        add(shelf(ResStringProvider(R.string.upcoming), DiscoverRequestType.UPCOMING_TV))
        add(BrowseRow.Genres)
        add(BrowseRow.Decades)
        TOP_TV_GENRES.forEach {
            add(
                shelf(
                    discoverGenreTitle(it.name, SeerrItemType.TV),
                    DiscoverRequestType.DISCOVER_TV,
                    DiscoverFilter(genreIds = listOf(it.id)),
                ),
            )
        }
    }

/**
 * The cards of the decades row, newest first
 *
 * Unlike movies there is no card for before the earliest decade, TMDB has next to nothing there.
 */
fun tvDecades(today: LocalDate): List<BrowseDecade> =
    decadeStartYears(today).map {
        BrowseDecade(it, "${it}s", decadeRange(it, today), DiscoverTvYearFilter)
    }

/**
 * The values of [DiscoverTvYearFilter], whole calendar periods for the same reason as
 * [movieYearFilterOptions]
 */
fun tvYearFilterOptions(today: LocalDate): List<FilterValueOption> =
    buildList {
        decadeStartYears(today).forEach {
            add(FilterValueOption("${it}s", wholeYears(it, it + 9)))
        }
        (today.year downTo TV_EARLIEST_LISTED_YEAR).forEach {
            add(FilterValueOption(it.toString(), wholeYears(it, it)))
        }
    }
