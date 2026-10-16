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
    private val appContext = context.applicationContext
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
                val all = DeviceActivationClient.resolveAll(input)
                val resolved = all.first()
                val playlist = client.load(resolved)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                saveFromDashboard(all)
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
            val fromDashboard = input.kind == PlaylistKind.DEVICE_ACTIVATION
            // From the dashboard: every playlist it assigns, the first of which is the one opened.
            val all = if (fromDashboard) {
                PlaylistTiming.measure("activation") { DeviceActivationClient.resolveAll(input) }
            } else {
                require(sourceStore.savedSources().any { it.sourceId() == input.sourceId() } || ApprovedServers.allows(input)) {
                    "Please add an account using Server 1 or Server 2."
                }
                listOf(input)
            }
            val resolved = all.first()
            val save: (PlaylistInput) -> Unit = if (fromDashboard) { _ -> saveFromDashboard(all) } else { source -> sourceStore.saveSource(source) }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val playlist = client.loadProgressively(resolved) { partial ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                save(resolved)
                onPartial(resolved, partial)
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            save(resolved)
            // A partial catalogue must never replace the complete cache or mark it fresh.
            PlaylistTiming.measure("cache_save") { cacheStore.save(resolved, playlist) }
            Result.success(playlist)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Result.failure(friendlyError(e)) }
        // An Error, so the line above lets it through and it used to take the whole app down
        // mid-load. load() already survives it via runCatching; this path now does too.
        catch (e: OutOfMemoryError) {
            Result.failure(IllegalStateException("This device does not have enough memory to load the whole playlist.", e))
        }
    }

    /**
     * Saves the playlists the dashboard sent - one or two - and remembers that they are the
     * dashboard's. The first is made the one in use; a second is saved beside it, for the viewer to
     * switch to from Playlists.
     *
     * One the viewer had already saved themselves is left exactly as they saved it - their name,
     * their password - and only made the one in use when it comes first: the dashboard handing out
     * the same account does not make it the dashboard's to rename, or later to take away. And a
     * dashboard playlist it no longer sends goes, cache and all, since it no longer gives it out.
     */
    private fun saveFromDashboard(all: List<PlaylistInput>) {
        val markers = sourceStore.dashboardSourceIds()
        val sentIds = all.map { it.sourceId() }.toSet()
        (markers - sentIds).forEach { goneId ->
            sourceStore.savedSources().firstOrNull { it.sourceId() == goneId }?.let { gone ->
                sourceStore.removeSource(goneId)
                cacheStore.deleteFor(gone)
            }
        }
        val nowMarked = (markers intersect sentIds).toMutableSet()
        all.forEachIndexed { index, sent ->
            val id = sent.sourceId()
            val own = sourceStore.savedSources().firstOrNull { it.sourceId() == id }?.takeIf { id !in markers }
            if (own != null) {
                if (index == 0) sourceStore.selectSavedSource(own)
            } else {
                sourceStore.saveSource(sent, makeActive = index == 0)
                nowMarked += id
            }
        }
        sourceStore.setDashboardSourceIds(nowMarked)
    }

    /** What [syncWithDashboard] found. */
    sealed interface DashboardSync {
        data object Unchanged : DashboardSync
        /** The dashboard took back [removed] (already off the device) and/or gave new playlists, now
         *  saved beside the others ([added]). */
        data class Changed(val removed: List<PlaylistInput>, val added: Boolean) : DashboardSync
    }

    /**
     * Asks the dashboard whether the playlist it gave this device is still assigned, and removes it
     * from the device when it is not.
     *
     * Deleting a playlist in the dashboard used to wipe it there and nowhere else: the device had
     * saved its own copy as an ordinary source, and Refresh reloaded that copy from the provider
     * without asking the dashboard again - so the playlist the reseller had deleted carried on, and
     * "came back" on every refresh. The dashboard decides what it hands out; this is where the
     * device hears that it took something back.
     *
     * Only the dashboard's own sources are ever removed (see [PlaylistSourceStore.dashboardSourceIds]).
     * Nothing changes when the dashboard cannot be reached: a television without internet must not
     * lose its playlist, and the question is asked again next time.
     *
     * A playlist the dashboard has given since - a Playlist 2 added later - is saved beside the
     * others, never switched to: what is on screen does not change under the viewer.
     */
    suspend fun syncWithDashboard(): DashboardSync = withContext(Dispatchers.IO) {
        val saved = sourceStore.savedSources()
        var markers = sourceStore.dashboardSourceIds()
        if (saved.isEmpty()) {
            if (markers.isNotEmpty()) sourceStore.setDashboardSourceIds(emptySet())
            return@withContext DashboardSync.Unchanged
        }
        val identity = PlaylistInput(
            name = "",
            kind = PlaylistKind.DEVICE_ACTIVATION,
            address = "",
            username = DeviceIdentity.mac(appContext),
            password = DeviceIdentity.deviceKey(appContext)
        )
        val assigned = try {
            DeviceActivationClient.resolveAll(identity)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ActivationPendingException) {
            emptyList()
        } catch (e: Exception) {
            return@withContext DashboardSync.Unchanged
        }
        val assignedIds = assigned.map { it.sourceId() }.toSet()
        if (markers.isEmpty()) {
            // Saved by a build that did not keep the marker. Sources matching what the dashboard
            // assigns now are evidently the ones it gave, so they are adopted, and a later deletion
            // in the dashboard reaches them. One the dashboard no longer assigns cannot be told apart
            // from a source the viewer typed in, so it is left alone - and with nothing adopted, the
            // device is not one the dashboard looks after, and nothing is added to it either.
            markers = assignedIds.filter { id -> saved.any { it.sourceId() == id } }.toSet()
            sourceStore.setDashboardSourceIds(markers)
            if (markers.isEmpty()) return@withContext DashboardSync.Unchanged
        }
        val removed = (markers - assignedIds).mapNotNull { id -> saved.firstOrNull { it.sourceId() == id } }
        removed.forEach { gone ->
            sourceStore.removeSource(gone.sourceId())
            cacheStore.deleteFor(gone)
        }
        val added = assigned.filter { sent -> sent.sourceId() !in markers && saved.none { it.sourceId() == sent.sourceId() } }
        added.forEach { sourceStore.saveSource(it, makeActive = false) }
        sourceStore.setDashboardSourceIds((markers intersect assignedIds) + added.map { it.sourceId() })
        if (removed.isEmpty() && added.isEmpty()) DashboardSync.Unchanged else DashboardSync.Changed(removed, added.isNotEmpty())
    }

    fun savedSources(): List<PlaylistInput> = sourceStore.savedSources()

    fun savedSource(): PlaylistInput? = sourceStore.savedSource()

    fun selectSavedSource(input: PlaylistInput) = sourceStore.selectSavedSource(input)

    fun renameSavedSource(name: String) = sourceStore.renameSavedSource(name)

    fun clearSavedSource() {
        val active = sourceStore.savedSource()
        if (active != null) sourceStore.setDashboardSourceIds(sourceStore.dashboardSourceIds() - active.sourceId())
        sourceStore.clearActiveSource()
        active?.let { cacheStore.deleteFor(it) }
        cacheStore.deleteLegacy()
    }

    /** [onLiveReady], when supplied, fires as soon as the cache's Live TV section has been decoded,
     *  ahead of the Movies and Series sections - see [PlaylistCacheStore.load]. */
    suspend fun loadCached(
        source: PlaylistInput? = sourceStore.savedSource(),
        onLiveReady: (suspend (LoadedPlaylist) -> Unit)? = null
    ): LoadedPlaylist? = withContext(Dispatchers.IO) {
        val selected = source ?: return@withContext null
        PlaylistTiming.measure("cache_read") { cacheStore.load(selected, onLiveReady) }
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
