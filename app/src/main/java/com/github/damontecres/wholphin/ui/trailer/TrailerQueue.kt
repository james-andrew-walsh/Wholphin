package com.github.damontecres.wholphin.ui.trailer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// https://developers.google.com/youtube/iframe_api_reference#Events
private const val STATE_ENDED = 0
private const val STATE_PLAYING = 1
private const val STATE_PAUSED = 2
private const val STATE_BUFFERING = 3
private const val ERROR_HTML5 = 5
private const val ERROR_APP_IDENTITY = 153

/**
 * What the page in the WebView reports about its player
 */
sealed interface TrailerEvent {
    /**
     * Whether the player is up or moving, so it's a point where autoplay can make a sound
     */
    val startsPlayback: Boolean get() = this is Ready || (this is StateChanged && isPlaying)

    data object Ready : TrailerEvent

    data class StateChanged(
        val state: Int,
    ) : TrailerEvent {
        val isPlaying: Boolean get() = state == STATE_PLAYING || state == STATE_BUFFERING
    }

    data class Error(
        val code: Int,
    ) : TrailerEvent

    data object AutoplayBlocked : TrailerEvent

    companion object {
        /**
         * Parses a [message] posted by the page, null if it isn't understood
         */
        fun parse(message: String?): TrailerEvent? {
            if (message == null) return null
            return try {
                val json = Json.parseToJsonElement(message).jsonObject
                val value = json["v"]?.jsonPrimitive?.intOrNull
                when (json["t"]?.jsonPrimitive?.content) {
                    "ready" -> Ready
                    "state" -> value?.let(::StateChanged)
                    "error" -> value?.let(::Error)
                    "blocked" -> AutoplayBlocked
                    else -> null
                }
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

enum class TrailerFailure {
    UNAVAILABLE,
    APP_IDENTITY,
    UNSUPPORTED,
}

/**
 * What to do next in response to something the [TrailerQueue] was told
 */
sealed interface TrailerAction {
    data object None : TrailerAction

    /**
     * Load the video, which may be the same one to try it again
     */
    data class Load(
        val videoId: String,
    ) : TrailerAction

    data object Close : TrailerAction

    data class Fail(
        val failure: TrailerFailure,
    ) : TrailerAction
}

/**
 * Decides what happens as the trailers in [videoIds] play or fail
 *
 * This doesn't depend on the WebView so that all of the fallback rules can be tested. The first
 * [TrailerAction.Close] or [TrailerAction.Fail] is final, anything arriving later is ignored.
 */
class TrailerQueue(
    private val videoIds: List<String>,
) {
    private var index = 0
    private var retried = false
    private var started = false
    private var finished = false

    fun start(): TrailerAction = loadCurrent()

    fun onEvent(event: TrailerEvent): TrailerAction =
        when {
            finished -> TrailerAction.None
            event is TrailerEvent.StateChanged -> onStateChanged(event.state)
            event is TrailerEvent.Error -> onError(event.code)
            else -> TrailerAction.None
        }

    /**
     * Call when the time for the current video to start has passed
     */
    fun onTimeout(): TrailerAction = if (finished || started) TrailerAction.None else next()

    private fun onStateChanged(state: Int): TrailerAction {
        when (state) {
            STATE_PLAYING, STATE_PAUSED, STATE_BUFFERING -> {
                started = true
            }

            STATE_ENDED -> {
                finished = true
                return TrailerAction.Close
            }
        }
        return TrailerAction.None
    }

    private fun onError(code: Int): TrailerAction =
        when {
            code == ERROR_APP_IDENTITY -> {
                // The other videos would fail in the same way, this isn't a problem with this one
                fail(TrailerFailure.APP_IDENTITY)
            }

            code == ERROR_HTML5 && !retried -> {
                retried = true
                loadCurrent()
            }

            else -> {
                next()
            }
        }

    private fun next(): TrailerAction {
        index++
        retried = false
        return loadCurrent()
    }

    private fun loadCurrent(): TrailerAction {
        started = false
        return videoIds.getOrNull(index)?.let(TrailerAction::Load) ?: fail(TrailerFailure.UNAVAILABLE)
    }

    private fun fail(failure: TrailerFailure): TrailerAction {
        finished = true
        return TrailerAction.Fail(failure)
    }
}
