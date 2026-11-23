package com.github.damontecres.wholphin.ui.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.services.UserPreferencesService
import com.github.damontecres.wholphin.ui.components.VoiceInputManager
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.util.DataLoadingState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * A TMDB keyword in a cloud
 *
 * @param movieCount how many movies TMDB tags with it, which sets its size
 * @param relatedness how strongly it goes with the word in the middle, higher is closer
 */
@Immutable
data class CloudWord(
    val id: Int,
    val name: String,
    val movieCount: Int,
    val relatedness: Float = 0f,
)

/**
 * A keyword and the keywords that go with it, most related first
 */
@Immutable
data class KeywordCloud(
    val center: CloudWord,
    val related: List<CloudWord>,
)

@Immutable
data class KeywordSearchState(
    val cloud: DataLoadingState<KeywordCloud>? = null,
    // Words visited before the current one, so Back can step back through them
    val history: List<CloudWord> = emptyList(),
    // Move focus to the middle word when the cloud loads. Not while typing, which would close the keyboard.
    val focusCloud: Boolean = false,
    // What was searched for when no keyword matched it
    val noMatch: String? = null,
    // A word chosen in the cloud or returned to with Back, for the search box to show
    val chosenWord: CloudWord? = null,
)

/**
 * Searches TMDB keywords through Seerr and builds a cloud of the keywords related to one of them
 *
 * TMDB has no list of related keywords, so they are worked out from the most popular movies with
 * the keyword: every other keyword on those movies is counted, then ranked by how much more often
 * it appears with the keyword than across all movies. Keywords with a similar name, such as
 * "prison escape" for "prison", are added too.
 */
@HiltViewModel
class KeywordSearchViewModel
    @Inject
    constructor(
        val navigationManager: NavigationManager,
        private val seerrService: SeerrService,
        val voiceInputManager: VoiceInputManager,
        userPreferencesService: UserPreferencesService,
    ) : ViewModel() {
        private val _state = MutableStateFlow(KeywordSearchState())
        val state: StateFlow<KeywordSearchState> = _state

        val seerrActive = seerrService.active

        val showVoiceSearchButton =
            userPreferencesService.flow.map {
                it.appPreferences.interfacePreferences.searchPreferences.showVoiceSearchButton
            }

        // The last query searched, so returning to the page doesn't search it again
        private var lastQuery: String? = null

        private var searchJob: Job? = null
        private var cloudJob: Job? = null

        /**
         * Show the cloud for the keyword that best matches [query]: one with exactly that name,
         * otherwise the shortest name containing it, since shorter names tend to be broader
         *
         * @param immediate search now rather than after a pause in typing, and move focus to the
         * cloud, as for a spoken search or the keyboard's search key
         */
        fun search(
            query: String,
            immediate: Boolean = false,
        ) {
            val trimmed = query.trim()
            if (!immediate && trimmed == lastQuery) return
            lastQuery = trimmed
            searchJob?.cancel()
            // What is typed now replaces any word chosen in the cloud
            _state.update { it.copy(chosenWord = null) }
            if (trimmed.isBlank()) return
            searchJob =
                viewModelScope.launchIO {
                    if (!immediate) delay(600.milliseconds)
                    val best =
                        try {
                            seerrService.api.searchApi
                                .searchKeywordGet(query = trimmed)
                                .results
                                .orEmpty()
                                .mapNotNull { kw ->
                                    val id = kw.id
                                    val name = kw.name
                                    if (id != null && !name.isNullOrBlank()) CloudWord(id, name, 0) else null
                                }.minWithOrNull(
                                    compareBy<CloudWord> { !it.name.equals(trimmed, ignoreCase = true) }
                                        .thenBy { it.name.length },
                                )
                        } catch (ex: CancellationException) {
                            throw ex
                        } catch (ex: Exception) {
                            Timber.w(ex, "Keyword search failed")
                            null
                        }
                    if (best == null) {
                        cloudJob?.cancel()
                        _state.update { it.copy(cloud = null, history = emptyList(), noMatch = trimmed) }
                        return@launchIO
                    }
                    val current = (state.value.cloud as? DataLoadingState.Success)?.data?.center
                    if (current?.id == best.id) {
                        _state.update { it.copy(focusCloud = immediate) }
                    } else {
                        _state.update { it.copy(history = emptyList(), noMatch = null) }
                        loadCloud(best, focusCloud = immediate)
                    }
                }
        }

        /** The cloud has taken focus */
        fun cloudFocused() {
            _state.update { it.copy(focusCloud = false) }
        }

        /**
         * Put [word], chosen in the current cloud, in the middle. Back returns to the current one.
         */
        fun select(word: CloudWord) {
            val current = (state.value.cloud as? DataLoadingState.Success)?.data?.center
            // The search box changes to this word, which shouldn't start another search
            lastQuery = word.name.trim()
            _state.update {
                it.copy(
                    history = if (current != null) it.history + current else it.history,
                    chosenWord = word,
                )
            }
            loadCloud(word, focusCloud = true)
        }

        /**
         * Go back to the previous word
         *
         * @return whether there was one
         */
        fun back(): Boolean {
            val previous = state.value.history.lastOrNull() ?: return false
            lastQuery = previous.name.trim()
            _state.update { it.copy(history = it.history.dropLast(1), chosenWord = previous) }
            loadCloud(previous, focusCloud = true)
            return true
        }

        private fun loadCloud(
            word: CloudWord,
            focusCloud: Boolean,
        ) {
            cloudJob?.cancel()
            _state.update { it.copy(cloud = DataLoadingState.Loading, focusCloud = focusCloud) }
            cloudJob =
                viewModelScope.launchIO {
                    val result =
                        try {
                            DataLoadingState.Success(buildCloud(word))
                        } catch (ex: CancellationException) {
                            throw ex
                        } catch (ex: Exception) {
                            Timber.w(ex, "Could not build keyword cloud for %s", word.id)
                            DataLoadingState.Error(ex)
                        }
                    _state.update { it.copy(cloud = result) }
                }
        }

        private suspend fun buildCloud(word: CloudWord): KeywordCloud =
            coroutineScope {
                val searchApi = seerrService.api.searchApi
                val moviesApi = seerrService.api.moviesApi
                val permits = Semaphore(CONCURRENT_REQUESTS)

                suspend fun movieCount(keywordId: Int): Int =
                    permits.withPermit {
                        try {
                            searchApi.discoverMoviesGet(page = 1, keywords = keywordId.toString()).totalResults ?: 0
                        } catch (ex: CancellationException) {
                            throw ex
                        } catch (ex: Exception) {
                            0
                        }
                    }

                val family =
                    async {
                        try {
                            searchApi
                                .searchKeywordGet(query = word.name)
                                .results
                                .orEmpty()
                                .mapNotNull { kw ->
                                    val id = kw.id
                                    val name = kw.name
                                    if (id != null && id != word.id && !name.isNullOrBlank()) id to name else null
                                }.take(MAX_FAMILY)
                        } catch (ex: CancellationException) {
                            throw ex
                        } catch (ex: Exception) {
                            emptyList()
                        }
                    }

                val first = searchApi.discoverMoviesGet(page = 1, keywords = word.id.toString())
                val total = first.totalResults ?: 0
                val morePages =
                    (2..SAMPLE_PAGES)
                        .filter { it <= (first.totalPages ?: 1) }
                        .map { page ->
                            async {
                                permits.withPermit {
                                    searchApi.discoverMoviesGet(page = page, keywords = word.id.toString()).results.orEmpty()
                                }
                            }
                        }
                val movieIds =
                    (first.results.orEmpty() + morePages.awaitAll().flatten())
                        .map { it.id }
                        .distinct()

                val keywordsByMovie =
                    movieIds
                        .map { movieId ->
                            async {
                                permits.withPermit {
                                    try {
                                        moviesApi.movieMovieIdGet(movieId = movieId).keywords.orEmpty()
                                    } catch (ex: CancellationException) {
                                        throw ex
                                    } catch (ex: Exception) {
                                        emptyList()
                                    }
                                }
                            }
                        }.awaitAll()

                val together = HashMap<Int, Int>()
                val names = HashMap<Int, String>()
                keywordsByMovie.forEach { keywords ->
                    keywords
                        .mapNotNull { kw ->
                            val id = kw.id
                            val name = kw.name
                            if (id != null && id != word.id && !name.isNullOrBlank() && name !in IGNORED) id to name else null
                        }.distinctBy { it.first }
                        .forEach { (id, name) ->
                            together[id] = (together[id] ?: 0) + 1
                            names[id] = name
                        }
                }
                val familyWords = family.await().filter { it.second !in IGNORED }
                familyWords.forEach { (id, name) -> names[id] = name }

                val candidates =
                    (
                        together.entries
                            .filter { it.value >= MIN_TOGETHER }
                            .sortedByDescending { it.value }
                            .take(MAX_CANDIDATES)
                            .map { it.key } + familyWords.map { it.first }
                    ).distinct()
                val counts = candidates.map { id -> async { id to movieCount(id) } }.awaitAll().toMap()

                val familyIds = familyWords.map { it.first }.toSet()
                val related =
                    candidates
                        .mapNotNull { id ->
                            val count = counts[id] ?: 0
                            if (count <= 0) return@mapNotNull null
                            // A name match counts as appearing together at least a couple of times
                            val seen = maxOf(together[id] ?: 0, if (id in familyIds) MIN_TOGETHER else 0)
                            CloudWord(
                                id = id,
                                name = names[id] ?: return@mapNotNull null,
                                movieCount = count,
                                relatedness = seen.toFloat() / (count + SMOOTHING),
                            )
                        }.sortedByDescending { it.relatedness }
                        .take(MAX_WORDS)

                KeywordCloud(
                    center = word.copy(movieCount = total, relatedness = 1f),
                    related = related,
                )
            }

        companion object {
            private const val MAX_FAMILY = 6

            // 3 pages of the most popular movies with the keyword
            private const val SAMPLE_PAGES = 3
            private const val CONCURRENT_REQUESTS = 8
            private const val MIN_TOGETHER = 2
            private const val MAX_CANDIDATES = 40
            private const val MAX_WORDS = 20

            // Keeps keywords on only a few movies from outranking common, clearly related ones
            private const val SMOOTHING = 50f

            // Tags about the credits rather than the story
            private val IGNORED = setOf("aftercreditsstinger", "duringcreditsstinger")
        }
    }
