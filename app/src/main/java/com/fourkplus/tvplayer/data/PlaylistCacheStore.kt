package com.fourkplus.tvplayer.data

import android.content.Context
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** On-disk, gzip'd binary cache of a loaded playlist, keyed per source so switching between saved playlists is instant. */
internal class PlaylistCacheStore(context: Context) {
    private val appContext = context.applicationContext
    private val legacyCacheFile = appContext.filesDir.resolve("playlist_cache_v1.bin.gz")

    /**
     * Reads a cached playlist back. The file stores Live TV first (see [save]), so when
     * [onLiveReady] is supplied it is invoked with a Live-only playlist as soon as that section has
     * been read - typically a fraction of the whole file - and the Movies/Series sections are
     * decoded afterwards. That lets the app open on a usable Live TV catalogue instead of holding a
     * loading screen until every one of eighty thousand-odd items has been rebuilt, mirroring how
     * the network path already publishes Live before the on-demand catalogues.
     */
    suspend fun load(
        source: PlaylistInput,
        onLiveReady: (suspend (LoadedPlaylist) -> Unit)? = null
    ): LoadedPlaylist? = withContext(Dispatchers.IO) {
        runCatching {
            val specificCache = cacheFileFor(source)
            val selectedFile = when {
                specificCache.exists() -> specificCache
                legacyCacheFile.exists() -> legacyCacheFile
                else -> return@runCatching null
            }
            DataInputStream(
                // Buffer decompressed bytes too: readInt/readBoolean otherwise call through
                // the inflater for each byte across hundreds of thousands of cached fields.
                GZIPInputStream(selectedFile.inputStream().buffered(IO_BUFFER_BYTES), IO_BUFFER_BYTES)
                    .buffered(IO_BUFFER_BYTES)
            ).use { input ->
                require(input.readInt() == CACHE_VERSION) { "Unsupported playlist cache." }
                val text = CachedStringReader()
                val name = text.read(input)
                val accountStatus = text.readNullable(input)
                val expiryEpochSeconds = input.readLong().takeIf { it > 0L }
                val items = ArrayList<PlaylistItem>()
                // Building the distinct group set inline (instead of a second
                // items.map{}.distinct() pass afterwards) skips allocating an
                // 80,000+-element intermediate list just to deduplicate it - the same
                // information is already right here as each item is read.
                val groups = LinkedHashSet<String>()
                // The partial handed to onLiveReady needs its own copy, since decoding continues to
                // append to `items` afterwards. The final result is the last reader of that list,
                // so it takes ownership instead of paying for another full copy.
                fun snapshot(own: Boolean) = LoadedPlaylist(
                    name = name,
                    items = if (own) items else ArrayList(items),
                    groups = groups.toList(),
                    accountStatus = accountStatus,
                    expiryEpochSeconds = expiryEpochSeconds
                )
                // Section order must match save()'s. Each section's kind is implied by its
                // position, so it is not stored per item.
                SECTION_KINDS.forEachIndexed { index, kind ->
                    if (index == 0) PlaylistTiming.measure("cache_read_live") {
                        input.readSection(kind, text, items, groups)
                    } else {
                        input.readSection(kind, text, items, groups)
                    }
                    if (index == 0 && onLiveReady != null) onLiveReady(snapshot(own = false))
                }
                snapshot(own = true)
            }
        }.getOrNull()
    }

    private fun DataInputStream.readSection(
        kind: MediaKind,
        text: CachedStringReader,
        items: ArrayList<PlaylistItem>,
        groups: LinkedHashSet<String>
    ) {
        val count = readInt()
        require(count in 0..500_000) { "Invalid playlist cache." }
        items.ensureCapacity(items.size + count)
        repeat(count) {
            // Field order here must exactly match save()'s write order - these are read into
            // locals first (rather than inline in the constructor call) so that order is
            // unambiguous regardless of Kotlin's argument evaluation rules, since each read has
            // the side effect of advancing the stream.
            val itemName = text.read(this)
            val streamUrl = text.read(this)
            val group = text.read(this)
            val logoUrl = text.readNullable(this)
            val channelId = text.readNullable(this)
            val description = text.readNullable(this)
            val year = text.readNullable(this)
            val rating = text.readNullable(this)
            val duration = text.readNullable(this)
            val addedEpochSeconds = readLong().takeIf { it > 0L }
            groups += group
            items += PlaylistItem(
                name = itemName,
                streamUrl = streamUrl,
                group = group,
                logoUrl = logoUrl,
                channelId = channelId,
                kind = kind,
                description = description,
                year = year,
                rating = rating,
                duration = duration,
                addedEpochSeconds = addedEpochSeconds
            )
        }
    }

    fun save(source: PlaylistInput, playlist: LoadedPlaylist) {
        val destination = cacheFileFor(source)
        val temporary = appContext.filesDir.resolve(destination.name + ".tmp")
        runCatching {
            DataOutputStream(
                // Buffer the uncompressed side too, mirroring load(): DataOutputStream otherwise
                // pushes every writeInt/writeBoolean/write straight into the deflater, which is
                // roughly ten separate compressor calls per item across the whole catalogue.
                BufferedOutputStream(
                    FastGzipOutputStream(temporary.outputStream().buffered(IO_BUFFER_BYTES), IO_BUFFER_BYTES),
                    IO_BUFFER_BYTES
                )
            ).use { output ->
                output.writeInt(CACHE_VERSION)
                output.writeSizedString(playlist.name)
                output.writeNullableString(playlist.accountStatus)
                output.writeLong(playlist.expiryEpochSeconds ?: 0L)
                // Grouped into per-kind sections with Live first so load() can hand back a usable
                // Live TV catalogue without decoding the (far larger) Movies and Series sections
                // behind it. The kind is implied by the section and so is not written per item.
                val byKind = playlist.items.groupBy { it.kind }
                SECTION_KINDS.forEach { kind ->
                    val section = byKind[kind].orEmpty()
                    output.writeInt(section.size)
                    section.forEach { item ->
                        output.writeSizedString(item.name)
                        output.writeSizedString(item.streamUrl)
                        output.writeSizedString(item.group)
                        output.writeNullableString(item.logoUrl)
                        output.writeNullableString(item.channelId)
                        output.writeNullableString(item.description)
                        output.writeNullableString(item.year)
                        output.writeNullableString(item.rating)
                        output.writeNullableString(item.duration)
                        // 0 stands in for absent: a catalogue timestamp is never legitimately 0.
                        output.writeLong(item.addedEpochSeconds ?: 0L)
                    }
                }
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
        }.onFailure { temporary.delete() }
    }

    fun deleteFor(source: PlaylistInput) {
        cacheFileFor(source).delete()
    }

    /** Epoch millis this source's cache was last written, or null if it was never saved -
     *  used to decide whether an auto-update interval has elapsed. */
    fun lastSavedAt(source: PlaylistInput): Long? {
        val file = cacheFileFor(source)
        return if (file.exists()) file.lastModified() else null
    }

    fun deleteLegacy() {
        if (legacyCacheFile.exists()) legacyCacheFile.delete()
    }

    /**
     * A filename is an identifier, so its digits are formatted against [Locale.ROOT] rather than
     * the device's locale. String.format follows the default locale, and on a device running
     * Arabic this produced a name in Arabic-Indic numerals - a different file for the same
     * playlist, so changing the app's language silently orphaned the cache and cost a full
     * re-download.
     *
     * A cache written under the old name is simply not found and is replaced, which costs one
     * download. That is why this needs no fallback, unlike the parental PIN.
     */
    private fun cacheFileFor(source: PlaylistInput) = appContext.filesDir.resolve(
        "playlist_cache_" + MessageDigest.getInstance("SHA-256")
            .digest(source.sourceId().toByteArray(StandardCharsets.UTF_8))
            .take(12).joinToString("") { String.format(Locale.ROOT, "%02x", it) } + ".bin.gz"
    )

    private fun DataOutputStream.writeSizedString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeSizedString(value)
    }

    /** Decodes the cache's length-prefixed strings through one growable scratch buffer instead of
     *  allocating a fresh ByteArray per field. A full catalogue carries roughly ten string fields
     *  per item, so the throwaway arrays alone ran to hundreds of thousands of allocations - and
     *  the GC pressure from them lands squarely on app startup. */
    private class CachedStringReader {
        private var scratch = ByteArray(256)

        fun read(input: DataInputStream): String {
            val size = input.readInt()
            require(size in 0..2_000_000) { "Invalid cached text." }
            if (scratch.size < size) scratch = ByteArray(maxOf(size, scratch.size * 2))
            input.readFully(scratch, 0, size)
            return String(scratch, 0, size, StandardCharsets.UTF_8)
        }

        fun readNullable(input: DataInputStream): String? =
            if (input.readBoolean()) read(input) else null
    }

    /** gzip tuned for a local cache, where the file is rewritten on every refresh and read back on
     *  every launch: the fastest compression level costs a modestly larger file on disk and saves
     *  far more CPU than the space is worth. The output is ordinary gzip, so a cache written at any
     *  level still reads back with the existing loader - this is not a format change. */
    private class FastGzipOutputStream(out: OutputStream, size: Int) : GZIPOutputStream(out, size) {
        init { def.setLevel(Deflater.BEST_SPEED) }
    }

    private companion object {
        /** Section order on disk. Live is deliberately first so it can be read and shown alone. */
        val SECTION_KINDS = listOf(MediaKind.LIVE, MediaKind.MOVIE, MediaKind.SERIES)
        // 6 adds each title's catalogue timestamp, which "recently added" sorts on. An older file is
    // rejected by load()'s version check and simply re-fetched.
    const val CACHE_VERSION = 6
        // Larger than the default 8KB: fewer read()/write() syscalls against the underlying
        // file for a cache that's routinely several MB (tens of thousands of items), which
        // matters more on the slower flash storage typical of budget TV boxes than it would
        // on a phone.
        const val IO_BUFFER_BYTES = 65_536
    }
}
