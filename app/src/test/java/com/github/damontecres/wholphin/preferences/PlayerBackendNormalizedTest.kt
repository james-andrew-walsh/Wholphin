package com.github.damontecres.wholphin.preferences

import org.junit.Assert
import org.junit.Test

class PlayerBackendNormalizedTest {
    @Test
    fun `External player falls back to ExoPlayer when disabled`() {
        Assert.assertEquals(
            PlayerBackend.EXO_PLAYER,
            PlayerBackend.EXTERNAL_PLAYER.normalized(externalPlayerEnabled = false, mpvEnabled = true),
        )
    }

    @Test
    fun `External player is kept when enabled`() {
        Assert.assertEquals(
            PlayerBackend.EXTERNAL_PLAYER,
            PlayerBackend.EXTERNAL_PLAYER.normalized(externalPlayerEnabled = true, mpvEnabled = false),
        )
    }

    @Test
    fun `MPV backends fall back to ExoPlayer when disabled`() {
        listOf(PlayerBackend.MPV, PlayerBackend.PREFER_MPV).forEach { backend ->
            Assert.assertEquals(
                PlayerBackend.EXO_PLAYER,
                backend.normalized(externalPlayerEnabled = true, mpvEnabled = false),
            )
        }
    }

    @Test
    fun `MPV backends are kept when enabled`() {
        listOf(PlayerBackend.MPV, PlayerBackend.PREFER_MPV).forEach { backend ->
            Assert.assertEquals(backend, backend.normalized(externalPlayerEnabled = false, mpvEnabled = true))
        }
    }

    @Test
    fun `Other backends are unchanged`() {
        listOf(PlayerBackend.EXO_PLAYER, PlayerBackend.UNRECOGNIZED).forEach { backend ->
            Assert.assertEquals(backend, backend.normalized(externalPlayerEnabled = false, mpvEnabled = false))
            Assert.assertEquals(backend, backend.normalized(externalPlayerEnabled = true, mpvEnabled = true))
        }
    }

    @Test
    fun `AV1 decoding needs the preference and the extension`() {
        val enabled = PlaybackOverrides.newBuilder().setDecodeAv1(true).build()
        val disabled = PlaybackOverrides.newBuilder().setDecodeAv1(false).build()
        Assert.assertTrue(enabled.av1DecodingEnabled(av1DecoderEnabled = true))
        Assert.assertFalse(enabled.av1DecodingEnabled(av1DecoderEnabled = false))
        Assert.assertFalse(disabled.av1DecodingEnabled(av1DecoderEnabled = true))
    }
}
