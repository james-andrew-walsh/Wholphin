package com.github.damontecres.wholphin.data.filter

import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.ui.discover.discoverGenreTitle
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.ui.util.StringProvider
import com.github.damontecres.wholphin.util.DiscoverRequestType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class MovieBrowseShelvesTest {
    private val today = LocalDate.of(2026, 10, 30)
    private val rows = movieBrowseRows(today)

    private fun shelf(index: Int) = (rows[index] as BrowseRow.Items).shelf

    private val highlyRated get() = shelf(2)
    private val newReleases get() = shelf(3)

    private fun genreTitle(name: String): StringProvider = discoverGenreTitle(name, SeerrItemType.MOVIE)

    @Test
    fun `rows are in the ticket order`() {
        val expected =
            listOf<StringProvider?>(
                ResStringProvider(R.string.popular_this_week),
                ResStringProvider(R.string.popular),
                ResStringProvider(R.string.highly_rated),
                ResStringProvider(R.string.new_releases),
                ResStringProvider(R.string.upcoming),
                null,
                null,
                genreTitle("Action"),
                genreTitle("Comedy"),
                genreTitle("Drama"),
                genreTitle("Science Fiction"),
                genreTitle("Thriller"),
                genreTitle("Animation"),
            )
        assertEquals(expected, rows.map { (it as? BrowseRow.Items)?.shelf?.title })
        assertEquals(BrowseRow.Genres, rows[5])
        assertEquals(BrowseRow.Decades, rows[6])
    }

    @Test
    fun `row types`() {
        assertEquals(DiscoverRequestType.TRENDING_MOVIES_WEEK, shelf(0).type)
        assertEquals(DiscoverRequestType.DISCOVER_MOVIES, shelf(1).type)
        assertEquals(DiscoverRequestType.DISCOVER_MOVIES, highlyRated.type)
        assertEquals(DiscoverRequestType.DISCOVER_MOVIES, newReleases.type)
        assertEquals(DiscoverRequestType.UPCOMING_MOVIES, shelf(4).type)
        (7..12).forEach { assertEquals(DiscoverRequestType.DISCOVER_MOVIES, shelf(it).type) }
    }

    @Test
    fun `genre rows use the TMDB ids`() {
        assertEquals(
            listOf(28, 35, 18, 878, 53, 16),
            (7..12).map { shelf(it).filter.genreIds?.single() },
        )
    }

    @Test
    fun `highly rated sorts by vote average with a vote floor`() {
        assertEquals("vote_average.desc", highlyRated.filter.sortBy?.key(false))
        assertEquals(1000, highlyRated.filter.voteCountGte)
    }

    @Test
    fun `new releases are recent, already released and newest first`() {
        val filter = newReleases.filter
        assertEquals("primary_release_date.desc", filter.sortBy?.key(false))
        assertEquals("2026-05-03", filter.primaryReleaseDateGte)
        assertEquals("2026-10-30", filter.primaryReleaseDateLte)
        assertEquals(20, filter.voteCountGte)
    }

    @Test
    fun `dates are zero padded`() {
        val filter =
            (movieBrowseRows(LocalDate.of(2026, 1, 5))[3] as BrowseRow.Items).shelf.filter
        assertEquals("2025-07-09", filter.primaryReleaseDateGte)
        assertEquals("2026-01-05", filter.primaryReleaseDateLte)
    }

    @Test
    fun `decade boundaries`() {
        assertEquals(DiscoverYearRange("1990-01-01", "1999-12-31"), decadeRange(1990, today))
    }

    @Test
    fun `current decade is capped at today`() {
        assertEquals(DiscoverYearRange("2020-01-01", "2026-10-30"), decadeRange(2020, today))
        assertEquals(
            DiscoverYearRange("2020-01-01", "2029-12-31"),
            decadeRange(2020, LocalDate.of(2030, 1, 1)),
        )
    }

    @Test
    fun `decades run from the current decade back to pre 1950`() {
        val decades = movieDecades(today)
        assertEquals(
            listOf("2020s", "2010s", "2000s", "1990s", "1980s", "1970s", "1960s", "1950s", "Pre-1950"),
            decades.map { it.name },
        )
        assertEquals(decades.size, decades.distinctBy { it.id }.size)
        assertEquals(DiscoverYearRange("2020-01-01", "2026-10-30"), decades.first().range)
    }

    @Test
    fun `pre 1950 has no lower bound`() {
        val decade = movieDecades(today).last()
        assertNull(decade.range.gte)
        assertEquals("1949-12-31", decade.range.lte)
    }

    @Test
    fun `a new decade appears on its own`() {
        assertEquals("2030s", movieDecades(LocalDate.of(2031, 2, 1)).first().name)
    }

    @Test
    fun `a decade limits the filter to its dates only`() {
        assertEquals(
            DiscoverFilter(
                primaryReleaseDateGte = "1990-01-01",
                primaryReleaseDateLte = "1999-12-31",
            ),
            movieDecades(today).first { it.name == "1990s" }.filter,
        )
    }

    @Test
    fun `year filter options are decades then years`() {
        val options = movieYearFilterOptions(today)
        assertEquals(36, options.size)
        assertEquals(FilterValueOption("2020s", DiscoverYearRange("2020-01-01", "2029-12-31")), options.first())
        assertEquals(FilterValueOption("Pre-1950", DiscoverYearRange(null, "1949-12-31")), options[8])
        assertEquals(FilterValueOption("2026", DiscoverYearRange("2026-01-01", "2026-12-31")), options[9])
        assertEquals(FilterValueOption("2000", DiscoverYearRange("2000-01-01", "2000-12-31")), options.last())
    }

    /** A saved choice must keep matching its option, and keep admitting new releases */
    @Test
    fun `year filter options do not change as days pass`() {
        assertEquals(
            movieYearFilterOptions(today),
            movieYearFilterOptions(today.plusDays(3)),
        )
    }

    @Test
    fun `year filter gets and sets both dates`() {
        val range = DiscoverYearRange("1990-01-01", "1999-12-31")
        val filter = DiscoverMovieYearFilter.set(range, DiscoverFilter(genreIds = listOf(28)))

        assertEquals(range, DiscoverMovieYearFilter.get(filter))
        assertEquals(listOf(28), filter.genreIds)
        assertEquals(1, filter.countFilters(listOf(DiscoverMovieYearFilter)))
    }

    @Test
    fun `year filter is unset without dates and supports one side`() {
        assertNull(DiscoverMovieYearFilter.get(DiscoverFilter()))

        val oneSided = DiscoverYearRange(null, "1949-12-31")
        assertEquals(oneSided, DiscoverMovieYearFilter.get(DiscoverMovieYearFilter.set(oneSided, DiscoverFilter())))
    }

    @Test
    fun `clearing the year filter removes both dates`() {
        val filter = DiscoverMovieYearFilter.set(DiscoverYearRange("1990-01-01", "1999-12-31"), DiscoverFilter())
        assertEquals(DiscoverFilter(), filter.delete(listOf(DiscoverMovieYearFilter)))
    }

    @Test
    fun `the shared movie filter list has no year filter`() {
        assertFalse(DiscoverMovieYearFilter in discoverMovieFilters)
        assertEquals(discoverMovieFilters + DiscoverMovieYearFilter, browseMovieFilters)
    }
}
