package com.github.damontecres.wholphin.ui.detail.discover

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.seerr.model.MovieDetails
import com.github.damontecres.wholphin.api.seerr.model.TvDetails
import com.github.damontecres.wholphin.data.filter.DiscoverFilter
import com.github.damontecres.wholphin.ui.components.Button
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.util.ResArgStringProvider
import com.github.damontecres.wholphin.util.DiscoverRequestType

/**
 * Something on a title's page that leads to more titles: its franchise, a studio or network that
 * made it, or one of its keywords
 */
@Immutable
data class ExploreLink(
    val kind: Kind,
    val id: Int,
    val name: String,
    // Whether a keyword lists TV series rather than movies
    val isTv: Boolean = false,
) {
    enum class Kind(
        @param:StringRes val label: Int,
    ) {
        COLLECTION(R.string.explore_franchise),
        STUDIO(R.string.explore_studio),
        NETWORK(R.string.explore_network),
        KEYWORD(R.string.explore_keyword),
    }

    /**
     * The page listing the titles this leads to
     */
    fun destination(): Destination =
        when (kind) {
            Kind.COLLECTION -> {
                Destination.DiscoverCollection(collectionId = id, name = name)
            }

            Kind.STUDIO -> {
                Destination.DiscoverMoreResult(
                    type = DiscoverRequestType.DISCOVER_MOVIES,
                    startIndex = 0,
                    initialFilter = DiscoverFilter(studioId = id),
                    titleOverride = ResArgStringProvider(R.string.explore_studio_title, name),
                )
            }

            Kind.NETWORK -> {
                Destination.DiscoverMoreResult(
                    type = DiscoverRequestType.DISCOVER_TV,
                    startIndex = 0,
                    initialFilter = DiscoverFilter(tvNetworkId = id),
                    titleOverride = ResArgStringProvider(R.string.explore_network_title, name),
                )
            }

            Kind.KEYWORD -> {
                Destination.DiscoverMoreResult(
                    type = if (isTv) DiscoverRequestType.DISCOVER_TV else DiscoverRequestType.DISCOVER_MOVIES,
                    startIndex = 0,
                    initialFilter = DiscoverFilter(keywordIds = listOf(id)),
                    titleOverride = ResArgStringProvider(R.string.explore_keyword_title, name),
                )
            }
        }
}

/** Studios and keywords can run long, so only the first few are offered */
private const val MAX_STUDIOS = 3
private const val MAX_KEYWORDS = 8

/**
 * A movie's franchise, then the studios that made it, then its keywords
 */
fun exploreLinks(movie: MovieDetails): List<ExploreLink> =
    buildList {
        movie.collection?.let { collection ->
            val id = collection.id
            val name = collection.name
            if (id != null && name.isNotNullOrBlank()) {
                add(ExploreLink(ExploreLink.Kind.COLLECTION, id, name))
            }
        }
        movie.productionCompanies
            ?.mapNotNull { company ->
                val id = company.id
                val name = company.name
                if (id != null && name.isNotNullOrBlank()) ExploreLink(ExploreLink.Kind.STUDIO, id, name) else null
            }?.distinctBy { it.id }
            ?.take(MAX_STUDIOS)
            ?.let(::addAll)
        addAll(keywordLinks(movie.keywords?.map { it.id to it.name }, isTv = false))
    }

/**
 * The networks a series aired on, then its keywords
 */
fun exploreLinks(series: TvDetails): List<ExploreLink> =
    buildList {
        series.networks
            ?.mapNotNull { network ->
                val id = network.id
                val name = network.name
                if (id != null && name.isNotNullOrBlank()) ExploreLink(ExploreLink.Kind.NETWORK, id, name) else null
            }?.distinctBy { it.id }
            ?.let(::addAll)
        addAll(keywordLinks(series.keywords?.map { it.id to it.name }, isTv = true))
    }

private fun keywordLinks(
    keywords: List<Pair<Int?, String?>>?,
    isTv: Boolean,
): List<ExploreLink> =
    keywords
        ?.mapNotNull { (id, name) ->
            if (id != null && name.isNotNullOrBlank()) ExploreLink(ExploreLink.Kind.KEYWORD, id, name, isTv) else null
        }?.distinctBy { it.id }
        ?.take(MAX_KEYWORDS)
        .orEmpty()

/**
 * A row of chips for [ExploreLink]s, each showing what kind of link it is above its name
 */
@Composable
fun ExploreRow(
    links: List<ExploreLink>,
    onClick: (ExploreLink) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Coming back from a link's page, focus returns to the chip that was chosen
    var lastFocused by rememberSaveable { mutableIntStateOf(0) }
    val restoreFocus = remember { FocusRequester() }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.explore_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp),
            modifier = Modifier.focusRestorer(restoreFocus),
        ) {
            itemsIndexed(links, key = { _, link -> "${link.kind}-${link.id}" }) { index, link ->
                Button(
                    onClick = { onClick.invoke(link) },
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    contentHeight = 36.dp,
                    modifier =
                        Modifier
                            .then(
                                if (index == lastFocused.coerceAtMost(links.lastIndex)) {
                                    Modifier.focusRequester(restoreFocus)
                                } else {
                                    Modifier
                                },
                            ).onFocusChanged { if (it.isFocused) lastFocused = index },
                ) {
                    Text(
                        text = stringResource(link.kind.label),
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalContentColor.current.copy(alpha = .7f),
                        maxLines = 1,
                        modifier =
                            Modifier
                                .align(Alignment.CenterVertically)
                                .padding(end = 8.dp),
                    )
                    Text(
                        text = link.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
        }
    }
}
