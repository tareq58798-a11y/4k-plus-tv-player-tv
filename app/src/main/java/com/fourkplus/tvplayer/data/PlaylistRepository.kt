package com.fourkplus.tvplayer.data

import android.content.Context
import java.net.SocketException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Facade over playlist loading, saved-source management, and caching. Delegates to
 * [XtreamProviderClient] (network), [PlaylistSourceStore] (encrypted saved-source prefs), and
 * [PlaylistCacheStore] (on-disk gzip cache) — this class owns only the orchestration between them.
 */
class PlaylistRepository(context: Context) {
    private val sourceStore = PlaylistSourceStore(context)
    private val cacheStore = PlaylistCacheStore(context)
    private val client = XtreamProviderClient()
    // In-memory only — cleared on process death. Movie/series metadata rarely changes mid-session,
    // so re-opening the same title's details page while the app is still running should be instant
    // instead of re-hitting the provider every time.
    private val movieDetailsCache = java.util.concurrent.ConcurrentHashMap<String, MovieDetailsInfo>()
    private val seriesDetailsCache = java.util.concurrent.ConcurrentHashMap<String, SeriesDetailsInfo>()

    suspend fun load(input: PlaylistInput): Result<LoadedPlaylist> = withContext(Dispatchers.IO) {
        runCatching {
            // Resolved via our own trusted activation backend, not raw end-user text entry,
            // so it deliberately skips the ApprovedServers host allowlist below.
            if (input.kind == PlaylistKind.DEVICE_ACTIVATION) {
                val resolved = DeviceActivationClient.resolve(input)
                val playlist = client.load(resolved)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                sourceStore.saveSource(resolved)
                cacheStore.save(resolved, playlist)
                return@runCatching playlist
            }
            // A source already sitting in the store was validated when it was first added (either
            // here, or via the trusted activation branch above) — reloading it (switching back to
            // it, refreshing, retrying after a cache miss) must not re-run the manual-entry-only
            // allowlist, which activation-resolved servers were never meant to satisfy.
            val alreadySaved = sourceStore.savedSources().any { it.sourceId() == input.sourceId() }
            require(alreadySaved || ApprovedServers.allows(input)) { "Please add an account using Server 1 or Server 2." }
            val playlist = client.load(input)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            sourceStore.saveSource(input)
            cacheStore.save(input, playlist)
            playlist
        }.recoverCatching { throw friendlyError(it) }
    }

    suspend fun loadProgressively(
        input: PlaylistInput,
        onPartial: suspend (PlaylistInput, LoadedPlaylist) -> Unit
    ): Result<LoadedPlaylist> = withContext(Dispatchers.IO) {
        try {
            val resolved = if (input.kind == PlaylistKind.DEVICE_ACTIVATION) {
                PlaylistTiming.measure("activation") { DeviceActivationClient.resolve(input) }
            } else {
                require(sourceStore.savedSources().any { it.sourceId() == input.sourceId() } || ApprovedServers.allows(input)) {
                    "Please add an account using Server 1 or Server 2."
                }
                input
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val playlist = client.loadProgressively(resolved) { partial ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                sourceStore.saveSource(resolved)
                onPartial(resolved, partial)
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            sourceStore.saveSource(resolved)
            // A partial catalogue must never replace the complete cache or mark it fresh.
            PlaylistTiming.measure("cache_save") { cacheStore.save(resolved, playlist) }
            Result.success(playlist)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Result.failure(friendlyError(e)) }
    }

    fun savedSources(): List<PlaylistInput> = sourceStore.savedSources()

    fun savedSource(): PlaylistInput? = sourceStore.savedSource()

    fun selectSavedSource(input: PlaylistInput) = sourceStore.selectSavedSource(input)

    fun renameSavedSource(name: String) = sourceStore.renameSavedSource(name)

    fun clearSavedSource() {
        val active = sourceStore.savedSource()
        sourceStore.clearActiveSource()
        active?.let { cacheStore.deleteFor(it) }
        cacheStore.deleteLegacy()
    }

    suspend fun loadCached(source: PlaylistInput? = sourceStore.savedSource()): LoadedPlaylist? = withContext(Dispatchers.IO) {
        val selected = source ?: return@withContext null
        PlaylistTiming.measure("cache_read") { cacheStore.load(selected) }
    }

    fun lastRefreshedAt(source: PlaylistInput): Long? = cacheStore.lastSavedAt(source)

    suspend fun movieDetails(movie: PlaylistItem): Result<MovieDetailsInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val cacheKey = movie.channelId
            cacheKey?.let(movieDetailsCache::get)?.let { return@runCatching it }
            val source = sourceStore.savedSource() ?: throw IllegalArgumentException("No saved provider is available.")
            require(source.kind == PlaylistKind.PROVIDER_LOGIN && !movie.channelId.isNullOrBlank()) {
                "Detailed information is not available for this playlist."
            }
            client.movieDetails(source, movie).also { info -> cacheKey?.let { movieDetailsCache[it] = info } }
        }.recoverCatching { throw friendlyError(it) }
    }

    suspend fun seriesDetails(series: PlaylistItem): Result<SeriesDetailsInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val cacheKey = series.channelId
            cacheKey?.let(seriesDetailsCache::get)?.let { return@runCatching it }
            val source = sourceStore.savedSource() ?: throw IllegalArgumentException("No saved provider is available.")
            require(source.kind == PlaylistKind.PROVIDER_LOGIN && !series.channelId.isNullOrBlank()) {
                "Series information is not available for this playlist."
            }
            client.seriesDetails(source, series).also { info -> cacheKey?.let { seriesDetailsCache[it] = info } }
        }.recoverCatching { throw friendlyError(it) }
    }

    suspend fun shortEpg(channel: PlaylistItem): Result<EpgNowNext> = withContext(Dispatchers.IO) {
        runCatching {
            val source = sourceStore.savedSource() ?: throw IllegalArgumentException("No saved provider is available.")
            require(source.kind == PlaylistKind.PROVIDER_LOGIN && !channel.channelId.isNullOrBlank()) {
                "Programme information is not available for this playlist."
            }
            val nowEpoch = System.currentTimeMillis() / 1000
            val programs = client.shortEpg(source, channel.channelId)
            EpgNowNext(
                now = programs.firstOrNull { nowEpoch in it.startEpochSeconds until it.endEpochSeconds },
                next = programs.filter { it.startEpochSeconds > nowEpoch }.minByOrNull { it.startEpochSeconds }
            )
        }.recoverCatching { throw friendlyError(it) }
    }

    private fun friendlyError(error: Throwable): Throwable = when {
        error is SocketException -> IllegalArgumentException("The server closed the connection. Verify the server address and try again.", error)
        error.message?.contains("TLS", true) == true -> IllegalArgumentException("This server uses HTTP rather than HTTPS. Please use its HTTP address.", error)
        error is org.json.JSONException -> IllegalArgumentException("This server returned an unsupported response. Confirm that it supports provider login.", error)
        else -> error
    }
}
