package com.github.damontecres.wholphin.services

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.api.seerr.infrastructure.ServerException
import com.github.damontecres.wholphin.data.SeerrServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.SeerrAuthMethod
import com.github.damontecres.wholphin.data.model.SeerrServer
import com.github.damontecres.wholphin.data.model.SeerrUser
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.net.ssl.SSLException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Listens for JF user switching in the app to also switch other settings like Seerr user/server
 */
@ActivityScoped
class UserSwitchListener
    @Inject
    constructor(
        @param:ActivityContext private val context: Context,
        private val serverRepository: ServerRepository,
        private val seerrServerRepository: SeerrServerRepository,
        private val seerrServerDao: SeerrServerDao,
        private val seerrApi: SeerrApi,
        private val homeSettingsService: HomeSettingsService,
    ) {
        init {
            context as AppCompatActivity
            context.lifecycleScope.launchDefault {
                serverRepository.currentUserFlow
                    .distinctUntilChanged { old, new ->
                        old?.id == new?.id && old?.uiLanguage == new?.uiLanguage
                    }.collectLatest { user ->
                        Timber.d("UserSwitch: %s", user?.id)
                        seerrServerRepository.clear()
                        homeSettingsService.currentSettings.update { HomePageResolvedSettings.EMPTY }
                        if (user != null) {
                            switchUser(user)
                        }
                    }
            }
        }

        private suspend fun switchUser(user: JellyfinUser) =
            supervisorScope {
                // Switch the locale to either the user's choice or the system default (empty)
                val localeList =
                    user.uiLanguage?.let { LocaleListCompat.forLanguageTags(it) }
                        ?: LocaleListCompat.getEmptyLocaleList()
                Timber.i("Switching locale to %s", localeList)
                withContext(WholphinDispatchers.Main) {
                    AppCompatDelegate.setApplicationLocales(localeList)
                }

                // Check for home settings
                launchIO {
                    homeSettingsService.loadCurrentSettings(user.id)
                }
                if (BuildConfig.DISCOVER_ENABLED) {
                    // Check for seerr server
                    launchIO {
                        seerrServerDao
                            .getUsersByJellyfinUser(user.rowId)
                            .lastOrNull()
                            ?.let { seerrUser ->
                                val server =
                                    seerrServerDao.getServer(seerrUser.serverId)?.server
                                if (server != null) {
                                    Timber.i("Found a seerr user & server")
                                    connectSeerr(server, seerrUser)
                                }
                            }
                    }
                }
            }

        private suspend fun connectSeerr(
            server: SeerrServer,
            seerrUser: SeerrUser,
        ) {
            val attempt = seerrServerRepository.newConnectionAttempt()
            retrySeerrConnection(
                // Stop if the user removed the server or set up another one
                keepTrying = { seerrServerRepository.isLatestAttempt(attempt) },
                // No need to keep sending the password while the app is in the background
                beforeRetry = { (context as AppCompatActivity).lifecycle.withStarted {} },
                onError = { ex ->
                    Timber.w(ex, "Error logging into %s", server.url)
                    seerrServerRepository.error(server, seerrUser, ex, attempt)
                },
            ) {
                // An error clears the API, so this is needed on each attempt
                seerrApi.update(server.url, seerrUser.credential)
                val userConfig =
                    if (seerrUser.authMethod != SeerrAuthMethod.API_KEY) {
                        seerrLogin(
                            seerrApi.api,
                            seerrUser.authMethod,
                            seerrUser.username,
                            seerrUser.password,
                        )
                    } else {
                        seerrApi.api.usersApi.authMeGet()
                    }
                seerrServerRepository.setIfLatest(server, seerrUser, userConfig, attempt)
            }
        }
    }

/**
 * How long to wait after a failed attempt, doubling from 5 seconds up to a minute
 *
 * @param attempt zero for the first failure
 */
internal fun seerrRetryDelay(attempt: Int): Duration = minOf(5.seconds * (1 shl attempt.coerceIn(0, 4)), 60.seconds)

/**
 * Whether trying Seerr again could work: a network error or a server error. A refused password or a
 * bad certificate won't fix itself.
 */
internal fun isSeerrRetryable(ex: Exception): Boolean =
    when (ex) {
        is SSLException -> false
        is IOException, is ServerException -> true
        else -> false
    }

/**
 * Runs [connect] until it succeeds, and keeps going while [isSeerrRetryable]
 *
 * A network can come up after the app starts, as a VPN does, and nothing else would try Seerr again
 * until the app restarts.
 *
 * @param keepTrying checked after each wait
 * @param onError called for every failure
 * @param beforeRetry can suspend to hold the next attempt back
 * @param connect throws if the attempt fails
 */
internal suspend fun retrySeerrConnection(
    keepTrying: () -> Boolean,
    onError: (Exception) -> Unit,
    beforeRetry: suspend () -> Unit = {},
    connect: suspend () -> Unit,
) {
    var attempt = 0
    while (true) {
        try {
            connect()
            return
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            onError(ex)
            if (!isSeerrRetryable(ex)) return
        }
        delay(seerrRetryDelay(attempt++))
        beforeRetry()
        if (!keepTrying()) return
    }
}
