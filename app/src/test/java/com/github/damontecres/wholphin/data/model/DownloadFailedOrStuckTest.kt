package com.github.damontecres.wholphin.data.model

import com.github.damontecres.wholphin.api.seerr.model.DownloadingItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DownloadFailedOrStuckTest {
    private val now = Instant.parse("2026-10-31T12:00:00Z")
    private val justNow = now.minusSeconds(60)
    private val hoursAgo = now.minusSeconds(3 * 60 * 60)
    private val downloading = listOf(DownloadingItem(status = "downloading"))

    private fun stuck(
        state: DownloadState = DownloadState.DOWNLOADING,
        mediaStatus: SeerrAvailability? = SeerrAvailability.PROCESSING,
        queue: List<DownloadingItem>? = downloading,
        status: DownloadStatus? = null,
        activeAt: Instant? = justNow,
    ) = downloadFailedOrStuck(state, mediaStatus, queue, status, activeAt, now)

    @Test
    fun `A failure is always offered`() {
        assertTrue(stuck(DownloadState.FAILED))
    }

    @Test
    fun `Unavailable counts when Radarr lost it`() {
        assertTrue(stuck(DownloadState.UNAVAILABLE, SeerrAvailability.DELETED))
    }

    @Test
    fun `Unavailable can be an old request beside a newer one that's working`() {
        assertFalse(stuck(DownloadState.UNAVAILABLE, SeerrAvailability.PROCESSING))
    }

    @Test
    fun `What's waiting on someone or something else isn't stuck`() {
        listOf(
            DownloadState.PENDING_APPROVAL,
            DownloadState.WAITING_FOR_RELEASE,
            DownloadState.DECLINED,
            DownloadState.READY,
        ).forEach {
            assertFalse(it.name, stuck(it, queue = null, activeAt = hoursAgo))
        }
    }

    @Test
    fun `A healthy download isn't stuck`() {
        assertFalse(stuck(status = DownloadStatus(state = "downloading")))
    }

    @Test
    fun `A stalled queue entry is stuck`() {
        assertTrue(stuck(queue = listOf(DownloadingItem(status = "warning"))))
        assertTrue(stuck(DownloadState.QUEUED, queue = listOf(DownloadingItem(status = "Paused"))))
    }

    @Test
    fun `A healthy entry beside a stalled one is still getting somewhere`() {
        assertFalse(stuck(queue = downloading + DownloadingItem(status = "warning")))
    }

    @Test
    fun `Nothing in the queue is stuck only after a while`() {
        assertFalse(stuck(DownloadState.SEARCHING, queue = null, activeAt = justNow))
        assertTrue(stuck(DownloadState.SEARCHING, queue = null, activeAt = hoursAgo))
        assertTrue(stuck(DownloadState.PREPARING, queue = emptyList(), activeAt = hoursAgo))
    }

    @Test
    fun `Without a time nothing can be said`() {
        assertFalse(stuck(DownloadState.SEARCHING, queue = null, activeAt = null))
    }

    @Test
    fun `An old queue entry isn't stuck for being old`() {
        assertFalse(stuck(activeAt = hoursAgo))
    }

    @Test
    fun `The status service can say what Seerr doesn't`() {
        assertTrue(stuck(status = DownloadStatus(state = "stalledDL")))
        assertTrue(stuck(DownloadState.PREPARING, status = DownloadStatus(state = "missingFiles")))
    }

    @Test
    fun `qBittorrent's states`() {
        listOf("stalledDL", "error", "missingFiles", "stoppedDL", "pausedDL").forEach {
            assertTrue(it, DownloadStatus(state = it).stuck)
        }
        listOf("downloading", "metaDL", "queuedDL", "checkingDL", "stalledUP", "unknown").forEach {
            assertFalse(it, DownloadStatus(state = it).stuck)
        }
        assertFalse(DownloadStatus().stuck)
    }

    private val noSeeds =
        DownloadStatus(
            state = "downloading",
            progress = 0.5,
            seedsConnected = 0,
            seedsSwarm = 0,
            downloadSpeed = 0,
        )

    @Test
    fun `No seeds and nothing arriving is stuck`() {
        assertTrue(noSeeds.stuck)
    }

    @Test
    fun `Seeds in the swarm or data arriving isn't`() {
        assertFalse(noSeeds.copy(seedsSwarm = 3).stuck)
        assertFalse(noSeeds.copy(downloadSpeed = 1000).stuck)
    }

    @Test
    fun `A finished download doesn't need seeds`() {
        assertFalse(noSeeds.copy(progress = 1.0).stuck)
    }

    @Test
    fun `Missing counts don't mean no seeds`() {
        assertFalse(noSeeds.copy(seedsConnected = null).stuck)
        assertFalse(noSeeds.copy(seedsSwarm = null).stuck)
    }

    private fun radarr(
        trackedState: String? = "downloading",
        trackedStatus: String? = "ok",
        tracked: Boolean = true,
    ) = DownloadStatus(
        radarr =
            RadarrStatus(
                available = true,
                tracked = tracked,
                trackedState = trackedState,
                trackedStatus = trackedStatus,
            ),
    )

    @Test
    fun `Radarr blocking the import is stuck`() {
        assertTrue(radarr(trackedState = "importBlocked").stuck)
    }

    @Test
    fun `A Radarr warning or error is stuck`() {
        assertTrue(radarr(trackedStatus = "warning").stuck)
        assertTrue(radarr(trackedStatus = "Error").stuck)
    }

    @Test
    fun `Radarr being fine isn't`() {
        assertFalse(radarr().stuck)
        assertFalse(radarr(trackedState = "importPending").stuck)
    }

    @Test
    fun `Only what Radarr tracks counts, a series never is`() {
        assertFalse(radarr(trackedStatus = "warning", tracked = false).stuck)
    }

    @Test
    fun `Seerr's times parse`() {
        assertEquals(Instant.parse("2020-09-12T10:00:27Z"), parseSeerrTime("2020-09-12T10:00:27.000Z"))
        assertNull(parseSeerrTime("yesterday"))
        assertNull(parseSeerrTime(null))
    }
}
