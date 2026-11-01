package com.github.damontecres.wholphin.data.model

import com.github.damontecres.wholphin.api.seerr.model.MovieDetails
import com.github.damontecres.wholphin.api.seerr.model.MovieDetailsReleases
import java.time.LocalDate
import java.time.format.DateTimeParseException

// TMDB's release types: limited & normal theatrical, digital & physical
private val THEATRICAL_RELEASES = setOf<Int?>(2, 3)
private val HOME_RELEASES = setOf<Int?>(4, 5)

private const val TMDB_STATUS_RELEASED = "Released"
private const val THEATRICAL_RELEASE_DAYS = 90L

// The dates on releases have a time, the one on the movie doesn't
private fun releaseDate(value: String?): LocalDate? =
    try {
        value?.take(10)?.let(LocalDate::parse)
    } catch (_: DateTimeParseException) {
        null
    }

/**
 * Whether a movie can be expected to be downloadable, by the same rule as Radarr
 *
 * That's once the first digital or physical release has come, anywhere. Without either it's 90 days
 * after the theatrical date, so old movies which TMDB has few dates for still count. Knowing no
 * dates at all, only TMDB's status can say that a movie isn't out.
 *
 * @param status TMDB's status of the movie
 * @param theatricalDate the movie's own release date
 * @param releases the release dates per country
 */
fun isMovieReleased(
    status: String?,
    theatricalDate: String?,
    releases: MovieDetailsReleases?,
    today: LocalDate,
): Boolean {
    val dates = releases?.results.orEmpty().flatMap { it.releaseDates.orEmpty() }

    fun earliest(types: Set<Int?>) = dates.filter { it.type in types }.mapNotNull { releaseDate(it.releaseDate) }.minOrNull()

    val home = earliest(HOME_RELEASES)
    if (home != null) return !home.isAfter(today)
    val theatrical = earliest(THEATRICAL_RELEASES) ?: releaseDate(theatricalDate)
    if (theatrical != null) return !theatrical.plusDays(THEATRICAL_RELEASE_DAYS).isAfter(today)
    return status == null || status == TMDB_STATUS_RELEASED
}

/**
 * Whether a requested movie has nothing to download yet because it isn't out, which is the reason
 * it has no download and not a problem with the request
 */
fun isWaitingForRelease(
    movie: MovieDetails,
    today: LocalDate,
): Boolean {
    val media = movie.mediaInfo ?: return false
    val approved =
        SeerrAvailability.from(media.status) == SeerrAvailability.PROCESSING ||
            media.requests.orEmpty().any { RequestStatus.from(it.status) == RequestStatus.APPROVED }
    return approved &&
        media.downloadStatus.isNullOrEmpty() &&
        !isMovieReleased(movie.status, movie.releaseDate, movie.releases, today)
}
