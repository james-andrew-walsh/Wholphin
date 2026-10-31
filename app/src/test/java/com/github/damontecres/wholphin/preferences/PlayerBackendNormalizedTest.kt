package com.github.damontecres.wholphin.preferences

import org.junit.Assert
import org.junit.Test

class PlayerBackendNormalizedTest {
    @Test
    fun `External player falls back to ExoPlayer when disabled`() {
        Assert.assertEquals(
            PlayerBackend.EXO_PLAYER,
            PlayerBackend.EXTERNAL_PLAYER.normalized(externalPlayerEnabled = false),
        )
    }

    @Test
    fun `External player is kept when enabled`() {
        Assert.assertEquals(
            PlayerBackend.EXTERNAL_PLAYER,
            PlayerBackend.EXTERNAL_PLAYER.normalized(externalPlayerEnabled = true),
        )
    }

    @Test
    fun `Other backends are unchanged`() {
        PlayerBackend.entries
            .filterNot { it == PlayerBackend.EXTERNAL_PLAYER }
            .forEach { backend ->
                Assert.assertEquals(backend, backend.normalized(externalPlayerEnabled = false))
                Assert.assertEquals(backend, backend.normalized(externalPlayerEnabled = true))
            }
    }
}
