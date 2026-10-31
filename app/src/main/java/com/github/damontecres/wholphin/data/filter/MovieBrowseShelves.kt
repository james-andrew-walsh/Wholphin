package com.github.damontecres.wholphin.data.filter

import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.ui.discover.discoverGenreTitle
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.ui.util.StringProvider
import com.github.damontecres.wholphin.util.DiscoverRequestType
import org.jellyfin.sdk.model.api.SortOrder
import java.time.LocalDate

// These three are judgment values, tune them against real results on the TV
const val HIGHLY_RATED_MIN_VOTES = 1000
const val NEW_RELEASE_MIN_VOTES = 20
const val NEW_RELEASE_WINDOW_DAYS = 180L

private const val EARLIEST_DECADE = 1950
private const val EARLIEST_LISTED_YEAR = 2000

/**
 * A TMDB movie genre
 */
data class MovieGenre(
    val id: Int,
    val name: String,
)

// The same ids as Seerr's src/components/Discover/constants.ts
val TOP_MOVIE_GENRES =
    listOf(
        MovieGenre(28, "Action"),
        MovieGenre(35, "Comedy"),
        MovieGenre(18, "Drama"),
        MovieGenre(878, "Science Fiction"),
        MovieGenre(53, "Thriller"),
        MovieGenre(16, "Animation"),
    )

/**
 * A row of movies, the row and its grid both use [type] with [filter]
 */
data class MovieShelf(
    val title: StringProvider,
    val type: DiscoverRequestType,
    val filter: DiscoverFilter = DiscoverFilter(),
)

/**
 * A row on the movie browse page
 */
sealed interface MovieBrowseRow {
    data class Items(
        val shelf: MovieShelf,
    ) : MovieBrowseRow

    data object Genres : MovieBrowseRow

    data object Decades : MovieBrowseRow
}

/**
 * A card in the decades row
 *
 * @param id unique within the row
 */
data class MovieDecade(
    val id: Int,
    val name: String,
    val range: DiscoverYearRange,
) {
    val filter: DiscoverFilter get() = DiscoverMovieYearFilter.set(range, DiscoverFilter())
}

/**
 * The rows of the movie browse page in display order
 */
fun movieBrowseRows(today: LocalDate): List<MovieBrowseRow> =
    buildList {
        add(shelf(ResStringProvider(R.string.popular_this_week), DiscoverRequestType.TRENDING_MOVIES_WEEK))
        add(shelf(ResStringProvider(R.string.popular), DiscoverRequestType.DISCOVER_MOVIES))
        add(
            shelf(
                ResStringProvider(R.string.highly_rated),
                DiscoverRequestType.DISCOVER_MOVIES,
                DiscoverFilter(
                    sortBy = DiscoverSortAndDirection(DiscoverSort.TMDB_VOTE, SortOrder.DESCENDING),
                    voteCountGte = HIGHLY_RATED_MIN_VOTES,
                ),
            ),
        )
        add(
            shelf(
                ResStringProvider(R.string.new_releases),
                DiscoverRequestType.DISCOVER_MOVIES,
                DiscoverFilter(
                    sortBy =
                        DiscoverSortAndDirection(
                            DiscoverSort.PRIMARY_RELEASE_DATE,
                            SortOrder.DESCENDING,
                        ),
                    primaryReleaseDateGte = today.minusDays(NEW_RELEASE_WINDOW_DAYS).toString(),
                    primaryReleaseDateLte = today.toString(),
                    voteCountGte = NEW_RELEASE_MIN_VOTES,
                ),
            ),
        )
        add(shelf(ResStringProvider(R.string.upcoming), DiscoverRequestType.UPCOMING_MOVIES))
        add(MovieBrowseRow.Genres)
        add(MovieBrowseRow.Decades)
        TOP_MOVIE_GENRES.forEach {
            add(
                shelf(
                    discoverGenreTitle(it.name, SeerrItemType.MOVIE),
                    DiscoverRequestType.DISCOVER_MOVIES,
                    DiscoverFilter(genreIds = listOf(it.id)),
                ),
            )
        }
    }

private fun shelf(
    title: StringProvider,
    type: DiscoverRequestType,
    filter: DiscoverFilter = DiscoverFilter(),
) = MovieBrowseRow.Items(MovieShelf(title, type, filter))

/**
 * The range for the decade starting at [startYear], ending no later than [today] so that the
 * current decade does not include unreleased movies
 */
fun decadeRange(
    startYear: Int,
    today: LocalDate,
): DiscoverYearRange {
    val end = LocalDate.of(startYear + 9, 12, 31)
    return DiscoverYearRange(
        gte = LocalDate.of(startYear, 1, 1).toString(),
        lte = (if (end.isAfter(today)) today else end).toString(),
    )
}

/**
 * The cards of the decades row, newest first
 */
fun movieDecades(today: LocalDate): List<MovieDecade> =
    decadeStartYears(today).map { MovieDecade(it, "${it}s", decadeRange(it, today)) } +
        MovieDecade(EARLIEST_DECADE - 10, "Pre-$EARLIEST_DECADE", preEarliestDecadeRange())

/**
 * The values of [DiscoverMovieYearFilter]: decades, then years down to [EARLIEST_LISTED_YEAR]
 *
 * These are whole calendar periods, unlike [decadeRange]. The chosen value is saved across
 * restarts, so an end date of today would stop new releases from appearing later and the choice
 * would no longer match its option.
 */
fun movieYearFilterOptions(today: LocalDate): List<FilterValueOption> =
    buildList {
        decadeStartYears(today).forEach {
            add(FilterValueOption("${it}s", wholeYears(it, it + 9)))
        }
        add(FilterValueOption("Pre-$EARLIEST_DECADE", preEarliestDecadeRange()))
        (today.year downTo EARLIEST_LISTED_YEAR).forEach {
            add(FilterValueOption(it.toString(), wholeYears(it, it)))
        }
    }

private fun decadeStartYears(today: LocalDate) = (today.year / 10 * 10 downTo EARLIEST_DECADE step 10).toList()

private fun wholeYears(
    firstYear: Int,
    lastYear: Int,
) = DiscoverYearRange("$firstYear-01-01", "$lastYear-12-31")

// Seerr fills the open side with 1900-01-01
private fun preEarliestDecadeRange() = DiscoverYearRange(null, "${EARLIEST_DECADE - 1}-12-31")
