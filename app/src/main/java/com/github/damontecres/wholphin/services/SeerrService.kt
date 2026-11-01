package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.api.seerr.SeerrApiClient
import com.github.damontecres.wholphin.api.seerr.infrastructure.ClientException
import com.github.damontecres.wholphin.api.seerr.model.CreditCast
import com.github.damontecres.wholphin.api.seerr.model.CreditCrew
import com.github.damontecres.wholphin.api.seerr.model.DiscoverGenresliderMovieGet200ResponseInner
import com.github.damontecres.wholphin.api.seerr.model.MediaInfo
import com.github.damontecres.wholphin.api.seerr.model.MovieDetails
import com.github.damontecres.wholphin.api.seerr.model.MovieResult
import com.github.damontecres.wholphin.api.seerr.model.RootFolder
import com.github.damontecres.wholphin.api.seerr.model.SearchGet200ResponseResultsInner
import com.github.damontecres.wholphin.api.seerr.model.ServiceProfile
import com.github.damontecres.wholphin.api.seerr.model.TvDetails
import com.github.damontecres.wholphin.api.seerr.model.TvResult
import com.github.damontecres.wholphin.data.filter.DiscoverFilter
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.MovieRatings
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.data.model.SeerrPermission
import com.github.damontecres.wholphin.data.model.hasPermission
import com.github.damontecres.wholphin.data.model.isInLibrary
import com.github.damontecres.wholphin.data.model.isRemovable
import com.github.damontecres.wholphin.data.model.serviceVersions
import com.github.damontecres.wholphin.ui.components.Genre
import com.github.damontecres.wholphin.ui.detail.discover.SeerrProfile
import com.github.damontecres.wholphin.ui.detail.discover.SeerrRequestData
import com.github.damontecres.wholphin.ui.detail.discover.SeerrRootFolder
import com.github.damontecres.wholphin.ui.detail.discover.getDiscoverRating
import com.github.damontecres.wholphin.ui.discover.toUUID
import com.github.damontecres.wholphin.ui.formatBytes
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.toLocalDate
import com.github.damontecres.wholphin.util.TrendingMoviesWeekRequestHandler
import com.github.damontecres.wholphin.util.TrendingTvWeekRequestHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

typealias SeerrSearchResult = SearchGet200ResponseResultsInner

private const val HTTP_NOT_FOUND = 404
private const val LIBRARY_PAGE_SIZE = 500

/**
 * How trying to remove a request ended. Only [Removed] has taken it out of Seerr.
 */
enum class RemoveRequestResult {
    Removed,

    /** It's in the library or Seerr says it is, nothing was touched */
    InLibrary,

    /** It couldn't be told that it's safe, nothing was touched */
    CheckFailed,

    /** Radarr or Sonarr may still have it, so Seerr's record was kept for it not to be orphaned */
    ServiceFailed,

    /** It's gone from Radarr or Sonarr but Seerr still has the record, trying again finishes this */
    RecordFailed,
}

/**
 * A movie or series that was just removed from Seerr
 */
data class RemovedMedia(
    val type: SeerrItemType,
    val tmdbId: Int,
)

/**
 * What identifies a movie or series, for looking for it in Jellyfin
 */
private class LibraryLookup(
    val kind: BaseItemKind,
    val media: MediaInfo?,
    val imdbId: String?,
)

/**
 * Main access for the current Seerr server (if any)
 *
 * Exposes a [SeerrApiClient] for queries
 */
@Singleton
class SeerrService
    @Inject
    constructor(
        private val seerApi: SeerrApi,
        private val seerrServerRepository: SeerrServerRepository,
        private val imageUrlService: ImageUrlService,
        private val jellyfinApi: ApiClient,
    ) {
        val api: SeerrApiClient get() = seerApi.api

        val active get() = seerrServerRepository.active

        private val _removedMedia = MutableSharedFlow<RemovedMedia>()

        /**
         * Tells pages further back that a request has gone, whichever page removed it
         */
        val removedMedia: SharedFlow<RemovedMedia> = _removedMedia

        suspend fun search(
            query: String,
            page: Int = 1,
        ): List<SeerrSearchResult> =
            api.searchApi
                .searchGet(query = query, page = page)
                .results
                .orEmpty()

        suspend fun discoverTv(page: Int = 1): List<DiscoverItem> =
            api.searchApi
                .discoverTvGet(page = page)
                .results
                ?.map { createDiscoverItem(it) }
                .orEmpty()

        suspend fun discoverMovies(page: Int = 1): List<DiscoverItem> =
            api.searchApi
                .discoverMoviesGet(page = page)
                .results
                ?.map { createDiscoverItem(it) }
                .orEmpty()

        suspend fun trending(page: Int = 1): List<DiscoverItem> =
            api.searchApi
                .discoverTrendingGet(page = page)
                .results
                ?.map { createDiscoverItem(it) }
                .orEmpty()

        suspend fun discoverMoviesBy(
            filter: DiscoverFilter,
            page: Int = 1,
        ): List<DiscoverItem> =
            filter
                .discoverMovies(api.searchApi, page)
                .results
                ?.map { createDiscoverItem(it) }
                .orEmpty()

        suspend fun discoverTvBy(
            filter: DiscoverFilter,
            page: Int = 1,
        ): List<DiscoverItem> =
            filter
                .discoverTv(api.searchApi, page)
                .results
                ?.map { createDiscoverItem(it) }
                .orEmpty()

        suspend fun trendingMoviesWeek(page: Int = 1): List<DiscoverItem> =
            TrendingMoviesWeekRequestHandler
                .execute(seerApi, page)
                .items
                .map { createDiscoverItem(it) }

        suspend fun trendingTvWeek(page: Int = 1): List<DiscoverItem> =
            TrendingTvWeekRequestHandler
                .execute(seerApi, page)
                .items
                .map { createDiscoverItem(it) }

        suspend fun movieGenres(): List<Genre> = createGenres(api.searchApi.discoverGenresliderMovieGet())

        suspend fun tvGenres(): List<Genre> = createGenres(api.searchApi.discoverGenresliderTvGet())

        private suspend fun createGenres(genres: List<DiscoverGenresliderMovieGet200ResponseInner>): List<Genre> =
            genres
                .filter { it.id != null && it.name != null }
                .map {
                    val imageUrl =
                        it.backdrops?.randomOrNull()?.let { path ->
                            createImageUrl(
                                imageType = ImageType.BACKDROP,
                                path = path,
                                mediaInfo = null,
                                // Don't need high resolution
                                backdropWidth = 1280,
                                backdropHeight = 720,
                            )
                        }
                    Genre(
                        id = it.id!!.toUUID(),
                        name = it.name!!,
                        imageUrl = imageUrl,
                    )
                }

        suspend fun upcomingMovies(page: Int = 1): List<DiscoverItem> =
            api.searchApi
                .discoverMoviesUpcomingGet(page = page)
                .results
                ?.map { createDiscoverItem(it) }
                .orEmpty()

        suspend fun upcomingTv(page: Int = 1): List<DiscoverItem> =
            api.searchApi
                .discoverTvUpcomingGet(page = page)
                .results
                ?.map { createDiscoverItem(it) }
                .orEmpty()

        /**
         * Get [DiscoverItem]s similar to the JF items such as movies, series, or people
         *
         * If Seerr integration is not active, this short circuits to return null
         *
         * @return the discovered items or null if no Seerr server configured
         */
        suspend fun similar(item: BaseItem): List<DiscoverItem>? =
            if (active.first()) {
                item.data.providerIds
                    ?.get("Tmdb")
                    ?.toIntOrNull()
                    ?.let {
                        when (item.type) {
                            BaseItemKind.MOVIE -> {
                                api.moviesApi
                                    .movieMovieIdSimilarGet(movieId = it)
                                    .results
                                    ?.map { createDiscoverItem(it) }
                            }

                            BaseItemKind.SERIES, BaseItemKind.SEASON, BaseItemKind.EPISODE -> {
                                api.tvApi
                                    .tvTvIdSimilarGet(tvId = it)
                                    .results
                                    ?.map { createDiscoverItem(it) }
                            }

                            BaseItemKind.PERSON -> {
                                personCredits(it)
                            }

                            else -> {
                                null
                            }
                        }
                    }.orEmpty()
            } else {
                null
            }

        /**
         * Get what can be found out about a movie's ratings. Failing leaves that part out instead
         * of throwing, and IMDb's page doesn't need Seerr at all.
         *
         * ratingscombined fails as a whole when either of its sources does, so that falls back to
         * just Rotten Tomatoes.
         */
        suspend fun movieRatings(
            tmdbId: Int?,
            imdbId: String?,
        ): MovieRatings {
            val ratings = MovieRatings(imdbUrl = MovieRatings.imdbUrlFor(imdbId))
            if (tmdbId == null || getDiscoverRating(tmdbId) { active.first() } != true) {
                return ratings
            }
            val combined = getDiscoverRating(tmdbId) { api.moviesApi.movieMovieIdRatingscombinedGet(tmdbId) }
            val rottenTomatoes =
                if (combined != null) {
                    combined.rt
                } else {
                    getDiscoverRating(tmdbId) { api.moviesApi.movieMovieIdRatingsGet(tmdbId) }
                }
            return ratings.copy(
                imdbScore = combined?.imdb?.criticsScore?.toFloat(),
                imdbUrl = ratings.imdbUrl ?: combined?.imdb?.url?.takeIf { MovieRatings.isAllowedRatingPage(it) },
                rottenTomatoesCritics = rottenTomatoes?.criticsScore,
                rottenTomatoesAudience = rottenTomatoes?.audienceScore,
                rottenTomatoesUrl = rottenTomatoes?.url?.takeIf { MovieRatings.isAllowedRatingPage(it) },
            )
        }

        /**
         * Get the combined movie & TV credits of a person as [DiscoverItem]s
         *
         * The two lists returned by the API are merged, de-duplicated by id since a person can be
         * both cast and crew on the same title, and sorted by release date descending with undated
         * entries last
         */
        suspend fun personCredits(personId: Int): List<DiscoverItem> =
            api.personApi
                .personPersonIdCombinedCreditsGet(personId = personId)
                .let { credits ->
                    val cast = credits.cast?.map { createDiscoverItem(it) }.orEmpty()
                    val crew = credits.crew?.map { createDiscoverItem(it) }.orEmpty()
                    (cast + crew)
                        .distinctBy { it.id }
                        .sortedByDescending { it.releaseDate }
                }

        /**
         * Create a [DiscoverItem] for a person in the library so that the full grid of their
         * credits can be opened from their page
         *
         * @return the item or null if the person has no TMDB id
         */
        fun discoverPerson(item: BaseItem): DiscoverItem? =
            item.data.providerIds
                ?.get("Tmdb")
                ?.toIntOrNull()
                ?.let {
                    DiscoverItem(
                        id = it,
                        type = SeerrItemType.PERSON,
                        title = item.name,
                        subtitle = null,
                        overview = item.data.overview,
                        availability = SeerrAvailability.UNKNOWN,
                        releaseDate = null,
                        posterUrl = null,
                        backDropUrl = null,
                        logoUrl = null,
                        jellyfinItemId = null,
                    )
                }

        suspend fun getTvSeries(item: BaseItem): TvDetails? =
            if (active.first()) {
                item.data.providerIds
                    ?.get("Tmdb")
                    ?.toIntOrNull()
                    ?.let { tvId ->
                        api.tvApi.tvTvIdGet(tvId = tvId)
                    }
            } else {
                null
            }

        /**
         * Take a movie or series out of Radarr or Sonarr and out of Seerr, so it can be requested again
         *
         * This deletes files, that's what Seerr tells Radarr and Sonarr to do. So it first looks at
         * what is true right now and touches nothing unless it's sure. An import can reach Jellyfin
         * before Seerr's next scan notices, which is why Seerr's word for it isn't enough.
         *
         * A torrent stays in the download client, Radarr and Sonarr only stop tracking it.
         */
        suspend fun removeRequest(
            type: SeerrItemType,
            tmdbId: Int,
        ): RemoveRequestResult {
            val lookup =
                try {
                    fetchLookup(type, tmdbId)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(ex, "Error fetching %s %s before removing it", type, tmdbId)
                    return RemoveRequestResult.CheckFailed
                }
            val media = lookup.media ?: return removed(type, tmdbId)
            if (isInLibrary(media)) return RemoveRequestResult.InLibrary
            if (!isRemovable(media)) return RemoveRequestResult.CheckFailed
            try {
                if (isInJellyfin(lookup, tmdbId)) return RemoveRequestResult.InLibrary
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.e(ex, "Error looking for %s %s in Jellyfin", type, tmdbId)
                return RemoveRequestResult.CheckFailed
            }

            val mediaId = media.id.toString()
            for (is4k in serviceVersions(media)) {
                try {
                    api.mediaApi.mediaMediaIdFileDelete(mediaId = mediaId, is4k = is4k)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    // Seerr answers with success when Radarr or Sonarr doesn't have it, so all this
                    // can tolerate is Seerr's own record having gone in the meantime
                    if (ex !is ClientException || ex.statusCode != HTTP_NOT_FOUND) {
                        Timber.e(ex, "Error removing %s %s from Radarr or Sonarr", type, tmdbId)
                        return RemoveRequestResult.ServiceFailed
                    }
                }
            }
            try {
                api.mediaApi.mediaMediaIdDelete(mediaId = mediaId)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.e(ex, "Error removing %s %s from Seerr", type, tmdbId)
                return RemoveRequestResult.RecordFailed
            }
            return removed(type, tmdbId)
        }

        private suspend fun removed(
            type: SeerrItemType,
            tmdbId: Int,
        ): RemoveRequestResult {
            _removedMedia.emit(RemovedMedia(type, tmdbId))
            return RemoveRequestResult.Removed
        }

        private suspend fun fetchLookup(
            type: SeerrItemType,
            tmdbId: Int,
        ): LibraryLookup =
            if (type == SeerrItemType.TV) {
                val series = api.tvApi.tvTvIdGet(tvId = tmdbId)
                LibraryLookup(BaseItemKind.SERIES, series.mediaInfo, series.externalIds?.imdbId)
            } else {
                val movie = api.moviesApi.movieMovieIdGet(movieId = tmdbId)
                LibraryLookup(BaseItemKind.MOVIE, movie.mediaInfo, movie.imdbId)
            }

        /**
         * Whether the user's Jellyfin has the movie or series, by anything that identifies it
         *
         * Jellyfin can't be asked for a provider ID, only whether an item has one, so this goes
         * through the library. Searching for the title would be cheaper, but a title spelled
         * differently there would look like it's not in the library and then files get deleted.
         *
         * @throws Exception if any part of the library can't be read
         */
        private suspend fun isInJellyfin(
            lookup: LibraryLookup,
            tmdbId: Int,
        ): Boolean {
            val itemIds =
                listOfNotNull(
                    lookup.media?.jellyfinMediaId?.toUUIDOrNull(),
                    lookup.media?.jellyfinMediaId4k?.toUUIDOrNull(),
                )
            val providerIds =
                listOfNotNull(
                    "tmdb" to tmdbId.toString(),
                    lookup.media?.tvdbId?.let { "tvdb" to it.toString() },
                    lookup.imdbId?.let { "imdb" to it },
                )
            var seen = 0
            while (true) {
                val page =
                    jellyfinApi.itemsApi
                        .getItems(
                            GetItemsRequest(
                                recursive = true,
                                includeItemTypes = listOf(lookup.kind),
                                fields = listOf(ItemFields.PROVIDER_IDS),
                                enableImages = false,
                                enableUserData = false,
                                startIndex = seen,
                                limit = LIBRARY_PAGE_SIZE,
                            ),
                        ).content
                val found =
                    page.items.any { item ->
                        item.id in itemIds ||
                            item.providerIds.orEmpty().any { (name, id) -> (name.lowercase() to id) in providerIds }
                    }
                if (found) return true
                seen += page.items.size
                if (seen >= page.totalRecordCount) return false
                check(page.items.isNotEmpty()) { "Library ended after $seen of ${page.totalRecordCount}" }
            }
        }

        suspend fun createImageUrl(
            imageType: ImageType,
            path: String?,
            mediaInfo: MediaInfo?,
            backdropHeight: Int = 1080,
            backdropWidth: Int = 1920,
        ): String? {
            if (mediaInfo != null) {
                val itemId = mediaInfo.jellyfinId
                if (itemId != null) {
                    return imageUrlService.getItemImageUrl(
                        itemId = itemId,
                        imageType = imageType,
                    )
                }
            }
            return path?.takeIf { it.isNotNullOrBlank() }?.let {
                val current = seerrServerRepository.current.firstOrNull() ?: return null
                val cacheImages = current.serverConfig.cacheImages == true
                val base =
                    if (cacheImages) {
                        current.server.url.removeSuffix("/") + "/imageproxy/tmdb"
                    } else {
                        "https://image.tmdb.org"
                    }
                val prefix =
                    when (imageType) {
                        ImageType.PRIMARY -> "/t/p/w500"
                        ImageType.BACKDROP -> "/t/p/w${backdropWidth}_and_h${backdropHeight}_multi_faces"
                        else -> throw IllegalArgumentException("Image type not supported: $imageType")
                    }
                return "${base}${prefix}$path"
            }
        }

        suspend fun getProfilesAndFolders(type: SeerrItemType): SeerrRequestData {
            var result = SeerrRequestData()
            val current = seerrServerRepository.current.firstOrNull()
            if (current != null && current.config.hasPermission(SeerrPermission.REQUEST_ADVANCED)) {
                if (type == SeerrItemType.MOVIE) {
                    val radarrs = api.serviceApi.serviceRadarrGet()
                    val radarr =
                        radarrs.firstOrNull { it.isDefault && !it.is4k }
                            ?: radarrs.firstOrNull { !it.is4k }
                    val radarr4k =
                        radarrs.firstOrNull { it.isDefault && it.is4k }
                            ?: radarrs.firstOrNull { it.is4k }

                    if (radarr != null && radarr.id != null) {
                        val settings = api.serviceApi.serviceRadarrRadarrIdGet(radarr.id)

                        setProfilesAndFolder(
                            settings.profiles,
                            settings.rootFolders,
                            settings.server?.activeProfileId,
                            settings.server?.activeDirectory,
                        ) { profiles, rootFolders ->
                            result =
                                result.copy(
                                    serverId = radarr.id,
                                    profiles = profiles,
                                    rootFolders = rootFolders,
                                )
                        }
                    }
                    if (radarr4k != null && radarr4k.id != null) {
                        val settings = api.serviceApi.serviceRadarrRadarrIdGet(radarr4k.id)
                        setProfilesAndFolder(
                            settings.profiles,
                            settings.rootFolders,
                            settings.server?.activeProfileId,
                            settings.server?.activeDirectory,
                        ) { profiles, rootFolders ->
                            result =
                                result.copy(
                                    server4kId = radarr4k.id,
                                    profiles4k = profiles,
                                    rootFolders4k = rootFolders,
                                )
                        }
                    }
                } else {
                    val sonarrs = api.serviceApi.serviceSonarrGet()
                    val sonarr =
                        sonarrs.firstOrNull { it.isDefault && !it.is4k }
                            ?: sonarrs.firstOrNull { !it.is4k }
                    val sonarr4k =
                        sonarrs.firstOrNull { it.isDefault && it.is4k }
                            ?: sonarrs.firstOrNull { it.is4k }

                    if (sonarr != null && sonarr.id != null) {
                        val settings = api.serviceApi.serviceSonarrSonarrIdGet(sonarr.id)

                        setProfilesAndFolder(
                            settings.profiles,
                            settings.rootFolders,
                            settings.server?.activeProfileId,
                            settings.server?.activeDirectory,
                        ) { profiles, rootFolders ->
                            result =
                                result.copy(
                                    serverId = sonarr.id,
                                    profiles = profiles,
                                    rootFolders = rootFolders,
                                )
                        }
                    }
                    if (sonarr4k != null && sonarr4k.id != null) {
                        val settings = api.serviceApi.serviceSonarrSonarrIdGet(sonarr4k.id)
                        setProfilesAndFolder(
                            settings.profiles,
                            settings.rootFolders,
                            settings.server?.activeProfileId,
                            settings.server?.activeDirectory,
                        ) { profiles, rootFolders ->
                            result =
                                result.copy(
                                    server4kId = sonarr4k.id,
                                    profiles4k = profiles,
                                    rootFolders4k = rootFolders,
                                )
                        }
                    }
                }
            }
            return result
        }

        private fun setProfilesAndFolder(
            profiles: List<ServiceProfile>?,
            rootFolders: List<RootFolder>?,
            activeProfileId: Int?,
            activeDirectory: String?,
            setter: (List<SeerrProfile>, List<SeerrRootFolder>) -> Unit,
        ) {
            val profiles =
                profiles.orEmpty().mapNotNull {
                    if (it.id != null && it.name != null) {
                        SeerrProfile(it.id, it.name, activeProfileId == it.id)
                    } else {
                        null
                    }
                }
            val rootFolders =
                rootFolders.orEmpty().mapNotNull {
                    if (it.id != null && it.path != null) {
                        val freeSpace = it.freeSpace?.let { formatBytes(it) } ?: ""
                        SeerrRootFolder(it.id, it.path, freeSpace, activeDirectory == it.path)
                    } else {
                        null
                    }
                }
            setter.invoke(profiles, rootFolders)
        }

        suspend fun createDiscoverItem(item: Any): DiscoverItem =
            when (item) {
                is MovieResult -> createDiscoverItem(item)
                is MovieDetails -> createDiscoverItem(item)
                is TvResult -> createDiscoverItem(item)
                is TvDetails -> createDiscoverItem(item)
                is SeerrSearchResult -> createDiscoverItem(item)
                is CreditCast -> createDiscoverItem(item)
                is CreditCrew -> createDiscoverItem(item)
                else -> throw IllegalArgumentException("Unsupported type ${item::class.qualifiedName}")
            }

        suspend fun createDiscoverItem(movie: MovieResult): DiscoverItem =
            DiscoverItem(
                id = movie.id,
                type = SeerrItemType.MOVIE,
                title = movie.title,
                subtitle = null,
                overview = movie.overview,
                availability =
                    SeerrAvailability.from(movie.mediaInfo?.status)
                        ?: SeerrAvailability.UNKNOWN,
                releaseDate = toLocalDate(movie.releaseDate),
                posterUrl = createImageUrl(ImageType.PRIMARY, movie.posterPath, movie.mediaInfo),
                backDropUrl = createImageUrl(ImageType.BACKDROP, movie.backdropPath, movie.mediaInfo),
                logoUrl = createImageUrl(ImageType.LOGO, null, movie.mediaInfo),
                jellyfinItemId = movie.mediaInfo?.jellyfinId,
            )

        suspend fun createDiscoverItem(movie: MovieDetails): DiscoverItem =
            DiscoverItem(
                id = movie.id ?: -1,
                type = SeerrItemType.MOVIE,
                title = movie.title,
                subtitle = null,
                overview = movie.overview,
                availability =
                    SeerrAvailability.from(movie.mediaInfo?.status)
                        ?: SeerrAvailability.UNKNOWN,
                releaseDate = toLocalDate(movie.releaseDate),
                posterUrl = createImageUrl(ImageType.PRIMARY, movie.posterPath, movie.mediaInfo),
                backDropUrl = createImageUrl(ImageType.BACKDROP, movie.backdropPath, movie.mediaInfo),
                logoUrl = createImageUrl(ImageType.LOGO, null, movie.mediaInfo),
                jellyfinItemId = movie.mediaInfo?.jellyfinId,
            )

        suspend fun createDiscoverItem(tv: TvResult): DiscoverItem =
            DiscoverItem(
                id = tv.id!!,
                type = SeerrItemType.TV,
                title = tv.name,
                subtitle = null,
                overview = tv.overview,
                availability =
                    SeerrAvailability.from(tv.mediaInfo?.status)
                        ?: SeerrAvailability.UNKNOWN,
                releaseDate = toLocalDate(tv.firstAirDate),
                posterUrl = createImageUrl(ImageType.PRIMARY, tv.posterPath, tv.mediaInfo),
                backDropUrl = createImageUrl(ImageType.BACKDROP, tv.backdropPath, tv.mediaInfo),
                logoUrl = createImageUrl(ImageType.LOGO, null, tv.mediaInfo),
                jellyfinItemId = tv.mediaInfo?.jellyfinId,
            )

        suspend fun createDiscoverItem(tv: TvDetails): DiscoverItem =
            DiscoverItem(
                id = tv.id!!,
                type = SeerrItemType.TV,
                title = tv.name,
                subtitle = null,
                overview = tv.overview,
                availability =
                    SeerrAvailability.from(tv.mediaInfo?.status)
                        ?: SeerrAvailability.UNKNOWN,
                releaseDate = toLocalDate(tv.firstAirDate),
                posterUrl = createImageUrl(ImageType.PRIMARY, tv.posterPath, tv.mediaInfo),
                backDropUrl = createImageUrl(ImageType.BACKDROP, tv.backdropPath, tv.mediaInfo),
                logoUrl = createImageUrl(ImageType.LOGO, null, tv.mediaInfo),
                jellyfinItemId = tv.mediaInfo?.jellyfinId,
            )

        suspend fun createDiscoverItem(search: SeerrSearchResult): DiscoverItem =
            DiscoverItem(
                id = search.id,
                type = SeerrItemType.fromString(search.mediaType),
                title = search.title ?: search.name,
                subtitle = null,
                overview = search.overview,
                availability =
                    SeerrAvailability.from(search.mediaInfo?.status)
                        ?: SeerrAvailability.UNKNOWN,
                releaseDate = toLocalDate(search.releaseDate ?: search.firstAirDate),
                posterUrl = createImageUrl(ImageType.PRIMARY, search.posterPath, search.mediaInfo),
                backDropUrl = createImageUrl(ImageType.BACKDROP, search.backdropPath, search.mediaInfo),
                logoUrl = createImageUrl(ImageType.LOGO, null, search.mediaInfo),
                jellyfinItemId = search.mediaInfo?.jellyfinId,
            )

        suspend fun createDiscoverItem(credit: CreditCast): DiscoverItem =
            DiscoverItem(
                id = credit.id!!,
                type = SeerrItemType.fromString(credit.mediaType, SeerrItemType.PERSON),
                title = credit.name ?: credit.title,
                subtitle = credit.character,
                overview = credit.overview,
                availability =
                    SeerrAvailability.from(credit.mediaInfo?.status)
                        ?: SeerrAvailability.UNKNOWN,
                releaseDate = toLocalDate(credit.releaseDate ?: credit.firstAirDate),
                posterUrl =
                    createImageUrl(
                        ImageType.PRIMARY,
                        credit.posterPath ?: credit.profilePath,
                        credit.mediaInfo,
                    ),
                backDropUrl =
                    createImageUrl(
                        ImageType.BACKDROP,
                        credit.backdropPath,
                        credit.mediaInfo,
                    ),
                logoUrl = createImageUrl(ImageType.LOGO, null, credit.mediaInfo),
                jellyfinItemId = credit.mediaInfo?.jellyfinId,
            )

        suspend fun createDiscoverItem(credit: CreditCrew): DiscoverItem =
            DiscoverItem(
                id = credit.id!!,
                type = SeerrItemType.fromString(credit.mediaType, SeerrItemType.PERSON),
                title = credit.name ?: credit.title,
                subtitle = credit.job,
                overview = credit.overview,
                availability =
                    SeerrAvailability.from(credit.mediaInfo?.status)
                        ?: SeerrAvailability.UNKNOWN,
                releaseDate = toLocalDate(credit.releaseDate ?: credit.firstAirDate),
                posterUrl =
                    createImageUrl(
                        ImageType.PRIMARY,
                        credit.posterPath ?: credit.profilePath,
                        credit.mediaInfo,
                    ),
                backDropUrl =
                    createImageUrl(
                        ImageType.BACKDROP,
                        credit.backdropPath,
                        credit.mediaInfo,
                    ),
                logoUrl = createImageUrl(ImageType.LOGO, null, credit.mediaInfo),
                jellyfinItemId = credit.mediaInfo?.jellyfinId,
            )
    }

val MediaInfo.jellyfinId: UUID?
    get() = jellyfinMediaId4k?.toUUIDOrNull() ?: jellyfinMediaId?.toUUIDOrNull()

val MediaInfo.jellyfinIdAsString: String?
    get() = jellyfinMediaId4k ?: jellyfinMediaId
