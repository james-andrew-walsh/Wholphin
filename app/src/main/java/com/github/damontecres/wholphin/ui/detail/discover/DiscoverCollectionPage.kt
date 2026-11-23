package com.github.damontecres.wholphin.ui.detail.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.DataLoadingState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber

@HiltViewModel(assistedFactory = DiscoverCollectionViewModel.Factory::class)
class DiscoverCollectionViewModel
    @AssistedInject
    constructor(
        val navigationManager: NavigationManager,
        private val seerrService: SeerrService,
        private val backdropService: BackdropService,
        @Assisted val collectionId: Int,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(collectionId: Int): DiscoverCollectionViewModel
        }

        val parts = MutableStateFlow<DataLoadingState<List<DiscoverItem>>>(DataLoadingState.Pending)

        init {
            viewModelScope.launchIO {
                backdropService.clearBackdrop()
                val result =
                    try {
                        DataLoadingState.Success(seerrService.collectionParts(collectionId))
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.w(ex, "Could not load collection %s", collectionId)
                        DataLoadingState.Error(ex)
                    }
                parts.update { result }
            }
        }
    }

/**
 * Every film in a franchise, in release order
 */
@Composable
fun DiscoverCollectionPage(
    destination: Destination.DiscoverCollection,
    modifier: Modifier = Modifier,
    viewModel: DiscoverCollectionViewModel =
        hiltViewModel<DiscoverCollectionViewModel, DiscoverCollectionViewModel.Factory>(
            creationCallback = { it.create(destination.collectionId) },
        ),
) {
    val parts by viewModel.parts.collectAsState()
    DiscoverItemGridPage(
        title = destination.name,
        items = parts,
        startIndex = 0,
        onClickItem = { viewModel.navigationManager.navigateTo(Destination.DiscoveredItem(it)) },
        modifier = modifier,
    )
}
