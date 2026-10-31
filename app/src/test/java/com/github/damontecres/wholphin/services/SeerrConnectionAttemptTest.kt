package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.SeerrAuthMethod
import com.github.damontecres.wholphin.data.model.SeerrServer
import com.github.damontecres.wholphin.data.model.SeerrUser
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SeerrConnectionAttemptTest {
    private val repository =
        SeerrServerRepository(
            seerrApi = mockk(relaxed = true),
            seerrServerDao = mockk(relaxed = true),
            serverRepository = mockk(relaxed = true),
            okHttpClient = mockk(relaxed = true),
        )
    private val server = SeerrServer(url = "http://192.168.1.2:5055")
    private val user =
        SeerrUser(
            jellyfinUserRowId = 1,
            serverId = server.id,
            authMethod = SeerrAuthMethod.JELLYFIN,
            username = "user",
            password = "",
            credential = null,
        )

    @Test
    fun `a failure from the latest attempt is recorded`() {
        val attempt = repository.newConnectionAttempt()
        repository.error(server, user, IOException("offline"), attempt)
        assertTrue(repository.connection.value is SeerrConnectionStatus.Error)
    }

    @Test
    fun `repeating a failure keeps the same state, so nothing emits`() {
        val attempt = repository.newConnectionAttempt()
        repository.error(server, user, IOException("first"), attempt)
        val first = repository.connection.value
        repository.error(server, user, IOException("second"), attempt)
        assertSame(first, repository.connection.value)
    }

    @Test
    fun `removing the server overtakes an attempt in flight`() {
        val attempt = repository.newConnectionAttempt()
        repository.error(server, user, IOException("offline"), attempt)
        repository.clear()

        assertFalse(repository.isLatestAttempt(attempt))
        repository.error(server, user, IOException("late"), attempt)
        assertEquals(SeerrConnectionStatus.NotConfigured, repository.connection.value)
    }

    @Test
    fun `a late success can't bring a removed server back`() =
        runTest {
            val attempt = repository.newConnectionAttempt()
            repository.clear()
            repository.setIfLatest(server, user, mockk(relaxed = true), attempt)
            assertEquals(SeerrConnectionStatus.NotConfigured, repository.connection.value)
        }
}
