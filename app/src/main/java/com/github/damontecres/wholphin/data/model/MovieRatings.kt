package com.github.damontecres.wholphin.data.model

import com.github.damontecres.wholphin.BuildConfig
import java.net.URI
import java.net.URISyntaxException

private val IMDB_ID = Regex("tt\\d+")
private val RATING_PAGE_HOSTS = setOf("www.imdb.com", "www.rottentomatoes.com")

private const val FRESH_THRESHOLD = 60

/**
 * What the rating sites say about a movie, and where to read it
 *
 * @param imdbScore out of 10
 * @param imdbUrl the movie's page, which is what allows selecting the rating
 * @param rottenTomatoesCritics percent
 * @param rottenTomatoesAudience percent
 * @param rottenTomatoesUrl the movie's page, which is what allows selecting the rating
 */
data class MovieRatings(
    val imdbScore: Float? = null,
    val imdbUrl: String? = null,
    val rottenTomatoesCritics: Int? = null,
    val rottenTomatoesAudience: Int? = null,
    val rottenTomatoesUrl: String? = null,
) {
    val rottenTomatoesFresh: Boolean? get() = rottenTomatoesCritics?.let { it >= FRESH_THRESHOLD }

    /**
     * Whether something selectable shows the IMDb score, so it doesn't need to be shown elsewhere
     */
    val showsImdbScore: Boolean get() = imdbUrl != null && imdbScore != null

    /**
     * Whether something selectable shows the critics' score, so it doesn't need to be shown elsewhere
     */
    val showsCriticsScore: Boolean get() = rottenTomatoesUrl != null && rottenTomatoesCritics != null

    /**
     * Fills in whatever is missing from [previous]
     *
     * Ratings are fetched again on resume, and a request failing then must not take away a rating
     * that may have focus.
     */
    fun keepKnown(previous: MovieRatings?): MovieRatings =
        copy(
            imdbScore = imdbScore ?: previous?.imdbScore,
            imdbUrl = imdbUrl ?: previous?.imdbUrl,
            rottenTomatoesCritics = rottenTomatoesCritics ?: previous?.rottenTomatoesCritics,
            rottenTomatoesAudience = rottenTomatoesAudience ?: previous?.rottenTomatoesAudience,
            rottenTomatoesUrl = rottenTomatoesUrl ?: previous?.rottenTomatoesUrl,
        )

    companion object {
        /**
         * The IMDb page for an ID like tt0078748, or null if that isn't what [imdbId] is
         */
        fun imdbUrlFor(imdbId: String?): String? = imdbId?.takeIf(IMDB_ID::matches)?.let { "https://www.imdb.com/title/$it" }

        /**
         * Whether [url] is somewhere a rating is allowed to lead
         */
        fun isAllowedRatingPage(url: String): Boolean =
            try {
                val uri = URI(url)
                uri.scheme == "https" && uri.host in RATING_PAGE_HOSTS && uri.rawUserInfo == null && uri.port == -1
            } catch (_: URISyntaxException) {
                false
            }

        /**
         * What is known before asking anyone, so that the row of ratings is there from the start
         *
         * @return null if the feature isn't enabled
         */
        fun initial(
            imdbId: String?,
            ratingPagesEnabled: Boolean = BuildConfig.RATING_PAGES_ENABLED,
        ): MovieRatings? = if (ratingPagesEnabled) MovieRatings(imdbUrl = imdbUrlFor(imdbId)) else null
    }
}
