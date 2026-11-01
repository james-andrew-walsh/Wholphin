package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.api.seerr.model.RelatedVideo
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

    private fun youTube(
        id: String,
        name: String = id,
    ) = RemoteTrailer(name, "https://www.youtube.com/watch?v=$id", "YouTube")

    private val unsorted =
        listOf(
            "Behind the Scenes",
            "Teaser Trailer",
            "Official Teaser",
            "B Trailer",
            "Theatrical Trailer",
            "Official Theatrical Trailer",
            "Official Trailer",
            "Clip",
        ).map { RemoteTrailer(it, "url", null) }

    @Test
    fun `All trailers are offered when remote is enabled`() {
        Assert.assertEquals(
            listOf(local, remote),
            TrailerService.offered(listOf(local, remote), remoteTrailersEnabled = true, inAppTrailersEnabled = false),
        )
    }

    @Test
    fun `Only local trailers are offered when remote is disabled`() {
        Assert.assertEquals(
            listOf(local),
            TrailerService.offered(listOf(local, remote), remoteTrailersEnabled = false, inAppTrailersEnabled = false),
        )
        Assert.assertEquals(
            emptyList<Any>(),
            TrailerService.offered(listOf(remote), remoteTrailersEnabled = false, inAppTrailersEnabled = false),
        )
    }

    @Test
    fun `Only playable remote trailers are offered in the app`() {
        val playable = youTube(VIDEO_A)
        val vimeo = RemoteTrailer("Vimeo", "https://vimeo.com/12345", null)
        Assert.assertEquals(
            listOf(local, playable),
            TrailerService.offered(
                listOf(local, remote, playable, vimeo),
                remoteTrailersEnabled = false,
                inAppTrailersEnabled = true,
            ),
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
        TrailerService.onClick(
            mockContext,
            remote,
            { destinations.add(it) },
            remoteTrailersEnabled = false,
            inAppTrailersEnabled = false,
        )
        Assert.assertTrue(destinations.isEmpty())
        // Nothing is stubbed, so this also fails if the context was used to start an activity
        confirmVerified(mockContext)
    }

    @Test
    fun `Local trailer plays in the app when remote is disabled`() {
        val destinations = mutableListOf<Destination>()
        TrailerService.onClick(
            mockContext,
            local,
            { destinations.add(it) },
            remoteTrailersEnabled = false,
            inAppTrailersEnabled = false,
        )
        Assert.assertEquals(
            listOf<Destination>(Destination.Playback(itemId = itemId, positionMs = 0L)),
            destinations,
        )
    }

    @Test
    fun `Remote trailer plays in the app from the clicked one onward`() {
        val first = youTube(VIDEO_A, "First")
        val second = youTube(VIDEO_B, "Second")
        val third = youTube(VIDEO_C, "Third")
        val destinations = mutableListOf<Destination>()
        TrailerService.onClick(
            mockContext,
            second,
            { destinations.add(it) },
            trailers = listOf(local, first, second, third),
            remoteTrailersEnabled = false,
            inAppTrailersEnabled = true,
        )
        Assert.assertEquals(
            listOf<Destination>(Destination.TrailerPlayer(listOf(VIDEO_B, VIDEO_C), "Second")),
            destinations,
        )
        confirmVerified(mockContext)
    }

    @Test
    fun `In app beats opening another app`() {
        val destinations = mutableListOf<Destination>()
        TrailerService.onClick(
            mockContext,
            youTube(VIDEO_A),
            { destinations.add(it) },
            remoteTrailersEnabled = true,
            inAppTrailersEnabled = true,
        )
        Assert.assertEquals(
            listOf<Destination>(Destination.TrailerPlayer(listOf(VIDEO_A), VIDEO_A)),
            destinations,
        )
        confirmVerified(mockContext)
    }

    @Test
    fun `Queue has no repeats and is capped`() {
        val trailers = List(15) { youTube(it.toString().padStart(11, 'x')) }
        val withRepeat = listOf(trailers[0], trailers[0].copy(name = "Same video")) + trailers.drop(1)
        var destination: Destination? = null
        TrailerService.onClick(
            mockContext,
            trailers[0],
            { destination = it },
            trailers = withRepeat,
            remoteTrailersEnabled = false,
            inAppTrailersEnabled = true,
        )
        val videoIds = (destination as Destination.TrailerPlayer).videoIds
        Assert.assertEquals(10, videoIds.size)
        Assert.assertEquals(videoIds.distinct(), videoIds)
        Assert.assertEquals("xxxxxxxxxx0", videoIds.first())
    }

    @Test
    fun `Unplayable remote trailer goes nowhere in the app`() {
        val destinations = mutableListOf<Destination>()
        TrailerService.onClick(
            mockContext,
            remote,
            { destinations.add(it) },
            remoteTrailersEnabled = false,
            inAppTrailersEnabled = true,
        )
        Assert.assertTrue(destinations.isEmpty())
        confirmVerified(mockContext)
    }

    @Test
    fun `YouTube video IDs are found`() {
        listOf(
            "https://www.youtube.com/watch?v=$VIDEO_A",
            "https://www.youtube.com/watch?v=$VIDEO_A/",
            "https://www.youtube.com/watch?t=10&v=$VIDEO_A&list=abc",
            "http://www.youtube.com/watch?v=$VIDEO_A",
            "https://WWW.YOUTUBE.COM/watch?v=$VIDEO_A",
            "https://youtube.com/watch?v=$VIDEO_A",
            "https://m.youtube.com/watch?v=$VIDEO_A",
            "https://www.youtube.com/embed/$VIDEO_A",
            "https://www.youtube.com/embed/$VIDEO_A/",
            "https://www.youtube.com/embed/$VIDEO_A?autoplay=1",
            "https://www.youtube.com/shorts/$VIDEO_A",
            "https://www.youtube.com/v/$VIDEO_A",
            "https://youtu.be/$VIDEO_A",
            "https://youtu.be/$VIDEO_A/",
            "https://youtu.be/$VIDEO_A?t=5",
            "https://www.youtube-nocookie.com/embed/$VIDEO_A",
        ).forEach {
            Assert.assertEquals(it, VIDEO_A, TrailerService.youTubeVideoId(it))
        }
    }

    @Test
    fun `Anything else has no YouTube video ID`() {
        listOf(
            "",
            "not a url",
            "https://www.youtube.com/watch?v=short",
            "https://www.youtube.com/watch?v=${VIDEO_A}x",
            "https://www.youtube.com/watch?v=abc%22def_gh",
            "https://www.youtube.com/watch",
            "https://www.youtube.com/$VIDEO_A",
            "https://www.youtube.com/playlist?list=$VIDEO_A",
            "https://www.youtube.com/embed/",
            "https://www.youtube.com/embed/$VIDEO_A/more",
            "https://music.youtube.com/watch?v=$VIDEO_A",
            "https://www.youtube.com.example.com/watch?v=$VIDEO_A",
            "https://evil.com/watch?v=$VIDEO_A",
            "https://vimeo.com/$VIDEO_A",
            "https://youtu.be/",
            "https://youtu.be/embed/$VIDEO_A",
            "ftp://www.youtube.com/watch?v=$VIDEO_A",
            "vnd.youtube:$VIDEO_A",
            "intent://www.youtube.com/watch?v=$VIDEO_A",
        ).forEach {
            Assert.assertNull(it, TrailerService.youTubeVideoId(it))
        }
    }

    @Test
    fun `Best trailers sort first`() {
        Assert.assertEquals(
            listOf(
                "Official Theatrical Trailer",
                "Official Trailer",
                "B Trailer",
                "Theatrical Trailer",
                "Official Teaser",
                "Teaser Trailer",
                "Behind the Scenes",
                "Clip",
            ),
            TrailerService.bestFirst(unsorted, inAppTrailersEnabled = true).map { it.name },
        )
    }

    @Test
    fun `Teasers stay last without the app playing trailers`() {
        Assert.assertEquals(
            listOf(
                "Official Theatrical Trailer",
                "Official Trailer",
                "B Trailer",
                "Theatrical Trailer",
                "Behind the Scenes",
                "Clip",
                "Official Teaser",
                "Teaser Trailer",
            ),
            TrailerService.bestFirst(unsorted, inAppTrailersEnabled = false).map { it.name },
        )
    }

    private fun video(
        name: String?,
        type: RelatedVideo.Type,
        url: String? = "https://www.youtube.com/watch?v=$VIDEO_A",
    ) = RelatedVideo(name = name, type = type, url = url, site = "YouTube")

    @Test
    fun `Seerr videos in the app include teasers with the best first`() {
        val videos =
            listOf(
                video("A Teaser", RelatedVideo.Type.TEASER),
                video("Clip", RelatedVideo.Type.CLIP),
                video("Official Trailer", RelatedVideo.Type.TRAILER),
                video(null, RelatedVideo.Type.TRAILER),
                video("No URL", RelatedVideo.Type.TRAILER, url = null),
            )
        Assert.assertEquals(
            listOf("Official Trailer", "A Teaser"),
            TrailerService
                .fromRelatedVideos(videos, remoteTrailersEnabled = false, inAppTrailersEnabled = true)
                .map { it.name },
        )
    }

    @Test
    fun `Seerr videos are unchanged without the app playing them`() {
        val videos =
            listOf(
                video("Z Trailer", RelatedVideo.Type.TRAILER),
                video("A Teaser", RelatedVideo.Type.TEASER),
                video("Official Trailer", RelatedVideo.Type.TRAILER),
            )
        Assert.assertEquals(
            listOf("Z Trailer", "Official Trailer"),
            TrailerService
                .fromRelatedVideos(videos, remoteTrailersEnabled = true, inAppTrailersEnabled = false)
                .map { it.name },
        )
        Assert.assertEquals(
            emptyList<Any>(),
            TrailerService.fromRelatedVideos(videos, remoteTrailersEnabled = false, inAppTrailersEnabled = false),
        )
    }

    @Test
    fun `Trailer player destinations have distinct keys`() {
        val one = Destination.TrailerPlayer(listOf(VIDEO_A, VIDEO_B), "Title")
        Assert.assertEquals(one.toString(), one.copy().toString())
        Assert.assertNotEquals(one.toString(), one.copy(videoIds = listOf(VIDEO_A, VIDEO_C)).toString())
        Assert.assertNotEquals(one.toString(), one.copy(title = "Other").toString())
    }

    private companion object {
        const val VIDEO_A = "dQw4w9WgXcQ"
        const val VIDEO_B = "aaaaaaaa_-1"
        const val VIDEO_C = "bbbbbbbb_-2"
    }
}
