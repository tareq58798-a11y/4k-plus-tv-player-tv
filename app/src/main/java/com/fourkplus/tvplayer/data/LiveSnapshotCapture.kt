package com.fourkplus.tvplayer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Still frames captured from live channels, kept in memory and on disk.
 *
 * Capturing one is expensive in a way no cache trick can hide: it means connecting to the stream,
 * waiting for it to buffer, and decoding a frame - seconds, per channel, and only one at a time
 * because most IPTV accounts refuse concurrent streams. Doing that on every launch is what made
 * channel cards sit empty while everything else on the page was ready.
 *
 * So a captured frame is written to disk and shown again immediately next time, however old it is.
 * A picture of this channel from yesterday evening tells the viewer what the channel is; an empty
 * grey box tells them nothing, and the difference between yesterday's frame and this minute's is
 * worth far less than the difference between having one and waiting for one. A fresh capture still
 * runs in the background when the saved frame has aged, and swaps in when it arrives.
 */
object LiveSnapshotCache {
    /** How old a saved frame may be before a new capture is worth starting behind it. */
    private const val FRESH_MS = 30 * 60 * 1000L

    /** Bounded: a long Live TV session would otherwise hold every channel it ever showed. */
    private const val MAX_IN_MEMORY = 40

    private data class Cached(val bitmap: Bitmap, val capturedAt: Long)

    private val cache = object : LinkedHashMap<String, Cached>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) =
            size > MAX_IN_MEMORY
    }

    /**
     * Serializes captures so only one connects to the provider at a time - many IPTV accounts cap
     * concurrent streams, and opening one per visible card at once would get all of them rejected
     * instead of just queued.
     */
    val captureMutex = Mutex()

    /** A frame recent enough that re-capturing it would not be worth the connection. */
    fun get(key: String): Bitmap? {
        val hit = synchronized(cache) { cache[key] } ?: return null
        return hit.bitmap.takeIf { System.currentTimeMillis() - hit.capturedAt < FRESH_MS }
    }

    /** Whatever is in memory, at any age, for showing while a newer one is fetched. */
    fun memory(key: String): Bitmap? = synchronized(cache) { cache[key]?.bitmap }

    fun isFresh(key: String): Boolean = get(key) != null

    /**
     * The saved frame for this channel, at any age. Reads a file, so it is suspending and belongs
     * off the main thread; the result is promoted into memory so the next card asking for it does
     * not touch the disk at all.
     */
    suspend fun fromDisk(context: Context, key: String): Bitmap? = withContext(Dispatchers.IO) {
        val file = fileFor(context, key)
        if (!file.exists()) return@withContext null
        val bitmap = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
            ?: return@withContext null
        synchronized(cache) { cache[key] = Cached(bitmap, file.lastModified()) }
        bitmap
    }

    /** Keeps [bitmap] for this session and writes it alongside for the next one. */
    fun put(context: Context, key: String, bitmap: Bitmap) {
        synchronized(cache) { cache[key] = Cached(bitmap, System.currentTimeMillis()) }
        runCatching {
            val file = fileFor(context, key)
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        }
    }

    // Channel keys come from provider data and can hold anything, so the filename is derived from
    // the key rather than being it.
    private fun fileFor(context: Context, key: String) =
        File(context.cacheDir, "live_snapshots/${key.hashCode().toUInt().toString(16)}.jpg")
}
