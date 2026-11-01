package com.github.damontecres.wholphin.services

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.seerr.model.RelatedVideo
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.LocalTrailer
import com.github.damontecres.wholphin.data.model.RemoteTrailer
import com.github.damontecres.wholphin.data.model.Trailer
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.nav.Destination
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import java.net.URI
import java.net.URISyntaxException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gets trailers for media
 */
@Singleton
class TrailerService
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
    ) {
        fun getRemoteTrailers(item: BaseItem): List<Trailer> =
            item.data.remoteTrailers
                ?.mapNotNull { t ->
                    t.url?.let { url ->
                        val name =
                            t.name
                                // TODO would be nice to clean up the trailer name
//                                                ?.replace(item.name ?: "", "")
//                                                ?.removePrefix(" - ")
                                ?: context.resources.getQuantityString(R.plurals.trailers, 1)
                        val subtitle =
                            when (url.toUri().host) {
                                "youtube.com", "www.youtube.com" -> "YouTube"
                                else -> null
                            }
                        RemoteTrailer(name, url, subtitle)
                    }
                }.orEmpty()
                .let { offered(bestFirst(it)) }

        suspend fun getLocalTrailers(item: BaseItem): List<Trailer> {
            val localTrailerCount = item.data.localTrailerCount ?: return emptyList()
            val localTrailers =
                if (localTrailerCount > 0) {
                    api.userLibraryApi.getLocalTrailers(item.id).content.map {
                        LocalTrailer(BaseItem.from(it, api))
                    }
                } else {
                    listOf()
                }
            return localTrailers
        }

        companion object {
            private const val MAX_QUEUED_TRAILERS = 10
            private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "www.youtube-nocookie.com")
            private val YOUTUBE_PATH_PREFIXES = listOf("/embed/", "/shorts/", "/v/")
            private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

            fun isValidVideoId(videoId: String): Boolean = VIDEO_ID.matches(videoId)

            /**
             * The YouTube video ID of a trailer's [url], or null if it isn't a YouTube video
             */
            fun youTubeVideoId(url: String): String? {
                val uri =
                    try {
                        URI(url)
                    } catch (_: URISyntaxException) {
                        return null
                    }
                if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
                val host = uri.host?.lowercase() ?: return null
                val path = uri.rawPath.orEmpty()
                val value =
                    when {
                        host == "youtu.be" -> {
                            path.removePrefix("/")
                        }

                        host !in YOUTUBE_HOSTS -> {
                            null
                        }

                        path == "/watch" || path == "/watch/" -> {
                            uri.rawQuery
                                ?.split('&')
                                ?.firstOrNull { it.startsWith("v=") }
                                ?.removePrefix("v=")
                        }

                        else -> {
                            YOUTUBE_PATH_PREFIXES
                                .firstOrNull { path.startsWith(it) }
                                ?.let { path.removePrefix(it) }
                        }
                    }
                return value?.removeSuffix("/")?.takeIf(::isValidVideoId)
            }

            /**
             * Sorts [trailers] with official trailers first, then other trailers, and teasers last.
             * Playing in the app falls back through the list, so there anything that isn't a
             * trailer or a teaser is what's last.
             */
            fun <T : Trailer> bestFirst(
                trailers: List<T>,
                inAppTrailersEnabled: Boolean = BuildConfig.IN_APP_TRAILERS_ENABLED,
            ): List<T> =
                trailers.sortedWith(
                    compareBy(
                        {
                            when {
                                it.name.contains("Official Trailer", true) -> 0
                                it.name.contains("Official Theatrical Trailer", true) -> 0
                                it.name.contains("Teaser", true) -> if (inAppTrailersEnabled) 2 else 4
                                it.name.contains("Trailer", true) -> 1
                                else -> 3
                            }
                        },
                        {
                            it.name
                        },
                    ),
                )

            /**
             * The trailers to offer from Seerr's [videos]. Playing in the app falls back through the
             * list, so it also gets the teasers and has the best ones first.
             */
            fun fromRelatedVideos(
                videos: List<RelatedVideo>?,
                remoteTrailersEnabled: Boolean = BuildConfig.REMOTE_TRAILERS_ENABLED,
                inAppTrailersEnabled: Boolean = BuildConfig.IN_APP_TRAILERS_ENABLED,
            ): List<Trailer> {
                val trailers =
                    videos
                        ?.filter {
                            it.type == RelatedVideo.Type.TRAILER ||
                                (inAppTrailersEnabled && it.type == RelatedVideo.Type.TEASER)
                        }?.filter { it.name.isNotNullOrBlank() && it.url.isNotNullOrBlank() }
                        ?.map {
                            RemoteTrailer(it.name!!, it.url!!, it.site)
                        }.orEmpty()
                return offered(
                    if (inAppTrailersEnabled) bestFirst(trailers, inAppTrailersEnabled) else trailers,
                    remoteTrailersEnabled,
                    inAppTrailersEnabled,
                )
            }

            /**
             * The [trailers] to offer. Remote trailers open another app, so they are removed when disabled.
             * Playing in the app needs a YouTube video, so then only the remote trailers without one are removed.
             */
            fun offered(
                trailers: List<Trailer>,
                remoteTrailersEnabled: Boolean = BuildConfig.REMOTE_TRAILERS_ENABLED,
                inAppTrailersEnabled: Boolean = BuildConfig.IN_APP_TRAILERS_ENABLED,
            ): List<Trailer> =
                when {
                    inAppTrailersEnabled -> trailers.filter { it !is RemoteTrailer || youTubeVideoId(it.url) != null }
                    remoteTrailersEnabled -> trailers
                    else -> trailers.filterNot { it is RemoteTrailer }
                }

            /**
             * Whether to show the trailer button. With no [trailers] it normally shows as disabled,
             * but it is hidden instead when remote trailers are disabled.
             */
            fun showButton(
                trailers: List<Trailer>,
                remoteTrailersEnabled: Boolean = BuildConfig.REMOTE_TRAILERS_ENABLED,
            ): Boolean = remoteTrailersEnabled || trailers.isNotEmpty()

            /**
             * The videos to play for [trailer]: itself, then what comes after it in [trailers] to fall back on
             */
            private fun videoIdsFrom(
                trailer: RemoteTrailer,
                trailers: List<Trailer>,
            ): List<String> {
                val index = trailers.indexOf(trailer)
                val following = if (index >= 0) trailers.drop(index + 1) else emptyList()
                return (listOf(trailer) + following)
                    .filterIsInstance<RemoteTrailer>()
                    .mapNotNull { youTubeVideoId(it.url) }
                    .distinct()
                    .take(MAX_QUEUED_TRAILERS)
            }

            /**
             * Note: This is explicitly <b>not</b> a member function because the injected Context is not an Activity.
             * We want to start the intent without a new task which requires the Activity context
             *
             * This can be provided by LocalContext.current from Compose
             *
             * @param trailers the trailers on the page, what comes after [trailer] is played if it can't be
             */
            fun onClick(
                context: Context,
                trailer: Trailer,
                navigateTo: (Destination) -> Unit,
                trailers: List<Trailer> = emptyList(),
                remoteTrailersEnabled: Boolean = BuildConfig.REMOTE_TRAILERS_ENABLED,
                inAppTrailersEnabled: Boolean = BuildConfig.IN_APP_TRAILERS_ENABLED,
            ) {
                when (trailer) {
                    is LocalTrailer -> {
                        navigateTo.invoke(
                            Destination.Playback(
                                itemId = trailer.baseItem.id,
                                positionMs = 0L,
                            ),
                        )
                    }

                    is RemoteTrailer -> {
                        if (inAppTrailersEnabled) {
                            val videoIds = videoIdsFrom(trailer, trailers)
                            if (videoIds.isNotEmpty()) {
                                navigateTo.invoke(Destination.TrailerPlayer(videoIds, trailer.name))
                            }
                        } else if (remoteTrailersEnabled) {
                            val intent = Intent(Intent.ACTION_VIEW, trailer.url.toUri())
                            context.startActivity(intent)
                        }
                    }
                }
            }
        }
    }
