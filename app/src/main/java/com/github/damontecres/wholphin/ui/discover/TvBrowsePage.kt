package com.github.damontecres.wholphin.ui.discover

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.github.damontecres.wholphin.data.filter.BrowseDecade
import com.github.damontecres.wholphin.data.filter.BrowseRow
import com.github.damontecres.wholphin.data.filter.BrowseShelf
import com.github.damontecres.wholphin.data.filter.tvBrowseRows
import com.github.damontecres.wholphin.data.filter.tvDecades
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.DiscoverRating
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.ui.components.Genre
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.util.DiscoverRequestType
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class TvBrowseViewModel
    @Inject
    constructor(
        private val seerrService: SeerrService,
        navigationManager: NavigationManager,
        backdropService: BackdropService,
    ) : BrowseViewModel(navigationManager, backdropService) {
        private val today = LocalDate.now()
        override val rows: List<BrowseRow> = tvBrowseRows(today)
        override val decades: List<BrowseDecade> = tvDecades(today)
        override val itemType = SeerrItemType.TV
        override val gridType = DiscoverRequestType.DISCOVER_TV

        override suspend fun fetchShelf(shelf: BrowseShelf): List<DiscoverItem> =
            when (shelf.type) {
                DiscoverRequestType.TRENDING_TV_WEEK -> seerrService.trendingTvWeek()
                DiscoverRequestType.UPCOMING_TV -> seerrService.upcomingTv()
                DiscoverRequestType.DISCOVER_TV -> seerrService.discoverTvBy(shelf.filter)
                else -> throw IllegalArgumentException("Unsupported row type ${shelf.type}")
            }

        override suspend fun fetchGenres(): List<Genre> = seerrService.tvGenres()

        override suspend fun fetchRating(id: Int): DiscoverRating = DiscoverRating(seerrService.api.tvApi.tvTvIdRatingsGet(tvId = id))
    }

/**
 * The series counterpart of [MovieBrowsePage], for the TV Shows tab
 */
@Composable
fun TvBrowsePage(
    preferences: UserPreferences,
    positionCallback: (RowColumn) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TvBrowseViewModel = hiltViewModel(),
) {
    BrowsePage(
        preferences = preferences,
        positionCallback = positionCallback,
        viewModel = viewModel,
        modifier = modifier,
    )
}
