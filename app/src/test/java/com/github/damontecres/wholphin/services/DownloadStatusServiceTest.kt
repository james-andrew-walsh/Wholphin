package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.DownloadStatus
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.SeerrServer
import com.github.damontecres.wholphin.data.model.fraction
import com.github.damontecres.wholphin.data.model.isDownloadHash
import com.github.damontecres.wholphin.data.model.leftQueue
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

private val HASH = "0123456789".repeat(4)

// Everything the contract can send, with made up values
private val FULL_RESPONSE =
    """
    {
      "hash": "$HASH",
      "state": "downloading",
      "state_label": "Downloading",
      "progress": 0.5,
      "size": 8589934592,
      "downloaded": 4294967296,
      "uploaded": 1024,
      "ratio": 0.25,
      "dlspeed": 2097152,
      "upspeed": 1024,
      "eta_seconds": 3600,
      "seeds_connected": 4,
      "seeds_swarm": 20,
      "leeches_connected": 1,
      "leeches_swarm": 7,
      "availability": 3.5,
      "connections": 9,
      "added_on": 1000,
      "active_seconds": 500,
      "trackers": {"total": 3, "working": 2, "not_working": 1, "message": "Made up message"},
      "peer_clients": [{"client": "Client A", "count": 3}, {"client": "Other", "count": 1}],
      "file_count": 2,
      "radarr": {
        "available": true,
        "tracked": true,
        "status": "downloading",
        "tracked_state": "downloading",
        "tracked_status": "ok",
        "messages": ["Made up warning"]
      },
      "history": {"interval_seconds": 5, "last_sample_at": 2000, "dl": [1, null, 3], "ul": [0, null, 1]},
      "client": {"connection_status": "connected", "dlspeed": 5, "upspeed": 6},
      "fetched_at": 2001
    }
    """.trimIndent()

class DownloadStatusParsingTest {
    private fun parse(body: String): DownloadStatus {
        val result = parseDownloadStatusResponse(200, body)
        return (result as DownloadStatusResult.Success).status
    }

    @Test
    fun `parses everything the page shows`() {
        val status = parse(FULL_RESPONSE)

        assertEquals("downloading", status.state)
        assertEquals("Downloading", status.stateLabel)
        assertEquals(.5f, status.fraction)
        assertEquals(8589934592L, status.size)
        assertEquals(4294967296L, status.downloaded)
        assertEquals(1024L, status.uploaded)
        assertEquals(.25, status.ratio)
        assertEquals(2097152L, status.downloadSpeed)
        assertEquals(1024L, status.uploadSpeed)
        assertEquals(3600L, status.etaSeconds)
        assertEquals(4, status.seedsConnected)
        assertEquals(20, status.seedsSwarm)
        assertEquals(1, status.leechesConnected)
        assertEquals(7, status.leechesSwarm)
        assertEquals(3.5, status.availability)
        assertEquals(9, status.connections)
        assertEquals(3, status.trackers?.total)
        assertEquals(2, status.trackers?.working)
        assertEquals("Made up message", status.trackers?.message)
        assertEquals("Client A", status.peerClients.first().client)
        assertEquals(2, status.fileCount)
        assertTrue(status.radarr.available)
        assertEquals(true, status.radarr.tracked)
        assertEquals("downloading", status.radarr.trackedState)
        assertEquals(listOf("Made up warning"), status.radarr.messages)
        assertEquals(5, status.history.intervalSeconds)
        assertEquals(2000L, status.history.lastSampleAt)
        assertEquals(listOf<Long?>(1, null, 3), status.history.dl)
        assertEquals(listOf<Long?>(0, null, 1), status.history.ul)
        assertEquals(2001L, status.fetchedAt)
    }

    @Test
    fun `missing and null fields stay missing`() {
        val status = parse("""{"eta_seconds": null, "history": null, "radarr": null, "peer_clients": null}""")

        assertNull(status.etaSeconds)
        assertNull(status.size)
        assertNull(status.fraction)
        assertNull(status.history.lastSampleAt)
        assertTrue(status.history.dl.isEmpty())
        assertFalse(status.radarr.available)
        assertTrue(status.peerClients.isEmpty())
    }

    @Test
    fun `an unknown state is just text`() {
        val status = parse("""{"state": "somethingNew", "state_label": "Unknown"}""")

        assertEquals("somethingNew", status.state)
        assertEquals("Unknown", status.stateLabel)
    }

    @Test
    fun `Radarr's extra keys are optional`() {
        val down = parse("""{"radarr": {"available": false}}""").radarr
        assertFalse(down.available)
        assertNull(down.tracked)

        val untracked = parse("""{"radarr": {"available": true, "tracked": false}}""").radarr
        assertTrue(untracked.available)
        assertEquals(false, untracked.tracked)
        assertTrue(untracked.messages.isEmpty())
    }

    @Test
    fun `a progress that can't be right isn't used`() {
        assertNull(parse("""{"progress": 1.5}""").fraction)
        assertNull(parse("""{"progress": -0.5}""").fraction)
    }

    @Test
    fun `only a complete download that Radarr doesn't track has left the queue`() {
        val tracked = """"radarr": {"available": true, "tracked": true}"""
        val untracked = """"radarr": {"available": true, "tracked": false}"""
        val unavailable = """"radarr": {"available": false}"""

        assertTrue(parse("""{"progress": 1.0, $untracked}""").leftQueue)
        assertFalse(parse("""{"progress": 0.9, $untracked}""").leftQueue)
        assertFalse(parse("""{$untracked}""").leftQueue)
        assertFalse(parse("""{"progress": 1.0, $tracked}""").leftQueue)
        assertFalse(parse("""{"progress": 1.0, $unavailable}""").leftQueue)
    }

    @Test
    fun `a body that can't be read is a failure`() {
        assertFailure(DownloadStatusError.SERVICE_UNAVAILABLE, 200, "<html>")
        assertFailure(DownloadStatusError.SERVICE_UNAVAILABLE, 200, """{"progress": "half"}""")
    }

    private fun assertFailure(
        error: DownloadStatusError,
        code: Int,
        body: String,
    ) = assertEquals(DownloadStatusResult.Failure(error), parseDownloadStatusResponse(code, body))

    @Test
    fun `errors follow the contract`() {
        assertFailure(DownloadStatusError.SESSION_REFUSED, 401, """{"error":"invalid_token"}""")
        assertFailure(DownloadStatusError.SESSION_REFUSED, 401, "")
        assertFailure(DownloadStatusError.AUTH_UNAVAILABLE, 401, """{"error":"auth_unavailable"}""")
        assertFailure(DownloadStatusError.QBITTORRENT_UNAVAILABLE, 503, """{"error":"qbittorrent_unavailable"}""")
        assertFailure(DownloadStatusError.SERVICE_UNAVAILABLE, 400, """{"error":"invalid_hash"}""")
        assertFailure(DownloadStatusError.SERVICE_UNAVAILABLE, 500, """{"error":"internal"}""")
        assertFailure(DownloadStatusError.SERVICE_UNAVAILABLE, 302, "")
    }

    @Test
    fun `only an unknown hash means the download is gone`() {
        assertEquals(
            DownloadStatusResult.Gone,
            parseDownloadStatusResponse(404, """{"error":"unknown_hash"}"""),
        )
        assertFailure(DownloadStatusError.SERVICE_UNAVAILABLE, 404, """{"error":"not_found"}""")
        assertFailure(DownloadStatusError.SERVICE_UNAVAILABLE, 404, "Not found")
    }

    @Test
    fun `the service is on Seerr's host`() {
        assertEquals(
            "http://seerr.test:8097/v1/downloads/$HASH",
            downloadStatusUrl("https://seerr.test:5055/", HASH).toString(),
        )
    }

    @Test
    fun `there is no URL without a Seerr server`() {
        assertNull(downloadStatusUrl(null, HASH))
        assertNull(downloadStatusUrl("", HASH))
    }

    @Test
    fun `hashes are 40 or 64 hex characters in either case`() {
        assertTrue(isDownloadHash("a".repeat(40)))
        assertTrue(isDownloadHash("A".repeat(64)))
        assertFalse(isDownloadHash("a".repeat(39)))
        assertFalse(isDownloadHash("a".repeat(41)))
        assertFalse(isDownloadHash("g".repeat(40)))
        assertFalse(isDownloadHash("a".repeat(40) + "/"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadStatusServiceTest {
    private val testDispatcher = StandardTestDispatcher()

    private val seerrServerRepository = mockk<SeerrServerRepository>()
    private val serverRepository = mockk<ServerRepository>()
    private val user = mockk<JellyfinUser>()

    private val requests = mutableListOf<Request>()
    private var failure: IOException? = null
    private var code = 200
    private var body = FULL_RESPONSE

    private val okHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                requests.add(chain.request())
                failure?.let { throw it }
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("Made up")
                    .body(body.toResponseBody())
                    .build()
            }.build()

    private val service = DownloadStatusService(okHttpClient, seerrServerRepository, serverRepository)

    @Before
    fun setUp() {
        WholphinDispatchers.configure(testDispatcher)
        every { seerrServerRepository.currentServer } returns flowOf(SeerrServer(url = "http://seerr.test:5055"))
        every { serverRepository.currentUser } returns user
        every { user.accessToken } returns "made-up-token"
    }

    @After
    fun tearDown() {
        WholphinDispatchers.reset()
    }

    @Test
    fun `gets the download with the token in the contract's header`() =
        runTest(testDispatcher) {
            val result = service.fetch(HASH)

            assertTrue(result is DownloadStatusResult.Success)
            val request = requests.single()
            assertEquals("GET", request.method)
            assertEquals("made-up-token", request.header("X-Emby-Token"))
            assertEquals("http://seerr.test:8097/v1/downloads/$HASH", request.url.toString())
        }

    @Test
    fun `the service being down is a result`() =
        runTest(testDispatcher) {
            failure = IOException("Made up failure")

            assertEquals(
                DownloadStatusResult.Failure(DownloadStatusError.SERVICE_UNAVAILABLE),
                service.fetch(HASH),
            )
        }

    @Test
    fun `nothing is sent without a token`() =
        runTest(testDispatcher) {
            every { user.accessToken } returns null

            assertEquals(
                DownloadStatusResult.Failure(DownloadStatusError.SESSION_REFUSED),
                service.fetch(HASH),
            )
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `nothing is sent without a Seerr server`() =
        runTest(testDispatcher) {
            every { seerrServerRepository.currentServer } returns MutableStateFlow(null)

            assertEquals(
                DownloadStatusResult.Failure(DownloadStatusError.SERVICE_UNAVAILABLE),
                service.fetch(HASH),
            )
            assertTrue(requests.isEmpty())
        }
}
