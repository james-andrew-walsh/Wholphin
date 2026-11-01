package com.github.damontecres.wholphin.services

import android.app.Application
import androidx.compose.runtime.derivedStateOf
import androidx.navigation3.runtime.NavBackStack
import com.github.damontecres.wholphin.ui.nav.Destination
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric because ACRA logs to android.util.Log when it isn't set up
@Config(application = Application::class, sdk = [34])
@RunWith(RobolectricTestRunner::class)
class NavigationManagerTest {
    private val trailer = Destination.TrailerPlayer(listOf("dQw4w9WgXcQ"), "Title")
    private val navigationManager =
        NavigationManager().apply {
            backStack = mutableListOf(Destination.Home(), Destination.Discover)
        }

    @Test
    fun `Pushing the current page again does nothing`() {
        navigationManager.navigateTo(trailer)
        navigationManager.navigateTo(trailer.copy())
        Assert.assertEquals(listOf(Destination.Home(), Destination.Discover, trailer), navigationManager.backStack)
    }

    @Test
    fun `Pushing an earlier page still works`() {
        navigationManager.navigateTo(trailer)
        navigationManager.navigateTo(Destination.Discover)
        Assert.assertEquals(
            listOf(Destination.Home(), Destination.Discover, trailer, Destination.Discover),
            navigationManager.backStack,
        )
    }

    @Test
    fun `goBackFrom leaves the current page`() {
        navigationManager.navigateTo(trailer)
        navigationManager.goBackFrom(trailer)
        Assert.assertEquals(listOf(Destination.Home(), Destination.Discover), navigationManager.backStack)
    }

    @Test
    fun `goBackFrom does nothing once the page was already left`() {
        navigationManager.navigateTo(trailer)
        navigationManager.goBack()
        navigationManager.goBackFrom(trailer)
        Assert.assertEquals(listOf(Destination.Home(), Destination.Discover), navigationManager.backStack)
    }

    @Test
    fun `goBackFrom never removes the last page`() {
        navigationManager.backStack = mutableListOf(trailer)
        navigationManager.goBackFrom(trailer)
        Assert.assertEquals(listOf<Destination>(trailer), navigationManager.backStack)
    }

    @Test
    fun `isCurrent is only the top page`() {
        navigationManager.navigateTo(trailer)
        Assert.assertTrue(navigationManager.isCurrent(trailer.copy()))
        Assert.assertFalse(navigationManager.isCurrent(Destination.Discover))
        navigationManager.navigateTo(Destination.Discover)
        Assert.assertFalse(navigationManager.isCurrent(trailer))
    }

    @Test
    fun `isCurrent is observed through leaving and coming back`() {
        navigationManager.backStack = NavBackStack(Destination.Home())
        navigationManager.navigateTo(trailer)
        // Only recalculates if what it read has changed, which is what a recomposition relies on
        val isCurrent = derivedStateOf { navigationManager.isCurrent(trailer) }
        Assert.assertTrue(isCurrent.value)
        navigationManager.goBackFrom(trailer)
        Assert.assertFalse(isCurrent.value)
        navigationManager.navigateTo(trailer)
        Assert.assertTrue(isCurrent.value)
    }
}
