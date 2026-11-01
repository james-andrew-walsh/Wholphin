package com.github.damontecres.wholphin.ui.discover

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.github.damontecres.wholphin.data.filter.BrowseDecade
import com.github.damontecres.wholphin.data.filter.BrowseRow
import com.github.damontecres.wholphin.data.filter.BrowseShelf
import com.github.damontecres.wholphin.data.filter.movieBrowseRows
import com.github.damontecres.wholphin.data.filter.movieDecades
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
class MovieBrowseViewModel
    @Inject
    constructor(
        private val seerrService: SeerrService,
        navigationManager: NavigationManager,
        backdropService: BackdropService,
    ) : BrowseViewModel(navigationManager, backdropService) {
        private val today = LocalDate.now()
        override val rows: List<BrowseRow> = movieBrowseRows(today)
        override val decades: List<BrowseDecade> = movieDecades(today)
        override val itemType = SeerrItemType.MOVIE
        override val gridType = DiscoverRequestType.DISCOVER_MOVIES

        override suspend fun fetchShelf(shelf: BrowseShelf): List<DiscoverItem> =
            when (shelf.type) {
                DiscoverRequestType.TRENDING_MOVIES_WEEK -> seerrService.trendingMoviesWeek()
                DiscoverRequestType.UPCOMING_MOVIES -> seerrService.upcomingMovies()
                DiscoverRequestType.DISCOVER_MOVIES -> seerrService.discoverMoviesBy(shelf.filter)
                else -> throw IllegalArgumentException("Unsupported row type ${shelf.type}")
            }

        override suspend fun fetchGenres(): List<Genre> = seerrService.movieGenres()

        override suspend fun fetchRating(id: Int): DiscoverRating =
            DiscoverRating(
                seerrService.api.moviesApi.movieMovieIdRatingsGet(movieId = id),
            )
    }

/**
 * Movie only replacement for the rows of [SeerrDiscoverPage]
 */
@Composable
fun MovieBrowsePage(
    preferences: UserPreferences,
    positionCallback: (RowColumn) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MovieBrowseViewModel = hiltViewModel(),
) {
    BrowsePage(
        preferences = preferences,
        positionCallback = positionCallback,
        viewModel = viewModel,
        modifier = modifier,
    )
}
