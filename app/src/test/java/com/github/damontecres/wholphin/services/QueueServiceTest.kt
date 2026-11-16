package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.AutoCancel
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.QueueNotices
import com.github.damontecres.wholphin.data.model.QueueRemoveResponse
import com.github.damontecres.wholphin.data.model.QueueStatus
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.data.model.SeerrServer
import com.github.damontecres.wholphin.data.model.TitleKey
import com.github.damontecres.wholphin.data.model.UnavailableResponse
import com.github.damontecres.wholphin.data.model.keys
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

// Everything the contract can send and some it doesn't, with made up values
private val QUEUE_RESPONSE =
    """
    {
      "fetched_at": 1700000000,
      "auto_cancel": "dry",
      "can_remove": true,
      "something_new": {"nested": [1, 2]},
      "rows": [
        {
          "key": "series:3",
          "kind": "series",
          "media_type": "tv",
          "tmdb_id": 33,
          "title": "Made Up Series",
          "year": 2020,
          "requested_by": ["Viewer A", "Viewer B"],
          "requested_at": 1690000000,
          "state": "downloading",
          "state_label": "Downloading",
          "progress": 0.25,
          "size": 8589934592,
          "dlspeed": 2097152,
          "seeds": 4,
          "swarm_seeds": 20,
          "downloads": 3,
          "partial": true,
          "problem": null,
          "cancel_at": null,
          "unknown_field": "ignored"
        },
        {
          "key": "request:9",
          "kind": "request",
          "media_type": null,
          "tmdb_id": null,
          "title": "Made Up Request",
          "year": null,
          "requested_by": [],
          "requested_at": null,
          "state": "searching",
          "state_label": "Searching",
          "progress": null,
          "size": null,
          "dlspeed": 0,
          "seeds": null,
          "swarm_seeds": null,
          "downloads": 0,
          "partial": false,
          "problem": "not_found",
          "cancel_at": 1700003600
        }
      ]
    }
    """.trimIndent()

private val NOTICES_RESPONSE =
    """
    {
      "notices": [
        {"id": 5, "media_type": "movie", "tmdb_id": 55, "title": "Made Up Movie", "year": 1999,
         "reason": "removed", "partial": false, "removed_by": "Viewer A", "at": 1700000000, "extra": true},
        {"id": 6, "media_type": null, "tmdb_id": null, "title": "Made Up Series", "year": null,
         "reason": "no_seeds", "partial": true, "removed_by": "", "at": 1700000100}
      ],
      "more": 0
    }
    """.trimIndent()

private val UNAVAILABLE_RESPONSE =
    """
    {
      "titles": [
        {"media_type": "movie", "tmdb_id": 55, "reason": "not_found", "at": 1700000000},
        {"media_type": "tv", "tmdb_id": 55, "reason": "no_seeds", "at": 1700000000, "extra": 1},
        {"media_type": "person", "tmdb_id": 7, "reason": "stuck", "at": 1700000000},
        {"media_type": "movie", "tmdb_id": null, "reason": "stuck", "at": null}
      ]
    }
    """.trimIndent()

class QueueResponseTest {
    @Test
    fun `reads every field of the queue in the server's order`() {
        val result = parseQueueResponse<QueueStatus>(200, QUEUE_RESPONSE)

        assertTrue(result is QueueResult.Success)
        val queue = (result as QueueResult.Success).value
        assertEquals(1700000000L, queue.fetchedAt)
        assertEquals(AutoCancel.DRY, AutoCancel.from(queue.autoCancel))
        assertTrue(queue.canRemove)
        assertEquals(listOf("series:3", "request:9"), queue.rows.map { it.key })

        val series = queue.rows[0]
        assertEquals("series", series.kind)
        assertEquals(SeerrItemType.TV, series.itemType)
        assertEquals(33, series.tmdbId)
        assertEquals("Made Up Series", series.title)
        assertEquals(2020, series.year)
        assertEquals(listOf("Viewer A", "Viewer B"), series.requestedBy)
        assertEquals(1690000000L, series.requestedAt)
        assertEquals("downloading", series.state)
        assertEquals("Downloading", series.stateLabel)
        assertEquals(0.25, series.progress!!, 0.0)
        assertEquals(8589934592L, series.size)
        assertEquals(2097152L, series.downloadSpeed)
        assertEquals(4, series.seeds)
        assertEquals(20, series.swarmSeeds)
        assertEquals(3, series.downloads)
        assertTrue(series.partial)
        assertNull(series.problem)
        assertNull(series.cancelAt)
        assertFalse(series.hasProblem)
    }

    @Test
    fun `nulls in the queue are missing values`() {
        val request = (parseQueueResponse<QueueStatus>(200, QUEUE_RESPONSE) as QueueResult.Success).value.rows[1]

        assertNull(request.itemType)
        assertNull(request.mediaType)
        assertNull(request.tmdbId)
        assertNull(request.year)
        assertEquals(emptyList<String>(), request.requestedBy)
        assertNull(request.progress)
        assertNull(request.size)
        assertNull(request.seeds)
        assertEquals("not_found", request.problem)
        assertEquals(1700003600L, request.cancelAt)
        assertTrue(request.hasProblem)
    }

    @Test
    fun `a row with only a key still reads`() {
        val result = parseQueueResponse<QueueStatus>(200, """{"rows": [{"key": "torrent:ab", "dlspeed": null}]}""")

        val row = (result as QueueResult.Success).value.rows.single()
        assertEquals("torrent:ab", row.key)
        assertEquals(0L, row.downloadSpeed)
        assertEquals(1, row.downloads)
        assertFalse(result.value.canRemove)
        assertEquals(AutoCancel.OFF, AutoCancel.from(result.value.autoCancel))
    }

    @Test
    fun `an error state or a blocked import is a problem`() {
        val result =
            parseQueueResponse<QueueStatus>(
                200,
                """{"rows": [{"key": "a", "state": "error"}, {"key": "b", "state": "import_blocked"}]}""",
            )

        assertTrue((result as QueueResult.Success).value.rows.all { it.hasProblem })
    }

    @Test
    fun `errors keep the service's code`() {
        assertEquals(QueueResult.Failure("invalid_token"), parseQueueResponse<QueueStatus>(401, """{"error":"invalid_token"}"""))
        assertEquals(
            QueueResult.Failure("auth_unavailable"),
            parseQueueResponse<QueueStatus>(401, """{"error":"auth_unavailable"}"""),
        )
        assertEquals(
            QueueResult.Failure("qbittorrent_unavailable"),
            parseQueueResponse<QueueStatus>(503, """{"error":"qbittorrent_unavailable"}"""),
        )
        assertEquals(QueueResult.Failure(), parseQueueResponse<QueueStatus>(502, "<html>Bad gateway</html>"))
    }

    @Test
    fun `a body that doesn't read is a failure, not a crash`() {
        assertEquals(QueueResult.Failure(), parseQueueResponse<QueueStatus>(200, "<html></html>"))
        assertEquals(QueueResult.Failure(), parseQueueResponse<QueueStatus>(200, """{"rows": [{"title": "No key"}]}"""))
        assertEquals(QueueResult.Failure(), parseQueueResponse<QueueStatus>(200, ""))
    }

    @Test
    fun `reads the result of a removal and why one was refused`() {
        assertEquals(
            QueueResult.Success(QueueRemoveResponse("removed")),
            parseQueueResponse<QueueRemoveResponse>(200, """{"result": "removed"}"""),
        )
        assertEquals(
            QueueResult.Success(QueueRemoveResponse("partial")),
            parseQueueResponse<QueueRemoveResponse>(200, """{"result": "partial", "new": 1}"""),
        )
        assertEquals(QueueResult.Failure("not_allowed"), parseQueueResponse<QueueRemoveResponse>(403, """{"error":"not_allowed"}"""))
        listOf("in_library", "not_in_queue", "changed", "not_ours", "finished", "bad_key").forEach {
            assertEquals(QueueResult.Failure(it), parseQueueResponse<QueueRemoveResponse>(409, """{"error":"$it"}"""))
        }
        assertEquals(QueueResult.Failure(), parseQueueResponse<QueueRemoveResponse>(400, "Bad request"))
    }

    @Test
    fun `reads the notices`() {
        val result = parseQueueResponse<QueueNotices>(200, NOTICES_RESPONSE)

        val notices = (result as QueueResult.Success).value.notices
        assertEquals(listOf(5, 6), notices.map { it.id })
        val removed = notices[0]
        assertEquals("movie", removed.mediaType)
        assertEquals(55, removed.tmdbId)
        assertEquals("Made Up Movie", removed.title)
        assertEquals(1999, removed.year)
        assertEquals("removed", removed.reason)
        assertFalse(removed.partial)
        assertEquals("Viewer A", removed.removedBy)
        assertEquals(1700000000L, removed.at)
        val partial = notices[1]
        assertNull(partial.mediaType)
        assertNull(partial.tmdbId)
        assertNull(partial.year)
        assertTrue(partial.partial)
    }

    @Test
    fun `no notices is an empty list`() {
        assertEquals(QueueResult.Success(QueueNotices()), parseQueueResponse<QueueNotices>(200, """{"notices": []}"""))
        assertEquals(QueueResult.Success(QueueNotices()), parseQueueResponse<QueueNotices>(200, "{}"))
    }

    @Test
    fun `a notice that is gone counts as acknowledged`() {
        assertEquals(QueueResult.Success(true), parseAckResponse(200, """{"acknowledged": true}"""))
        assertEquals(QueueResult.Success(true), parseAckResponse(404, """{"error":"not_found"}"""))
        assertEquals(QueueResult.Success(true), parseAckResponse(404, ""))
        assertEquals(QueueResult.Failure("invalid_token"), parseAckResponse(401, """{"error":"invalid_token"}"""))
        assertEquals(QueueResult.Failure(), parseAckResponse(200, "nope"))
    }

    @Test
    fun `reads the unavailable titles, only movies and series with an ID`() {
        val result = parseQueueResponse<UnavailableResponse>(200, UNAVAILABLE_RESPONSE)

        val response = (result as QueueResult.Success).value
        assertEquals(4, response.titles.size)
        assertEquals("not_found", response.titles[0].reason)
        assertEquals(1700000000L, response.titles[0].at)
        assertEquals(
            setOf(TitleKey(SeerrItemType.MOVIE, 55), TitleKey(SeerrItemType.TV, 55)),
            response.keys(),
        )
    }

    @Test
    fun `the queue service is beside Seerr on its own port`() {
        assertEquals("http://seerr.lan:8098/v1/queue", queueServiceUrl("http://seerr.lan:5055/", "v1/queue").toString())
        assertEquals("http://seerr.local:8098/v1/notices/4/ack", queueServiceUrl("http://seerr.local", "v1/notices/4/ack").toString())
    }

    @Test
    fun `the queue service only gets the token where the status service would`() {
        assertNull(queueServiceUrl(null, "v1/queue"))
        assertNull(queueServiceUrl("https://seerr.lan:5055", "v1/queue"))
        assertNull(queueServiceUrl("http://requests.example.com:5055", "v1/queue"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class QueueServiceTest {
    private val testDispatcher = StandardTestDispatcher()

    private val seerrServerRepository = mockk<SeerrServerRepository>()
    private val serverRepository = mockk<ServerRepository>()
    private val user = mockk<JellyfinUser>()

    private val requests = mutableListOf<Request>()
    private val bodies = mutableListOf<String?>()
    private var failure: IOException? = null
    private var code = 200
    private var body = QUEUE_RESPONSE

    private val okHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requests.add(request)
                bodies.add(request.body?.let { Buffer().also(it::writeTo).readUtf8() })
                failure?.let { throw it }
                Response
                    .Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("Made up")
                    .body(body.toResponseBody())
                    .build()
            }.build()

    private val service = QueueService(okHttpClient, seerrServerRepository, serverRepository)

    @Before
    fun setUp() {
        WholphinDispatchers.configure(testDispatcher)
        every { seerrServerRepository.currentServer } returns flowOf(SeerrServer(url = "http://seerr.local:5055"))
        every { serverRepository.currentUser } returns user
        every { user.accessToken } returns "made-up-token"
    }

    @After
    fun tearDown() {
        WholphinDispatchers.reset()
    }

    @Test
    fun `gets the queue with the token in the contract's header`() =
        runTest(testDispatcher) {
            val result = service.queue()

            assertTrue(result is QueueResult.Success)
            val request = requests.single()
            assertEquals("GET", request.method)
            assertEquals("http://seerr.local:8098/v1/queue", request.url.toString())
            assertEquals("made-up-token", request.header("X-Emby-Token"))
        }

    @Test
    fun `removes a row by its key and counts the removal`() =
        runTest(testDispatcher) {
            body = """{"result": "removed"}"""

            val result = service.remove("series:3")

            assertEquals(QueueResult.Success(QueueRemoveResponse("removed")), result)
            val request = requests.single()
            assertEquals("POST", request.method)
            assertEquals("http://seerr.local:8098/v1/queue/remove", request.url.toString())
            assertEquals("""{"key":"series:3"}""", bodies.single())
            assertTrue(request.body?.contentType().toString().startsWith("application/json"))
            assertEquals(1, service.removals.value)
        }

    @Test
    fun `a refused removal isn't counted`() =
        runTest(testDispatcher) {
            code = 409
            body = """{"error": "in_library"}"""

            assertEquals(QueueResult.Failure("in_library"), service.remove("movie:12"))
            assertEquals(0, service.removals.value)
        }

    @Test
    fun `acknowledges a notice with an empty POST`() =
        runTest(testDispatcher) {
            body = """{"acknowledged": true}"""

            assertEquals(QueueResult.Success(true), service.acknowledge(5))
            val request = requests.single()
            assertEquals("POST", request.method)
            assertEquals("http://seerr.local:8098/v1/notices/5/ack", request.url.toString())
            assertEquals("", bodies.single())
        }

    @Test
    fun `gets the notices and the unavailable titles`() =
        runTest(testDispatcher) {
            body = NOTICES_RESPONSE
            assertTrue(service.notices() is QueueResult.Success)
            body = UNAVAILABLE_RESPONSE
            assertTrue(service.unavailable() is QueueResult.Success)

            assertEquals(
                listOf("http://seerr.local:8098/v1/notices", "http://seerr.local:8098/v1/unavailable"),
                requests.map { it.url.toString() },
            )
        }

    @Test
    fun `a connection that fails is a failure`() =
        runTest(testDispatcher) {
            failure = IOException("Made up")

            assertEquals(QueueResult.Failure(), service.queue())
        }

    @Test
    fun `nothing is sent without a Seerr server on the local network`() =
        runTest(testDispatcher) {
            every { seerrServerRepository.currentServer } returns flowOf(SeerrServer(url = "https://requests.example.com"))

            assertEquals(QueueResult.Failure(), service.queue())
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `nothing is sent without a signed in user`() =
        runTest(testDispatcher) {
            every { serverRepository.currentUser } returns null

            assertEquals(QueueResult.Failure(), service.notices())
            assertTrue(requests.isEmpty())
        }
}
