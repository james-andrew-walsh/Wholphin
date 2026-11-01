package com.github.damontecres.wholphin.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieRatingsTest {
    @Test
    fun `imdbUrlFor builds the page for a valid ID`() {
        assertEquals("https://www.imdb.com/title/tt0078748", MovieRatings.imdbUrlFor("tt0078748"))
    }

    @Test
    fun `imdbUrlFor refuses anything else`() {
        listOf(null, "", "tt", "0078748", "tt123abc", "tt0078748/../evil", "TT0078748", " tt0078748").forEach {
            assertNull(it, MovieRatings.imdbUrlFor(it))
        }
    }

    @Test
    fun `Rating pages are allowed`() {
        listOf(
            "https://www.imdb.com/title/tt0078748",
            "https://www.rottentomatoes.com/m/alien",
            "https://www.rottentomatoes.com/m/alien?a=1#b",
        ).forEach {
            assertTrue(it, MovieRatings.isAllowedRatingPage(it))
        }
    }

    @Test
    fun `Anything else is not a rating page`() {
        listOf(
            "",
            "not a url",
            "http://www.imdb.com/title/tt0078748",
            "intent://www.imdb.com/title/tt0078748",
            "javascript:alert(1)",
            "https://imdb.com/title/tt0078748",
            "https://m.imdb.com/title/tt0078748",
            "https://www.imdb.com.evil.example/title/tt0078748",
            "https://evil.example/www.imdb.com",
            "https://www.imdb.com@evil.example/",
            "https://user@www.imdb.com/",
            "https://www.imdb.com:8443/",
            "https://www.imdb.com\\@evil.example/",
        ).forEach {
            assertFalse(it, MovieRatings.isAllowedRatingPage(it))
        }
    }

    @Test
    fun `Fresh starts at 60`() {
        assertNull(MovieRatings().rottenTomatoesFresh)
        assertEquals(false, MovieRatings(rottenTomatoesCritics = 59).rottenTomatoesFresh)
        assertEquals(true, MovieRatings(rottenTomatoesCritics = 60).rottenTomatoesFresh)
    }

    @Test
    fun `A score only counts as shown if it can be selected`() {
        assertFalse(MovieRatings(imdbScore = 8.4f).showsImdbScore)
        assertFalse(MovieRatings(imdbUrl = "url").showsImdbScore)
        assertTrue(MovieRatings(imdbScore = 8.4f, imdbUrl = "url").showsImdbScore)
        assertFalse(MovieRatings(rottenTomatoesCritics = 93).showsCriticsScore)
        assertFalse(MovieRatings(rottenTomatoesUrl = "url", rottenTomatoesAudience = 94).showsCriticsScore)
        assertTrue(MovieRatings(rottenTomatoesCritics = 93, rottenTomatoesUrl = "url").showsCriticsScore)
    }

    @Test
    fun `keepKnown fills in what a failed reload lost`() {
        val previous =
            MovieRatings(
                imdbScore = 8.4f,
                imdbUrl = "imdb",
                rottenTomatoesCritics = 93,
                rottenTomatoesAudience = 94,
                rottenTomatoesUrl = "rt",
            )
        assertEquals(previous, MovieRatings(imdbUrl = "imdb").keepKnown(previous))
    }

    @Test
    fun `keepKnown prefers new values`() {
        val previous = MovieRatings(imdbScore = 8.4f, rottenTomatoesCritics = 93)
        val new = MovieRatings(imdbScore = 8.5f)
        assertEquals(MovieRatings(imdbScore = 8.5f, rottenTomatoesCritics = 93), new.keepKnown(previous))
        assertEquals(new, new.keepKnown(null))
    }

    @Test
    fun `initial has the IMDb page only when enabled`() {
        assertEquals(
            MovieRatings(imdbUrl = "https://www.imdb.com/title/tt0078748"),
            MovieRatings.initial("tt0078748", ratingPagesEnabled = true),
        )
        assertEquals(MovieRatings(), MovieRatings.initial(null, ratingPagesEnabled = true))
        assertNull(MovieRatings.initial("tt0078748", ratingPagesEnabled = false))
    }
}
