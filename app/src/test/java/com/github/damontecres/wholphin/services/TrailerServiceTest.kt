package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.LocalTrailer
import com.github.damontecres.wholphin.data.model.RemoteTrailer
import com.github.damontecres.wholphin.ui.nav.Destination
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.sdk.model.UUID
import org.junit.Assert
import org.junit.Test

class TrailerServiceTest {
    private val itemId = UUID.randomUUID()
    private val mockItem = mockk<BaseItem> { every { id } returns itemId }
    private val mockContext = mockk<Context>()

    private val local = LocalTrailer(mockItem)
    private val remote = RemoteTrailer("Trailer", "https://www.youtube.com/watch?v=abc", "YouTube")

    @Test
    fun `All trailers are offered when remote is enabled`() {
        Assert.assertEquals(
            listOf(local, remote),
            TrailerService.offered(listOf(local, remote), remoteTrailersEnabled = true),
        )
    }

    @Test
    fun `Only local trailers are offered when remote is disabled`() {
        Assert.assertEquals(
            listOf(local),
            TrailerService.offered(listOf(local, remote), remoteTrailersEnabled = false),
        )
        Assert.assertEquals(
            emptyList<Any>(),
            TrailerService.offered(listOf(remote), remoteTrailersEnabled = false),
        )
    }

    @Test
    fun `Button stays when remote is enabled`() {
        Assert.assertTrue(TrailerService.showButton(emptyList(), remoteTrailersEnabled = true))
        Assert.assertTrue(TrailerService.showButton(listOf(remote), remoteTrailersEnabled = true))
    }

    @Test
    fun `Button only shows with trailers when remote is disabled`() {
        Assert.assertFalse(TrailerService.showButton(emptyList(), remoteTrailersEnabled = false))
        Assert.assertTrue(TrailerService.showButton(listOf(local), remoteTrailersEnabled = false))
    }

    @Test
    fun `Remote trailer does not launch when disabled`() {
        val destinations = mutableListOf<Destination>()
        TrailerService.onClick(mockContext, remote, { destinations.add(it) }, remoteTrailersEnabled = false)
        Assert.assertTrue(destinations.isEmpty())
        // Nothing is stubbed, so this also fails if the context was used to start an activity
        confirmVerified(mockContext)
    }

    @Test
    fun `Local trailer plays in the app when remote is disabled`() {
        val destinations = mutableListOf<Destination>()
        TrailerService.onClick(mockContext, local, { destinations.add(it) }, remoteTrailersEnabled = false)
        Assert.assertEquals(
            listOf<Destination>(Destination.Playback(itemId = itemId, positionMs = 0L)),
            destinations,
        )
    }
}
