package com.github.damontecres.wholphin.data.model

import com.github.damontecres.wholphin.api.seerr.model.MediaInfo
import com.github.damontecres.wholphin.api.seerr.model.Season
import com.github.damontecres.wholphin.services.SeerrUserConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoveRequestTest {
    private val manager = SeerrUserConfig(id = 1, permissions = 16)
    private val admin = SeerrUserConfig(id = 2, permissions = 2)
    private val requester = SeerrUserConfig(id = 3, permissions = 32)
    private val requested = MediaInfo(id = 5, status = SeerrAvailability.PROCESSING.status)

    private fun canRemove(
        media: MediaInfo? = requested,
        user: SeerrUserConfig? = manager,
        enabled: Boolean = true,
    ) = canRemoveRequest(user, media, enabled)

    @Test
    fun `Offered for anything requested and not in the library`() {
        listOf(
            SeerrAvailability.UNKNOWN,
            SeerrAvailability.PENDING,
            SeerrAvailability.PROCESSING,
            SeerrAvailability.DELETED,
        ).forEach {
            assertTrue(it.name, canRemove(requested.copy(status = it.status)))
        }
    }

    @Test
    fun `Needs Manage Requests or admin`() {
        assertTrue(canRemove(user = manager))
        assertTrue(canRemove(user = admin))
        assertFalse(canRemove(user = requester))
        assertFalse(canRemove(user = null))
    }

    @Test
    fun `Off unless the flavor has it`() {
        assertFalse(canRemove(enabled = false))
    }

    @Test
    fun `Needs a Seerr record`() {
        assertFalse(canRemove(media = null))
        assertFalse(canRemove(media = MediaInfo(status = SeerrAvailability.PROCESSING.status)))
    }

    @Test
    fun `Never offered for what is in the library`() {
        assertFalse(canRemove(requested.copy(status = SeerrAvailability.AVAILABLE.status)))
        assertFalse(canRemove(requested.copy(status = SeerrAvailability.PARTIALLY_AVAILABLE.status)))
    }

    @Test
    fun `A failed 4K request can't take the normal version with it, or the other way around`() {
        assertFalse(canRemove(requested.copy(status4k = SeerrAvailability.AVAILABLE.status)))
        assertFalse(canRemove(requested.copy(status4k = SeerrAvailability.PARTIALLY_AVAILABLE.status)))
        assertTrue(canRemove(requested.copy(status4k = SeerrAvailability.UNKNOWN.status)))
    }

    @Test
    fun `A series processing a new season still has the seasons already in`() {
        val available = Season(seasonNumber = 1, status = SeerrAvailability.AVAILABLE.status)
        val partial = Season(seasonNumber = 1, status = SeerrAvailability.PARTIALLY_AVAILABLE.status)
        val waiting = Season(seasonNumber = 2, status = SeerrAvailability.PROCESSING.status)

        assertFalse(canRemove(requested.copy(seasons = listOf(available, waiting))))
        assertFalse(canRemove(requested.copy(seasons = listOf(partial, waiting))))
        assertFalse(
            canRemove(
                requested.copy(seasons = listOf(Season(status4k = SeerrAvailability.AVAILABLE.status))),
            ),
        )
        assertTrue(canRemove(requested.copy(seasons = listOf(waiting))))
    }

    @Test
    fun `A blocklisted item can't be requested again`() {
        assertFalse(canRemove(requested.copy(status = SeerrAvailability.BLOCKLISTED.status)))
    }

    @Test
    fun `Only versions which reached Radarr or Sonarr are removed there`() {
        assertEquals(emptyList<Boolean>(), serviceVersions(MediaInfo()))
        assertEquals(listOf(false), serviceVersions(MediaInfo(externalServiceId = 7)))
        assertEquals(listOf(true), serviceVersions(MediaInfo(serviceId4k = 1)))
        assertEquals(listOf(false, true), serviceVersions(MediaInfo(serviceId = 1, externalServiceId4k = 7)))
    }

    @Test
    fun `The first server's ID is 0, which still reached it`() {
        assertEquals(listOf(false), serviceVersions(MediaInfo(serviceId = 0)))
    }
}
