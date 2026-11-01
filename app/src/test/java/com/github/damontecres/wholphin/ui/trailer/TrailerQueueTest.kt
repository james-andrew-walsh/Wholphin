package com.github.damontecres.wholphin.ui.trailer

import org.junit.Assert
import org.junit.Test

class TrailerQueueTest {
    private val queue = TrailerQueue(listOf("one", "two"))

    private fun error(code: Int) = queue.onEvent(TrailerEvent.Error(code))

    private fun state(state: Int) = queue.onEvent(TrailerEvent.StateChanged(state))

    private fun load(videoId: String) = TrailerAction.Load(videoId)

    private val unavailable = TrailerAction.Fail(TrailerFailure.UNAVAILABLE)

    @Test
    fun `Starts with the first video`() {
        Assert.assertEquals(load("one"), queue.start())
    }

    @Test
    fun `Nothing to play fails`() {
        Assert.assertEquals(unavailable, TrailerQueue(emptyList()).start())
    }

    @Test
    fun `Errors move to the next video`() {
        listOf(2, 100, 101, 150, 999).forEach { code ->
            val queue = TrailerQueue(listOf("one", "two"))
            queue.start()
            Assert.assertEquals("code=$code", load("two"), queue.onEvent(TrailerEvent.Error(code)))
        }
    }

    @Test
    fun `Fails after the last video`() {
        queue.start()
        Assert.assertEquals(load("two"), error(150))
        Assert.assertEquals(unavailable, error(150))
    }

    @Test
    fun `Error 5 retries once then moves on`() {
        queue.start()
        Assert.assertEquals(load("one"), error(5))
        Assert.assertEquals(load("two"), error(5))
        // The retry is per video
        Assert.assertEquals(load("two"), error(5))
        Assert.assertEquals(unavailable, error(5))
    }

    @Test
    fun `Error 153 stops`() {
        queue.start()
        Assert.assertEquals(TrailerAction.Fail(TrailerFailure.APP_IDENTITY), error(153))
        Assert.assertEquals(TrailerAction.None, error(150))
        Assert.assertEquals(TrailerAction.None, queue.onTimeout())
    }

    @Test
    fun `Ended closes`() {
        queue.start()
        Assert.assertEquals(TrailerAction.None, state(1))
        Assert.assertEquals(TrailerAction.Close, state(0))
        Assert.assertEquals(TrailerAction.None, error(150))
    }

    @Test
    fun `Timeout moves to the next video when nothing started`() {
        queue.start()
        Assert.assertEquals(TrailerAction.None, state(-1))
        Assert.assertEquals(TrailerAction.None, state(5))
        Assert.assertEquals(load("two"), queue.onTimeout())
        Assert.assertEquals(unavailable, queue.onTimeout())
    }

    @Test
    fun `Timeout does nothing once started`() {
        listOf(1, 2, 3).forEach { state ->
            val queue = TrailerQueue(listOf("one", "two"))
            queue.start()
            queue.onEvent(TrailerEvent.StateChanged(state))
            Assert.assertEquals("state=$state", TrailerAction.None, queue.onTimeout())
        }
    }

    @Test
    fun `Timeout applies again to the next video`() {
        queue.start()
        state(1)
        Assert.assertEquals(load("two"), error(150))
        Assert.assertEquals(unavailable, queue.onTimeout())
    }

    @Test
    fun `Other events do nothing`() {
        queue.start()
        Assert.assertEquals(TrailerAction.None, queue.onEvent(TrailerEvent.Ready))
        Assert.assertEquals(TrailerAction.None, queue.onEvent(TrailerEvent.AutoplayBlocked))
    }

    @Test
    fun `Messages from the page are parsed`() {
        Assert.assertEquals(TrailerEvent.Ready, TrailerEvent.parse("""{"t":"ready"}"""))
        Assert.assertEquals(TrailerEvent.StateChanged(-1), TrailerEvent.parse("""{"t":"state","v":-1}"""))
        Assert.assertEquals(TrailerEvent.Error(150), TrailerEvent.parse("""{"t":"error","v":150}"""))
        Assert.assertEquals(TrailerEvent.AutoplayBlocked, TrailerEvent.parse("""{"t":"blocked"}"""))
    }

    @Test
    fun `Unknown messages are ignored`() {
        listOf(
            null,
            "",
            "not json",
            "[]",
            "1",
            "{}",
            """{"t":"other"}""",
            """{"t":"state"}""",
            """{"t":"error","v":"x"}""",
            """{"t":{"a":1}}""",
        ).forEach {
            Assert.assertNull("message=$it", TrailerEvent.parse(it))
        }
    }

    @Test
    fun `Autoplay can make a sound from the player being ready`() {
        Assert.assertTrue(TrailerEvent.Ready.startsPlayback)
        Assert.assertTrue(TrailerEvent.StateChanged(1).startsPlayback)
        Assert.assertTrue(TrailerEvent.StateChanged(3).startsPlayback)
        Assert.assertFalse(TrailerEvent.StateChanged(-1).startsPlayback)
        Assert.assertFalse(TrailerEvent.StateChanged(0).startsPlayback)
        Assert.assertFalse(TrailerEvent.StateChanged(2).startsPlayback)
        Assert.assertFalse(TrailerEvent.Error(150).startsPlayback)
        Assert.assertFalse(TrailerEvent.AutoplayBlocked.startsPlayback)
    }

    @Test
    fun `Playing includes buffering`() {
        Assert.assertTrue(TrailerEvent.StateChanged(1).isPlaying)
        Assert.assertTrue(TrailerEvent.StateChanged(3).isPlaying)
        Assert.assertFalse(TrailerEvent.StateChanged(2).isPlaying)
        Assert.assertFalse(TrailerEvent.StateChanged(0).isPlaying)
    }
}
