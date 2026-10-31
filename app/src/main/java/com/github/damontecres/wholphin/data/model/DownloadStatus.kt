package com.github.damontecres.wholphin.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val hashRegex = Regex("""[0-9a-fA-F]{40}|[0-9a-fA-F]{64}""")

/**
 * Whether Seerr's download ID is a torrent hash, which is all the status service can look up
 */
fun isDownloadHash(downloadId: String): Boolean = hashRegex.matches(downloadId)

/**
 * What the status service knows about a download
 *
 * Everything has a default so that a missing field shows as missing instead of failing the page.
 * Sizes are in bytes, speeds are in bytes per second and times are Unix seconds on the server's clock.
 *
 * @param state the raw qBittorrent state, it is free text because new versions can add more
 * @param stateLabel a plain label for [state]
 * @param size the size of the selected files
 */
@Serializable
data class DownloadStatus(
    val state: String? = null,
    @SerialName("state_label") val stateLabel: String? = null,
    val progress: Double? = null,
    val size: Long? = null,
    val downloaded: Long? = null,
    val uploaded: Long? = null,
    val ratio: Double? = null,
    @SerialName("dlspeed") val downloadSpeed: Long? = null,
    @SerialName("upspeed") val uploadSpeed: Long? = null,
    @SerialName("eta_seconds") val etaSeconds: Long? = null,
    @SerialName("seeds_connected") val seedsConnected: Int? = null,
    @SerialName("seeds_swarm") val seedsSwarm: Int? = null,
    @SerialName("leeches_connected") val leechesConnected: Int? = null,
    @SerialName("leeches_swarm") val leechesSwarm: Int? = null,
    val availability: Double? = null,
    val connections: Int? = null,
    val trackers: TrackerSummary? = null,
    @SerialName("peer_clients") val peerClients: List<PeerClientCount> = emptyList(),
    @SerialName("file_count") val fileCount: Int? = null,
    val radarr: RadarrStatus = RadarrStatus(),
    val history: BandwidthHistory = BandwidthHistory(),
    @SerialName("fetched_at") val fetchedAt: Long? = null,
)

/**
 * @param message why a tracker isn't working, already cleaned up by the server
 */
@Serializable
data class TrackerSummary(
    val total: Int? = null,
    val working: Int? = null,
    val message: String? = null,
)

@Serializable
data class PeerClientCount(
    val client: String? = null,
    val count: Int? = null,
)

/**
 * What Radarr's queue says about the download
 *
 * Only [available] is always sent, the rest are absent when they don't apply.
 *
 * @param available false if Radarr can't be reached, which says nothing about the download
 * @param tracked whether Radarr's queue has the download
 */
@Serializable
data class RadarrStatus(
    val available: Boolean = false,
    val tracked: Boolean? = null,
    val status: String? = null,
    @SerialName("tracked_state") val trackedState: String? = null,
    @SerialName("tracked_status") val trackedStatus: String? = null,
    val messages: List<String> = emptyList(),
)

/**
 * Samples of the download and upload speeds, oldest first
 *
 * A null sample means none was taken, which is a gap and not a speed of zero. A restart of the
 * service loses all of this, so being empty is normal.
 *
 * @param intervalSeconds how far apart the samples are
 * @param lastSampleAt when the last sample in each list was taken
 */
@Serializable
data class BandwidthHistory(
    @SerialName("interval_seconds") val intervalSeconds: Int? = null,
    @SerialName("last_sample_at") val lastSampleAt: Long? = null,
    val dl: List<Long?> = emptyList(),
    val ul: List<Long?> = emptyList(),
)

/**
 * How much is downloaded, or null if the server's value can't be used
 */
val DownloadStatus.fraction: Float?
    get() = progress?.takeIf { it in 0.0..1.0 }?.toFloat()

/**
 * Whether the download is done and has left Radarr's queue
 *
 * Radarr's queue starts empty after a restart, so being unknown to it only counts once the download
 * is complete. An unfinished one is still downloading whatever Radarr says.
 */
val DownloadStatus.leftQueue: Boolean
    get() = radarr.available && radarr.tracked == false && progress?.let { it >= 1.0 } == true
