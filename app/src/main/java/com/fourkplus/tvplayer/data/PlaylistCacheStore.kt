package com.fourkplus.tvplayer.data

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** On-disk, gzip'd binary cache of a loaded playlist, keyed per source so switching between saved playlists is instant. */
internal class PlaylistCacheStore(context: Context) {
    private val appContext = context.applicationContext
    private val legacyCacheFile = appContext.filesDir.resolve("playlist_cache_v1.bin.gz")

    suspend fun load(source: PlaylistInput): LoadedPlaylist? = withContext(Dispatchers.IO) {
        runCatching {
            val specificCache = cacheFileFor(source)
            val selectedFile = when {
                specificCache.exists() -> specificCache
                legacyCacheFile.exists() -> legacyCacheFile
                else -> return@runCatching null
            }
            val loaded = DataInputStream(GZIPInputStream(selectedFile.inputStream().buffered(IO_BUFFER_BYTES))).use { input ->
                require(input.readInt() == CACHE_VERSION) { "Unsupported playlist cache." }
                val name = input.readSizedString()
                val accountStatus = input.readNullableString()
                val expiryEpochSeconds = input.readLong().takeIf { it > 0L }
                val itemCount = input.readInt()
                require(itemCount in 0..500_000) { "Invalid playlist cache." }
                val items = ArrayList<PlaylistItem>(itemCount)
                // Building the distinct group set inline (instead of a second
                // items.map{}.distinct() pass afterwards) skips allocating an
                // 80,000+-element intermediate list just to deduplicate it - the same
                // information is already right here as each item is read.
                val groups = LinkedHashSet<String>()
                repeat(itemCount) {
                    // Field order here must exactly match save()'s write order below - these
                    // are read into locals first (rather than inline in the constructor call)
                    // so that order is unambiguous regardless of Kotlin's argument evaluation
                    // rules, since each read has the side effect of advancing the stream.
                    val itemName = input.readSizedString()
                    val streamUrl = input.readSizedString()
                    val group = input.readSizedString()
                    val logoUrl = input.readNullableString()
                    val channelId = input.readNullableString()
                    val kind = MediaKind.valueOf(input.readSizedString())
                    val description = input.readNullableString()
                    val year = input.readNullableString()
                    val rating = input.readNullableString()
                    val duration = input.readNullableString()
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
                        duration = duration
                    )
                }
                LoadedPlaylist(
                    name = name,
                    items = items,
                    groups = groups.toList(),
                    accountStatus = accountStatus,
                    expiryEpochSeconds = expiryEpochSeconds
                )
            }
            if (selectedFile == legacyCacheFile && loaded.name != source.name) return@runCatching null
            if (selectedFile == legacyCacheFile) save(source, loaded)
            loaded
        }.getOrNull()
    }

    fun save(source: PlaylistInput, playlist: LoadedPlaylist) {
        val destination = cacheFileFor(source)
        val temporary = appContext.filesDir.resolve(destination.name + ".tmp")
        runCatching {
            DataOutputStream(GZIPOutputStream(temporary.outputStream().buffered(IO_BUFFER_BYTES))).use { output ->
                output.writeInt(CACHE_VERSION)
                output.writeSizedString(playlist.name)
                output.writeNullableString(playlist.accountStatus)
                output.writeLong(playlist.expiryEpochSeconds ?: 0L)
                output.writeInt(playlist.items.size)
                playlist.items.forEach { item ->
                    output.writeSizedString(item.name)
                    output.writeSizedString(item.streamUrl)
                    output.writeSizedString(item.group)
                    output.writeNullableString(item.logoUrl)
                    output.writeNullableString(item.channelId)
                    output.writeSizedString(item.kind.name)
                    output.writeNullableString(item.description)
                    output.writeNullableString(item.year)
                    output.writeNullableString(item.rating)
                    output.writeNullableString(item.duration)
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

    fun deleteLegacy() {
        if (legacyCacheFile.exists()) legacyCacheFile.delete()
    }

    private fun cacheFileFor(source: PlaylistInput) = appContext.filesDir.resolve(
        "playlist_cache_" + MessageDigest.getInstance("SHA-256")
            .digest(source.sourceId().toByteArray(StandardCharsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) } + ".bin.gz"
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

    private fun DataInputStream.readSizedString(): String {
        val size = readInt()
        require(size in 0..2_000_000) { "Invalid cached text." }
        val bytes = ByteArray(size)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readBoolean()) readSizedString() else null

    private companion object {
        const val CACHE_VERSION = 4
        // Larger than the default 8KB: fewer read()/write() syscalls against the underlying
        // file for a cache that's routinely several MB (tens of thousands of items), which
        // matters more on the slower flash storage typical of budget TV boxes than it would
        // on a phone.
        const val IO_BUFFER_BYTES = 65_536
    }
}
