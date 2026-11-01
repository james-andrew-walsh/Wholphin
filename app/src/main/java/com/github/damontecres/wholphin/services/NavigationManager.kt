package com.github.damontecres.wholphin.services

import androidx.navigation3.runtime.NavBackStack
import com.github.damontecres.wholphin.ui.nav.Destination
import org.acra.ACRA
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages navigating between pages and manages the app's back stack
 */
@Singleton
class NavigationManager
    @Inject
    constructor() {
        var backStack: MutableList<Destination> = NavBackStack(Destination.Home())

        /**
         * Go to the specified [com.github.damontecres.wholphin.ui.nav.Destination]
         *
         * Already being there does nothing, so that a double press doesn't open a page twice
         */
        fun navigateTo(destination: Destination) {
            synchronized(this) {
                if (backStack.lastOrNull() != destination) {
                    backStack.add(destination)
                }
            }
            log()
        }

        /**
         * Go to the specified [Destination], but reset the back stack to Home first
         */
        fun navigateToFromDrawer(destination: Destination) {
            goToHome()
            backStack.add(destination)
            log()
        }

        /**
         * Go to the previous page
         */
        fun goBack() {
            synchronized(this) {
                if (backStack.size > 1) {
                    backStack.removeLastOrNull()
                }
            }
            log()
        }

        /**
         * Go to the previous page, but only if the current page is still [destination]
         *
         * For a page closing itself from something asynchronous, which may land after the user has
         * already left and would otherwise take them back one page too far
         */
        fun goBackFrom(destination: Destination) {
            synchronized(this) {
                if (backStack.size > 1 && backStack.lastOrNull() == destination) {
                    backStack.removeLastOrNull()
                }
            }
            log()
        }

        /**
         * Whether [destination] is the current page
         *
         * Reading this in a composable is observed. A page that was left is still composed during
         * the transition, this tells it apart from the one the user is on.
         */
        fun isCurrent(destination: Destination): Boolean = backStack.lastOrNull() == destination

        /**
         * Go all the way back to the home page
         */
        fun goToHome() {
            while (backStack.size > 1) {
                backStack.removeLastOrNull()
            }
            if (backStack[0] !is Destination.Home) {
                backStack[0] = Destination.Home()
            }
            log()
        }

        /**
         * Go all the way back to the home page, and reload it from scratch
         */
        fun reloadHome() {
            goToHome()
            val id = (backStack[0] as Destination.Home).id + 1
            backStack[0] = Destination.Home(id)
            log()
        }

        /**
         * Resets the backstack to the specified destination
         */
        fun replace(destination: Destination) {
            while (backStack.size > 1) {
                backStack.removeLastOrNull()
            }
            if (backStack.isEmpty()) {
                backStack.add(0, destination)
            } else {
                backStack[0] = destination
            }
            log()
        }

        private fun log() {
            val dest = backStack.lastOrNull().toString()
            Timber.i("Current Destination: %s", dest)
            ACRA.errorReporter.putCustomData("destination", dest)
        }
    }
