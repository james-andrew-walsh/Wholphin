package com.github.damontecres.wholphin.data.model

import com.github.damontecres.wholphin.api.seerr.model.DownloadingItem
import com.github.damontecres.wholphin.api.seerr.model.MediaInfo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DownloadStateTest {
    private val now = Instant.parse("2026-10-31T12:00:00Z")

    private fun progress(
        requestStatus: RequestStatus = RequestStatus.APPROVED,
        requestExists: Boolean = true,
        mediaStatus: SeerrAvailability? = SeerrAvailability.PROCESSING,
        queue: List<DownloadingItem>? = null,
        inJellyfin: Boolean = false,
        seenInQueue: Boolean = false,
    ) = downloadProgress(
        requestStatus = requestStatus,
        requestExists = requestExists,
        mediaStatus = mediaStatus,
        queue = queue,
        inJellyfin = inJellyfin,
        seenInQueue = seenInQueue,
        now = now,
    )

    private fun queued(
        status: String?,
        size: Double? = 1000.0,
        sizeLeft: Double? = 250.0,
        timeLeft: String? = "00:10:00",
        estimatedCompletionTime: String? = null,
    ) = DownloadingItem(
        propertySize = size,
        sizeLeft = sizeLeft,
        status = status,
        timeLeft = timeLeft,
        estimatedCompletionTime = estimatedCompletionTime,
    )

    @Test
    fun `in Jellyfin is ready whatever Seerr says`() {
        RequestStatus.entries.forEach {
            assertEquals(DownloadState.READY, progress(requestStatus = it, inJellyfin = true).state)
        }
        assertEquals(
            DownloadState.READY,
            progress(mediaStatus = SeerrAvailability.DELETED, inJellyfin = true).state,
        )
    }

    @Test
    fun `Seerr available without Jellyfin is not ready`() {
        val status =
            progress(
                requestStatus = RequestStatus.COMPLETED,
                mediaStatus = SeerrAvailability.AVAILABLE,
                inJellyfin = false,
            )
        assertEquals(DownloadState.PREPARING, status.state)
    }

    @Test
    fun `the request's own outcome comes first`() {
        assertEquals(
            DownloadState.PENDING_APPROVAL,
            progress(requestStatus = RequestStatus.PENDING).state,
        )
        assertEquals(DownloadState.DECLINED, progress(requestStatus = RequestStatus.DECLINED).state)
        assertEquals(DownloadState.FAILED, progress(requestStatus = RequestStatus.FAILURE).state)
    }

    @Test
    fun `approved without a queue entry is searching`() {
        listOf(
            SeerrAvailability.UNKNOWN,
            SeerrAvailability.PENDING,
            SeerrAvailability.PROCESSING,
            null,
        ).forEach {
            assertEquals(DownloadState.SEARCHING, progress(mediaStatus = it).state)
        }
    }

    @Test
    fun `an unknown request status does not claim anything final`() {
        assertEquals(DownloadState.SEARCHING, progress(requestStatus = RequestStatus.UNKNOWN).state)
    }

    @Test
    fun `a queue entry going away means it is being prepared, not searched for again`() {
        assertEquals(DownloadState.PREPARING, progress(seenInQueue = true).state)
    }

    @Test
    fun `deleted, blocklisted and removed requests are unavailable`() {
        assertEquals(
            DownloadState.UNAVAILABLE,
            progress(
                requestStatus = RequestStatus.COMPLETED,
                mediaStatus = SeerrAvailability.DELETED,
            ).state,
        )
        assertEquals(
            DownloadState.UNAVAILABLE,
            progress(mediaStatus = SeerrAvailability.BLOCKLISTED).state,
        )
        assertEquals(DownloadState.UNAVAILABLE, progress(requestExists = false).state)
    }

    @Test
    fun `a live download wins over deleted media`() {
        val status =
            progress(
                requestStatus = RequestStatus.COMPLETED,
                mediaStatus = SeerrAvailability.DELETED,
                queue = listOf(queued("failed"), queued("downloading")),
            )
        assertEquals(DownloadState.DOWNLOADING, status.state)
        assertEquals(0.75f, status.fraction)
        assertEquals(10.minutes, status.timeLeft)
        assertTrue(keepsRefreshing(status.state, RequestStatus.COMPLETED))
    }

    @Test
    fun `deleted media without a live download stays unavailable`() {
        listOf(null, emptyList<DownloadingItem>(), listOf(queued("failed"))).forEach {
            val status =
                progress(
                    requestStatus = RequestStatus.COMPLETED,
                    mediaStatus = SeerrAvailability.DELETED,
                    queue = it,
                )
            assertEquals(it.toString(), DownloadState.UNAVAILABLE, status.state)
        }
    }

    @Test
    fun `a download leaving the queue of deleted media is being prepared`() {
        // Seerr keeps it deleted until a scan finds the import
        listOf(RequestStatus.APPROVED, RequestStatus.COMPLETED).forEach {
            listOf(null, emptyList<DownloadingItem>()).forEach { queue ->
                val status =
                    progress(
                        requestStatus = it,
                        mediaStatus = SeerrAvailability.DELETED,
                        queue = queue,
                        seenInQueue = true,
                    )
                assertEquals(it.toString(), DownloadState.PREPARING, status.state)
                assertTrue(keepsRefreshing(status.state, it))
            }
        }
    }

    @Test
    fun `a failed download of deleted media isn't final on an approved request`() {
        val status =
            progress(
                requestStatus = RequestStatus.APPROVED,
                mediaStatus = SeerrAvailability.DELETED,
                queue = listOf(queued("failed")),
            )
        assertEquals(DownloadState.FAILED, status.state)
        assertTrue(keepsRefreshing(status.state, RequestStatus.APPROVED))
    }

    @Test
    fun `deleted media on an approved request without a download stays unavailable`() {
        val status =
            progress(
                requestStatus = RequestStatus.APPROVED,
                mediaStatus = SeerrAvailability.DELETED,
            )
        assertEquals(DownloadState.UNAVAILABLE, status.state)
    }

    @Test
    fun `blocklisted media ignores the queue`() {
        assertEquals(
            DownloadState.UNAVAILABLE,
            progress(
                mediaStatus = SeerrAvailability.BLOCKLISTED,
                queue = listOf(queued("downloading")),
            ).state,
        )
    }

    @Test
    fun `a completed request isn't preparing unless the media is available`() {
        listOf(
            SeerrAvailability.UNKNOWN,
            SeerrAvailability.PENDING,
            SeerrAvailability.PROCESSING,
            null,
        ).forEach {
            listOf(false, true).forEach { seen ->
                val status =
                    progress(
                        requestStatus = RequestStatus.COMPLETED,
                        mediaStatus = it,
                        queue = emptyList(),
                        seenInQueue = seen,
                    )
                assertEquals(it.toString(), DownloadState.UNAVAILABLE, status.state)
                assertFalse(keepsRefreshing(status.state, RequestStatus.COMPLETED))
            }
        }
        assertEquals(
            DownloadState.PREPARING,
            progress(
                requestStatus = RequestStatus.COMPLETED,
                mediaStatus = SeerrAvailability.PARTIALLY_AVAILABLE,
            ).state,
        )
    }

    @Test
    fun `a completed request still shows a download in the queue`() {
        assertEquals(
            DownloadState.DOWNLOADING,
            progress(
                requestStatus = RequestStatus.COMPLETED,
                queue = listOf(queued("downloading")),
            ).state,
        )
    }

    @Test
    fun `queue statuses are normalized`() {
        val expected =
            mapOf(
                "downloading" to DownloadState.DOWNLOADING,
                "warning" to DownloadState.DOWNLOADING,
                "queued" to DownloadState.QUEUED,
                "paused" to DownloadState.QUEUED,
                "delay" to DownloadState.QUEUED,
                "downloadClientUnavailable" to DownloadState.QUEUED,
                "fallback" to DownloadState.QUEUED,
                "unknown" to DownloadState.QUEUED,
                null to DownloadState.QUEUED,
                "completed" to DownloadState.PREPARING,
                "failed" to DownloadState.FAILED,
            )
        expected.forEach { (status, state) ->
            assertEquals(status, state, progress(queue = listOf(queued(status))).state)
        }
    }

    @Test
    fun `progress is shown for a download`() {
        val status = progress(queue = listOf(queued("downloading")))
        assertEquals(0.75f, status.fraction)
        assertEquals(10.minutes, status.timeLeft)
    }

    @Test
    fun `finished and failed entries show no progress`() {
        listOf("completed", "failed").forEach {
            val status = progress(queue = listOf(queued(it)))
            assertNull(status.fraction)
            assertNull(status.timeLeft)
        }
    }

    @Test
    fun `the best queue entry is used`() {
        val status =
            progress(
                queue =
                    listOf(
                        queued("failed"),
                        queued("completed"),
                        queued("queued"),
                        queued("downloading", sizeLeft = 500.0),
                    ),
            )
        assertEquals(DownloadState.DOWNLOADING, status.state)
        assertEquals(0.5f, status.fraction)
    }

    @Test
    fun `an empty queue is the same as no queue`() {
        assertEquals(DownloadState.SEARCHING, progress(queue = emptyList()).state)
    }

    @Test
    fun `only a live queue entry counts as seen`() {
        assertFalse(seenInQueue(false, null))
        assertFalse(seenInQueue(false, emptyList()))
        assertTrue(seenInQueue(true, emptyList()))
        assertTrue(seenInQueue(false, listOf(queued("downloading"))))
        assertTrue(seenInQueue(false, listOf(queued("completed"))))
        assertFalse(seenInQueue(false, listOf(queued("failed"))))
        // Radarr is about to search again, so what comes next isn't an import
        assertFalse(seenInQueue(true, listOf(queued("failed"))))
    }

    @Test
    fun `a failed download on an approved request keeps refreshing`() {
        assertTrue(keepsRefreshing(DownloadState.FAILED, RequestStatus.APPROVED))
        assertFalse(keepsRefreshing(DownloadState.FAILED, RequestStatus.FAILURE))
    }

    @Test
    fun `only states which can change keep refreshing`() {
        val active =
            setOf(
                DownloadState.PENDING_APPROVAL,
                DownloadState.SEARCHING,
                DownloadState.QUEUED,
                DownloadState.DOWNLOADING,
                DownloadState.PREPARING,
            )
        DownloadState.entries.forEach {
            assertEquals(it.name, it in active, keepsRefreshing(it, RequestStatus.COMPLETED))
        }
    }

    @Test
    fun `fraction needs usable sizes`() {
        assertEquals(0.75f, downloadFraction(1000.0, 250.0))
        assertEquals(0f, downloadFraction(1000.0, 1000.0))
        assertEquals(1f, downloadFraction(1000.0, 0.0))
        assertNull(downloadFraction(0.0, 0.0))
        assertNull(downloadFraction(-1.0, 0.0))
        assertNull(downloadFraction(null, 0.0))
        assertNull(downloadFraction(1000.0, null))
        assertNull(downloadFraction(1000.0, 2000.0))
        assertNull(downloadFraction(1000.0, -1.0))
        assertNull(downloadFraction(Double.NaN, 0.0))
        assertNull(downloadFraction(1000.0, Double.NaN))
        assertNull(downloadFraction(Double.POSITIVE_INFINITY, 0.0))
    }

    @Test
    fun `fraction handles sizes larger than an int`() {
        assertEquals(0.5f, downloadFraction(16_000_000_000.0, 8_000_000_000.0))
    }

    @Test
    fun `percent rounds down`() {
        assertEquals(0, downloadPercent(0f))
        assertEquals(99, downloadPercent(0.999f))
        assertEquals(100, downloadPercent(1f))
    }

    @Test
    fun `time left is parsed from a TimeSpan`() {
        assertEquals(1.hours + 2.minutes + 3.seconds, parseTimeLeft("01:02:03"))
        assertEquals(26.hours + 3.minutes, parseTimeLeft("1.02:03:00"))
        assertEquals(10.seconds, parseTimeLeft("00:00:10.1234567"))
    }

    @Test
    fun `unusable time left is ignored`() {
        listOf(
            null,
            "",
            "soon",
            "12:00",
            "00:00:00",
            "-00:01:00",
            "01:02:03:04",
        ).forEach {
            assertNull(it, parseTimeLeft(it))
        }
    }

    private fun timeLeft(
        timeLeft: String?,
        estimatedCompletionTime: String?,
    ): Duration? = downloadTimeLeft(timeLeft, estimatedCompletionTime, now)

    @Test
    fun `time left is preferred over the completion time`() {
        assertEquals(5.minutes, timeLeft("00:05:00", "2026-10-31T13:00:00.000Z"))
    }

    @Test
    fun `the completion time is used without a time left`() {
        assertEquals(30.minutes, timeLeft(null, "2026-10-31T12:30:00.000Z"))
        assertEquals(30.minutes, timeLeft("nonsense", "2026-10-31T12:30:00.000Z"))
    }

    @Test
    fun `nothing is made up when neither value is usable`() {
        assertNull(timeLeft(null, null))
        assertNull(timeLeft(null, "not a date"))
        assertNull(timeLeft(null, "2026-10-31T11:00:00.000Z"))
        assertNull(timeLeft(null, "2026-10-31T12:00:00.000Z"))
        assertNull(timeLeft("00:00:00", null))
    }

    @Test
    fun `time left rounds up to a minute`() {
        assertEquals(1.minutes, timeLeft("00:00:05", null))
        assertEquals(2.minutes, timeLeft("00:01:01", null))
        assertEquals(1.minutes, timeLeft(null, "2026-10-31T12:00:00.500Z"))
    }

    @Test
    fun `no estimate from a download without one`() {
        val status =
            progress(
                queue =
                    listOf(
                        queued(
                            "downloading",
                            size = 0.0,
                            sizeLeft = 0.0,
                            timeLeft = null,
                            estimatedCompletionTime = null,
                        ),
                    ),
            )
        assertEquals(DownloadState.DOWNLOADING, status.state)
        assertNull(status.fraction)
        assertNull(status.timeLeft)
    }

    @Test
    fun `refresh backs off and is bounded`() {
        assertEquals(15.seconds, downloadRefreshDelay(0))
        assertEquals(30.seconds, downloadRefreshDelay(1))
        assertEquals(1.minutes, downloadRefreshDelay(2))
        assertEquals(4.minutes, downloadRefreshDelay(4))
        assertEquals(5.minutes, downloadRefreshDelay(5))
        assertEquals(5.minutes, downloadRefreshDelay(100))
    }

    @Test
    fun `refresh batch takes turns`() {
        val items = (0..4).toList()
        assertEquals(items, nextRefreshBatch(items, 3, 10))
        assertEquals(listOf(0, 1), nextRefreshBatch(items, 0, 2))
        assertEquals(listOf(4, 0), nextRefreshBatch(items, 4, 2))
    }

    @Test
    fun `paging stops at the total`() {
        assertTrue(hasMoreDownloads(fetched = 20, total = 21, lastPageSize = 20, pageSize = 20))
        assertFalse(hasMoreDownloads(fetched = 40, total = 40, lastPageSize = 20, pageSize = 20))
        assertFalse(hasMoreDownloads(fetched = 20, total = 50, lastPageSize = 0, pageSize = 20))
    }

    @Test
    fun `paging without a total stops at a short page`() {
        assertTrue(hasMoreDownloads(fetched = 20, total = null, lastPageSize = 20, pageSize = 20))
        assertFalse(hasMoreDownloads(fetched = 25, total = null, lastPageSize = 5, pageSize = 20))
    }

    @Test
    fun `media info reads what the server sends`() {
        // From server/lib/downloadtracker.ts & server/entity/Media.ts at v3.5.1, an invalid date is
        // sent as null and a missing timeleft is left out. Not observed from a live server yet.
        val media =
            Json { ignoreUnknownKeys = true }.decodeFromString<MediaInfo>(
                """
                {
                  "status": 3,
                  "status4k": 1,
                  "downloadStatus": [{
                    "mediaType": "movie",
                    "externalId": 5,
                    "size": 16000000000,
                    "sizeLeft": 4000000000.5,
                    "status": "downloading",
                    "estimatedCompletionTime": null,
                    "title": "Some.Release",
                    "downloadId": "ABC123"
                  }],
                  "downloadStatus4k": []
                }
                """.trimIndent(),
            )
        assertEquals(1, media.status4k)
        assertEquals(emptyList<DownloadingItem>(), media.downloadStatus4k)
        val item = media.downloadStatus!!.single()
        assertEquals(5, item.externalId)
        assertEquals(16_000_000_000.0, item.propertySize)
        assertEquals(4_000_000_000.5, item.sizeLeft)
        assertEquals("downloading", item.status)
        assertNull(item.timeLeft)
        assertNull(item.estimatedCompletionTime)
        assertEquals("Some.Release", item.title)
        assertEquals("ABC123", item.downloadId)
    }

    @Test
    fun `the download's ID comes along while it is going through the queue`() {
        listOf("downloading", "queued", "completed").forEach {
            val status = progress(queue = listOf(DownloadingItem(status = it, downloadId = "ABC123")))
            assertEquals(it, "ABC123", status.downloadId)
        }
    }

    @Test
    fun `a failed download has no ID to follow`() {
        val status = progress(queue = listOf(DownloadingItem(status = "failed", downloadId = "ABC123")))

        assertEquals(DownloadState.FAILED, status.state)
        assertNull(status.downloadId)
    }

    @Test
    fun `the ID comes from the entry that sets the state`() {
        val status =
            progress(
                queue =
                    listOf(
                        DownloadingItem(status = "failed", downloadId = "OLD"),
                        DownloadingItem(status = "downloading", downloadId = "NEW"),
                    ),
            )

        assertEquals("NEW", status.downloadId)
    }
}
