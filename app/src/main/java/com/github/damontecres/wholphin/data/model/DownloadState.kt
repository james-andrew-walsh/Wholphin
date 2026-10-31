package com.github.damontecres.wholphin.data.model

import com.github.damontecres.wholphin.api.seerr.model.DownloadingItem
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/**
 * Where a requested movie is on its way to being playable
 *
 * @param active whether the state can change without anyone opening the page again
 */
enum class DownloadState(
    val active: Boolean,
) {
    PENDING_APPROVAL(true),
    SEARCHING(true),
    QUEUED(true),
    DOWNLOADING(true),
    PREPARING(true),
    READY(false),
    FAILED(false),
    DECLINED(false),
    UNAVAILABLE(false),
}

/**
 * A [DownloadState] with what Seerr supplied about it, nothing here is ever estimated locally
 *
 * @param fraction how much is downloaded, from 0 to 1
 */
data class DownloadProgress(
    val state: DownloadState,
    val fraction: Float? = null,
    val timeLeft: Duration? = null,
)

// Best entry first: a retried grab can leave a failed entry beside the new download
private val queueStatePriority =
    listOf(
        DownloadState.DOWNLOADING,
        DownloadState.QUEUED,
        DownloadState.PREPARING,
        DownloadState.FAILED,
    )

/**
 * Normalize a Radarr queue status as passed through by Seerr
 *
 * `warning` is how Radarr reports a stalled download, which is still in progress. `completed` is
 * downloaded and waiting for import. Anything else (queued, paused, delay, etc) hasn't started.
 */
private fun queueState(item: DownloadingItem): DownloadState =
    when (item.status?.lowercase()) {
        "downloading", "warning" -> DownloadState.DOWNLOADING
        "completed" -> DownloadState.PREPARING
        "failed" -> DownloadState.FAILED
        else -> DownloadState.QUEUED
    }

/**
 * Normalize everything known about a request
 *
 * Being in Jellyfin wins over everything, it is the only thing that makes a movie playable. Seerr
 * saying the media is available is not enough, it can learn that from Radarr before Jellyfin has
 * the item, so [inJellyfin] must come from the user's own Jellyfin lookup.
 *
 * @param requestStatus the status of this user's request
 * @param requestExists false if the request has since been removed
 * @param mediaStatus the Seerr availability, of the 4K version if the request is for 4K
 * @param queue the Seerr download status, of the 4K version if the request is for 4K
 * @param inJellyfin whether the user's Jellyfin returned the item
 * @param seenInQueue whether a queue entry was seen earlier, once it is gone the movie was imported
 * @param now the current time
 */
fun downloadProgress(
    requestStatus: RequestStatus,
    requestExists: Boolean,
    mediaStatus: SeerrAvailability?,
    queue: List<DownloadingItem>?,
    inJellyfin: Boolean,
    seenInQueue: Boolean,
    now: Instant,
): DownloadProgress {
    if (inJellyfin) return DownloadProgress(DownloadState.READY)
    if (!requestExists) return DownloadProgress(DownloadState.UNAVAILABLE)
    when (requestStatus) {
        RequestStatus.DECLINED -> return DownloadProgress(DownloadState.DECLINED)
        RequestStatus.FAILURE -> return DownloadProgress(DownloadState.FAILED)
        RequestStatus.PENDING -> return DownloadProgress(DownloadState.PENDING_APPROVAL)
        else -> {}
    }
    if (mediaStatus == SeerrAvailability.BLOCKLISTED) return DownloadProgress(DownloadState.UNAVAILABLE)
    val entry = queue?.minByOrNull { queueStatePriority.indexOf(queueState(it)) }
    // Seerr keeps deleted media as deleted while Radarr grabs it again, so a live entry wins
    if (mediaStatus == SeerrAvailability.DELETED && (entry == null || queueState(entry) == DownloadState.FAILED)) {
        return DownloadProgress(DownloadState.UNAVAILABLE)
    }
    if (entry != null) {
        val state = queueState(entry)
        return if (state == DownloadState.DOWNLOADING || state == DownloadState.QUEUED) {
            DownloadProgress(
                state = state,
                fraction = downloadFraction(entry.propertySize, entry.sizeLeft),
                timeLeft = downloadTimeLeft(entry.timeLeft, entry.estimatedCompletionTime, now),
            )
        } else {
            DownloadProgress(state)
        }
    }
    val available =
        mediaStatus == SeerrAvailability.AVAILABLE ||
            mediaStatus == SeerrAvailability.PARTIALLY_AVAILABLE
    // Seerr leaves a completed request as it is when the movie is requested again, so the media's
    // status can belong to a newer request and nothing is on its way for this one
    if (requestStatus == RequestStatus.COMPLETED && !available) {
        return DownloadProgress(DownloadState.UNAVAILABLE)
    }
    return DownloadProgress(
        if (seenInQueue || available) DownloadState.PREPARING else DownloadState.SEARCHING,
    )
}

/**
 * Whether a download has been seen in the queue, so that its entry going away means it was imported
 *
 * A failed entry doesn't count, it goes away when Radarr searches again.
 */
fun seenInQueue(
    previous: Boolean,
    queue: List<DownloadingItem>?,
): Boolean =
    if (queue.isNullOrEmpty()) {
        previous
    } else {
        queue.any { queueState(it) != DownloadState.FAILED }
    }

/**
 * Whether a row can still change, so needs refreshing
 *
 * A failed download on an approved request isn't final, Radarr normally searches again.
 */
fun keepsRefreshing(
    state: DownloadState,
    requestStatus: RequestStatus,
): Boolean = state.active || (state == DownloadState.FAILED && requestStatus == RequestStatus.APPROVED)

/**
 * How much is downloaded, or null if the sizes supplied can't say
 */
fun downloadFraction(
    size: Double?,
    sizeLeft: Double?,
): Float? {
    if (size == null || sizeLeft == null || !size.isFinite() || !sizeLeft.isFinite()) return null
    if (size <= 0.0 || sizeLeft < 0.0 || sizeLeft > size) return null
    return ((size - sizeLeft) / size).toFloat()
}

/**
 * Whole percent downloaded, rounded down so that 100 is only shown when it is true
 */
fun downloadPercent(fraction: Float): Int = (fraction * 100).toInt().coerceIn(0, 100)

/**
 * The time left supplied by the server, preferring [timeLeft] since it doesn't rely on the TV's clock
 *
 * @return the time rounded up to a minute since Seerr only updates about once a minute, or null if neither value is usable
 */
fun downloadTimeLeft(
    timeLeft: String?,
    estimatedCompletionTime: String?,
    now: Instant,
): Duration? =
    (parseTimeLeft(timeLeft) ?: timeUntil(estimatedCompletionTime, now))?.let {
        ceil(it.inWholeMilliseconds / 60_000.0).toLong().minutes
    }

private val timeLeftRegex = Regex("""(?:(\d{1,8})\.)?(\d{1,2}):(\d{2}):(\d{2})(?:\.\d+)?""")

/**
 * Parse a .NET TimeSpan as sent by Radarr: `[d.]hh:mm:ss[.fffffff]`
 *
 * @return the positive duration or null, a zero time left is not an estimate
 */
fun parseTimeLeft(value: String?): Duration? {
    val match = value?.trim()?.let(timeLeftRegex::matchEntire) ?: return null
    val (days, hours, minutes, seconds) = match.destructured
    val duration =
        days.ifEmpty { "0" }.toInt().days +
            hours.toInt().hours +
            minutes.toInt().minutes +
            seconds.toInt().seconds
    return duration.takeIf { it.isPositive() }
}

private fun timeUntil(
    value: String?,
    now: Instant,
): Duration? {
    if (value == null) return null
    return try {
        java.time.Duration
            .between(now, Instant.parse(value))
            .toKotlinDuration()
            .takeIf { it.isPositive() }
    } catch (_: DateTimeParseException) {
        null
    }
}

private const val REFRESH_INTERVAL_MS = 15_000L
private const val MAX_REFRESH_INTERVAL_MS = 300_000L
private const val MAX_REFRESH_DOUBLINGS = 5

/**
 * How long to wait before refreshing again, doubling after each refresh that fails
 */
fun downloadRefreshDelay(failures: Int): Duration =
    (REFRESH_INTERVAL_MS shl failures.coerceIn(0, MAX_REFRESH_DOUBLINGS))
        .coerceAtMost(MAX_REFRESH_INTERVAL_MS)
        .milliseconds

/**
 * Take up to [limit] of the [items] starting at [start], wrapping around so that every item gets
 * its turn when there are more than the limit
 */
fun <T> nextRefreshBatch(
    items: List<T>,
    start: Int,
    limit: Int,
): List<T> =
    if (items.size <= limit) {
        items
    } else {
        List(limit) { items[(start + it) % items.size] }
    }

/**
 * Whether there is another page of requests after [fetched] have been fetched
 *
 * @param total the total supplied by the server, if any
 * @param lastPageSize how many requests were on the last page
 * @param pageSize how many requests were asked for
 */
fun hasMoreDownloads(
    fetched: Int,
    total: Int?,
    lastPageSize: Int,
    pageSize: Int,
): Boolean =
    if (lastPageSize == 0) {
        false
    } else if (total != null) {
        fetched < total
    } else {
        lastPageSize == pageSize
    }
