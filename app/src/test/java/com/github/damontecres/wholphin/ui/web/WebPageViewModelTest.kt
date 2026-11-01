package com.github.damontecres.wholphin.ui.web

import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebPageViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val navigationManager = mockk<NavigationManager>(relaxed = true)
    private val destination = Destination.WebPage("https://www.imdb.com/title/tt0078748", "Alien")

    private fun advanceSeconds(seconds: Long) {
        testDispatcher.scheduler.advanceTimeBy(seconds * 1000)
        testDispatcher.scheduler.runCurrent()
    }

    @Before
    fun setUp() {
        WholphinDispatchers.configure(testDispatcher)
    }

    @After
    fun tearDown() {
        WholphinDispatchers.reset()
    }

    @Test
    fun `No page finishing in time is a failure`() {
        val viewModel = WebPageViewModel(navigationManager, destination)
        advanceSeconds(29)
        assertEquals(WebPageState(), viewModel.state.value)
        advanceSeconds(1)
        assertEquals(WebPageState(failed = true), viewModel.state.value)
    }

    @Test
    fun `A page finishing stops the timeout`() {
        val viewModel = WebPageViewModel(navigationManager, destination)
        advanceSeconds(1)
        viewModel.onLoaded()
        advanceSeconds(60)
        assertEquals(WebPageState(loaded = true), viewModel.state.value)
    }

    @Test
    fun `A failure is kept when the page finishes loading afterwards`() {
        val viewModel = WebPageViewModel(navigationManager, destination)
        viewModel.onFailed()
        viewModel.onLoaded()
        assertEquals(WebPageState(loaded = true, failed = true), viewModel.state.value)
    }

    @Test
    fun `Closing only leaves this page`() {
        WebPageViewModel(navigationManager, destination).close()
        verify(exactly = 1) { navigationManager.goBackFrom(destination) }
    }
}
