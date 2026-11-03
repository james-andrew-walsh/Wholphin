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

class TvBrowseShelvesTest {
    private val today = LocalDate.of(2026, 10, 30)
    private val rows = tvBrowseRows(today)

    private fun shelf(index: Int) = (rows[index] as BrowseRow.Items).shelf

    private val highlyRated get() = shelf(2)
    private val newSeries get() = shelf(3)

    private fun genreTitle(name: String): StringProvider = discoverGenreTitle(name, SeerrItemType.TV)

    @Test
    fun `rows are in the same order as the movie rows`() {
        val expected =
            listOf<StringProvider?>(
                ResStringProvider(R.string.popular_this_week),
                ResStringProvider(R.string.popular),
                ResStringProvider(R.string.highly_rated),
                ResStringProvider(R.string.new_series),
                ResStringProvider(R.string.upcoming),
                null,
                null,
                genreTitle("Drama"),
                genreTitle("Comedy"),
                genreTitle("Crime"),
                genreTitle("Sci-Fi & Fantasy"),
                genreTitle("Action & Adventure"),
                genreTitle("Animation"),
            )
        assertEquals(expected, rows.map { (it as? BrowseRow.Items)?.shelf?.title })
        assertEquals(BrowseRow.Genres, rows[5])
        assertEquals(BrowseRow.Decades, rows[6])
    }

    @Test
    fun `row types`() {
        assertEquals(DiscoverRequestType.TRENDING_TV_WEEK, shelf(0).type)
        assertEquals(DiscoverRequestType.DISCOVER_TV, shelf(1).type)
        assertEquals(DiscoverRequestType.DISCOVER_TV, highlyRated.type)
        assertEquals(DiscoverRequestType.DISCOVER_TV, newSeries.type)
        assertEquals(DiscoverRequestType.UPCOMING_TV, shelf(4).type)
        (7..12).forEach { assertEquals(DiscoverRequestType.DISCOVER_TV, shelf(it).type) }
    }

    @Test
    fun `genre rows use the TMDB TV ids`() {
        assertEquals(
            listOf(18, 35, 80, 10765, 10759, 16),
            (7..12).map { shelf(it).filter.genreIds?.single() },
        )
    }

    @Test
    fun `highly rated sorts by vote average with a vote floor`() {
        assertEquals("vote_average.desc", highlyRated.filter.sortBy?.key(true))
        assertEquals(TV_HIGHLY_RATED_MIN_VOTES, highlyRated.filter.voteCountGte)
    }

    @Test
    fun `new series started recently, already aired and newest first`() {
        val filter = newSeries.filter
        assertEquals("first_air_date.desc", filter.sortBy?.key(true))
        assertEquals("2026-05-03", filter.firstAirDateGte)
        assertEquals("2026-10-30", filter.firstAirDateLte)
        assertEquals(TV_NEW_SERIES_MIN_VOTES, filter.voteCountGte)
    }

    @Test
    fun `no row uses the movie only date fields`() {
        rows.filterIsInstance<BrowseRow.Items>().forEach {
            assertNull(it.shelf.filter.primaryReleaseDateGte)
            assertNull(it.shelf.filter.primaryReleaseDateLte)
        }
    }

    @Test
    fun `decades run from the current decade back to the 1950s`() {
        val decades = tvDecades(today)
        assertEquals(
            listOf("2020s", "2010s", "2000s", "1990s", "1980s", "1970s", "1960s", "1950s"),
            decades.map { it.name },
        )
        assertEquals(decades.size, decades.distinctBy { it.id }.size)
        assertEquals(DiscoverYearRange("2020-01-01", "2026-10-30"), decades.first().range)
    }

    @Test
    fun `a decade limits the filter to its first air dates only`() {
        assertEquals(
            DiscoverFilter(
                firstAirDateGte = "1990-01-01",
                firstAirDateLte = "1999-12-31",
            ),
            tvDecades(today).first { it.name == "1990s" }.filter,
        )
    }

    @Test
    fun `year filter options are decades then years`() {
        val options = tvYearFilterOptions(today)
        assertEquals(35, options.size)
        assertEquals(FilterValueOption("2020s", DiscoverYearRange("2020-01-01", "2029-12-31")), options.first())
        assertEquals(FilterValueOption("2026", DiscoverYearRange("2026-01-01", "2026-12-31")), options[8])
        assertEquals(FilterValueOption("2000", DiscoverYearRange("2000-01-01", "2000-12-31")), options.last())
    }

    /** A chosen value must keep matching its option */
    @Test
    fun `year filter options do not change as days pass`() {
        assertEquals(tvYearFilterOptions(today), tvYearFilterOptions(today.plusDays(3)))
    }

    @Test
    fun `year filter gets and sets both dates`() {
        val range = DiscoverYearRange("1990-01-01", "1999-12-31")
        val filter = DiscoverTvYearFilter.set(range, DiscoverFilter(genreIds = listOf(18)))

        assertEquals(range, DiscoverTvYearFilter.get(filter))
        assertEquals(listOf(18), filter.genreIds)
        assertEquals(1, filter.countFilters(listOf(DiscoverTvYearFilter)))
    }

    @Test
    fun `clearing the year filter removes both dates`() {
        val filter = DiscoverTvYearFilter.set(DiscoverYearRange("1990-01-01", "1999-12-31"), DiscoverFilter())
        assertEquals(DiscoverFilter(), filter.delete(listOf(DiscoverTvYearFilter)))
    }

    @Test
    fun `movie and TV year filters don't read each other's dates`() {
        val movie = DiscoverMovieYearFilter.set(DiscoverYearRange("1990-01-01", "1999-12-31"), DiscoverFilter())
        val tv = DiscoverTvYearFilter.set(DiscoverYearRange("1990-01-01", "1999-12-31"), DiscoverFilter())

        assertNull(DiscoverTvYearFilter.get(movie))
        assertNull(DiscoverMovieYearFilter.get(tv))
    }

    @Test
    fun `the shared TV filter list has no year filter`() {
        assertFalse(DiscoverTvYearFilter in discoverTvFilters)
        assertEquals(discoverTvFilters + DiscoverTvYearFilter + DiscoverLanguageFilter, browseTvFilters)
    }
}
