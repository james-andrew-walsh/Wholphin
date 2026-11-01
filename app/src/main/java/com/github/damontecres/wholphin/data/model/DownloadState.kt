package com.github.damontecres.wholphin.data.model

import com.github.damontecres.wholphin.api.seerr.model.DownloadingItem
import com.github.damontecres.wholphin.api.seerr.model.Season
import kotlinx.serialization.Serializable
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
    WAITING_FOR_RELEASE(true),
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
 * @param downloadId the download client's ID, only set while the download is going through the queue
 */
@Serializable
data class DownloadProgress(
    val state: DownloadState,
    val fraction: Float? = null,
    val timeLeft: Duration? = null,
    val downloadId: String? = null,
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
 * @param released false if a movie isn't out, see [isMovieReleased]
 */
fun downloadProgress(
    requestStatus: RequestStatus,
    requestExists: Boolean,
    mediaStatus: SeerrAvailability?,
    queue: List<DownloadingItem>?,
    inJellyfin: Boolean,
    seenInQueue: Boolean,
    now: Instant,
    released: Boolean = true,
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
    val failed = entry != null && queueState(entry) == DownloadState.FAILED
    // Seerr keeps deleted media as deleted while Radarr grabs it again and until a scan finds the
    // import, so a live download or one that has just left the queue wins
    if (mediaStatus == SeerrAvailability.DELETED && !seenInQueue && (entry == null || failed)) {
        // Unlike on a completed request, Radarr normally searches again after this
        val finished = !(failed && requestStatus == RequestStatus.APPROVED)
        return DownloadProgress(if (finished) DownloadState.UNAVAILABLE else DownloadState.FAILED)
    }
    if (entry != null) return queueProgress(entry, now)
    val available =
        mediaStatus == SeerrAvailability.AVAILABLE ||
            mediaStatus == SeerrAvailability.PARTIALLY_AVAILABLE
    // Seerr leaves a completed request as it is when the movie is requested again, so the media's
    // status can belong to a newer request and nothing is on its way for this one. Deleted media
    // only gets here after a download was seen, which is on its way
    if (requestStatus == RequestStatus.COMPLETED && !available && mediaStatus != SeerrAvailability.DELETED) {
        return DownloadProgress(DownloadState.UNAVAILABLE)
    }
    return DownloadProgress(
        when {
            seenInQueue || available -> DownloadState.PREPARING
            released -> DownloadState.SEARCHING
            else -> DownloadState.WAITING_FOR_RELEASE
        },
    )
}

/**
 * Whether the seasons a series request is for are in the library
 *
 * Seerr reports a series as available, and it has a Jellyfin ID, as soon as any season of it is in.
 * That says nothing about a request for another season. A season is partially available from its
 * first episode, which only means it's in once [queue] is empty: an airing season stays that way.
 *
 * @param requested the requested season numbers, empty for a movie
 * @param mediaSeasons Seerr's availability per season, if it sent any
 * @param is4k whether to read the 4K availability
 * @param queue the queue entries for the [requested] seasons
 * @return null if it can't be told, the media on a request has no seasons when the details can't be fetched
 */
fun requestedSeasonsInLibrary(
    requested: List<Int>,
    mediaSeasons: List<Season>?,
    is4k: Boolean,
    queue: List<DownloadingItem>?,
): Boolean? {
    if (requested.isEmpty()) return true
    if (mediaSeasons.isNullOrEmpty()) return null
    return requested.all { number ->
        val season = mediaSeasons.firstOrNull { it.seasonNumber == number }
        val availability = SeerrAvailability.from(if (is4k) season?.status4k else season?.status)
        availability == SeerrAvailability.AVAILABLE ||
            (availability == SeerrAvailability.PARTIALLY_AVAILABLE && queue.isNullOrEmpty())
    }
}

/**
 * The entries of a queue that a request is for
 *
 * Seerr's queue covers a whole series. An entry which doesn't say its season could be for this one.
 *
 * @param seasons the requested season numbers, empty for a movie
 */
fun requestedQueue(
    queue: List<DownloadingItem>?,
    seasons: List<Int>,
): List<DownloadingItem>? =
    if (seasons.isEmpty()) {
        queue
    } else {
        queue?.filter { item -> item.episode?.seasonNumber.let { it == null || it in seasons } }
    }

/**
 * What a Seerr queue entry says about a download
 */
fun queueProgress(
    entry: DownloadingItem,
    now: Instant,
): DownloadProgress {
    val state = queueState(entry)
    return if (state == DownloadState.DOWNLOADING || state == DownloadState.QUEUED) {
        DownloadProgress(
            state = state,
            fraction = downloadFraction(entry.propertySize, entry.sizeLeft),
            timeLeft = downloadTimeLeft(entry.timeLeft, entry.estimatedCompletionTime, now),
            downloadId = entry.downloadId,
        )
    } else {
        DownloadProgress(state, downloadId = entry.downloadId.takeIf { state.active })
    }
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

// Radarr searches as soon as it's asked to and then at every RSS sync, so by now it's finding nothing
private val STUCK_AFTER = 2.hours

// `warning` is how Radarr reports a stalled download, and nobody here pauses a download
private val STALLED_QUEUE_STATUSES = setOf("warning", "paused", "failed")

/**
 * Whether a download has failed or is stuck for any reason, which offers removal on its row
 *
 * It is if any of these hold:
 * - it failed, or Radarr lost it and Seerr has marked it as deleted
 * - every entry it has in the queue is stalled, a healthy one beside them is still making progress
 * - the status service says so, see [DownloadStatus.stuck]
 * - nothing has been in the queue for [STUCK_AFTER]
 *
 * Not being out yet is no problem, and neither is waiting for someone to approve a request.
 *
 * @param mediaStatus the Seerr availability, of the 4K version if the request is for 4K
 * @param queue the queue entries that the request is for
 * @param status the status service's details of the download, if there are any
 * @param activeAt the last time there was a queue entry, or else when the request last changed
 */
fun downloadFailedOrStuck(
    state: DownloadState,
    mediaStatus: SeerrAvailability?,
    queue: List<DownloadingItem>?,
    status: DownloadStatus?,
    activeAt: Instant?,
    now: Instant,
): Boolean =
    when (state) {
        DownloadState.FAILED -> {
            true
        }

        DownloadState.UNAVAILABLE -> {
            mediaStatus == SeerrAvailability.DELETED
        }

        DownloadState.SEARCHING,
        DownloadState.QUEUED,
        DownloadState.DOWNLOADING,
        DownloadState.PREPARING,
        -> {
            if (queue.isNullOrEmpty()) {
                activeAt != null &&
                    java.time.Duration
                        .between(activeAt, now)
                        .toKotlinDuration() >= STUCK_AFTER
            } else {
                queue.all { it.status?.lowercase() in STALLED_QUEUE_STATUSES } || status?.stuck == true
            }
        }

        DownloadState.PENDING_APPROVAL,
        DownloadState.WAITING_FOR_RELEASE,
        DownloadState.READY,
        DownloadState.DECLINED,
        -> {
            false
        }
    }

/**
 * Parse a time as Seerr sends it, null if it can't be
 */
fun parseSeerrTime(value: String?): Instant? =
    try {
        value?.let(Instant::parse)
    } catch (_: DateTimeParseException) {
        null
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
