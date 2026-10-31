package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.DownloadStatus
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

// The service runs beside Seerr, on its host. Plain HTTP on the LAN, which the app's network
// security config allows the same as for Seerr
private const val STATUS_PORT = 8097
private const val TOKEN_HEADER = "X-Emby-Token"
private const val HTTP_OK = 200
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_NOT_FOUND = 404
private const val HTTP_UNAVAILABLE = 503
private const val ERROR_AUTH_UNAVAILABLE = "auth_unavailable"
private const val ERROR_UNKNOWN_HASH = "unknown_hash"

private val json =
    Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

@Serializable
private data class StatusError(
    val error: String? = null,
)

enum class DownloadStatusError {
    /** The token was rejected, signing out is up to Jellyfin and not this optional service */
    SESSION_REFUSED,

    /** The service couldn't reach Jellyfin to check the token, so this says nothing about it */
    AUTH_UNAVAILABLE,

    QBITTORRENT_UNAVAILABLE,

    SERVICE_UNAVAILABLE,
}

sealed interface DownloadStatusResult {
    data class Success(
        val status: DownloadStatus,
    ) : DownloadStatusResult

    /** qBittorrent no longer has the download, which is normal after Radarr removes a finished one */
    data object Gone : DownloadStatusResult

    data class Failure(
        val error: DownloadStatusError,
    ) : DownloadStatusResult
}

/**
 * The address of a download on the status service
 *
 * @param seerrUrl the configured Seerr server, whose host the service is on
 * @return the URL or null if there is no usable Seerr server
 */
internal fun downloadStatusUrl(
    seerrUrl: String?,
    hash: String,
): HttpUrl? {
    val host = seerrUrl?.toHttpUrlOrNull()?.host ?: return null
    return HttpUrl
        .Builder()
        .scheme("http")
        .host(host)
        .port(STATUS_PORT)
        .addPathSegments("v1/downloads")
        .addPathSegment(hash)
        .build()
}

/**
 * Normalize the status service's response
 *
 * Only `unknown_hash` means the download is gone. Any other 404 is a wrong route or something else
 * on the port.
 */
internal fun parseDownloadStatusResponse(
    code: Int,
    body: String,
): DownloadStatusResult =
    when (code) {
        HTTP_OK -> {
            try {
                DownloadStatusResult.Success(json.decodeFromString<DownloadStatus>(body))
            } catch (_: SerializationException) {
                DownloadStatusResult.Failure(DownloadStatusError.SERVICE_UNAVAILABLE)
            }
        }

        HTTP_UNAUTHORIZED -> {
            DownloadStatusResult.Failure(
                if (errorCode(body) == ERROR_AUTH_UNAVAILABLE) {
                    DownloadStatusError.AUTH_UNAVAILABLE
                } else {
                    DownloadStatusError.SESSION_REFUSED
                },
            )
        }

        HTTP_NOT_FOUND -> {
            if (errorCode(body) == ERROR_UNKNOWN_HASH) {
                DownloadStatusResult.Gone
            } else {
                DownloadStatusResult.Failure(DownloadStatusError.SERVICE_UNAVAILABLE)
            }
        }

        HTTP_UNAVAILABLE -> {
            DownloadStatusResult.Failure(DownloadStatusError.QBITTORRENT_UNAVAILABLE)
        }

        else -> {
            DownloadStatusResult.Failure(DownloadStatusError.SERVICE_UNAVAILABLE)
        }
    }

private fun errorCode(body: String): String? =
    try {
        json.decodeFromString<StatusError>(body).error
    } catch (_: SerializationException) {
        null
    }

/**
 * Read-only client for the status service, which has the live details of a download
 *
 * The service is optional, so everything that can go wrong comes back as a result.
 */
@Singleton
class DownloadStatusService
    @Inject
    constructor(
        @StandardOkHttpClient okHttpClient: OkHttpClient,
        private val seerrServerRepository: SeerrServerRepository,
        private val serverRepository: ServerRepository,
    ) {
        // A redirect would carry the token along, it is only meant for the service
        private val client =
            okHttpClient
                .newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(3.seconds)
                .callTimeout(8.seconds)
                .build()

        suspend fun fetch(hash: String): DownloadStatusResult {
            val token =
                serverRepository.currentUser?.accessToken
                    ?: return DownloadStatusResult.Failure(DownloadStatusError.SESSION_REFUSED)
            val url =
                downloadStatusUrl(seerrServerRepository.currentServer.first()?.url, hash)
                    ?: return DownloadStatusResult.Failure(DownloadStatusError.SERVICE_UNAVAILABLE)
            return try {
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header(TOKEN_HEADER, token)
                        .get()
                        .build()
                // Interruptible so that leaving the page doesn't leave a call running
                runInterruptible(WholphinDispatchers.IO) {
                    client.newCall(request).execute().use {
                        parseDownloadStatusResponse(it.code, it.body.string())
                    }
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                // Only the type: OkHttp quotes a header value it refuses, which here is the token
                Timber.w("Download status failed: %s", ex.javaClass.simpleName)
                DownloadStatusResult.Failure(DownloadStatusError.SERVICE_UNAVAILABLE)
            }
        }
    }
