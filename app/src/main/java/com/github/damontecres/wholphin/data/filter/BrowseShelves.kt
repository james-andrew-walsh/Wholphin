package com.github.damontecres.wholphin.data.filter

import com.github.damontecres.wholphin.ui.util.StringProvider
import com.github.damontecres.wholphin.util.DiscoverRequestType
import java.time.LocalDate

internal const val EARLIEST_DECADE = 1950

/**
 * A TMDB genre, movies and TV have different ids
 */
data class BrowseGenre(
    val id: Int,
    val name: String,
)

/**
 * A row of items, the row and its grid both use [type] with [filter]
 */
data class BrowseShelf(
    val title: StringProvider,
    val type: DiscoverRequestType,
    val filter: DiscoverFilter = DiscoverFilter(),
)

/**
 * A row on a browse page
 */
sealed interface BrowseRow {
    data class Items(
        val shelf: BrowseShelf,
    ) : BrowseRow

    data object Genres : BrowseRow

    data object Decades : BrowseRow
}

/**
 * A card in the decades row
 *
 * @param id unique within the row
 * @param yearFilter how [range] goes into a [DiscoverFilter]
 */
data class BrowseDecade(
    val id: Int,
    val name: String,
    val range: DiscoverYearRange,
    val yearFilter: DiscoverFilterBy<DiscoverYearRange>,
) {
    val filter: DiscoverFilter get() = yearFilter.set(range, DiscoverFilter())
}

internal fun shelf(
    title: StringProvider,
    type: DiscoverRequestType,
    filter: DiscoverFilter = DiscoverFilter(),
) = BrowseRow.Items(BrowseShelf(title, type, filter))

/**
 * The range for the decade starting at [startYear], ending no later than [today] so that the
 * current decade does not include what is yet to come out
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

internal fun decadeStartYears(today: LocalDate) = (today.year / 10 * 10 downTo EARLIEST_DECADE step 10).toList()

internal fun wholeYears(
    firstYear: Int,
    lastYear: Int,
) = DiscoverYearRange("$firstYear-01-01", "$lastYear-12-31")
