package com.github.damontecres.wholphin.ui.components

import android.content.res.Resources
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.PlayerBackend
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert
import org.junit.Before
import org.junit.Test

class BuildPlayWithTest {
    private val mockResources = mockk<Resources>()

    @Before
    fun setup() {
        every { mockResources.getStringArray(R.array.player_backend_options) } returns
            arrayOf("ExoPlayer", "MPV", "Prefer MPV", "External Player")
        every { mockResources.getString(R.string.transcoding) } returns "Transcoding"
    }

    /**
     * Click on every item to find out what it would start
     */
    private fun clickAll(externalPlayerEnabled: Boolean): List<Pair<Boolean, PlayerBackend?>> {
        val clicked = mutableListOf<Pair<Boolean, PlayerBackend?>>()
        buildPlayWith(mockResources, externalPlayerEnabled) { transcode, backend ->
            clicked.add(transcode to backend)
        }.forEach { it.onClick.invoke() }
        return clicked
    }

    @Test
    fun `External player is offered when enabled`() {
        Assert.assertEquals(
            listOf(
                false to PlayerBackend.EXO_PLAYER,
                false to PlayerBackend.MPV,
                false to PlayerBackend.EXTERNAL_PLAYER,
                true to null,
            ),
            clickAll(externalPlayerEnabled = true),
        )
    }

    @Test
    fun `External player is omitted when disabled`() {
        Assert.assertEquals(
            listOf(
                false to PlayerBackend.EXO_PLAYER,
                false to PlayerBackend.MPV,
                true to null,
            ),
            clickAll(externalPlayerEnabled = false),
        )
    }
}
