package com.fourkplus.tvplayer

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.fourkplus.tvplayer.data.ContinueWatchingStore
import com.fourkplus.tvplayer.data.LoadedPlaylist
import com.fourkplus.tvplayer.data.MediaKind
import com.fourkplus.tvplayer.data.PlaylistItem
import com.fourkplus.tvplayer.ui.design.BackdropState
import com.fourkplus.tvplayer.ui.design.NavDestination

/**
 * The four landing pages. Each one only gathers data - the layout, focus behaviour and animation
 * all live in [LandingScaffold], so the sections cannot drift apart visually.
 *
 * Every row is built from the app's own stores: the same preference files the browsing screens
 * read and write. Nothing here invents history, progress or metadata; a provider that supplies no
 * artwork or no runtime simply produces a plainer card.
 */

private const val RECORD_SEPARATOR = ''

private fun SharedPreferences.idList(key: String): List<String> =
    getString(key, "").orEmpty().split(RECORD_SEPARATOR).filter(String::isNotBlank)

private fun SharedPreferences.progressMap(prefix: String): Map<String, Long> =
    all.mapNotNull { (key, value) ->
        if (key.startsWith(prefix) && value is Long && value > 0L) key.removePrefix(prefix) to value else null
    }.toMap()

/** "1h 02m" for the time still to run, or null when the runtime or position is unknown. */
private fun remainingLabel(item: PlaylistItem, positionMs: Long?): String? {
    val position = positionMs?.takeIf { it > 0L } ?: return null
    val total = parseDurationToMillis(item.duration)?.takeIf { it > position } ?: return null
    val minutes = (total - position) / 60_000L
    if (minutes <= 0L) return null
    return if (minutes >= 60L) "${minutes / 60}h ${"%02d".format(minutes % 60)}m" else "${minutes}m"
}

@Composable
private fun rememberEntries(
    ids: List<String>,
    catalogue: Map<String, PlaylistItem>,
    progress: Map<String, Long>,
    timeLeftFormat: String
): List<LandingEntry> = remember(ids, catalogue, progress) {
    ids.mapNotNull { id ->
        val item = catalogue[id] ?: return@mapNotNull null
        val position = progress[id]
        LandingEntry(
            item = item,
            progress = watchedFraction(item, progress),
            badge = remainingLabel(item, position)?.let { timeLeftFormat.format(it) }
        )
    }
}

@Composable
internal fun HomeLandingScreen(
    playlist: LoadedPlaylist?,
    backdrop: BackdropState,
    onNavigate: (NavDestination) -> Unit,
    onSearch: () -> Unit,
    onLanguage: () -> Unit,
    onSettings: () -> Unit,
    onPlay: (PlaylistItem, String?) -> Unit
) {
    val context = LocalContext.current
    val movieStore = remember { context.getSharedPreferences("movie_library", Context.MODE_PRIVATE) }
    val seriesStore = remember { context.getSharedPreferences("series_library", Context.MODE_PRIVATE) }
    val liveStore = remember { context.getSharedPreferences("favorite_channels", Context.MODE_PRIVATE) }
    val timeLeft = stringResource(R.string.landing_time_left)

    val byKey = remember(playlist) { playlist?.items.orEmpty().associateBy(::channelKey) }
    val movieProgress = remember(movieStore, playlist) { movieStore.progressMap("progress_") }
    val latest = remember(playlist) { ContinueWatchingStore.read(context) }

    // The single most recently touched title leads, then each section's own history behind it.
    // There is no global timeline in the app's storage, so this is the most honest ordering
    // available: what you were last watching, then what you have been watching.
    val orderedIds = remember(playlist, latest) {
        val ids = LinkedHashSet<String>()
        latest?.itemKey?.let(ids::add)
        ids += movieStore.idList("recent_v1")
        ids += seriesStore.idList("recent_v1")
        ids += liveStore.idList("recent_ids_v3")
        ids.toList()
    }
    val entries = remember(orderedIds, byKey, movieProgress, latest) {
        orderedIds.mapNotNull { id ->
            val item = byKey[id] ?: return@mapNotNull null
            LandingEntry(
                item = item,
                episodeId = latest?.episodeId?.takeIf { latest.itemKey == id },
                progress = if (item.kind == MediaKind.MOVIE) watchedFraction(item, movieProgress) else null,
                badge = if (item.kind == MediaKind.MOVIE) {
                    remainingLabel(item, movieProgress[id])?.let { timeLeft.format(it) }
                } else null
            )
        }.take(24)
    }

    val favorites = rememberMixedFavorites(movieStore, seriesStore, liveStore)
    LandingScaffold(
        destination = NavDestination.HOME,
        rows = listOf(
            LandingRow("home_continue", stringResource(R.string.continue_watching_title), entries)
        ),
        tile = null,
        backdrop = backdrop,
        onSelect = { onPlay(it.item, it.episodeId) },
        isFavorite = { favorites.contains(it) },
        onToggleFavorite = { favorites.toggle(it) },
        onNavigate = onNavigate,
        onSearch = onSearch,
        onLanguage = onLanguage,
        onSettings = onSettings,
        emptyMessage = stringResource(R.string.home_no_history),
        footer = { LandingDeviceStrip(playlist) }
    )
}

@Composable
internal fun MoviesLandingScreen(
    playlist: LoadedPlaylist?,
    backdrop: BackdropState,
    onNavigate: (NavDestination) -> Unit,
    onSearch: () -> Unit,
    onLanguage: () -> Unit,
    onSettings: () -> Unit,
    onOpenAll: () -> Unit,
    onPlay: (PlaylistItem, String?) -> Unit
) {
    val context = LocalContext.current
    val store = remember { context.getSharedPreferences("movie_library", Context.MODE_PRIVATE) }
    val timeLeft = stringResource(R.string.landing_time_left)
    val catalogue = remember(playlist) {
        playlist?.items.orEmpty().filter { it.kind == MediaKind.MOVIE }.associateBy(::channelKey)
    }
    val progress = remember(store, playlist) { store.progressMap("progress_") }
    val recent = rememberEntries(remember(store, playlist) { store.idList("recent_v1") }, catalogue, progress, timeLeft)
    val favorites = rememberFavoriteSet(store, "favorites")
    val favoriteEntries = rememberEntries(
        remember(favorites.ids, catalogue) { favorites.ids.filter(catalogue::containsKey) },
        catalogue, progress, timeLeft
    )

    LandingScaffold(
        destination = NavDestination.MOVIES,
        rows = listOf(
            LandingRow("movies_recent", stringResource(R.string.landing_recently_watched_movies), recent),
            LandingRow("movies_favorites", stringResource(R.string.section_favorites), favoriteEntries)
        ),
        tile = LandingTile(
            title = stringResource(R.string.landing_all_movie_categories),
            caption = stringResource(R.string.landing_browse_library),
            onClick = onOpenAll
        ),
        backdrop = backdrop,
        onSelect = { onPlay(it.item, null) },
        isFavorite = { favorites.ids.contains(channelKey(it)) },
        onToggleFavorite = { favorites.toggle(channelKey(it)) },
        onNavigate = onNavigate,
        onSearch = onSearch,
        onLanguage = onLanguage,
        onSettings = onSettings,
        emptyMessage = stringResource(R.string.landing_nothing_yet)
    )
}

@Composable
internal fun SeriesLandingScreen(
    playlist: LoadedPlaylist?,
    backdrop: BackdropState,
    onNavigate: (NavDestination) -> Unit,
    onSearch: () -> Unit,
    onLanguage: () -> Unit,
    onSettings: () -> Unit,
    onOpenAll: () -> Unit,
    onPlay: (PlaylistItem, String?) -> Unit
) {
    val context = LocalContext.current
    val store = remember { context.getSharedPreferences("series_library", Context.MODE_PRIVATE) }
    val catalogue = remember(playlist) {
        playlist?.items.orEmpty().filter { it.kind == MediaKind.SERIES }.associateBy(::channelKey)
    }
    val latest = remember(playlist) { ContinueWatchingStore.read(context) }
    // A series' own progress lives per episode, so the card carries no bar: showing one would mean
    // guessing how far through the whole run the viewer is, which the app does not know.
    val recent = remember(store, catalogue, latest) {
        store.idList("recent_v1").mapNotNull { id ->
            catalogue[id]?.let {
                LandingEntry(it, episodeId = latest?.episodeId?.takeIf { _ -> latest.itemKey == id })
            }
        }
    }
    val favorites = rememberFavoriteSet(store, "favorites")
    val favoriteEntries = remember(favorites.ids, catalogue) {
        favorites.ids.mapNotNull { id -> catalogue[id]?.let { LandingEntry(it) } }
    }

    LandingScaffold(
        destination = NavDestination.SERIES,
        rows = listOf(
            LandingRow("series_recent", stringResource(R.string.landing_recently_watched_series), recent),
            LandingRow("series_favorites", stringResource(R.string.section_favorites), favoriteEntries)
        ),
        tile = LandingTile(
            title = stringResource(R.string.landing_all_series_categories),
            caption = stringResource(R.string.landing_browse_library),
            onClick = onOpenAll
        ),
        backdrop = backdrop,
        onSelect = { onPlay(it.item, it.episodeId) },
        isFavorite = { favorites.ids.contains(channelKey(it)) },
        onToggleFavorite = { favorites.toggle(channelKey(it)) },
        onNavigate = onNavigate,
        onSearch = onSearch,
        onLanguage = onLanguage,
        onSettings = onSettings,
        emptyMessage = stringResource(R.string.landing_nothing_yet)
    )
}

@Composable
internal fun LiveLandingScreen(
    playlist: LoadedPlaylist?,
    backdrop: BackdropState,
    onNavigate: (NavDestination) -> Unit,
    onSearch: () -> Unit,
    onLanguage: () -> Unit,
    onSettings: () -> Unit,
    onOpenAll: () -> Unit,
    onPlay: (PlaylistItem, String?) -> Unit
) {
    val context = LocalContext.current
    val store = remember { context.getSharedPreferences("favorite_channels", Context.MODE_PRIVATE) }
    val catalogue = remember(playlist) {
        playlist?.items.orEmpty().filter { it.kind == MediaKind.LIVE }.associateBy(::channelKey)
    }
    val recent = remember(store, catalogue) {
        store.idList("recent_ids_v3").mapNotNull { id -> catalogue[id]?.let { LandingEntry(it) } }
    }
    val favorites = rememberFavoriteSet(store, "ids")
    val favoriteEntries = remember(favorites.ids, catalogue) {
        favorites.ids.mapNotNull { id -> catalogue[id]?.let { LandingEntry(it) } }
    }

    LandingScaffold(
        destination = NavDestination.LIVE,
        rows = listOf(
            LandingRow("live_recent", stringResource(R.string.landing_recently_watched_channels), recent),
            LandingRow("live_favorites", stringResource(R.string.section_favorites), favoriteEntries)
        ),
        tile = LandingTile(
            title = stringResource(R.string.landing_all_channel_categories),
            caption = stringResource(R.string.landing_browse_channels),
            onClick = onOpenAll
        ),
        backdrop = backdrop,
        onSelect = { onPlay(it.item, null) },
        isFavorite = { favorites.ids.contains(channelKey(it)) },
        onToggleFavorite = { favorites.toggle(channelKey(it)) },
        onNavigate = onNavigate,
        onSearch = onSearch,
        onLanguage = onLanguage,
        onSettings = onSettings,
        emptyMessage = stringResource(R.string.home_no_history)
    )
}

/**
 * A section's favourites, read from and written straight back to the same preference entry the
 * browsing screens use. The landing pages are another view onto that one set, never a second copy
 * of it.
 */
internal class FavoriteSet(
    private val store: SharedPreferences,
    private val key: String,
    initial: Set<String>
) {
    var ids by mutableStateOf(initial)
        private set

    fun toggle(id: String) {
        val updated = if (id in ids) ids - id else ids + id
        ids = updated
        store.edit().putStringSet(key, updated).apply()
    }
}

@Composable
internal fun rememberFavoriteSet(store: SharedPreferences, key: String): FavoriteSet =
    remember(store, key) { FavoriteSet(store, key, store.getStringSet(key, emptySet()).orEmpty().toSet()) }

/** Home spans all three sections, so its favourite toggle has to route to the right store. */
internal class MixedFavorites(
    private val movies: FavoriteSet,
    private val series: FavoriteSet,
    private val live: FavoriteSet
) {
    private fun setFor(item: PlaylistItem) = when (item.kind) {
        MediaKind.MOVIE -> movies
        MediaKind.SERIES -> series
        MediaKind.LIVE -> live
    }

    fun contains(item: PlaylistItem) = channelKey(item) in setFor(item).ids
    fun toggle(item: PlaylistItem) = setFor(item).toggle(channelKey(item))
}

@Composable
private fun rememberMixedFavorites(
    movieStore: SharedPreferences,
    seriesStore: SharedPreferences,
    liveStore: SharedPreferences
): MixedFavorites {
    val movies = rememberFavoriteSet(movieStore, "favorites")
    val series = rememberFavoriteSet(seriesStore, "favorites")
    val live = rememberFavoriteSet(liveStore, "ids")
    return remember(movies, series, live) { MixedFavorites(movies, series, live) }
}
