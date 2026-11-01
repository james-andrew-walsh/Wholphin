package com.github.damontecres.wholphin.ui.discover

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.filter.BrowseDecade
import com.github.damontecres.wholphin.data.filter.BrowseRow
import com.github.damontecres.wholphin.data.filter.BrowseShelf
import com.github.damontecres.wholphin.data.filter.DiscoverFilter
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.DiscoverRating
import com.github.damontecres.wholphin.data.model.QuickDetailsData
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.components.Genre
import com.github.damontecres.wholphin.ui.components.HeaderUtils
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.detail.discover.getDiscoverRating
import com.github.damontecres.wholphin.ui.ifElse
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.listToDotString
import com.github.damontecres.wholphin.ui.main.HomePageHeader
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.rememberPosition
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.ui.util.ScrollToTopBringIntoViewSpec
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.DiscoverRequestType
import com.github.damontecres.wholphin.util.successValue
import com.google.common.cache.CacheBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber

// The TV is on weak Wi-Fi, so rows only load as focus gets near them
private const val ROWS_LOADED_AHEAD = 2
private const val MAX_CONCURRENT_ROW_REQUESTS = 2
private const val BACKDROP_DELAY_MS = 250L

/**
 * The rows of a browse page, [MovieBrowseViewModel] and [TvBrowseViewModel] say what goes in them
 */
abstract class BrowseViewModel(
    val navigationManager: NavigationManager,
    private val backdropService: BackdropService,
) : ViewModel() {
    abstract val rows: List<BrowseRow>
    abstract val decades: List<BrowseDecade>

    // What the genre and decade cards open
    abstract val itemType: SeerrItemType
    abstract val gridType: DiscoverRequestType

    protected abstract suspend fun fetchShelf(shelf: BrowseShelf): List<DiscoverItem>

    protected abstract suspend fun fetchGenres(): List<Genre>

    protected abstract suspend fun fetchRating(id: Int): DiscoverRating

    private val _rowItems = MutableStateFlow<Map<Int, DataLoadingState<List<DiscoverItem>>>>(mapOf())
    val rowItems: StateFlow<Map<Int, DataLoadingState<List<DiscoverItem>>>> = _rowItems

    private val _genres = MutableStateFlow<DataLoadingState<List<Genre>>>(DataLoadingState.Pending)
    val genres: StateFlow<DataLoadingState<List<Genre>>> = _genres

    val rating = MutableStateFlow<Map<Int, DiscoverRating>>(mapOf())

    private val limiter = Semaphore(MAX_CONCURRENT_ROW_REQUESTS)
    private var requestedThrough = -1

    init {
        viewModelScope.launchIO {
            backdropService.clearBackdrop()
        }
    }

    /**
     * Load the rows through a few rows past the [focusedRow], each row only loads once
     */
    fun ensureLoaded(focusedRow: Int) {
        val target = minOf(focusedRow + ROWS_LOADED_AHEAD, rows.lastIndex)
        val first =
            synchronized(this) {
                (requestedThrough + 1).also {
                    requestedThrough = maxOf(requestedThrough, target)
                }
            }
        for (index in first..target) {
            loadRow(index)
        }
    }

    private fun loadRow(index: Int) {
        when (val row = rows[index]) {
            is BrowseRow.Items -> {
                _rowItems.update { it + (index to DataLoadingState.Loading) }
                loadLimited(index, { fetchShelf(row.shelf) }) { result ->
                    _rowItems.update { it + (index to result) }
                }
            }

            BrowseRow.Genres -> {
                _genres.value = DataLoadingState.Loading
                loadLimited(index, { fetchGenres() }) { _genres.value = it }
            }

            // Static cards
            BrowseRow.Decades -> {}
        }
    }

    private fun <T> loadLimited(
        index: Int,
        fetch: suspend () -> T,
        onResult: (DataLoadingState<T>) -> Unit,
    ) {
        viewModelScope.launchIO {
            val result: DataLoadingState<T> =
                limiter.withPermit {
                    try {
                        DataLoadingState.Success(fetch())
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.e(ex, "Error loading %s row %s", itemType, index)
                        DataLoadingState.Error(ex)
                    }
                }
            onResult(result)
            if (result is DataLoadingState.Error) {
                // The rows below would otherwise wait for focus to reach the error, so load past it
                ensureLoaded(index)
            }
        }
    }

    fun updateBackdrop(item: DiscoverFocusedItem?) {
        viewModelScope.launchIO {
            if (item != null) {
                backdropService.submit("discover_${item.id}", item.backDropUrl)
                if (item is DiscoverFocusedItem.Item) {
                    updateRating(item.id)
                }
            } else {
                backdropService.clearBackdrop()
            }
        }
    }

    private val ratingCache =
        CacheBuilder
            .newBuilder()
            .maximumSize(100)
            .build<Int, DiscoverRating>()

    private fun updateRating(id: Int) {
        viewModelScope.launchIO {
            val cachedResult = ratingCache.getIfPresent(id)
            if (cachedResult != null) {
                return@launchIO
            }
            val result = getDiscoverRating(id) { fetchRating(id) } ?: DiscoverRating(null, null)

            ratingCache.put(id, result)
            rating.update {
                ratingCache.asMap().toMap()
            }
        }
    }
}

/**
 * The page behind [MovieBrowsePage] and [TvBrowsePage]
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowsePage(
    preferences: UserPreferences,
    positionCallback: (RowColumn) -> Unit,
    viewModel: BrowseViewModel,
    modifier: Modifier = Modifier,
) {
    val rows = viewModel.rows
    val rowItems by viewModel.rowItems.collectAsState()
    val genres by viewModel.genres.collectAsState()
    val ratingMap by viewModel.rating.collectAsState()

    val decades =
        remember {
            DataLoadingState.Success(
                viewModel.decades.map {
                    Genre(id = it.id.toUUID(), name = it.name, imageUrl = null)
                },
            )
        }
    val genresTitle = remember { ResStringProvider(R.string.genres) }
    val decadesTitle = remember { ResStringProvider(R.string.decades) }

    val focusRequesters = remember { List(rows.size) { FocusRequester() } }
    var position by rememberPosition(0, -1)
    // Loading rows are short, so per item triggers would compose & load too many of them at once
    LaunchedEffect(position.row) {
        viewModel.ensureLoaded(position.row.coerceAtLeast(0))
    }
    val focusedItem: DiscoverFocusedItem? =
        remember(position, rowItems, genres) {
            when (rows.getOrNull(position.row)) {
                is BrowseRow.Items -> {
                    rowItems[position.row]
                        ?.successValue
                        ?.getOrNull(position.column)
                        ?.let { DiscoverFocusedItem.Item(it) }
                }

                BrowseRow.Genres -> {
                    genres.successValue
                        ?.getOrNull(position.column)
                        ?.let { focusedGenre(it, viewModel.itemType) }
                }

                BrowseRow.Decades -> {
                    decades.data
                        .getOrNull(position.column)
                        ?.let { focusedGenre(it, viewModel.itemType) }
                }

                null -> {
                    null
                }
            }
        }
    LaunchedEffect(focusedItem) {
        // Scrolling quickly shouldn't fetch a rating for every card passed over
        delay(BACKDROP_DELAY_MS)
        viewModel.updateBackdrop(focusedItem)
    }
    var firstFocused by rememberSaveable { mutableStateOf(false) }
    var rowsHaveFocus by remember { mutableStateOf(false) }
    val firstRow = rowItems[0]
    LaunchedEffect(firstRow) {
        // Only a loading row has nothing to focus, a failed row has its error message's button
        if (!firstFocused && (firstRow is DataLoadingState.Success<*> || firstRow is DataLoadingState.Error)) {
            firstFocused = focusRequesters.getOrNull(0)?.tryRequestFocus("discover") == true
        } else if (firstFocused && !rowsHaveFocus) {
            focusRequesters.getOrNull(position.row)?.tryRequestFocus()
        }
    }

    Column(
        modifier = modifier,
    ) {
        val details =
            remember(focusedItem, ratingMap) {
                (focusedItem as? DiscoverFocusedItem.Item)?.let { focusedItem ->
                    buildList {
                        focusedItem.item
                            .releaseDate
                            ?.year
                            ?.toString()
                            ?.let(::add)
                    }.let {
                        val rating = focusedItem.id.let { ratingMap[it] }
                        val str =
                            listToDotString(
                                it,
                                rating?.audienceRating,
                                rating?.criticRating?.toFloat(),
                            )
                        QuickDetailsData(str)
                    }
                }
            }
        HomePageHeader(
            title = focusedItem?.title?.getString(),
            subtitle = focusedItem?.subtitle,
            overview = focusedItem?.overview,
            overviewTwoLines = false,
            quickDetails = details,
            timeRemaining = null,
            endsAt = null,
            showLogo = preferences.appPreferences.interfacePreferences.showLogos,
            logoImageUrl = focusedItem?.logoUrl,
            modifier =
                Modifier
                    .padding(
                        top = HeaderUtils.topPadding,
                        // Intentionally no bottom padding because of tabs
                        bottom = 0.dp,
                        start = HeaderUtils.startPadding,
                    ).height(HeaderUtils.height),
        )

        val density = LocalDensity.current
        val spaceAbovePx =
            with(density) {
                // The size of the row titles & spacing
                50.dp.toPx()
            }
        val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current

        CompositionLocalProvider(
            LocalBringIntoViewSpec provides ScrollToTopBringIntoViewSpec(spaceAbovePx),
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
                modifier =
                    Modifier
                        .onFocusChanged { rowsHaveFocus = it.hasFocus }
                        .focusRestorer()
                        .fillMaxSize(),
            ) {
                itemsIndexed(rows) { rowIndex, row ->
                    CompositionLocalProvider(LocalBringIntoViewSpec provides defaultBringIntoViewSpec) {
                        val onRowFocus = { index: Int ->
                            firstFocused = true
                            position = RowColumn(rowIndex, index)
                            positionCallback.invoke(position)
                        }
                        // A failed row has no cards to report focus, only its error message's button
                        val failedRowFocus = Modifier.onFocusChanged { if (it.hasFocus) onRowFocus(0) }
                        when (row) {
                            is BrowseRow.Items -> {
                                val shelf = row.shelf
                                DiscoverRow(
                                    row =
                                        DiscoverRowData(
                                            shelf.title,
                                            rowItems[rowIndex] ?: DataLoadingState.Pending,
                                            shelf.type,
                                        ),
                                    onClickItem = { index, item ->
                                        position = RowColumn(rowIndex, index)
                                        viewModel.navigationManager.navigateTo(item.destination)
                                    },
                                    onLongClickItem = { _, _ -> },
                                    onCardFocus = onRowFocus,
                                    focusRequester = focusRequesters[rowIndex],
                                    enableViewMore = true,
                                    onClickViewMore = {
                                        rowItems[rowIndex]?.successValue?.size?.let {
                                            position = RowColumn(rowIndex, it)
                                            positionCallback.invoke(position)
                                        }
                                        viewModel.navigationManager.navigateTo(
                                            Destination.DiscoverMoreResult(
                                                type = shelf.type,
                                                initialFilter = shelf.filter,
                                                titleOverride = shelf.title,
                                            ),
                                        )
                                    },
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .ifElse(
                                                rowItems[rowIndex] is DataLoadingState.Error,
                                                Modifier
                                                    .focusRequester(focusRequesters[rowIndex])
                                                    .then(failedRowFocus),
                                            ),
                                )
                            }

                            BrowseRow.Genres -> {
                                DiscoverGenreRow(
                                    title = genresTitle,
                                    items = genres,
                                    onClickItem = { index, genre ->
                                        position = RowColumn(rowIndex, index)
                                        viewModel.navigationManager.navigateTo(
                                            Destination.DiscoverMoreResult(
                                                type = viewModel.gridType,
                                                initialFilter =
                                                    DiscoverFilter(
                                                        genreIds = listOf(genre.id.toInt()),
                                                    ),
                                                titleOverride =
                                                    discoverGenreTitle(
                                                        genre.name,
                                                        viewModel.itemType,
                                                    ),
                                                startIndex = 0,
                                            ),
                                        )
                                    },
                                    onCardFocus = onRowFocus,
                                    modifier =
                                        Modifier
                                            .focusRequester(focusRequesters[rowIndex])
                                            .ifElse(genres is DataLoadingState.Error, failedRowFocus),
                                )
                            }

                            BrowseRow.Decades -> {
                                DiscoverGenreRow(
                                    title = decadesTitle,
                                    items = decades,
                                    onClickItem = { index, _ ->
                                        position = RowColumn(rowIndex, index)
                                        val decade = viewModel.decades[index]
                                        viewModel.navigationManager.navigateTo(
                                            Destination.DiscoverMoreResult(
                                                type = viewModel.gridType,
                                                initialFilter = decade.filter,
                                                titleOverride =
                                                    discoverGenreTitle(
                                                        decade.name,
                                                        viewModel.itemType,
                                                    ),
                                                startIndex = 0,
                                            ),
                                        )
                                    },
                                    onCardFocus = onRowFocus,
                                    modifier = Modifier.focusRequester(focusRequesters[rowIndex]),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun focusedGenre(
    genre: Genre,
    type: SeerrItemType,
) = DiscoverFocusedItem.Genre(
    id = genre.id.toInt(),
    name = genre.name,
    type = type,
    backDropUrl = genre.imageUrl,
)
