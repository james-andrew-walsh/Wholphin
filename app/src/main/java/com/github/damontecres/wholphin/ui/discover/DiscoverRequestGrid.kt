package com.github.damontecres.wholphin.ui.discover

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.filter.DiscoverFilter
import com.github.damontecres.wholphin.data.filter.DiscoverFilterBy
import com.github.damontecres.wholphin.data.filter.DiscoverSort
import com.github.damontecres.wholphin.data.filter.DiscoverSortAndDirection
import com.github.damontecres.wholphin.data.filter.FilterValueOption
import com.github.damontecres.wholphin.data.filter.browseMovieFilters
import com.github.damontecres.wholphin.data.filter.discoverMovieFilters
import com.github.damontecres.wholphin.data.filter.discoverTvFilters
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.services.FilterOptionCache
import com.github.damontecres.wholphin.services.KeyValueService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SeerrApi
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.ui.cards.DiscoverItemCard
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.detail.CardGrid
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.DiscoverMovieRequestHandler
import com.github.damontecres.wholphin.util.DiscoverRequestPager
import com.github.damontecres.wholphin.util.DiscoverRequestType
import com.github.damontecres.wholphin.util.DiscoverTvRequestHandler
import com.github.damontecres.wholphin.util.TrendingMoviesWeekRequestHandler
import com.github.damontecres.wholphin.util.TrendingRequestHandler
import com.github.damontecres.wholphin.util.UpcomingMovieRequestHandler
import com.github.damontecres.wholphin.util.UpcomingTvRequestHandler
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import timber.log.Timber

@HiltViewModel(assistedFactory = DiscoverRequestViewModel.Factory::class)
class DiscoverRequestViewModel
    @AssistedInject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val seerrService: SeerrService,
        val navigationManager: NavigationManager,
        private val api: SeerrApi,
        private val filterOptionCache: FilterOptionCache,
        private val keyValueService: KeyValueService,
        private val serverRepository: ServerRepository,
        @Assisted val type: DiscoverRequestType,
        @Assisted startIndex: Int,
        @Assisted initialFilter: DiscoverFilter,
        @Assisted private val persistKey: String?,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(
                type: DiscoverRequestType,
                startIndex: Int,
                initialFilter: DiscoverFilter,
                persistKey: String?,
            ): DiscoverRequestViewModel
        }

        private val _state = MutableStateFlow(DiscoverRequestState())
        val state: StateFlow<DiscoverRequestState> = _state

        init {
            viewModelScope.launchDefault {
                val moviesOnly = BuildConfig.MOVIES_ONLY_DISCOVER_ENABLED
                val sortOptions = discoverSortOptions(type, moviesOnly)
                val potentialFilterOptions = discoverFilterOptions(type, moviesOnly)
                val saved = loadSavedFilter()
                val sortAndDirection =
                    when (type) {
                        DiscoverRequestType.DISCOVER_TV -> DiscoverSortAndDirection()
                        DiscoverRequestType.DISCOVER_MOVIES -> saved?.sortBy ?: initialFilter.sortBy ?: DiscoverSortAndDirection()
                        DiscoverRequestType.TRENDING -> DiscoverSortAndDirection()
                        DiscoverRequestType.UPCOMING_TV -> DiscoverSortAndDirection()
                        DiscoverRequestType.UPCOMING_MOVIES -> DiscoverSortAndDirection()
                        DiscoverRequestType.TRENDING_MOVIES_WEEK -> DiscoverSortAndDirection()
                        DiscoverRequestType.UNKNOWN -> DiscoverSortAndDirection()
                    }
                val filterOptions =
                    if (potentialFilterOptions.isNotEmpty()) {
                        // If the initial filter includes filtering (eg genres), then don't allow for changing it
                        potentialFilterOptions.filter { it.get(initialFilter) == null }
                    } else {
                        potentialFilterOptions
                    }
                _state.update {
                    it.copy(
                        startIndex = startIndex,
                        sortOptions = sortOptions,
                        filterOptions = filterOptions,
                        sortAndDirection = sortAndDirection,
                    )
                }
                fetchData(sortAndDirection, saved?.copy(sortBy = null) ?: initialFilter, startIndex)
            }
        }

        private var fetchJob: Job? = null
        private var saveJob: Job? = null

        /**
         * Get the saved filter and sort for the current user, if this grid has any
         */
        private suspend fun loadSavedFilter(): DiscoverFilter? {
            val key = persistKey ?: return null
            val userId = serverRepository.currentUser?.id ?: return null
            return try {
                keyValueService.get<DiscoverFilter?>(userId, key, null).first()
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.w(ex, "Could not read saved filter %s", key)
                null
            }
        }

        private fun persist() {
            val key = persistKey ?: return
            val userId = serverRepository.currentUser?.id ?: return
            saveJob?.cancel()
            saveJob =
                viewModelScope.launchIO {
                    // Read the state here so that a late save can't store an older selection
                    val current = state.value
                    keyValueService.save(
                        userId,
                        key,
                        current.filter.copy(sortBy = current.sortAndDirection),
                    )
                }
        }

        private suspend fun fetchData(
            sortAndDirection: DiscoverSortAndDirection,
            filter: DiscoverFilter,
            startIndex: Int = 0,
        ) {
            fetchJob?.cancel()
            try {
                _state.update {
                    it.copy(
                        filter = filter,
                        sortAndDirection = sortAndDirection,
                        loading = DataLoadingState.Loading,
                    )
                }
                val filter = filter.copy(sortBy = sortAndDirection)
                fetchJob =
                    viewModelScope.launchDefault {
                        val pager =
                            when (type) {
                                DiscoverRequestType.DISCOVER_TV -> {
                                    DiscoverRequestPager(
                                        api,
                                        DiscoverTvRequestHandler(filter),
                                        seerrService::createDiscoverItem,
                                        viewModelScope,
                                    )
                                }

                                DiscoverRequestType.DISCOVER_MOVIES -> {
                                    DiscoverRequestPager(
                                        api,
                                        DiscoverMovieRequestHandler(filter),
                                        seerrService::createDiscoverItem,
                                        viewModelScope,
                                    )
                                }

                                DiscoverRequestType.TRENDING -> {
                                    DiscoverRequestPager(
                                        api,
                                        TrendingRequestHandler,
                                        seerrService::createDiscoverItem,
                                        viewModelScope,
                                    )
                                }

                                DiscoverRequestType.UPCOMING_TV -> {
                                    DiscoverRequestPager(
                                        api,
                                        UpcomingTvRequestHandler,
                                        seerrService::createDiscoverItem,
                                        viewModelScope,
                                    )
                                }

                                DiscoverRequestType.UPCOMING_MOVIES -> {
                                    DiscoverRequestPager(
                                        api,
                                        UpcomingMovieRequestHandler,
                                        seerrService::createDiscoverItem,
                                        viewModelScope,
                                    )
                                }

                                DiscoverRequestType.TRENDING_MOVIES_WEEK -> {
                                    DiscoverRequestPager(
                                        api,
                                        TrendingMoviesWeekRequestHandler,
                                        seerrService::createDiscoverItem,
                                        viewModelScope,
                                    )
                                }

                                DiscoverRequestType.UNKNOWN -> {
                                    throw IllegalArgumentException("Cannot display grid for DiscoverRequestType.UNKNOWN")
                                }
                            }.init(startIndex)
                        _state.update {
                            it.copy(
                                loading = DataLoadingState.Success(pager),
                            )
                        }
                    }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.e(ex, "Error initializing %s", type)
                _state.update {
                    it.copy(
                        loading = DataLoadingState.Error(ex),
                    )
                }
            }
        }

        fun onSortChange(newSort: DiscoverSortAndDirection) {
            viewModelScope.launchDefault {
                _state.update { it.copy(startIndex = 0) }
                fetchData(newSort, state.value.filter)
                persist()
            }
        }

        fun onFilterChange(newFilter: DiscoverFilter) {
            viewModelScope.launchDefault {
                _state.update { it.copy(startIndex = 0) }
                fetchData(state.value.sortAndDirection, newFilter)
                persist()
            }
        }

        fun resetFilters() {
            viewModelScope.launchDefault {
                _state.update { it.copy(startIndex = 0) }
                fetchData(DiscoverSortAndDirection(), DiscoverFilter())
                persist()
            }
        }

        suspend fun getPossibleFilterValues(filter: DiscoverFilterBy<*>): List<FilterValueOption> =
            filterOptionCache.getFilterOptionValues(null, filter)
    }

data class DiscoverRequestState(
    val loading: DataLoadingState<List<DiscoverItem?>> = DataLoadingState.Pending,
    val startIndex: Int = 0,
    val sortOptions: List<DiscoverSort> = emptyList(),
    val filterOptions: List<DiscoverFilterBy<*>> = emptyList(),
    val filter: DiscoverFilter = DiscoverFilter(),
    val sortAndDirection: DiscoverSortAndDirection = DiscoverSortAndDirection(),
)

/**
 * The sorts offered for a grid of the [type]
 *
 * @param moviesOnly whether this is the movie browsing experience
 */
fun discoverSortOptions(
    type: DiscoverRequestType,
    moviesOnly: Boolean,
): List<DiscoverSort> {
    val defaultSorts = DiscoverSort.entries - DiscoverSort.PRIMARY_RELEASE_DATE
    return when (type) {
        DiscoverRequestType.DISCOVER_TV -> {
            defaultSorts
        }

        DiscoverRequestType.DISCOVER_MOVIES -> {
            if (moviesOnly) {
                listOf(
                    DiscoverSort.POPULARITY,
                    DiscoverSort.PRIMARY_RELEASE_DATE,
                    DiscoverSort.TMDB_VOTE,
                    DiscoverSort.ALPHABETICAL,
                )
            } else {
                defaultSorts
            }
        }

        DiscoverRequestType.TRENDING,
        DiscoverRequestType.UPCOMING_TV,
        DiscoverRequestType.UPCOMING_MOVIES,
        DiscoverRequestType.TRENDING_MOVIES_WEEK,
        DiscoverRequestType.UNKNOWN,
        -> {
            emptyList()
        }
    }
}

/**
 * The filters that could be offered for a grid of the [type]
 *
 * @param moviesOnly whether this is the movie browsing experience
 */
fun discoverFilterOptions(
    type: DiscoverRequestType,
    moviesOnly: Boolean,
): List<DiscoverFilterBy<*>> =
    when (type) {
        DiscoverRequestType.DISCOVER_TV -> {
            discoverTvFilters
        }

        DiscoverRequestType.DISCOVER_MOVIES -> {
            if (moviesOnly) browseMovieFilters else discoverMovieFilters
        }

        DiscoverRequestType.TRENDING,
        DiscoverRequestType.UPCOMING_TV,
        DiscoverRequestType.UPCOMING_MOVIES,
        DiscoverRequestType.TRENDING_MOVIES_WEEK,
        DiscoverRequestType.UNKNOWN,
        -> {
            emptyList()
        }
    }

/**
 * @param persistKey if set, the filter and sort are saved per user under this key and restored
 */
@Composable
fun DiscoverRequestGrid(
    destination: Destination.DiscoverMoreResult,
    showTitle: Boolean,
    positionCallback: (columns: Int, position: Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModelKey: String? = null,
    persistKey: String? = null,
    viewModel: DiscoverRequestViewModel =
        hiltViewModel<DiscoverRequestViewModel, DiscoverRequestViewModel.Factory>(
            key = viewModelKey,
            creationCallback = {
                it.create(
                    destination.type,
                    destination.startIndex,
                    destination.initialFilter,
                    persistKey,
                )
            },
        ),
) {
    val state by viewModel.state.collectAsState()
    val canReset =
        persistKey != null &&
            (state.filter != DiscoverFilter() || state.sortAndDirection != DiscoverSortAndDirection())

    var showHeader by rememberSaveable { mutableStateOf(state.startIndex < 6) }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        DiscoverGridHeader(
            showHeader = showHeader,
            showTitle = showTitle,
            title =
                destination.titleOverride?.getString()
                    ?: stringResource(destination.type.stringRes),
            sortAndDirection = state.sortAndDirection,
            onSortChange = viewModel::onSortChange,
            sortOptions = state.sortOptions,
            getPossibleFilterValues = viewModel::getPossibleFilterValues,
            onClickShowViewOptions = {},
            currentFilter = state.filter,
            filterOptions = state.filterOptions,
            onFilterChange = viewModel::onFilterChange,
            onReset = if (canReset) viewModel::resetFilters else null,
            modifier = Modifier.fillMaxWidth(),
        )
        when (val s = state.loading) {
            DataLoadingState.Pending,
            DataLoadingState.Loading,
            -> {
                LoadingPage(Modifier)
            }

            is DataLoadingState.Error -> {
                ErrorMessage(s, Modifier)
            }

            is DataLoadingState.Success<List<DiscoverItem?>> -> {
                val gridFocusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) { gridFocusRequester.tryRequestFocus() }
                CardGrid(
                    initialPosition = state.startIndex,
                    pager = s.data,
                    onClickItem = { index, item ->
                        viewModel.navigationManager.navigateTo(item.destination)
                    },
                    onLongClickItem = { index, item -> },
                    onClickPlay = { _, _ -> },
                    letterPosition = { 0 },
                    gridFocusRequester = gridFocusRequester,
                    showJumpButtons = false,
                    showLetterButtons = false,
                    cardContent = { (item, index, onClick, onLongClick, widthPx, mod) ->
                        DiscoverItemCard(
                            item = item,
                            onClick = onClick,
                            onLongClick = onLongClick,
                            modifier = mod,
                            width = Dp.Unspecified,
                        )
                    },
                    columns = 6,
                    positionCallback = { columns, index ->
                        showHeader = index < columns
                        positionCallback.invoke(columns, index)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
