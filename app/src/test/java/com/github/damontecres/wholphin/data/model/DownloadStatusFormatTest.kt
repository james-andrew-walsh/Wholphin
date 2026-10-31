package com.github.damontecres.wholphin.data.model

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

private const val KIB = 1024L
private const val MIB = 1024L * KIB
private const val GIB = 1024L * MIB
private const val TOLERANCE = 0.0001f

class DownloadStatusFormatTest {
    private val defaultLocale = Locale.getDefault()

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `sizes use the largest unit that fits`() {
        assertEquals("0 B", formatSize(0))
        assertEquals("512.00 B", formatSize(512))
        assertEquals("1.50 MiB", formatSize(MIB + MIB / 2))
        assertEquals("4.25 GiB", formatSize(4 * GIB + GIB / 4))
    }

    @Test
    fun `speeds are sizes per second`() {
        assertEquals("0 B/s", formatSpeed(0))
        assertEquals("2.00 KiB/s", formatSpeed(2 * KIB))
        assertEquals("1.50 MiB/s", formatSpeed(MIB + MIB / 2))
    }

    @Test
    fun `missing or impossible values are not formatted`() {
        assertNull(formatSize(null))
        assertNull(formatSize(-1))
        assertNull(formatSpeed(null))
        assertNull(formatSpeed(-1))
        assertNull(formatCount(null))
        assertNull(formatCount(-1))
        assertNull(formatDecimal(null))
        assertNull(formatDecimal(-1.0))
        assertNull(formatDecimal(Double.NaN))
        assertNull(formatDecimal(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `time left shows the two largest units`() {
        assertEquals("45s", formatTimeLeft(45))
        assertEquals("5m", formatTimeLeft(312))
        assertEquals("3h 20m", formatTimeLeft(12005))
        assertEquals("1h 0m", formatTimeLeft(3600))
        assertEquals("2d 3h", formatTimeLeft(183659))
    }

    @Test
    fun `only a positive time left is an estimate`() {
        assertNull(formatTimeLeft(null))
        assertNull(formatTimeLeft(0))
        assertNull(formatTimeLeft(-5))
    }

    @Test
    fun `ratios and counts`() {
        assertEquals("0.00", formatDecimal(0.0))
        assertEquals("1.25", formatDecimal(1.2512))
        assertEquals("0", formatCount(0))
        assertEquals("12", formatCount(12))
    }

    @Test
    fun `peer clients stop at the limit and skip incomplete entries`() {
        val clients =
            listOf(
                PeerClientCount("Client A", 5),
                PeerClientCount(null, 4),
                PeerClientCount("Client B", null),
                PeerClientCount("Client C", 2),
                PeerClientCount("Client D", 1),
            )
        assertEquals("Client A 5, Client C 2", formatPeerClients(clients, limit = 2))
        assertNull(formatPeerClients(emptyList()))
        assertNull(formatPeerClients(listOf(PeerClientCount(" ", 1))))
    }

    @Test
    fun `refresh backs off from 5 to 60 seconds`() {
        assertEquals(5.seconds, downloadDetailRefreshDelay(0))
        assertEquals(10.seconds, downloadDetailRefreshDelay(1))
        assertEquals(20.seconds, downloadDetailRefreshDelay(2))
        assertEquals(40.seconds, downloadDetailRefreshDelay(3))
        assertEquals(60.seconds, downloadDetailRefreshDelay(4))
        assertEquals(60.seconds, downloadDetailRefreshDelay(1000))
    }

    @Test
    fun `graph ceiling is the smallest round speed that fits`() {
        assertEquals(KIB, graphCeiling(0))
        assertEquals(KIB, graphCeiling(-1))
        assertEquals(KIB, graphCeiling(KIB))
        assertEquals(2 * KIB, graphCeiling(KIB + 1))
        assertEquals(5 * MIB, graphCeiling(3 * MIB))
        assertEquals(5 * MIB, graphCeiling(5 * MIB))
        assertEquals(10 * MIB, graphCeiling(5 * MIB + 1))
        assertEquals(500 * MIB, graphCeiling(499 * MIB))
        assertEquals(GIB, graphCeiling(900 * MIB))
    }

    private fun history(
        dl: List<Long?>,
        ul: List<Long?> = dl.map { null },
        lastSampleAt: Long? = 1000,
        interval: Int? = 5,
    ) = BandwidthHistory(
        intervalSeconds = interval,
        lastSampleAt = lastSampleAt,
        dl = dl,
        ul = ul,
    )

    private fun assertPoints(
        expected: FloatArray,
        actual: FloatArray,
    ) = assertArrayEquals(expected, actual, TOLERANCE)

    @Test
    fun `graph scales samples to its window and ceiling`() {
        val graph = bandwidthGraph(history(listOf(0L, KIB, 2 * KIB)), now = 1000, windowSeconds = 20)

        assertEquals(2 * KIB, graph.ceiling)
        assertEquals(1, graph.download.size)
        assertPoints(floatArrayOf(.5f, 0f, .75f, .5f, 1f, 1f), graph.download.single())
        assertTrue(graph.upload.isEmpty())
    }

    @Test
    fun `a missing sample is a gap and not zero`() {
        val graph =
            bandwidthGraph(
                history(listOf(KIB, KIB, null, null, KIB)),
                now = 1000,
                windowSeconds = 20,
            )

        assertEquals(2, graph.download.size)
        assertPoints(floatArrayOf(0f, 1f, .25f, 1f), graph.download[0])
        assertPoints(floatArrayOf(1f, 1f), graph.download[1])
    }

    @Test
    fun `an impossible sample is also a gap`() {
        val graph = bandwidthGraph(history(listOf(KIB, -1L, KIB)), now = 1000, windowSeconds = 20)

        assertEquals(2, graph.download.size)
    }

    @Test
    fun `download and upload are scaled the same`() {
        val graph =
            bandwidthGraph(
                history(dl = listOf(MIB), ul = listOf(5 * MIB)),
                now = 1000,
                windowSeconds = 20,
            )

        assertEquals(5 * MIB, graph.ceiling)
        assertPoints(floatArrayOf(1f, .2f), graph.download.single())
        assertPoints(floatArrayOf(1f, 1f), graph.upload.single())
    }

    @Test
    fun `lines end early if sampling has stopped`() {
        val graph = bandwidthGraph(history(listOf(KIB, KIB)), now = 1010, windowSeconds = 20)

        assertPoints(floatArrayOf(.25f, 1f, .5f, 1f), graph.download.single())
    }

    @Test
    fun `samples older than the window are dropped and don't set the scale`() {
        val samples = listOf<Long?>(GIB, KIB, KIB, KIB)
        val graph = bandwidthGraph(history(samples), now = 1000, windowSeconds = 10)

        assertEquals(KIB, graph.ceiling)
        assertPoints(floatArrayOf(0f, 1f, .5f, 1f, 1f, 1f), graph.download.single())
    }

    @Test
    fun `nothing is drawn once every sample is too old`() {
        val graph = bandwidthGraph(history(listOf(GIB)), now = 1021, windowSeconds = 20)

        assertTrue(graph.download.isEmpty())
        assertEquals(KIB, graph.ceiling)
    }

    @Test
    fun `an empty history after a restart is an empty graph`() {
        val graph = bandwidthGraph(history(emptyList(), lastSampleAt = null), now = 1000)

        assertTrue(graph.download.isEmpty())
        assertTrue(graph.upload.isEmpty())
        assertEquals(KIB, graph.ceiling)
    }

    @Test
    fun `a whole hour of samples fits the default window`() {
        val graph = bandwidthGraph(history(List(720) { KIB }), now = 1000)

        val points = graph.download.single()
        assertEquals(1440, points.size)
        assertEquals(1f, points[1438], TOLERANCE)
        assertFalse(points[0] < 0f)
    }
}
