package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.api.seerr.infrastructure.ClientException
import com.github.damontecres.wholphin.api.seerr.infrastructure.ServerException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SeerrRetryTest {
    @Test
    fun `delay doubles up to a minute`() {
        assertEquals(
            listOf(5, 10, 20, 40, 60, 60).map { it.seconds },
            (0..5).map { seerrRetryDelay(it) },
        )
    }

    @Test
    fun `delay stays at a minute for very large attempts`() {
        assertEquals(60.seconds, seerrRetryDelay(Int.MAX_VALUE))
        assertEquals(5.seconds, seerrRetryDelay(-1))
    }

    @Test
    fun `success on the first attempt reports nothing`() =
        runTest {
            val errors = mutableListOf<Exception>()
            var attempts = 0
            retrySeerrConnection({ true }, { errors.add(it) }) { attempts++ }
            assertEquals(1, attempts)
            assertTrue(errors.isEmpty())
        }

    @Test
    fun `network errors are retried until it connects`() =
        runTest {
            val errors = mutableListOf<Exception>()
            var attempts = 0
            retrySeerrConnection({ true }, { errors.add(it) }) {
                attempts++
                if (attempts < 3) throw SocketTimeoutException("timeout")
            }
            assertEquals(3, attempts)
            assertEquals(2, errors.size)
            assertEquals(15_000L, currentTime)
        }

    @Test
    fun `other errors are reported once and not retried`() =
        runTest {
            val errors = mutableListOf<Exception>()
            var attempts = 0
            retrySeerrConnection({ true }, { errors.add(it) }) {
                attempts++
                throw IllegalStateException("refused")
            }
            assertEquals(1, attempts)
            assertEquals(1, errors.size)
        }

    @Test
    fun `stops after the wait once keepTrying turns false`() =
        runTest {
            var attempts = 0
            var checks = 0
            retrySeerrConnection(keepTrying = { checks++ == 0 }, onError = {}) {
                attempts++
                throw IOException("offline")
            }
            assertEquals(2, attempts)
            assertEquals(15_000L, currentTime)
        }

    @Test
    fun `server errors are retried but certificate and client errors are not`() {
        assertTrue(isSeerrRetryable(SocketTimeoutException("timeout")))
        assertTrue(isSeerrRetryable(ServerException("bad gateway", 502)))
        assertFalse(isSeerrRetryable(SSLHandshakeException("bad certificate")))
        assertFalse(isSeerrRetryable(ClientException("forbidden", 403)))
        assertFalse(isSeerrRetryable(IllegalStateException("bug")))
    }

    @Test
    fun `beforeRetry holds the next attempt back`() =
        runTest {
            val open = CompletableDeferred<Unit>()
            var attempts = 0
            val job =
                launch {
                    retrySeerrConnection(
                        keepTrying = { true },
                        beforeRetry = { open.await() },
                        onError = {},
                    ) {
                        attempts++
                        if (attempts == 1) throw IOException("offline")
                    }
                }
            advanceTimeBy(600_000)
            runCurrent()
            assertEquals(1, attempts)
            open.complete(Unit)
            job.join()
            assertEquals(2, attempts)
        }

    @Test
    fun `cancellation is not reported and ends the retries`() {
        val errors = mutableListOf<Exception>()
        assertThrows(CancellationException::class.java) {
            runTest {
                retrySeerrConnection({ true }, { errors.add(it) }) {
                    throw CancellationException("user switched")
                }
            }
        }
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `cancelling during the wait stops the retries`() =
        runTest {
            var attempts = 0
            val job =
                launch {
                    retrySeerrConnection({ true }, {}) {
                        attempts++
                        throw IOException("offline")
                    }
                }
            runCurrent()
            advanceTimeBy(1_000)
            job.cancel()
            advanceUntilIdle()
            assertEquals(1, attempts)
        }
}
