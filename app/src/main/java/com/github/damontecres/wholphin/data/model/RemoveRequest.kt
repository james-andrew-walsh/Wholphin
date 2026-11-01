package com.github.damontecres.wholphin.data.model

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.api.seerr.model.MediaInfo
import com.github.damontecres.wholphin.services.SeerrUserConfig

/**
 * Whether Seerr says that any of this is in the library
 *
 * Removing a series takes all of it out of Sonarr, files included, and Seerr can have the series
 * as processing for a new request while an older season is available. So every season counts here,
 * as does the other of the normal and 4K versions since the Seerr record is shared between them.
 */
fun isInLibrary(media: MediaInfo): Boolean {
    val statuses =
        listOf(media.status, media.status4k) +
            media.seasons.orEmpty().flatMap { listOf(it.status, it.status4k) }
    return statuses.any {
        val availability = SeerrAvailability.from(it)
        availability == SeerrAvailability.AVAILABLE || availability == SeerrAvailability.PARTIALLY_AVAILABLE
    }
}

/**
 * Whether Seerr has a record to remove and removing it can't lose anything from the library
 *
 * A blocklisted item keeps its record when removed and can't be requested again anyway.
 */
fun isRemovable(media: MediaInfo?): Boolean =
    media?.id != null &&
        !isInLibrary(media) &&
        SeerrAvailability.from(media.status) != SeerrAvailability.BLOCKLISTED

/**
 * Whether the user can be offered removing this request and starting over
 */
fun canRemoveRequest(
    user: SeerrUserConfig?,
    media: MediaInfo?,
    enabled: Boolean = BuildConfig.REMOVE_REQUEST_ENABLED,
): Boolean = enabled && user.hasPermission(SeerrPermission.MANAGE_REQUESTS) && isRemovable(media)

/**
 * Which versions reached Radarr or Sonarr, as the `is4k` values to remove them with
 *
 * The server's ID is Seerr's own and starts at 0, so only null means it never got there.
 */
fun serviceVersions(media: MediaInfo): List<Boolean> =
    listOfNotNull(
        false.takeIf { media.serviceId != null || media.externalServiceId != null },
        true.takeIf { media.serviceId4k != null || media.externalServiceId4k != null },
    )
