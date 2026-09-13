package com.fourkplus.tvplayer.data

import android.graphics.Bitmap
import kotlinx.coroutines.sync.Mutex

/** Short-lived cache of live-channel still frames captured by
 *  [com.fourkplus.tvplayer.LiveSnapshotEffect], keyed by channel key, so Home doesn't re-decode
 *  a channel's stream every time it's shown. */
object LiveSnapshotCache {
    private const val FRESH_MS = 10 * 60 * 1000L
    private data class Cached(val bitmap: Bitmap, val capturedAt: Long)
    private val cache = LinkedHashMap<String, Cached>()

    /** Serializes snapshot captures so only one connects to the provider at a time — many IPTV
     *  accounts cap concurrent streams, and opening one per visible "recently watched" card at
     *  once would get all of them rejected instead of just queued. */
    val captureMutex = Mutex()

    fun get(key: String): Bitmap? {
        val hit = synchronized(cache) { cache[key] } ?: return null
        return hit.bitmap.takeIf { System.currentTimeMillis() - hit.capturedAt < FRESH_MS }
    }

    fun put(key: String, bitmap: Bitmap) {
        synchronized(cache) { cache[key] = Cached(bitmap, System.currentTimeMillis()) }
    }
}
