package com.fourkplus.tvplayer.data

import kotlinx.coroutines.sync.Semaphore

/** Short-lived cache and concurrency limiter for "now/next" programme lookups shown on Live TV
 *  rows and previews. The cache keeps a channel list from re-fetching the same channel's guide
 *  every recomposition; the semaphore caps how many EPG requests are in flight at once so
 *  scrolling a large channel list doesn't fire hundreds of simultaneous calls at the provider. */
object EpgStore {
    private const val FRESH_MS = 3 * 60 * 1000L
    private data class Cached(val value: EpgNowNext, val fetchedAt: Long)
    private val cache = LinkedHashMap<String, Cached>()
    val gate = Semaphore(4)

    fun get(key: String): EpgNowNext? {
        val hit = synchronized(cache) { cache[key] } ?: return null
        return hit.value.takeIf { System.currentTimeMillis() - hit.fetchedAt < FRESH_MS }
    }

    fun put(key: String, value: EpgNowNext) {
        synchronized(cache) { cache[key] = Cached(value, System.currentTimeMillis()) }
    }
}
