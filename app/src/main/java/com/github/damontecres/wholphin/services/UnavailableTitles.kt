package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.model.TitleKey
import com.github.damontecres.wholphin.data.model.keys
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private val MIN_REFRESH_INTERVAL = 60.seconds
private val REFRESH_INTERVAL = 5.minutes

/**
 * The titles the queue service gave up on, for every page that shows posters
 *
 * The set stays as it was when the service can't be reached, so a brief outage doesn't take the
 * marks away. Without the service it stays empty and nothing shows.
 */
@Singleton
class UnavailableTitles
    @Inject
    constructor(
        private val queueService: QueueService,
        private val seerrServerRepository: SeerrServerRepository,
    ) {
        private val _titles = MutableStateFlow<Set<TitleKey>>(emptySet())
        val titles: StateFlow<Set<TitleKey>> = _titles

        private val mutex = Mutex()
        private var lastRefresh: TimeMark? = null

        // The server and user the titles are for
        private var source: Pair<String?, Int?>? = null

        /**
         * Fetch the titles, at most once a minute
         *
         * @param force fetch even if the last fetch was within the minute, after something changed
         */
        suspend fun refresh(force: Boolean = false) {
            if (!BuildConfig.QUEUE_PAGE_ENABLED) return
            mutex.withLock {
                val last = lastRefresh
                if (!force && last != null && last.elapsedNow() < MIN_REFRESH_INTERVAL) return
                lastRefresh = TimeSource.Monotonic.markNow()
                (queueService.unavailable() as? QueueResult.Success)?.let { _titles.value = it.value.keys() }
            }
        }

        /**
         * Keep the titles fresh for as long as the caller stays active
         *
         * Starts over for another Seerr server or user, whose titles can be different.
         */
        suspend fun keepFresh() {
            if (!BuildConfig.QUEUE_PAGE_ENABLED) return
            seerrServerRepository.current
                .map { it?.server?.url to it?.config?.id }
                .distinctUntilChanged()
                .collectLatest { current ->
                    val changed = current != source
                    if (changed) {
                        source = current
                        _titles.value = emptySet()
                    }
                    if (current.first == null) return@collectLatest
                    var force = changed
                    while (true) {
                        refresh(force)
                        force = false
                        delay(REFRESH_INTERVAL)
                    }
                }
        }
    }
