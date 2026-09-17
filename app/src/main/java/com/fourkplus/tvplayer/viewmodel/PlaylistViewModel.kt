package com.fourkplus.tvplayer.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fourkplus.tvplayer.data.EpgNowNext
import com.fourkplus.tvplayer.data.LoadedPlaylist
import com.fourkplus.tvplayer.data.MovieDetailsInfo
import com.fourkplus.tvplayer.data.PlaylistInput
import com.fourkplus.tvplayer.data.PlaylistItem
import com.fourkplus.tvplayer.data.PlaylistRepository
import com.fourkplus.tvplayer.data.SeriesDetailsInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the playlist/data-layer state that used to live as plain `remember { mutableStateOf(...) }`
 * variables inside `App()`. Screens read [uiState] and call the suspend functions below instead of
 * calling [PlaylistRepository] directly. Screen *navigation* (which composable is on screen)
 * deliberately stays outside this class — it has a different lifecycle and covers screens (Settings,
 * Activation) that have nothing to do with playlist data.
 */
class PlaylistViewModel(private val repository: PlaylistRepository, private val appContext: Context) : ViewModel() {

    data class PlaylistUiState(
        val loadedPlaylist: LoadedPlaylist? = null,
        val savedPlaylists: List<PlaylistInput> = emptyList(),
        val activeSource: PlaylistInput? = null,
        val bootstrapping: Boolean = true,
        val bootstrapFailed: Boolean = false,
        // Surfaced on the loading screen so it's visible (not just inferred) whether a slow
        // startup is reading the on-disk cache or genuinely re-fetching from the provider -
        // the two look identical to a user watching the same spinner either way.
        val bootstrappingFromNetwork: Boolean = false,
        val loadingCatalogues: Boolean = false,
        val catalogueLoadFailed: Boolean = false
    )

    private val _uiState = MutableStateFlow(PlaylistUiState())
    val uiState: StateFlow<PlaylistUiState> = _uiState.asStateFlow()

    // Avoids re-fetching a playlist the user already visited this session. Not Compose-observed
    // state on purpose: nothing renders this map directly, only uiState.loadedPlaylist.
    private val memoryCache = mutableMapOf<String, LoadedPlaylist>()
    private fun memoryKey(source: PlaylistInput) = "${source.kind.name}|${source.address.trim()}|${source.username.trim()}"

    private var bootstrapJob: kotlinx.coroutines.Job? = null

    init {
        bootstrap()
    }

    private fun bootstrap() {
        bootstrapJob = viewModelScope.launch {
            val activeSource = repository.savedSource()
            if (activeSource == null) {
                _uiState.update { it.copy(bootstrapping = false) }
                return@launch
            }
            // Clearing `bootstrapping` on the Live-only section drops the loading screen as soon as
            // Live TV is usable, rather than holding it until the whole catalogue has been rebuilt
            // from disk. Movies and Series arrive moments later in the full result below.
            val cached = repository.loadCached(activeSource) { liveOnly ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    _uiState.update {
                        it.copy(
                            loadedPlaylist = liveOnly,
                            activeSource = activeSource,
                            savedPlaylists = repository.savedSources(),
                            bootstrapping = false
                        )
                    }
                }
            }
            if (cached != null) {
                memoryCache[memoryKey(activeSource)] = cached
                _uiState.update {
                    it.copy(loadedPlaylist = cached, activeSource = activeSource, savedPlaylists = repository.savedSources(), bootstrapping = false)
                }
                // The cached copy is shown immediately for speed; if the user's auto-update
                // interval has elapsed, silently refresh from the provider in the background
                // instead of waiting for a manual refresh.
                if (shouldAutoRefresh(activeSource)) {
                    repository.load(activeSource).onSuccess { playlist ->
                        memoryCache[memoryKey(activeSource)] = playlist
                        _uiState.update { it.copy(loadedPlaylist = playlist) }
                    }
                }
            } else {
                _uiState.update { it.copy(bootstrappingFromNetwork = true) }
                repository.load(activeSource)
                    .onSuccess { playlist ->
                        memoryCache[memoryKey(activeSource)] = playlist
                        _uiState.update {
                            it.copy(loadedPlaylist = playlist, activeSource = activeSource, savedPlaylists = repository.savedSources(), bootstrapping = false)
                        }
                    }
                    .onFailure {
                        _uiState.update {
                            it.copy(activeSource = activeSource, savedPlaylists = repository.savedSources(), bootstrapping = false, bootstrapFailed = true)
                        }
                    }
            }
            // Pre-warm the cache for the user's other saved playlists so switching between them is instant.
            repository.savedSources().filterNot { it.let(::memoryKey) == memoryKey(activeSource) }.forEach { source ->
                repository.loadCached(source)?.let { memoryCache[memoryKey(source)] = it }
            }
        }
    }

    private fun shouldAutoRefresh(source: PlaylistInput): Boolean {
        val interval = appContext.getSharedPreferences("playback_settings", Context.MODE_PRIVATE)
            .getString("auto_update_interval", "daily") ?: "daily"
        if (interval == "everytime") return true
        val lastRefreshed = repository.lastRefreshedAt(source) ?: return true
        val thresholdMs = if (interval == "every_2_days") 2 * DAY_MS else DAY_MS
        return System.currentTimeMillis() - lastRefreshed >= thresholdMs
    }

    private var catalogueJob: kotlinx.coroutines.Job? = null

    private fun stopCatalogueLoad() {
        bootstrapJob?.cancel()
        catalogueJob?.cancel()
        catalogueJob = null
        _uiState.update { it.copy(loadingCatalogues = false, catalogueLoadFailed = false) }
    }

    suspend fun addPlaylist(input: PlaylistInput): Result<LoadedPlaylist> {
        stopCatalogueLoad()
        val ready = kotlinx.coroutines.CompletableDeferred<Result<LoadedPlaylist>>()
        val job = viewModelScope.launch {
            try {
                val result = repository.loadProgressively(input) { source, partial ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        _uiState.update { it.copy(
                            loadedPlaylist = partial, activeSource = source,
                            savedPlaylists = repository.savedSources(), bootstrapping = false,
                            bootstrapFailed = false, loadingCatalogues = true, catalogueLoadFailed = false
                        ) }
                        ready.complete(Result.success(partial))
                    }
                }
                result.onSuccess { playlist ->
                    val source = repository.savedSource() ?: input
                    memoryCache[memoryKey(source)] = playlist
                    _uiState.update { it.copy(
                        loadedPlaylist = playlist, activeSource = source,
                        savedPlaylists = repository.savedSources(), bootstrapping = false,
                        bootstrapFailed = false, loadingCatalogues = false, catalogueLoadFailed = false
                    ) }
                }.onFailure {
                    _uiState.update { it.copy(loadingCatalogues = false, catalogueLoadFailed = ready.isCompleted) }
                }
                ready.complete(result)
            } catch (e: kotlinx.coroutines.CancellationException) {
                ready.cancel(e)
                throw e
            }
        }
        catalogueJob = job
        return try { ready.await() }
        catch (e: kotlinx.coroutines.CancellationException) {
            // Leaving activation before any usable result must not activate later.
            if (!ready.isCompleted || ready.isCancelled) job.cancel()
            throw e
        }
    }

    suspend fun switchTo(source: PlaylistInput): Result<Unit> {
        stopCatalogueLoad()
        val previous = repository.savedSource()
        return runCatching {
            repository.selectSavedSource(source)
            val playlist = memoryCache[memoryKey(source)]
                ?: repository.loadCached(source)
                ?: repository.load(source).getOrThrow()
            memoryCache[memoryKey(source)] = playlist
            _uiState.update { it.copy(loadedPlaylist = playlist, activeSource = source, savedPlaylists = repository.savedSources()) }
        }.onFailure {
            previous?.let { runCatching { repository.selectSavedSource(it) } }
        }
    }

    suspend fun removeSource(source: PlaylistInput): Result<Unit> = runCatching {
        stopCatalogueLoad()
        repository.selectSavedSource(source)
        repository.clearSavedSource()
        memoryCache.remove(memoryKey(source))
        val next = repository.savedSource()
        val nextPlaylist = next?.let { candidate ->
            memoryCache[memoryKey(candidate)] ?: repository.loadCached(candidate) ?: repository.load(candidate).getOrNull()
        }
        if (next != null && nextPlaylist != null) memoryCache[memoryKey(next)] = nextPlaylist
        _uiState.update { it.copy(loadedPlaylist = nextPlaylist, activeSource = next, savedPlaylists = repository.savedSources()) }
    }

    suspend fun refreshActive(): Result<LoadedPlaylist> {
        stopCatalogueLoad()
        val source = repository.savedSource() ?: return Result.failure(IllegalStateException("No saved playlist."))
        return repository.load(source).onSuccess { playlist ->
            memoryCache[memoryKey(source)] = playlist
            _uiState.update { it.copy(loadedPlaylist = playlist) }
        }
    }

    suspend fun renameActive(name: String): Result<Unit> = runCatching {
        repository.renameSavedSource(name)
        _uiState.update { it.copy(activeSource = repository.savedSource(), savedPlaylists = repository.savedSources()) }
    }

    // Pass-through per-item detail lookups: read-only, don't touch uiState, but keep MoviesScreen/
    // SeriesScreen from needing their own PlaylistRepository instance.
    suspend fun movieDetails(movie: PlaylistItem): Result<MovieDetailsInfo> = repository.movieDetails(movie)
    suspend fun seriesDetails(series: PlaylistItem): Result<SeriesDetailsInfo> = repository.seriesDetails(series)
    suspend fun shortEpg(channel: PlaylistItem): Result<EpgNowNext> = repository.shortEpg(channel)

    class Factory(private val appContext: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlaylistViewModel(PlaylistRepository(appContext), appContext) as T
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
