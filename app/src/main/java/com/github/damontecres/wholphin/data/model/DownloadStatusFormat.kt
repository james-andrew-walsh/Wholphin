package com.github.damontecres.wholphin.data.model

import com.github.damontecres.wholphin.ui.formatBytes
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// The formatters return null for a value which is missing or can't be right, the page shows that as
// a dash. None of them make up a number.

private const val DETAIL_REFRESH_INTERVAL_MS = 5_000L
private const val MAX_DETAIL_REFRESH_INTERVAL_MS = 60_000L
private const val MAX_DETAIL_REFRESH_DOUBLINGS = 4

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 60 * SECONDS_PER_MINUTE
private const val SECONDS_PER_DAY = 24 * SECONDS_PER_HOUR
private const val BYTES_PER_UNIT = 1024L

/**
 * How much of the history the graph shows, in seconds
 */
const val GRAPH_WINDOW_SECONDS = 3600L

// The lowest top of the graph's scale, so that an idle download doesn't look like a busy one
private const val MIN_GRAPH_CEILING = BYTES_PER_UNIT
private val graphSteps = listOf(1L, 2L, 5L, 10L, 20L, 50L, 100L, 200L, 500L)

/**
 * How long to wait before refreshing the detail page again, doubling after each failure
 */
fun downloadDetailRefreshDelay(failures: Int): Duration =
    (DETAIL_REFRESH_INTERVAL_MS shl failures.coerceIn(0, MAX_DETAIL_REFRESH_DOUBLINGS))
        .coerceAtMost(MAX_DETAIL_REFRESH_INTERVAL_MS)
        .milliseconds

fun formatSize(bytes: Long?): String? =
    when {
        bytes == null || bytes < 0 -> null
        bytes == 0L -> "0 B"
        else -> formatBytes(bytes)
    }

fun formatSpeed(bytesPerSecond: Long?): String? = formatSize(bytesPerSecond)?.let { "$it/s" }

/**
 * The time left, with the two largest units that matter
 *
 * @return null unless it's a positive time, a zero time left is not an estimate
 */
fun formatTimeLeft(seconds: Long?): String? {
    if (seconds == null || seconds <= 0) return null
    val days = seconds / SECONDS_PER_DAY
    val hours = seconds % SECONDS_PER_DAY / SECONDS_PER_HOUR
    val minutes = seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds}s"
    }
}

/**
 * A ratio or availability
 */
fun formatDecimal(value: Double?): String? =
    value
        ?.takeIf { it.isFinite() && it >= 0.0 }
        ?.let { String.format(Locale.getDefault(), "%.2f", it) }

fun formatCount(value: Int?): String? = value?.takeIf { it >= 0 }?.toString()

/**
 * The first few peer clients, the server already has them with the largest first
 */
fun formatPeerClients(
    clients: List<PeerClientCount>,
    limit: Int = 4,
): String? =
    clients
        .filter { !it.client.isNullOrBlank() && it.count != null }
        .take(limit)
        .joinToString(", ") { "${it.client} ${it.count}" }
        .ifEmpty { null }

/**
 * The top of the graph's scale: the smallest round speed that fits [peak], so that its labels are
 * round numbers in the units the speeds are shown in
 */
fun graphCeiling(peak: Long): Long {
    if (peak <= MIN_GRAPH_CEILING) return MIN_GRAPH_CEILING
    var unit = 1L
    while (peak / unit >= BYTES_PER_UNIT) unit *= BYTES_PER_UNIT
    val step = graphSteps.firstOrNull { it * unit >= peak }
    return if (step != null) step * unit else BYTES_PER_UNIT * unit
}

/**
 * The lines of the bandwidth graph, ready to be scaled up to the size of the canvas
 *
 * Each segment is a run of samples without a gap, as x and y pairs from 0 to 1. x is 1 at the right
 * edge of the graph and y is 1 at [ceiling]. A segment with a single point is one sample between
 * two gaps.
 *
 * @param ceiling the speed at the top of the graph
 */
class BandwidthGraph(
    val ceiling: Long,
    val download: List<FloatArray>,
    val upload: List<FloatArray>,
)

/**
 * Turn the [history] into a graph of the last [windowSeconds] up to [now]
 *
 * If sampling has stopped the lines end before the right edge, rather than seeming to be current.
 *
 * @param now the server's time, the TV's clock could be different to it
 */
fun bandwidthGraph(
    history: BandwidthHistory,
    now: Long?,
    windowSeconds: Long = GRAPH_WINDOW_SECONDS,
): BandwidthGraph {
    val interval = history.intervalSeconds?.toLong()?.takeIf { it > 0 }
    val lastSample = history.lastSampleAt
    if (interval == null || lastSample == null || windowSeconds <= 0) {
        return BandwidthGraph(MIN_GRAPH_CEILING, emptyList(), emptyList())
    }
    val staleness = ((now ?: lastSample) - lastSample).coerceAtLeast(0)
    val samplesInWindow =
        if (staleness > windowSeconds) 0 else ((windowSeconds - staleness) / interval + 1).toInt()
    val download = history.dl.takeLast(samplesInWindow)
    val upload = history.ul.takeLast(samplesInWindow)
    val ceiling = graphCeiling(maxOf(download.peak(), upload.peak()))
    val spacing = interval.toFloat() / windowSeconds
    val end = 1f - staleness.toFloat() / windowSeconds
    return BandwidthGraph(
        ceiling = ceiling,
        download = download.segments(end, spacing, ceiling),
        upload = upload.segments(end, spacing, ceiling),
    )
}

private fun List<Long?>.peak(): Long = maxOfOrNull { it ?: 0L } ?: 0L

private fun isSample(value: Long?): Boolean = value != null && value >= 0

private fun List<Long?>.segments(
    end: Float,
    spacing: Float,
    ceiling: Long,
): List<FloatArray> {
    val segments = ArrayList<FloatArray>()
    var start = 0
    while (start < size) {
        if (!isSample(this[start])) {
            start++
            continue
        }
        var stop = start
        while (stop < size && isSample(this[stop])) stop++
        val points = FloatArray((stop - start) * 2)
        for (index in start until stop) {
            val point = (index - start) * 2
            points[point] = end - (lastIndex - index) * spacing
            points[point + 1] = this[index]!!.toFloat() / ceiling
        }
        segments.add(points)
        start = stop
    }
    return segments
}
