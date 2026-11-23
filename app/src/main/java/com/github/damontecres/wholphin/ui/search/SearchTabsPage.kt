package com.github.damontecres.wholphin.ui.search

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.components.TabDetails
import com.github.damontecres.wholphin.ui.components.TabbedPage

/**
 * Search, with a tab for the regular search and one for exploring keywords
 *
 * It opens on keywords, unless a query was sent in, such as by voice search or another app, which
 * only the regular search takes.
 */
@Composable
fun SearchTabsPage(
    initialQuery: String,
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
) {
    val tabs = remember { listOf(TabDetails(R.string.search), TabDetails(R.string.keywords_tab)) }
    var selectedTab by rememberSaveable { mutableIntStateOf(if (initialQuery.isBlank()) KEYWORDS_TAB else SEARCH_TAB) }
    TabbedPage(
        selectedTabIndex = selectedTab,
        tabs = tabs,
        updateSelectedTabIndex = { selectedTab = it },
        isShowClock = preferences.appPreferences.interfacePreferences.showClock,
        modifier = modifier,
    ) { tabIndex, _ ->
        when (tabIndex) {
            SEARCH_TAB -> {
                SearchPage(
                    initialQuery = initialQuery,
                    userPreferences = preferences,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
                KeywordSearchPage(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

private const val SEARCH_TAB = 0
private const val KEYWORDS_TAB = 1
