package com.github.damontecres.wholphin.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.MovieRatings

private val ChipPadding = PaddingValues(horizontal = 12.dp)
private val ChipContentHeight = 32.dp

/**
 * A row of ratings which can be selected to read the page they came from
 *
 * There is a chip for each rating with a URL, showing its score if there is one.
 *
 * @param onClickImdb called with [MovieRatings.imdbUrl]
 * @param onClickRottenTomatoes called with [MovieRatings.rottenTomatoesUrl]
 * @param onFocused called when a chip gets focus
 */
@Composable
fun RatingChips(
    ratings: MovieRatings,
    imdbFocusRequester: FocusRequester,
    rottenTomatoesFocusRequester: FocusRequester,
    onClickImdb: (String) -> Unit,
    onClickRottenTomatoes: (String) -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    showImdb: Boolean = true,
    showRottenTomatoes: Boolean = true,
) {
    val imdbUrl = ratings.imdbUrl?.takeIf { showImdb }
    val rottenTomatoesUrl = ratings.rottenTomatoesUrl?.takeIf { showRottenTomatoes }
    if (imdbUrl == null && rottenTomatoesUrl == null) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.focusGroup(),
    ) {
        imdbUrl?.let { url ->
            RatingChip(
                text =
                    ratings.imdbScore?.let { stringResource(R.string.rating_imdb_score, it) }
                        ?: stringResource(R.string.rating_imdb),
                onClick = { onClickImdb.invoke(url) },
                onFocused = onFocused,
                focusRequester = imdbFocusRequester,
            )
        }
        rottenTomatoesUrl?.let { url ->
            RatingChip(
                text =
                    ratings.rottenTomatoesCritics?.let { "$it%" }
                        ?: stringResource(R.string.rating_rotten_tomatoes),
                icon =
                    when (ratings.rottenTomatoesFresh) {
                        true -> R.drawable.ic_rotten_tomatoes_fresh
                        false -> R.drawable.ic_rotten_tomatoes_rotten
                        null -> null
                    },
                onClick = { onClickRottenTomatoes.invoke(url) },
                onFocused = onFocused,
                focusRequester = rottenTomatoesFocusRequester,
            )
        }
    }
}

@Composable
private fun RatingChip(
    text: String,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    focusRequester: FocusRequester,
    @DrawableRes icon: Int? = null,
) {
    Button(
        onClick = onClick,
        contentPadding = ChipPadding,
        contentHeight = ChipContentHeight,
        modifier =
            Modifier
                .onFocusChanged { if (it.isFocused) onFocused.invoke() }
                .focusRequester(focusRequester),
    ) {
        icon?.let {
            Icon(
                painter = painterResource(it),
                contentDescription = stringResource(R.string.rating_rotten_tomatoes),
                tint = Color.Unspecified,
                modifier =
                    Modifier
                        .padding(end = 6.dp)
                        .size(18.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
        )
    }
}
