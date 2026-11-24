package com.github.damontecres.wholphin.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.filter.DiscoverFilter
import com.github.damontecres.wholphin.ui.components.Button
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.SearchEditTextBox
import com.github.damontecres.wholphin.ui.components.VoiceSearchButton
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.util.ResArgStringProvider
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.DiscoverRequestType
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * Search TMDB keywords and explore them as a cloud: the chosen keyword in the middle, related
 * keywords around it sized by how many movies have them. Choosing a word moves it to the middle.
 */
@Composable
fun KeywordSearchPage(
    modifier: Modifier = Modifier,
    viewModel: KeywordSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val seerrActive by viewModel.seerrActive.collectAsState(true)
    val keyboardController = LocalSoftwareKeyboardController.current

    var query by rememberSaveable { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    val textFieldFocusRequester = remember { FocusRequester() }
    val centerFocusRequester = remember { FocusRequester() }
    var focusedWord by remember { mutableStateOf<CloudWord?>(null) }
    val showVoiceSearchButton by viewModel.showVoiceSearchButton.collectAsState(true)

    LaunchedEffect(query) { viewModel.search(query) }
    // Show the word in the middle after one is chosen in the cloud or Back returns to one
    LaunchedEffect(state.chosenWord) {
        state.chosenWord?.let { query = it.name }
    }
    LifecycleResumeEffect(Unit) {
        onPauseOrDispose { viewModel.voiceInputManager.stopListening() }
    }
    LaunchedEffect(Unit) {
        if (state.cloud == null) textFieldFocusRequester.tryRequestFocus()
    }
    BackHandler(enabled = state.history.isNotEmpty()) { viewModel.back() }

    if (!seerrActive) {
        Text(
            text = stringResource(R.string.keyword_search_needs_seerr),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth().padding(top = 64.dp),
        )
        return
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.padding(horizontal = 32.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (showVoiceSearchButton) {
                VoiceSearchButton(
                    onSpeechResult = { spokenText ->
                        query = spokenText
                        viewModel.search(spokenText, immediate = true)
                    },
                    voiceInputManager = viewModel.voiceInputManager,
                )
            }
            SearchEditTextBox(
                value = query,
                onValueChange = {
                    isSearchActive = true
                    query = it
                },
                onSearchClick = {
                    keyboardController?.hide()
                    viewModel.search(query, immediate = true)
                },
                readOnly = !isSearchActive,
                modifier =
                    Modifier
                        .widthIn(max = 480.dp)
                        .focusRequester(textFieldFocusRequester)
                        .onFocusChanged { if (!it.isFocused) isSearchActive = false }
                        .onPreviewKeyEvent { event ->
                            val isActivationKey = event.key in listOf(Key.DirectionCenter, Key.Enter)
                            if (event.type == KeyEventType.KeyUp && isActivationKey && !isSearchActive) {
                                isSearchActive = true
                                keyboardController?.show()
                                true
                            } else {
                                false
                            }
                        },
            )
            val center = (state.cloud as? DataLoadingState.Success)?.data?.center
            if (center != null) {
                Button(
                    onClick = {
                        viewModel.navigationManager.navigateTo(keywordGrid(center, isTv = false))
                    },
                ) {
                    Text(
                        text =
                            pluralStringResource(
                                R.plurals.keyword_movies_button,
                                center.movieCount,
                                center.movieCount,
                                center.name,
                            ),
                    )
                }
                Button(
                    onClick = {
                        viewModel.navigationManager.navigateTo(keywordGrid(center, isTv = true))
                    },
                ) {
                    Text(text = stringResource(R.string.keyword_tv_button))
                }
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
        ) {
            when (val cloud = state.cloud) {
                null -> {
                    Text(
                        text =
                            state.noMatch?.let { stringResource(R.string.keyword_no_match, it) }
                                ?: stringResource(R.string.keyword_search_hint),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                DataLoadingState.Pending,
                DataLoadingState.Loading,
                -> {
                    // Taking focus would pull it out of the search box while typing
                    LoadingPage(Modifier.fillMaxSize(), focusEnabled = false)
                }

                is DataLoadingState.Error -> {
                    ErrorMessage(cloud.message, cloud.exception, Modifier.fillMaxSize())
                }

                is DataLoadingState.Success -> {
                    // Wait for the new cloud, not the one still showing while it loads
                    LaunchedEffect(cloud.data.center.id, state.focusCloud, state.refreshing) {
                        if (state.focusCloud && !state.refreshing) {
                            centerFocusRequester.tryRequestFocus()
                            viewModel.cloudFocused()
                        }
                    }
                    WordCloud(
                        cloud = cloud.data,
                        centerFocusRequester = centerFocusRequester,
                        onFocusWord = { focusedWord = it },
                        onClickWord = { word ->
                            if (word.id == cloud.data.center.id) {
                                viewModel.navigationManager.navigateTo(keywordGrid(word, isTv = false))
                            } else {
                                viewModel.select(word)
                            }
                        },
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .alpha(if (state.refreshing) .4f else 1f),
                    )
                }
            }
        }

        // What the focused word is and what selecting it does
        val cloud = (state.cloud as? DataLoadingState.Success)?.data
        val word = focusedWord?.takeIf { cloud != null }
        Text(
            text =
                when {
                    word == null || cloud == null -> {
                        ""
                    }

                    word.id == cloud.center.id -> {
                        pluralStringResource(R.plurals.keyword_center_status, word.movieCount, word.name, word.movieCount)
                    }

                    else -> {
                        pluralStringResource(R.plurals.keyword_word_status, word.movieCount, word.name, word.movieCount)
                    }
                },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

private fun keywordGrid(
    word: CloudWord,
    isTv: Boolean,
): Destination =
    Destination.DiscoverMoreResult(
        type = if (isTv) DiscoverRequestType.DISCOVER_TV else DiscoverRequestType.DISCOVER_MOVIES,
        startIndex = 0,
        initialFilter = DiscoverFilter(keywordIds = listOf(word.id)),
        titleOverride = ResArgStringProvider(R.string.explore_keyword_title, word.name),
    )

private const val CENTER_FONT = 52f
private const val MIN_FONT = 18f
private const val MAX_FONT = 40f

/**
 * The words of a [KeywordCloud] laid out around the middle one, the most related nearest
 */
@Composable
fun WordCloud(
    cloud: KeywordCloud,
    centerFocusRequester: FocusRequester,
    onFocusWord: (CloudWord) -> Unit,
    onClickWord: (CloudWord) -> Unit,
    modifier: Modifier = Modifier,
) {
    val words = remember(cloud) { listOf(cloud.center) + cloud.related }
    val sizes =
        remember(cloud) {
            val logs = cloud.related.map { ln(it.movieCount.toFloat().coerceAtLeast(1f)) }
            val low = logs.minOrNull() ?: 0f
            val high = logs.maxOrNull() ?: 1f
            listOf(CENTER_FONT) +
                logs.map { value ->
                    val fraction = if (high > low) (value - low) / (high - low) else .5f
                    MIN_FONT + fraction * (MAX_FONT - MIN_FONT)
                }
        }
    Layout(
        content = {
            words.forEachIndexed { index, word ->
                CloudWordChip(
                    word = word,
                    fontSize = sizes[index],
                    isCenter = index == 0,
                    onFocused = { onFocusWord.invoke(word) },
                    onClick = { onClickWord.invoke(word) },
                    modifier = if (index == 0) Modifier.focusRequester(centerFocusRequester) else Modifier,
                )
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val gap = 12.dp.roundToPx()
        val placed = mutableListOf<IntRect>()
        val positions =
            placeables.mapIndexed { index, placeable ->
                spiralPosition(index, placeable.width, placeable.height, width, height, gap, placed)
                    ?.also { placed.add(IntRect(it.first, it.second, it.first + placeable.width, it.second + placeable.height)) }
            }
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                // A word that doesn't fit is left out of view rather than overlapping another
                val position = positions[index] ?: (-width to -height)
                placeable.place(position.first, position.second)
            }
        }
    }
}

/**
 * Walk outwards from the middle on a spiral until the word fits without touching another.
 * Each word starts at a different angle so they spread around the middle.
 */
private fun spiralPosition(
    index: Int,
    wordWidth: Int,
    wordHeight: Int,
    width: Int,
    height: Int,
    gap: Int,
    placed: List<IntRect>,
): Pair<Int, Int>? {
    val centerX = width / 2
    val centerY = height / 2
    val startAngle = index * 2.39996 // the golden angle in radians
    var step = 0
    while (step < 3000) {
        val t = step * 0.1
        val radius = 6.0 * t
        val angle = startAngle + t
        // Wider than tall, like the screen
        val x = (centerX + radius * 1.8 * cos(angle) - wordWidth / 2.0).toInt()
        val y = (centerY + radius * sin(angle) - wordHeight / 2.0).toInt()
        step++
        if (x < 0 || y < 0 || x + wordWidth > width || y + wordHeight > height) {
            if (radius * 1.8 > width && radius > height) break
            continue
        }
        val rect = IntRect(x - gap, y - gap, x + wordWidth + gap, y + wordHeight + gap)
        if (placed.none { it.overlaps(rect) }) return x to y
    }
    return null
}

@Composable
private fun CloudWordChip(
    word: CloudWord,
    fontSize: Float,
    isCenter: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val background =
        when {
            focused -> MaterialTheme.colorScheme.onSurface
            isCenter -> MaterialTheme.colorScheme.primary.copy(alpha = .25f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .0f)
        }
    val color =
        when {
            focused -> MaterialTheme.colorScheme.inverseOnSurface
            isCenter -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        }
    Text(
        text = word.name,
        fontSize = fontSize.sp,
        fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
        color = color,
        maxLines = 1,
        modifier =
            modifier
                .onFocusChanged { if (it.isFocused) onFocused.invoke() }
                .background(background, RoundedCornerShape(12.dp))
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}
