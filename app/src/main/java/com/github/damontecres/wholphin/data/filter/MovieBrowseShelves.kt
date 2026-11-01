package com.github.damontecres.wholphin.data.filter

import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.ui.discover.discoverGenreTitle
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.util.DiscoverRequestType
import org.jellyfin.sdk.model.api.SortOrder
import java.time.LocalDate

// These three are judgment values, tune them against real results on the TV
const val HIGHLY_RATED_MIN_VOTES = 1000
const val NEW_RELEASE_MIN_VOTES = 20
const val NEW_RELEASE_WINDOW_DAYS = 180L

private const val EARLIEST_LISTED_YEAR = 2000

// The same ids as Seerr's src/components/Discover/constants.ts
val TOP_MOVIE_GENRES =
    listOf(
        BrowseGenre(28, "Action"),
        BrowseGenre(35, "Comedy"),
        BrowseGenre(18, "Drama"),
        BrowseGenre(878, "Science Fiction"),
        BrowseGenre(53, "Thriller"),
        BrowseGenre(16, "Animation"),
    )

/**
 * The rows of the movie browse page in display order
 */
fun movieBrowseRows(today: LocalDate): List<BrowseRow> =
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
        add(BrowseRow.Genres)
        add(BrowseRow.Decades)
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

/**
 * The cards of the decades row, newest first
 */
fun movieDecades(today: LocalDate): List<BrowseDecade> =
    decadeStartYears(today).map {
        BrowseDecade(it, "${it}s", decadeRange(it, today), DiscoverMovieYearFilter)
    } + BrowseDecade(EARLIEST_DECADE - 10, "Pre-$EARLIEST_DECADE", preEarliestDecadeRange(), DiscoverMovieYearFilter)

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

// Seerr fills the open side with 1900-01-01
private fun preEarliestDecadeRange() = DiscoverYearRange(null, "${EARLIEST_DECADE - 1}-12-31")
