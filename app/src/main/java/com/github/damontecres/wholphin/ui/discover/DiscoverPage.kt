package com.github.damontecres.wholphin.ui.discover

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.TabDetails
import com.github.damontecres.wholphin.ui.components.TabbedPage
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.nav.NavDrawerItem
import com.github.damontecres.wholphin.util.DiscoverRequestType

private const val MOVIES_FILTER_KEY = "discover_movies_filter"

@Composable
fun DiscoverPage(
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
) {
    val tabs =
        remember {
            listOf(
                TabDetails(R.string.discover),
                TabDetails(R.string.request),
                TabDetails(R.string.search),
                TabDetails(R.string.movies_title),
                TabDetails(R.string.tv_shows_title),
            ).let { if (BuildConfig.MOVIES_ONLY_DISCOVER_ENABLED) it.dropLast(1) else it }
        }
    var showHeader by rememberSaveable { mutableStateOf(true) }

    TabbedPage(
        itemId = NavDrawerItem.Discover.id,
        tabs = tabs,
        modifier = modifier,
        showTabs = showHeader,
    ) { tabIndex, tabDetails ->
        when (tabIndex) {
            // Discover
            0 -> {
                if (BuildConfig.BROWSE_DISCOVER_ENABLED) {
                    MovieBrowsePage(
                        preferences = preferences,
                        positionCallback = { showHeader = it.row < 1 },
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .focusRequester(tabDetails.contentFocusRequester),
                    )
                } else {
                    SeerrDiscoverPage(
                        preferences = preferences,
                        positionCallback = { showHeader = it.row < 1 },
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .focusRequester(tabDetails.contentFocusRequester),
                    )
                }
            }

            // Requests
            1 -> {
                SeerrRequestsPage(
                    focusRequesterOnEmpty = tabDetails.tabFocusRequester,
                    positionCallback = { columns, index -> showHeader = index < columns },
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .focusRequester(tabDetails.contentFocusRequester),
                )
            }

            // Search
            2 -> {
                DiscoverSearchPage(
                    preferences = preferences,
                    positionCallback = { columns, index -> showHeader = index < columns },
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .focusRequester(tabDetails.contentFocusRequester),
                )
            }

            // Movies
            3 -> {
                DiscoverRequestGrid(
                    viewModelKey = "movies",
                    persistKey = if (BuildConfig.BROWSE_DISCOVER_ENABLED) MOVIES_FILTER_KEY else null,
                    showTitle = false,
                    destination =
                        Destination.DiscoverMoreResult(
                            DiscoverRequestType.DISCOVER_MOVIES,
                            0,
                        ),
                    positionCallback = { columns, index -> showHeader = index < columns },
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .focusRequester(tabDetails.contentFocusRequester),
                )
            }

            // TV
            4 -> {
                if (BuildConfig.BROWSE_DISCOVER_ENABLED) {
                    TvBrowsePage(
                        preferences = preferences,
                        positionCallback = { showHeader = it.row < 1 },
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .focusRequester(tabDetails.contentFocusRequester),
                    )
                } else {
                    DiscoverRequestGrid(
                        viewModelKey = "tv",
                        showTitle = false,
                        destination =
                            Destination.DiscoverMoreResult(
                                DiscoverRequestType.DISCOVER_TV,
                                0,
                            ),
                        positionCallback = { columns, index -> showHeader = index < columns },
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .focusRequester(tabDetails.contentFocusRequester),
                    )
                }
            }

            else -> {
                ErrorMessage("Invalid tab index $tabIndex", null)
            }
        }
    }
}
