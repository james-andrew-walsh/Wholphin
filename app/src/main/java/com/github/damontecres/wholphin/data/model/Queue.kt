package com.github.damontecres.wholphin.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// What the queue service sends. Everything that can be missing has a default so that a field the
// app doesn't know or a null doesn't fail the whole response. Times are Unix seconds on the server's
// clock, sizes are in bytes and speeds in bytes per second.

private const val QUEUE_REFRESH_INTERVAL_MS = 10_000L
private const val MAX_QUEUE_REFRESH_INTERVAL_MS = 300_000L
private const val MAX_QUEUE_REFRESH_DOUBLINGS = 5

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 60 * SECONDS_PER_MINUTE
private const val SECONDS_PER_DAY = 24 * SECONDS_PER_HOUR

/**
 * How the service treats a download that has a problem for too long
 */
enum class AutoCancel {
    ON,

    /** It only logs what it would cancel */
    DRY,

    OFF,
    ;

    companion object {
        fun from(value: String?): AutoCancel =
            when (value) {
                "on" -> ON
                "dry" -> DRY
                else -> OFF
            }
    }
}

/**
 * Why a download isn't getting anywhere
 */
enum class QueueProblem {
    NO_SEEDS,
    STUCK,
    NOT_FOUND,
    ;

    companion object {
        fun from(value: String?): QueueProblem? =
            when (value) {
                "no_seeds" -> NO_SEEDS
                "stuck" -> STUCK
                "not_found" -> NOT_FOUND
                else -> null
            }
    }
}

/**
 * Everything being downloaded or searched for, for every user
 *
 * @param canRemove whether the current user may remove rows
 * @param rows in the order the service sorted them
 */
@Serializable
data class QueueStatus(
    @SerialName("fetched_at") val fetchedAt: Long? = null,
    @SerialName("auto_cancel") val autoCancel: String? = null,
    @SerialName("can_remove") val canRemove: Boolean = false,
    val rows: List<QueueRow> = emptyList(),
)

/**
 * @param key what the service knows the row by, it is sent back to remove it
 * @param kind `movie`, `series`, `request` or `torrent`
 * @param state the raw state, free text because the service can add more
 * @param stateLabel a plain label for [state]
 * @param downloads how many downloads the row stands for, a series can have several
 * @param partial a series which is in the library, only some episodes are missing
 * @param cancelAt when the service cancels it because of [problem]
 */
@Serializable
data class QueueRow(
    val key: String,
    val kind: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("tmdb_id") val tmdbId: Int? = null,
    val title: String = "",
    val year: Int? = null,
    @SerialName("requested_by") val requestedBy: List<String> = emptyList(),
    @SerialName("requested_at") val requestedAt: Long? = null,
    val state: String? = null,
    @SerialName("state_label") val stateLabel: String? = null,
    val progress: Double? = null,
    val size: Long? = null,
    @SerialName("dlspeed") val downloadSpeed: Long = 0,
    val seeds: Int? = null,
    @SerialName("swarm_seeds") val swarmSeeds: Int? = null,
    val downloads: Int = 1,
    val partial: Boolean = false,
    val problem: String? = null,
    @SerialName("cancel_at") val cancelAt: Long? = null,
) {
    /**
     * The movie or series a row is for, null for a request or a stray download
     */
    val itemType: SeerrItemType?
        get() =
            when (kind) {
                "movie" -> SeerrItemType.MOVIE
                "series" -> SeerrItemType.TV
                else -> null
            }

    /**
     * Whether the row needs attention, which shows on it
     */
    val hasProblem: Boolean
        get() = QueueProblem.from(problem) != null || state == "error" || state == "import_blocked"
}

@Serializable
data class QueueRemoveResponse(
    val result: String? = null,
)

/**
 * Something of the user's which the service cancelled or someone removed
 *
 * @param reason `not_found`, `no_seeds`, `stuck` or `removed`
 * @param partial only the missing episodes of a series were stopped
 * @param removedBy who removed it when [reason] is `removed`
 * @param at when it happened
 */
@Serializable
data class QueueNotice(
    val id: Int,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("tmdb_id") val tmdbId: Int? = null,
    val title: String = "",
    val year: Int? = null,
    val reason: String? = null,
    val partial: Boolean = false,
    @SerialName("removed_by") val removedBy: String? = null,
    val at: Long? = null,
)

@Serializable
data class QueueNotices(
    val notices: List<QueueNotice> = emptyList(),
)

@Serializable
data class QueueAckResponse(
    val acknowledged: Boolean = false,
)

/**
 * A title the service gave up on
 */
@Serializable
data class UnavailableTitle(
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("tmdb_id") val tmdbId: Int? = null,
    val reason: String? = null,
    val at: Long? = null,
)

@Serializable
data class UnavailableResponse(
    val titles: List<UnavailableTitle> = emptyList(),
)

/**
 * One removal from the queue, by a person or by auto-cancel, kept for good
 *
 * @param reason `not_found`, `no_seeds`, `stuck` or `removed`
 * @param partial only some downloads or missing episodes of a series went, the series stayed
 * @param removedBy who removed it, `Ourflix` for auto-cancel, blank if not known
 * @param requestedBy the names of who had requested it, empty if nobody or not known
 * @param at when it happened
 */
@Serializable
data class QueueRemoval(
    val id: Int,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("tmdb_id") val tmdbId: Int? = null,
    val title: String = "",
    val year: Int? = null,
    val reason: String? = null,
    val partial: Boolean = false,
    @SerialName("removed_by") val removedBy: String? = null,
    @SerialName("requested_by") val requestedBy: List<String> = emptyList(),
    val at: Long? = null,
) {
    /**
     * The movie or series it was, null if the service didn't say
     */
    val itemType: SeerrItemType?
        get() =
            SeerrItemType.fromString(mediaType).takeIf { it == SeerrItemType.MOVIE || it == SeerrItemType.TV }
}

/**
 * Every removal, newest first
 */
@Serializable
data class QueueRemovals(
    val removals: List<QueueRemoval> = emptyList(),
)

/**
 * A movie or series by its TMDB ID, the same ID can be both a movie and a different series
 */
data class TitleKey(
    val type: SeerrItemType,
    val tmdbId: Int,
)

/**
 * The titles in a response, without any that don't say what they are
 */
fun UnavailableResponse.keys(): Set<TitleKey> =
    titles
        .mapNotNull { title ->
            val type = SeerrItemType.fromString(title.mediaType)
            val tmdbId = title.tmdbId
            if ((type == SeerrItemType.MOVIE || type == SeerrItemType.TV) && tmdbId != null) {
                TitleKey(type, tmdbId)
            } else {
                null
            }
        }.toSet()

/**
 * Whether a poster gets the red X: nothing has been requested and the service gave up on it before
 *
 * A new request makes it pending, which takes the X away without anything else having to happen.
 */
fun showsUnavailable(
    availability: SeerrAvailability?,
    type: SeerrItemType?,
    tmdbId: Int?,
    unavailable: Set<TitleKey>,
): Boolean {
    val notRequested =
        availability == null || availability == SeerrAvailability.UNKNOWN || availability == SeerrAvailability.DELETED
    if (!notRequested || type == null || tmdbId == null) return false
    return TitleKey(type, tmdbId) in unavailable
}

/**
 * How long to wait before refreshing the queue again, doubling after each failure
 */
fun queueRefreshDelay(failures: Int): Duration =
    (QUEUE_REFRESH_INTERVAL_MS shl failures.coerceIn(0, MAX_QUEUE_REFRESH_DOUBLINGS))
        .coerceAtMost(MAX_QUEUE_REFRESH_INTERVAL_MS)
        .milliseconds

/**
 * A short time to go, in its largest whole unit: 2d, 3h or 40m
 *
 * The last minute shows as 1m, not as nothing.
 */
fun formatCountdown(seconds: Long): String =
    when {
        seconds >= SECONDS_PER_DAY -> "${seconds / SECONDS_PER_DAY}d"
        seconds >= SECONDS_PER_HOUR -> "${seconds / SECONDS_PER_HOUR}h"
        else -> "${(seconds / SECONDS_PER_MINUTE).coerceAtLeast(1)}m"
    }

/**
 * Which item focus belongs on once the one that had it has gone, null if it hasn't gone
 *
 * The next item which is still there, or the one before if it was the last. A focused item which
 * was never in [previous] hasn't gone, it can just be that it hasn't loaded yet.
 *
 * @param previous the keys before they changed
 */
fun <K> keyAfterRemoval(
    previous: List<K>,
    keys: List<K>,
    focused: K?,
): K? {
    val index = previous.indexOf(focused)
    if (index < 0 || focused in keys) return null
    val remaining = keys.toSet()
    return previous.drop(index + 1).firstOrNull { it in remaining }
        ?: previous.take(index).lastOrNull { it in remaining }
}

/**
 * The next item which is still there once [gone] has gone, without falling back to an earlier one
 */
fun <K> nextRemaining(
    previous: List<K>,
    keys: List<K>,
    gone: K,
): K? {
    val remaining = keys.toSet()
    return previous.drop(previous.indexOf(gone) + 1).firstOrNull { it in remaining }
}
