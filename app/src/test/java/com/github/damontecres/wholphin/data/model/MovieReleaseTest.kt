package com.github.damontecres.wholphin.data.model

import com.github.damontecres.wholphin.api.seerr.model.DownloadingItem
import com.github.damontecres.wholphin.api.seerr.model.MediaInfo
import com.github.damontecres.wholphin.api.seerr.model.MediaRequest
import com.github.damontecres.wholphin.api.seerr.model.MovieDetails
import com.github.damontecres.wholphin.api.seerr.model.MovieDetailsReleases
import com.github.damontecres.wholphin.api.seerr.model.MovieDetailsReleasesResultsInner
import com.github.damontecres.wholphin.api.seerr.model.MovieDetailsReleasesResultsInnerReleaseDatesInner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

private const val THEATRICAL = 3
private const val DIGITAL = 4
private const val PHYSICAL = 5

class MovieReleaseTest {
    private val today = LocalDate.parse("2026-10-31")

    private fun releases(vararg dates: Pair<Int, String>) =
        MovieDetailsReleases(
            results =
                listOf(
                    MovieDetailsReleasesResultsInner(
                        releaseDates =
                            dates.map { (type, date) ->
                                MovieDetailsReleasesResultsInnerReleaseDatesInner(
                                    type = type,
                                    releaseDate = "${date}T00:00:00.000Z",
                                )
                            },
                    ),
                ),
        )

    private fun released(
        releases: MovieDetailsReleases? = null,
        theatricalDate: String? = null,
        status: String? = "Released",
    ) = isMovieReleased(status, theatricalDate, releases, today)

    @Test
    fun `A digital or physical date in the past means it's out`() {
        assertTrue(released(releases(DIGITAL to "2026-10-01")))
        assertTrue(released(releases(PHYSICAL to "2026-10-01")))
    }

    @Test
    fun `The day itself counts`() {
        assertTrue(released(releases(DIGITAL to "2026-10-31")))
    }

    @Test
    fun `A digital or physical date to come means it isn't out, whatever the cinemas did`() {
        assertFalse(released(releases(THEATRICAL to "2020-01-01", DIGITAL to "2026-11-01")))
    }

    @Test
    fun `The first home release of any country counts`() {
        val releases =
            MovieDetailsReleases(
                results = releases(DIGITAL to "2027-01-01").results!! + releases(PHYSICAL to "2026-10-01").results!!,
            )
        assertTrue(released(releases))
    }

    @Test
    fun `Without a home release it's 90 days after the cinemas`() {
        assertFalse(released(releases(THEATRICAL to "2026-08-03")))
        assertTrue(released(releases(THEATRICAL to "2026-08-02")))
    }

    @Test
    fun `An old movie TMDB has only the movie's own date for is out`() {
        assertTrue(released(theatricalDate = "1979-05-25"))
    }

    @Test
    fun `A movie's own date works like the cinemas`() {
        assertFalse(released(theatricalDate = "2026-10-01"))
    }

    @Test
    fun `Without any dates only TMDB's status can say it isn't out`() {
        assertFalse(released(status = "Post Production"))
        assertTrue(released(status = "Released"))
        assertTrue(released(status = null))
    }

    @Test
    fun `A date that can't be read counts as no date`() {
        assertTrue(released(theatricalDate = "soon"))
        assertFalse(released(releases(DIGITAL to "soon"), status = "Planned"))
    }

    private fun movie(
        media: MediaInfo?,
        releaseDate: String = "2026-10-01",
    ) = MovieDetails(status = "In Production", releaseDate = releaseDate, mediaInfo = media)

    private val processing = MediaInfo(status = SeerrAvailability.PROCESSING.status)

    @Test
    fun `A movie being processed with no download waits for its release`() {
        assertTrue(isWaitingForRelease(movie(processing), today))
    }

    @Test
    fun `So does an approved request`() {
        val media =
            MediaInfo(
                status = SeerrAvailability.DELETED.status,
                requests = listOf(MediaRequest(id = 1, status = RequestStatus.APPROVED.status)),
            )
        assertTrue(isWaitingForRelease(movie(media), today))
    }

    @Test
    fun `A movie that's out isn't waiting`() {
        assertFalse(isWaitingForRelease(movie(processing, releaseDate = "2026-01-01"), today))
    }

    @Test
    fun `A download means it's not waiting, a release can turn up early`() {
        val media = processing.copy(downloadStatus = listOf(DownloadingItem(status = "downloading")))
        assertFalse(isWaitingForRelease(movie(media), today))
    }

    @Test
    fun `Nothing is waiting without a request or before it's approved`() {
        assertFalse(isWaitingForRelease(movie(null), today))
        assertFalse(isWaitingForRelease(movie(MediaInfo(status = SeerrAvailability.PENDING.status)), today))
    }

    @Test
    fun `A row says it's waiting instead of searching`() {
        fun state(released: Boolean) =
            downloadProgress(
                requestStatus = RequestStatus.APPROVED,
                requestExists = true,
                mediaStatus = SeerrAvailability.PROCESSING,
                queue = null,
                inJellyfin = false,
                seenInQueue = false,
                now = java.time.Instant.EPOCH,
                released = released,
            ).state

        assertEquals(DownloadState.SEARCHING, state(released = true))
        assertEquals(DownloadState.WAITING_FOR_RELEASE, state(released = false))
    }

    @Test
    fun `A download beats not being out yet`() {
        val progress =
            downloadProgress(
                requestStatus = RequestStatus.APPROVED,
                requestExists = true,
                mediaStatus = SeerrAvailability.PROCESSING,
                queue = listOf(DownloadingItem(status = "downloading")),
                inJellyfin = false,
                seenInQueue = false,
                now = java.time.Instant.EPOCH,
                released = false,
            )
        assertEquals(DownloadState.DOWNLOADING, progress.state)
    }
}
