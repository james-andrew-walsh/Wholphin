package com.github.damontecres.wholphin.preferences

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.ui.ifElse
import com.github.damontecres.wholphin.ui.playback.isDown
import com.github.damontecres.wholphin.ui.playback.isUp
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

val PlaybackPreferences.skipBackOnResume: Duration?
    get() =
        if (skipBackOnResumeSeconds > 0) {
            skipBackOnResumeSeconds.milliseconds
        } else {
            null
        }

/**
 * The backend to actually use: [PlayerBackend.EXTERNAL_PLAYER], [PlayerBackend.MPV] and
 * [PlayerBackend.PREFER_MPV] fall back to [PlayerBackend.EXO_PLAYER] when they are disabled, even if
 * they were stored or requested
 */
fun PlayerBackend.normalized(
    externalPlayerEnabled: Boolean = BuildConfig.EXTERNAL_PLAYER_ENABLED,
    mpvEnabled: Boolean = BuildConfig.MPV_ENABLED,
): PlayerBackend =
    when {
        this == PlayerBackend.EXTERNAL_PLAYER && !externalPlayerEnabled -> PlayerBackend.EXO_PLAYER
        (this == PlayerBackend.MPV || this == PlayerBackend.PREFER_MPV) && !mpvEnabled -> PlayerBackend.EXO_PLAYER
        else -> this
    }

/**
 * Whether to use AV1 software decoding, which needs the extension to be part of the build
 */
fun PlaybackOverrides.av1DecodingEnabled(av1DecoderEnabled: Boolean = BuildConfig.AV1_DECODER_ENABLED): Boolean =
    av1DecoderEnabled && decodeAv1

/**
 * Adds modifiers so that pressing Up on the first element scrolls to the bottom and vice versa
 */
@Composable
fun Modifier.lazyListWrapScrolling(
    state: LazyListState,
    focused: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    firstFocusRequester: FocusRequester,
    lastFocusRequester: FocusRequester,
): Modifier {
    val scope = rememberCoroutineScope()
    return this
        .ifElse(
            isFirst,
            Modifier
                .focusRequester(firstFocusRequester)
                .onKeyEvent {
                    if (focused && isUp(it) && it.type == KeyEventType.KeyDown) {
                        scope.launch {
                            state.animateScrollToItem(state.layoutInfo.totalItemsCount - 1)
                            lastFocusRequester.tryRequestFocus()
                        }
                        return@onKeyEvent true
                    }
                    false
                },
        ).ifElse(
            isLast,
            Modifier
                .focusRequester(lastFocusRequester)
                .onKeyEvent {
                    if (focused && isDown(it) && it.type == KeyEventType.KeyDown) {
                        scope.launch {
                            state.animateScrollToItem(0)
                            firstFocusRequester.tryRequestFocus()
                        }
                        return@onKeyEvent true
                    }
                    false
                },
        )
}
