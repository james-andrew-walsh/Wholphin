package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.QueueAckResponse
import com.github.damontecres.wholphin.data.model.QueueNotices
import com.github.damontecres.wholphin.data.model.QueueRemovals
import com.github.damontecres.wholphin.data.model.QueueRemoveResponse
import com.github.damontecres.wholphin.data.model.QueueStatus
import com.github.damontecres.wholphin.data.model.UnavailableResponse
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// Beside the status service, with the same rules for where it can be
private const val QUEUE_PORT = 8098

private const val HTTP_OK = 200
private const val HTTP_NOT_FOUND = 404
private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * What came back from the queue service
 */
sealed interface QueueResult<out T> {
    data class Success<T>(
        val value: T,
    ) : QueueResult<T>

    /**
     * @param error the service's error code, null if it couldn't be reached or didn't say
     */
    data class Failure(
        val error: String? = null,
    ) : QueueResult<Nothing>
}

/**
 * The address of a path on the queue service, see [lanServiceUrl]
 */
internal fun queueServiceUrl(
    seerrUrl: String?,
    path: String,
): HttpUrl? = lanServiceUrl(seerrUrl, QUEUE_PORT)?.addPathSegments(path)?.build()

/**
 * Normalize a response of the queue service, only a 200 with a body that reads is a success
 */
internal inline fun <reified T> parseQueueResponse(
    code: Int,
    body: String,
): QueueResult<T> =
    if (code == HTTP_OK) {
        try {
            QueueResult.Success(serviceJson.decodeFromString<T>(body))
        } catch (_: SerializationException) {
            QueueResult.Failure()
        } catch (_: IllegalArgumentException) {
            QueueResult.Failure()
        }
    } else {
        QueueResult.Failure(serviceErrorCode(body))
    }

/**
 * Normalize the response to acknowledging a notice
 *
 * A notice the service doesn't have is as good as acknowledged, it is gone either way.
 *
 * @return whether the notice is gone
 */
internal fun parseAckResponse(
    code: Int,
    body: String,
): QueueResult<Boolean> =
    if (code == HTTP_NOT_FOUND) {
        QueueResult.Success(true)
    } else {
        when (val result = parseQueueResponse<QueueAckResponse>(code, body)) {
            is QueueResult.Success -> QueueResult.Success(result.value.acknowledged)
            is QueueResult.Failure -> result
        }
    }

internal fun removeRequestBody(key: String): RequestBody =
    Json
        .encodeToString(JsonObject.serializer(), JsonObject(mapOf("key" to JsonPrimitive(key))))
        .toRequestBody(JSON_MEDIA_TYPE)

/**
 * Client for the queue service, which has every download of every user and can remove them
 *
 * The service is optional, so everything that can go wrong comes back as a result.
 */
@Singleton
class QueueService
    @Inject
    constructor(
        @StandardOkHttpClient okHttpClient: OkHttpClient,
        private val seerrServerRepository: SeerrServerRepository,
        private val serverRepository: ServerRepository,
    ) {
        private val client = lanServiceClient(okHttpClient)

        private val _removals = MutableStateFlow(0)

        /**
         * Counts the removals made from the app, so that pages showing what was removed know they
         * are out of date
         */
        val removals: StateFlow<Int> = _removals

        suspend fun queue(): QueueResult<QueueStatus> =
            call("v1/queue", null) { code, body -> parseQueueResponse(code, body) }

        /**
         * Remove what a row is for, the row's download and its request
         */
        suspend fun remove(key: String): QueueResult<QueueRemoveResponse> {
            val result =
                call("v1/queue/remove", removeRequestBody(key)) { code, body ->
                    parseQueueResponse<QueueRemoveResponse>(code, body)
                }
            if (result is QueueResult.Success) _removals.update { it + 1 }
            return result
        }

        /**
         * The current user's notices which haven't been acknowledged
         */
        suspend fun notices(): QueueResult<QueueNotices> =
            call("v1/notices", null) { code, body -> parseQueueResponse(code, body) }

        suspend fun acknowledge(id: Int): QueueResult<Boolean> =
            call("v1/notices/$id/ack", ByteArray(0).toRequestBody()) { code, body -> parseAckResponse(code, body) }

        suspend fun unavailable(): QueueResult<UnavailableResponse> =
            call("v1/unavailable", null) { code, body -> parseQueueResponse(code, body) }

        /**
         * Everything removed from the queue so far, by anyone, newest first
         */
        suspend fun removalHistory(): QueueResult<QueueRemovals> =
            call("v1/removals", null) { code, body -> parseQueueResponse(code, body) }

        /**
         * @param post the body to POST, or null to GET
         */
        private suspend fun <T> call(
            path: String,
            post: RequestBody?,
            parse: (Int, String) -> QueueResult<T>,
        ): QueueResult<T> {
            val token = serverRepository.currentUser?.accessToken ?: return QueueResult.Failure()
            val url =
                queueServiceUrl(seerrServerRepository.currentServer.first()?.url, path)
                    ?: return QueueResult.Failure()
            return try {
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header(TOKEN_HEADER, token)
                        .apply { if (post != null) post(post) else get() }
                        .build()
                // Interruptible so that leaving the page doesn't leave a call running
                runInterruptible(WholphinDispatchers.IO) {
                    client.newCall(request).execute().use {
                        parse(it.code, it.body.string())
                    }
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                // Only the type: OkHttp quotes a header value it refuses, which here is the token
                Timber.w("Queue service failed: %s", ex.javaClass.simpleName)
                QueueResult.Failure()
            }
        }
    }
