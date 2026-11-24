package com.github.damontecres.wholphin.ui.detail.movie

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ChosenStreams
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.MovieRatings
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.components.GenreText
import com.github.damontecres.wholphin.ui.components.HeaderUtils
import com.github.damontecres.wholphin.ui.components.OverviewText
import com.github.damontecres.wholphin.ui.components.QuickDetails
import com.github.damontecres.wholphin.ui.components.TitleOrLogo
import com.github.damontecres.wholphin.ui.components.VideoStreamDetails
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.letNotEmpty
import com.github.damontecres.wholphin.util.ExceptionHandler
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.PersonKind

@Composable
fun MovieDetailsHeader(
    preferences: UserPreferences,
    movie: BaseItem,
    chosenStreams: ChosenStreams?,
    bringIntoViewRequester: BringIntoViewRequester,
    overviewOnClick: () -> Unit,
    modifier: Modifier = Modifier,
    ratings: MovieRatings? = null,
    onClickDirector: (BaseItemPerson) -> Unit = {},
    directorFocusRequester: FocusRequester? = null,
) {
    val dto = movie.data
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        // Title
        TitleOrLogo(
            item = movie,
            showLogo = preferences.appPreferences.interfacePreferences.showLogos,
            modifier =
                Modifier
                    .fillMaxWidth(.75f)
                    .padding(start = HeaderUtils.startPadding),
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(.60f),
        ) {
            val quickDetails = movie.ui.quickDetails
            QuickDetails(
                // Anything shown in RatingChips isn't repeated here
                quickDetails.copy(
                    communityRating = quickDetails.communityRating.takeUnless { ratings?.showsImdbScore == true },
                    criticRating = quickDetails.criticRating.takeUnless { ratings?.showsCriticsScore == true },
                ),
                movie.timeRemainingOrRuntime,
                Modifier.padding(start = HeaderUtils.startPadding),
            )

            dto.genres?.letNotEmpty {
                GenreText(it, Modifier.padding(start = HeaderUtils.startPadding))
            }

            VideoStreamDetails(
                chosenStreams = chosenStreams,
                numberOfVersions = movie.data.mediaSourceCount ?: 0,
                modifier =
                    Modifier.padding(
                        start = HeaderUtils.startPadding,
                        top = 4.dp,
                        bottom = 16.dp,
                    ),
            )
            dto.taglines?.firstOrNull()?.let { tagline ->
                Text(
                    text = tagline,
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(start = HeaderUtils.startPadding),
                )
            }

            // Description
            dto.overview?.let { overview ->
                OverviewText(
                    overview = overview,
                    maxLines = 3,
                    onClick = overviewOnClick,
                    textBoxHeight = Dp.Unspecified,
                    modifier =
                        Modifier.onFocusChanged {
                            if (it.isFocused) {
                                scope.launch(ExceptionHandler()) {
                                    bringIntoViewRequester.bringIntoView()
                                }
                            }
                        },
                )
            }

            // Each director's name opens a page of the films they directed, as on the Discover page
            val directors =
                remember(movie.data.people) {
                    movie.data.people
                        ?.filter { it.type == PersonKind.DIRECTOR && it.name.isNotNullOrBlank() }
                        ?.distinctBy { it.id }
                        .orEmpty()
                }

            if (directors.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.directed_by_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = HeaderUtils.startPadding),
                    )
                    directors.forEachIndexed { index, director ->
                        OverviewText(
                            overview = director.name!!,
                            maxLines = 1,
                            onClick = { onClickDirector.invoke(director) },
                            textBoxHeight = Dp.Unspecified,
                            modifier =
                                Modifier
                                    .then(
                                        if (index == 0 && directorFocusRequester != null) {
                                            Modifier.focusRequester(directorFocusRequester)
                                        } else {
                                            Modifier
                                        },
                                    ).onFocusChanged {
                                        if (it.isFocused) {
                                            scope.launch(ExceptionHandler()) {
                                                bringIntoViewRequester.bringIntoView()
                                            }
                                        }
                                    },
                        )
                    }
                }
            }
        }
    }
}
