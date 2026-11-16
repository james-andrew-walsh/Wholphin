package com.github.damontecres.wholphin.ui.discover

import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.QueueRemoval
import com.github.damontecres.wholphin.data.model.QueueRow
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.data.model.TitleKey
import com.github.damontecres.wholphin.data.model.formatCountdown
import com.github.damontecres.wholphin.data.model.keyAfterRemoval
import com.github.damontecres.wholphin.data.model.nextRemaining
import com.github.damontecres.wholphin.data.model.queueRefreshDelay
import com.github.damontecres.wholphin.data.model.showsUnavailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val NOW = 1_700_000_000L
private const val MINUTE = 60L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

class QueueProblemTextTest {
    @Test
    fun `nothing to say without a cancel time`() {
        assertNull(queueProblemText(NOW, null, "no_seeds", "on"))
        assertNull(queueProblemText(NOW, null, null, "dry"))
    }

    @Test
    fun `each problem has its own words`() {
        val cancelAt = NOW + 3 * HOUR
        assertEquals(R.string.queue_problem_no_seeds, queueProblemText(NOW, cancelAt, "no_seeds", "on")?.problem)
        assertEquals(R.string.queue_problem_stuck, queueProblemText(NOW, cancelAt, "stuck", "on")?.problem)
        assertEquals(R.string.queue_problem_not_found, queueProblemText(NOW, cancelAt, "not_found", "on")?.problem)
        assertNull(queueProblemText(NOW, cancelAt, "something_new", "on")?.problem)
        assertNull(queueProblemText(NOW, cancelAt, null, "on")?.problem)
    }

    @Test
    fun `counts down to the cancel time in the largest unit`() {
        assertEquals(
            QueueProblemText(R.string.queue_problem_no_seeds, R.string.queue_cancels_in, "3h"),
            queueProblemText(NOW, NOW + 3 * HOUR + 40 * MINUTE, "no_seeds", "on"),
        )
        assertEquals(
            QueueProblemText(R.string.queue_problem_not_found, R.string.queue_cancels_in, "2d"),
            queueProblemText(NOW, NOW + 2 * DAY + 5 * HOUR, "not_found", "on"),
        )
        assertEquals(
            QueueProblemText(R.string.queue_problem_stuck, R.string.queue_cancels_in, "40m"),
            queueProblemText(NOW, NOW + 40 * MINUTE, "stuck", "on"),
        )
    }

    @Test
    fun `the counting down is the same in test mode`() {
        assertEquals(
            QueueProblemText(R.string.queue_problem_stuck, R.string.queue_cancels_in, "40m"),
            queueProblemText(NOW, NOW + 40 * MINUTE, "stuck", "dry"),
        )
    }

    @Test
    fun `once the time has passed it is cancelling soon`() {
        assertEquals(
            QueueProblemText(R.string.queue_problem_no_seeds, R.string.queue_cancelling_soon),
            queueProblemText(NOW, NOW, "no_seeds", "on"),
        )
        assertEquals(
            QueueProblemText(R.string.queue_problem_no_seeds, R.string.queue_cancelling_soon),
            queueProblemText(NOW, NOW - HOUR, "no_seeds", "off"),
        )
        assertEquals(
            QueueProblemText(R.string.queue_problem_no_seeds, R.string.queue_cancelling_soon),
            queueProblemText(NOW, NOW - HOUR, "no_seeds", null),
        )
    }

    @Test
    fun `or only would be in test mode`() {
        assertEquals(
            QueueProblemText(R.string.queue_problem_not_found, R.string.queue_would_cancel),
            queueProblemText(NOW, NOW - MINUTE, "not_found", "dry"),
        )
    }

    @Test
    fun `countdowns are short`() {
        assertEquals("1m", formatCountdown(1))
        assertEquals("1m", formatCountdown(MINUTE))
        assertEquals("1m", formatCountdown(2 * MINUTE - 1))
        assertEquals("40m", formatCountdown(40 * MINUTE + 30))
        assertEquals("59m", formatCountdown(HOUR - 1))
        assertEquals("1h", formatCountdown(HOUR))
        assertEquals("23h", formatCountdown(DAY - 1))
        assertEquals("1d", formatCountdown(DAY))
        assertEquals("14d", formatCountdown(14 * DAY + 23 * HOUR))
    }

    @Test
    fun `the queue refreshes every 10 seconds and backs off when it fails`() {
        assertEquals(10.seconds, queueRefreshDelay(0))
        assertEquals(20.seconds, queueRefreshDelay(1))
        assertEquals(5.minutes, queueRefreshDelay(10))
        assertEquals(10.seconds, queueRefreshDelay(-1))
    }
}

class QueueNoticeTextTest {
    @Test
    fun `each reason has its own words`() {
        assertEquals(NoticeText(R.string.queue_notice_not_found), noticeText("not_found", false, null))
        assertEquals(NoticeText(R.string.queue_notice_no_seeds), noticeText("no_seeds", false, ""))
        assertEquals(NoticeText(R.string.queue_notice_stuck), noticeText("stuck", false, null))
    }

    @Test
    fun `a removal names who did it`() {
        assertEquals(NoticeText(R.string.queue_notice_removed, "Viewer A"), noticeText("removed", false, "Viewer A"))
        assertEquals(NoticeText(R.string.queue_notice_removed_unknown), noticeText("removed", false, ""))
        assertEquals(NoticeText(R.string.queue_notice_removed_unknown), noticeText("removed", false, null))
    }

    @Test
    fun `a partial series says only the missing episodes stopped`() {
        assertEquals(NoticeText(R.string.queue_notice_no_seeds, partial = true), noticeText("no_seeds", true, null))
        assertEquals(
            NoticeText(R.string.queue_notice_removed, "Viewer A", partial = true),
            noticeText("removed", true, "Viewer A"),
        )
    }

    @Test
    fun `a reason the app doesn't know is still a cancellation`() {
        assertEquals(NoticeText(R.string.queue_notice_cancelled), noticeText("something_new", false, null))
        assertEquals(NoticeText(R.string.queue_notice_cancelled), noticeText(null, false, null))
    }

    @Test
    fun `focus goes to the next notice, not back to an earlier one`() {
        assertEquals(3, nextRemaining(listOf(1, 2, 3), listOf(1, 3), 2))
        assertNull(nextRemaining(listOf(1, 2, 3), listOf(1, 2), 3))
        assertEquals(4, nextRemaining(listOf(1, 2, 3, 4), listOf(1, 4), 2))
    }
}

class UnavailableBadgeTest {
    private val unavailable = setOf(TitleKey(SeerrItemType.MOVIE, 10), TitleKey(SeerrItemType.TV, 20))

    @Test
    fun `a title nobody has requested since gets the X`() {
        assertTrue(showsUnavailable(SeerrAvailability.UNKNOWN, SeerrItemType.MOVIE, 10, unavailable))
        assertTrue(showsUnavailable(SeerrAvailability.DELETED, SeerrItemType.TV, 20, unavailable))
        assertTrue(showsUnavailable(null, SeerrItemType.MOVIE, 10, unavailable))
    }

    @Test
    fun `a title that has been requested again doesn't`() {
        listOf(
            SeerrAvailability.PENDING,
            SeerrAvailability.PROCESSING,
            SeerrAvailability.PARTIALLY_AVAILABLE,
            SeerrAvailability.AVAILABLE,
            SeerrAvailability.BLOCKLISTED,
        ).forEach {
            assertFalse(it.name, showsUnavailable(it, SeerrItemType.MOVIE, 10, unavailable))
        }
    }

    @Test
    fun `a movie and a series with the same ID are different titles`() {
        assertFalse(showsUnavailable(SeerrAvailability.UNKNOWN, SeerrItemType.TV, 10, unavailable))
        assertFalse(showsUnavailable(SeerrAvailability.UNKNOWN, SeerrItemType.MOVIE, 20, unavailable))
    }

    @Test
    fun `other titles and items without a type don't`() {
        assertFalse(showsUnavailable(SeerrAvailability.UNKNOWN, SeerrItemType.MOVIE, 11, unavailable))
        assertFalse(showsUnavailable(SeerrAvailability.UNKNOWN, null, 10, unavailable))
        assertFalse(showsUnavailable(SeerrAvailability.UNKNOWN, SeerrItemType.MOVIE, null, unavailable))
        assertFalse(showsUnavailable(SeerrAvailability.UNKNOWN, SeerrItemType.PERSON, 10, unavailable))
        assertFalse(showsUnavailable(SeerrAvailability.UNKNOWN, SeerrItemType.MOVIE, 10, emptySet()))
    }
}

class QueueRemoveTest {
    @Test
    fun `the first press only asks for a second`() {
        assertFalse(removeConfirmed(null, 1_000))
    }

    @Test
    fun `a second press within 4 seconds removes`() {
        assertTrue(removeConfirmed(1_000, 1_000))
        assertTrue(removeConfirmed(1_000, 4_999))
    }

    @Test
    fun `a second press after that asks again`() {
        assertFalse(removeConfirmed(1_000, 5_000))
        assertFalse(removeConfirmed(1_000, 60_000))
        // A clock that went backwards isn't a second press
        assertFalse(removeConfirmed(1_000, 999))
    }

    @Test
    fun `each refusal has plain words`() {
        assertEquals(R.string.queue_remove_in_library, removeFailureRes("in_library"))
        assertEquals(R.string.queue_remove_not_allowed, removeFailureRes("not_allowed"))
        assertEquals(R.string.queue_remove_changed, removeFailureRes("changed"))
        assertEquals(R.string.queue_remove_changed, removeFailureRes("not_in_queue"))
        assertEquals(R.string.queue_remove_not_ours, removeFailureRes("not_ours"))
        assertEquals(R.string.queue_remove_not_ours, removeFailureRes("finished"))
        assertEquals(R.string.queue_remove_failed, removeFailureRes("bad_key"))
        assertEquals(R.string.queue_remove_failed, removeFailureRes(null))
    }

    @Test
    fun `focus moves to the next row, or the one before, when the focused one goes`() {
        assertEquals("c", keyAfterRemoval(listOf("a", "b", "c"), listOf("a", "c"), "b"))
        assertEquals("b", keyAfterRemoval(listOf("a", "b", "c"), listOf("a", "b"), "c"))
        assertNull(keyAfterRemoval(listOf("a", "b"), listOf("a", "b"), "a"))
        assertNull(keyAfterRemoval(listOf("a"), emptyList(), "a"))
    }

    @Test
    fun `movies and series open their Discover page, other rows nothing`() {
        val movie = queueDestination(QueueRow(key = "movie:12", kind = "movie", mediaType = "movie", tmdbId = 120, title = "M"))
        assertEquals(120, movie?.item?.id)
        assertEquals(SeerrItemType.MOVIE, movie?.item?.type)
        val series = queueDestination(QueueRow(key = "series:3", kind = "series", mediaType = "tv", tmdbId = 30))
        assertEquals(SeerrItemType.TV, series?.item?.type)

        assertNull(queueDestination(QueueRow(key = "movie:13", kind = "movie", tmdbId = null)))
        assertNull(queueDestination(QueueRow(key = "request:9", kind = "request", mediaType = "movie", tmdbId = 9)))
        assertNull(queueDestination(QueueRow(key = "torrent:ab", kind = "torrent")))
    }

    @Test
    fun `tried titles open their Discover page when the service said what they were`() {
        val movie = triedDestination(QueueRemoval(id = 1, mediaType = "movie", tmdbId = 120, title = "M"))
        assertEquals(120, movie?.item?.id)
        assertEquals(SeerrItemType.MOVIE, movie?.item?.type)
        assertEquals("M", movie?.item?.title)
        assertEquals(SeerrItemType.TV, triedDestination(QueueRemoval(id = 2, mediaType = "tv", tmdbId = 30))?.item?.type)

        assertNull(triedDestination(QueueRemoval(id = 3, mediaType = "movie", tmdbId = null)))
        assertNull(triedDestination(QueueRemoval(id = 4, mediaType = null, tmdbId = 5)))
        assertNull(triedDestination(QueueRemoval(id = 5, mediaType = "person", tmdbId = 5)))
    }
}
