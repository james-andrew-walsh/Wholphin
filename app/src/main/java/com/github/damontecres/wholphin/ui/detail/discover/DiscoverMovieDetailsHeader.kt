package com.github.damontecres.wholphin.ui.detail.discover

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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.seerr.model.MovieDetails
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.DiscoverRating
import com.github.damontecres.wholphin.data.model.MovieRatings
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.jellyfinId
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.components.GenreText
import com.github.damontecres.wholphin.ui.components.HeaderUtils
import com.github.damontecres.wholphin.ui.components.OverviewText
import com.github.damontecres.wholphin.ui.components.QuickDetailsText
import com.github.damontecres.wholphin.ui.components.TitleOrLogo
import com.github.damontecres.wholphin.ui.formatDuration
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.letNotEmpty
import com.github.damontecres.wholphin.ui.listToDotString
import com.github.damontecres.wholphin.ui.roundMinutes
import com.github.damontecres.wholphin.util.ExceptionHandler
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.ImageType
import java.util.Locale
import kotlin.time.Duration.Companion.minutes

/**
 * A person to open from the "Directed by" line, by their TMDB id
 */
internal fun directorItem(
    id: Int,
    name: String,
) = DiscoverItem(
    id = id,
    type = SeerrItemType.PERSON,
    title = name,
    subtitle = null,
    overview = null,
    availability = SeerrAvailability.UNKNOWN,
    releaseDate = null,
    posterUrl = null,
    backDropUrl = null,
    logoUrl = null,
    jellyfinItemId = null,
)

@Composable
fun DiscoverMovieDetailsHeader(
    preferences: UserPreferences,
    movie: MovieDetails,
    rating: DiscoverRating?,
    bringIntoViewRequester: BringIntoViewRequester,
    overviewOnClick: () -> Unit,
    showLogo: Boolean,
    modifier: Modifier = Modifier,
    ratings: MovieRatings? = null,
    onClickDirector: (DiscoverItem) -> Unit = {},
    directorFocusRequester: FocusRequester? = null,
) {
    val imageUrlService = LocalImageUrlService.current
    val logoImageUrl =
        remember(movie) {
            movie.mediaInfo?.jellyfinId?.let {
                imageUrlService.getItemImageUrl(it, ImageType.LOGO)
            }
        }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        TitleOrLogo(
            title = movie.title,
            logoImageUrl = logoImageUrl,
            showLogo = showLogo,
            modifier =
                Modifier
                    .fillMaxWidth(.75f)
                    .padding(start = HeaderUtils.startPadding),
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(.60f),
        ) {
            val resources = LocalResources.current
            val details =
                remember(movie, rating, ratings, resources) {
                    buildList {
                        movie.releaseDate?.let(::add)
                        movie.runtime
                            ?.toDouble()
                            ?.minutes
                            ?.roundMinutes
                            ?.let { add(resources.formatDuration(it)) }
                        val release =
                            movie.releases
                                ?.results
                                ?.firstOrNull { it.iso31661 == Locale.getDefault().country }
                                ?: movie.releases
                                    ?.results
                                    ?.firstOrNull { it.iso31661 == Locale.US.country }
                                ?: movie.releases
                                    ?.results
                                    ?.firstOrNull()

                        release
                            ?.releaseDates
                            ?.firstOrNull()
                            ?.certification
                            ?.takeIf { it.isNotNullOrBlank() }
                            ?.let(::add)
                    }.let {
                        // Anything shown in RatingChips isn't repeated here. They don't have the
                        // audience rating, which is Rotten Tomatoes' and not the IMDb score.
                        listToDotString(
                            it,
                            rating?.audienceRating,
                            rating?.criticRating?.toFloat().takeUnless { ratings?.showsCriticsScore == true },
                        )
                    }
                }

            QuickDetailsText(details, Modifier.padding(start = HeaderUtils.startPadding))
            movie.genres?.mapNotNull { it.name }?.letNotEmpty {
                GenreText(it, Modifier.padding(start = HeaderUtils.startPadding))
            }

            val tagline = remember { movie.tagline?.takeIf { it.isNotNullOrBlank() } }
            tagline?.let {
                Text(
                    text = tagline,
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(start = HeaderUtils.startPadding),
                )
            }

            // Description
            movie.overview?.let { overview ->
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

            // Each director's name opens a page of the films they directed
            val directors =
                remember(movie.credits?.crew) {
                    movie.credits
                        ?.crew
                        ?.filter { it.job == "Director" && it.id != null && it.name.isNotNullOrBlank() }
                        ?.distinctBy { it.id }
                        ?.map { directorItem(it.id!!, it.name!!) }
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
                            overview = director.title!!,
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
