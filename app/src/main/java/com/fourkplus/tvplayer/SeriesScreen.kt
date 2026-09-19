package com.fourkplus.tvplayer

import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.fourkplus.tvplayer.data.LoadedPlaylist
import com.fourkplus.tvplayer.data.MediaKind
import com.fourkplus.tvplayer.data.PlaylistInput
import com.fourkplus.tvplayer.data.PlaylistItem
import com.fourkplus.tvplayer.data.PlaylistKind
import com.fourkplus.tvplayer.data.SeriesDetailsInfo
import com.fourkplus.tvplayer.data.SeriesEpisode
import com.fourkplus.tvplayer.ui.theme.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.fourkplus.tvplayer.ui.design.BackdropState

private enum class SeriesView { BROWSE, CATEGORY, DETAILS, PLAYER }
private val seasonEpisodePattern = Regex("s(\\d{1,2})[\\s._-]*e(\\d{1,3})", RegexOption.IGNORE_CASE)

@Composable
internal fun SeriesScreen(
    playlist: LoadedPlaylist?,
    loadDetails: suspend (PlaylistItem) -> Result<SeriesDetailsInfo>,
    source: PlaylistInput?,
    onBack: () -> Unit,
    requirePin: (() -> Unit) -> Unit,
    resumeRequest: ResumeRequest? = null,
    onResumeHandled: () -> Unit = {},
    /** A category to open straight into, such as Favorites, instead of the browse view. */
    openCategory: String? = null,
    onOpenCategoryHandled: () -> Unit = {},
    // The app-wide background, and the details fetch that supplies a title's landscape artwork, so
    // the browser's grid can drive the background the way the landing pages do.
    backdrop: BackdropState? = null,
    loadBio: (suspend (PlaylistItem) -> ItemBio?)? = null
) {
    val context = LocalContext.current
    val parental = remember { context.getSharedPreferences("parental_settings", Context.MODE_PRIVATE) }
    var hiddenCategories by remember {
        mutableStateOf(parental.getStringSet("hidden_series_categories", emptySet()).orEmpty().toSet())
    }
    val lockedCategories = remember { parental.getStringSet("locked_series_categories", emptySet()).orEmpty().toSet() }
    val seriesItems = remember(playlist, hiddenCategories) {
        playlist?.items?.filter { it.kind == MediaKind.SERIES && it.group !in hiddenCategories }.orEmpty()
    }
    // Bumped after a long-press reorder so `categories` recomputes even though `seriesItems`
    // itself (the applyCategoryOrder input) hasn't changed.
    var categoryOrderVersion by remember { mutableIntStateOf(0) }
    val categories = remember(seriesItems, categoryOrderVersion) { applyCategoryOrder(context, MediaKind.SERIES, seriesItems.map { it.group }.distinct()) }
    // The shelf whose long-press menu is open on the touch layout. Held here rather than inside
    // the shelf so the dialog outlives the row that opened it - a shelf that gets hidden or moved
    // is recomposed out from under its own menu.
    var shelfMenuFor by remember { mutableStateOf<String?>(null) }
    shelfMenuFor?.let { menuCategory ->
        CategoryActionsDialog(
            category = localizedSectionTitle(menuCategory),
            onHide = {
                val updated = hiddenCategories + menuCategory
                hiddenCategories = updated
                parental.edit().putStringSet("hidden_series_categories", updated).apply()
            },
            // No hand-move on a touch layout: nudging one place at a time is a D-pad gesture and
            // there is nothing on a phone that performs it.
            onMoveManually = null,
            onMoveToTop = { moveCategoryToEnd(context, MediaKind.SERIES, categories, menuCategory, toTop = true); categoryOrderVersion++ },
            onMoveToBottom = { moveCategoryToEnd(context, MediaKind.SERIES, categories, menuCategory, toTop = false); categoryOrderVersion++ },
            onDismiss = { shelfMenuFor = null }
        )
    }
    val store = remember { context.getSharedPreferences("series_library", Context.MODE_PRIVATE) }
    // Opened straight into a named category - the Favorites box on the landing page uses this -
    // instead of landing on the browse view and making the viewer find it again. This has to be the
    // state's initial value rather than an effect: the browser below is the same composable for
    // both views, so it composes and claims focus on the very first pass. Setting the category
    // afterwards would leave the ring on Continue watching while the grid showed something else.
    var view by remember { mutableStateOf(if (openCategory != null) SeriesView.CATEGORY else SeriesView.BROWSE) }
    // Which list the open details page was reached from, and the poster to scroll back to and
    // focus once it closes - the grid is torn down while details are showing, so without these it
    // rebuilds scrolled to the top with focus on the first item.
    var detailsReturnView by remember { mutableStateOf(SeriesView.BROWSE) }
    var restoreFocusKey by remember { mutableStateOf<String?>(null) }
    var selectedCategory by remember { mutableStateOf(openCategory ?: "Continue watching") }
    // Held here rather than inside the browser: opening a details page replaces the browser, and a
    // scroll position remembered there would not survive the trip back. See LandscapeSeriesBrowser.
    val categoryListState = rememberLazyListState()
    var selectedSeries by remember { mutableStateOf<PlaylistItem?>(null) }
    var selectedEpisode by remember { mutableStateOf<SeriesEpisode?>(null) }
    var details by remember { mutableStateOf<SeriesDetailsInfo?>(null) }
    var detailsLoading by remember { mutableStateOf(false) }
    var detailsError by remember { mutableStateOf<String?>(null) }
    var selectedSeason by remember { mutableIntStateOf(1) }
    var search by remember { mutableStateOf("") }
    var favoriteIds by remember {
        mutableStateOf(store.getStringSet("favorites", emptySet()).orEmpty().toSet())
    }
    var recentIds by remember {
        mutableStateOf(store.getString("recent_v1", "").orEmpty().split('\u001F').filter(String::isNotBlank))
    }
    var continueSeriesIds by remember {
        mutableStateOf(store.getStringSet("continue_series", emptySet()).orEmpty().toSet())
    }
    var progress by remember {
        mutableStateOf(
            store.all.mapNotNull { (key, value) ->
                if (key.startsWith("episode_progress_") && value is Long && value > 0L) {
                    key.removePrefix("episode_progress_") to value
                } else null
            }.toMap()
        )
    }
    var watchedEpisodeIds by remember {
        mutableStateOf(store.getStringSet("watched_episodes", emptySet()).orEmpty().toSet())
    }

    // The initial values above already carry the requested category; this only tells the caller it
    // has been consumed, and covers a request arriving while the screen is already up.
    LaunchedEffect(openCategory) {
        val target = openCategory ?: return@LaunchedEffect
        selectedCategory = target
        search = ""
        view = SeriesView.CATEGORY
        onOpenCategoryHandled()
    }

    val byId = remember(seriesItems) { seriesItems.associateBy(::channelKey) }
    val favorites = remember(seriesItems, favoriteIds) { seriesItems.filter { channelKey(it) in favoriteIds } }
    val recent = remember(byId, recentIds) { recentIds.mapNotNull(byId::get) }
    val continueWatching = remember(seriesItems, continueSeriesIds) {
        seriesItems.filter { channelKey(it) in continueSeriesIds }
    }

    fun toggleFavorite(series: PlaylistItem) {
        val id = channelKey(series)
        val updated = if (id in favoriteIds) favoriteIds - id else favoriteIds + id
        favoriteIds = updated
        store.edit().putStringSet("favorites", updated).apply()
    }

    // M3U playlists (including MAC-activation accounts that resolve to an M3U link rather than
    // Xtream) list every episode as its own flat entry instead of a series+episode API hierarchy,
    // so there is no series_id to fetch episodes from. Synthesize the season/episode breakdown
    // from sibling entries sharing the same group instead of calling the Xtream-only endpoint.
    fun buildLocalSeriesDetails(series: PlaylistItem): SeriesDetailsInfo {
        val siblings = seriesItems.filter { it.group == series.group }
        val episodes = siblings.mapIndexed { index, item ->
            val match = seasonEpisodePattern.find(item.name)
            val season = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val episodeNumber = match?.groupValues?.get(2)?.toIntOrNull() ?: (index + 1)
            val title = match?.let { item.name.removeRange(it.range).trim(' ', '-', '.', '_', ':') }
                ?.ifBlank { item.name } ?: item.name
            SeriesEpisode(
                id = item.streamUrl,
                seasonNumber = season,
                episodeNumber = episodeNumber,
                title = title,
                streamUrl = item.streamUrl,
                thumbnailUrl = item.logoUrl,
                duration = item.duration,
                description = item.description
            )
        }.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
        return SeriesDetailsInfo(posterUrl = series.logoUrl, episodes = episodes)
    }

    // Providers very often name an episode after the series it belongs to ("Backstrom-S1.E2"),
    // which would read as the series name twice over in the player's title. The episode's own
    // title is only appended when it actually says something the series name and S/E numbers
    // haven't already.
    fun episodeLabel(series: PlaylistItem, episode: SeriesEpisode): String {
        val base = "${series.name} • S${episode.seasonNumber} E${episode.episodeNumber}"
        val title = episode.title.trim()
        val redundant = title.isEmpty() || title.contains(series.name, ignoreCase = true)
        return if (redundant) base else "$base • $title"
    }

    fun episodePlaylistItem(series: PlaylistItem, episode: SeriesEpisode) = PlaylistItem(
        name = episodeLabel(series, episode),
        streamUrl = episode.streamUrl,
        group = series.name,
        logoUrl = episode.thumbnailUrl ?: series.logoUrl,
        channelId = episode.id,
        kind = MediaKind.SERIES,
        description = episode.description,
        duration = episode.duration
    )

    fun openDetails(series: PlaylistItem) {
        detailsReturnView = if (view == SeriesView.CATEGORY) SeriesView.CATEGORY else SeriesView.BROWSE
        selectedSeries = series
        selectedEpisode = null
        details = null
        detailsError = null
        selectedSeason = store.getInt("last_season_${channelKey(series)}", 1)
        view = SeriesView.DETAILS
    }

    fun recordRecent(series: PlaylistItem) {
        val id = channelKey(series)
        val updated = (listOf(id) + recentIds.filterNot { it == id }).take(30)
        recentIds = updated
        store.edit().putString("recent_v1", updated.joinToString("\u001F")).apply()
    }

    fun saveEpisodeProgress(series: PlaylistItem, episode: SeriesEpisode, position: Long, duration: Long) {
        val finished = duration > 0L && position >= duration - 20_000L
        val normalized = if (finished) 0L else position.coerceAtLeast(0L)
        progress = if (normalized == 0L) progress - episode.id else progress + (episode.id to normalized)
        val updatedContinue = if (normalized > 0L) continueSeriesIds + channelKey(series) else {
            val otherEpisodeInProgress = details?.episodes.orEmpty().any { it.id != episode.id && (progress[it.id] ?: 0L) > 0L }
            if (otherEpisodeInProgress) continueSeriesIds else continueSeriesIds - channelKey(series)
        }
        continueSeriesIds = updatedContinue
        val editor = store.edit()
            .putLong("episode_progress_${episode.id}", normalized)
            .putStringSet("continue_series", updatedContinue)
            .putString("last_episode_${channelKey(series)}", episode.id)
        if (finished && episode.id !in watchedEpisodeIds) {
            watchedEpisodeIds = watchedEpisodeIds + episode.id
            editor.putStringSet("watched_episodes", watchedEpisodeIds)
        }
        editor.apply()
        if (normalized > 0L) {
            com.fourkplus.tvplayer.data.ContinueWatchingStore.record(context, MediaKind.SERIES, channelKey(series), episode.id)
        } else {
            com.fourkplus.tvplayer.data.ContinueWatchingStore.clear(context, channelKey(series))
        }
    }

    fun goBack() {
        when (view) {
            SeriesView.BROWSE -> onBack()
            SeriesView.CATEGORY -> { search = ""; view = SeriesView.BROWSE }
            SeriesView.DETAILS -> {
                restoreFocusKey = selectedSeries?.let(::channelKey)
                view = detailsReturnView
            }
            SeriesView.PLAYER -> view = SeriesView.DETAILS
        }
    }
    BackHandler(onBack = ::goBack)

    LaunchedEffect(selectedSeries, view) {
        val series = selectedSeries
        if (series != null && view == SeriesView.DETAILS && details == null && !detailsLoading) {
            if (source == null || source.kind == PlaylistKind.PROVIDER_LOGIN) {
                detailsLoading = true
                loadDetails(series)
                    .onSuccess { loaded ->
                        details = loaded
                        val available = loaded.seasons
                        if (selectedSeason !in available) selectedSeason = available.firstOrNull() ?: 1
                    }
                    .onFailure { detailsError = it.message }
                detailsLoading = false
            } else {
                val loaded = buildLocalSeriesDetails(series)
                details = loaded
                val available = loaded.seasons
                if (selectedSeason !in available) selectedSeason = available.firstOrNull() ?: 1
            }
        }
    }

    LaunchedEffect(resumeRequest, byId) {
        val request = resumeRequest ?: return@LaunchedEffect
        val series = byId[request.itemKey]
        if (series == null) {
            onResumeHandled()
            return@LaunchedEffect
        }
        selectedSeries = series
        selectedEpisode = null
        details = null
        detailsError = null
        selectedSeason = store.getInt("last_season_${channelKey(series)}", 1)
        view = SeriesView.DETAILS
        if (request.episodeId == null) onResumeHandled()
    }

    LaunchedEffect(details, resumeRequest) {
        val request = resumeRequest ?: return@LaunchedEffect
        val episodeId = request.episodeId ?: return@LaunchedEffect
        val loadedDetails = details ?: return@LaunchedEffect
        val series = selectedSeries?.takeIf { channelKey(it) == request.itemKey } ?: return@LaunchedEffect
        loadedDetails.episodes.firstOrNull { it.id == episodeId }?.let { episode ->
            selectedEpisode = episode
            selectedSeason = episode.seasonNumber
            if (request.autoPlay) view = SeriesView.PLAYER
        }
        onResumeHandled()
    }

    // No flat background fill here otherwise: the themed backdrop is painted app-wide behind the
    // Scaffold in MainActivity. The series' own backdrop/poster below is a per-title image, not a
    // decorative one, so it's drawn on top of that shared background instead of replacing it.
    Box(Modifier.fillMaxSize()) {
        if (view == SeriesView.DETAILS) {
            val pageBackdrop = details?.backdropUrl ?: details?.posterUrl ?: selectedSeries?.logoUrl
            if (!pageBackdrop.isNullOrBlank()) {
                AsyncImage(
                    model = pageBackdrop,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = .56f),
                                MaterialTheme.colorScheme.background.copy(alpha = .78f),
                                MaterialTheme.colorScheme.background.copy(alpha = .96f)
                            )
                        )
                    )
                )
            }
        }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        // Rendered here, above the landscape/portrait split, as a single orientation- and
        // layout-independent overlay so its call site never changes position in the
        // composition — required both to keep the player instance alive across rotation and
        // for picture-in-picture (which needs the video to fill the Activity's own window).
        if (view == SeriesView.PLAYER) {
            val series = selectedSeries
            val episode = selectedEpisode
            if (series != null && episode != null) {
                val allEpisodes = details?.episodes.orEmpty()
                val relatedItems = remember(series, allEpisodes) {
                    allEpisodes.map { episodePlaylistItem(series, it) }
                }
                MoviePlayer(
                    movie = episodePlaylistItem(series, episode),
                    startPosition = progress[episode.id] ?: 0L,
                    onProgress = { position, duration ->
                        saveEpisodeProgress(series, episode, position, duration)
                    },
                    onExit = { view = SeriesView.DETAILS },
                    modifier = Modifier.fillMaxSize(),
                    relatedItems = relatedItems,
                    onRelatedItemChange = { item ->
                        allEpisodes.firstOrNull { it.id == item.channelId }?.let { next ->
                            selectedEpisode = next
                            selectedSeason = next.seasonNumber
                            recordRecent(series)
                        }
                    }
                )
            }
            return@BoxWithConstraints
        }
        if (landscape && view in setOf(SeriesView.BROWSE, SeriesView.CATEGORY)) {
            LandscapeSeriesBrowser(
                seriesItems = seriesItems,
                categories = categories,
                selectedCategory = selectedCategory,
                search = search,
                favoriteIds = favoriteIds,
                recent = recent,
                favorites = favorites,
                continueWatching = continueWatching,
                restoreFocusKey = restoreFocusKey,
                onRestoreHandled = { restoreFocusKey = null },
                onCategory = { category ->
                    fun enter() { selectedCategory = category; search = ""; view = SeriesView.CATEGORY }
                    if (category in lockedCategories) requirePin(::enter) else enter()
                },
                onSearch = { search = it },
                onFavorite = ::toggleFavorite,
                onSeries = ::openDetails,
                onCategoriesReordered = { categoryOrderVersion++ },
                // Hiding lives here rather than in the browser, because the set of hidden
                // categories is this screen's state - the browser only knows which row was
                // long-pressed.
                onHideCategory = { hidden ->
                    val updated = hiddenCategories + hidden
                    hiddenCategories = updated
                    parental.edit().putStringSet("hidden_series_categories", updated).apply()
                },
                categoryListState = categoryListState,
                onBack = {
                    if (view == SeriesView.CATEGORY) view = SeriesView.BROWSE else onBack()
                },
                backdrop = backdrop,
                loadBio = loadBio
            )
            return@BoxWithConstraints
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = if (landscape) 34.dp else 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedIconButton(onClick = ::goBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.cd_back)) }
                Text(
                    when (view) {
                        SeriesView.BROWSE -> stringResource(R.string.nav_series)
                        SeriesView.CATEGORY -> localizedSectionTitle(selectedCategory)
                        else -> details?.originalTitle ?: selectedSeries?.name ?: stringResource(R.string.nav_series)
                    },
                    modifier = Modifier.weight(1f),
                    fontSize = if (landscape) 23.sp else 22.sp,
                    lineHeight = if (landscape) 27.sp else 26.sp,
                    fontWeight = FontWeight.Black
                )
                if (view == SeriesView.CATEGORY && selectedCategory !in setOf("Continue watching", "Recently watched", "Favorites")) {
                    TextButton(onClick = {
                        hiddenCategories = hiddenCategories + selectedCategory
                        parental.edit().putStringSet("hidden_series_categories", hiddenCategories).apply()
                        search = ""
                        view = SeriesView.BROWSE
                    }) {
                        Icon(Icons.Default.VisibilityOff, null)
                        Spacer(Modifier.width(5.dp))
                        Text(stringResource(R.string.action_hide))
                    }
                }
                if (view == SeriesView.DETAILS && selectedSeries != null) {
                    AnimatedIconButton(onClick = { toggleFavorite(selectedSeries!!) }) {
                        Icon(
                            if (channelKey(selectedSeries!!) in favoriteIds) Icons.Default.Star else Icons.Default.StarBorder,
                            if (channelKey(selectedSeries!!) in favoriteIds) stringResource(R.string.cd_favorite_remove) else stringResource(R.string.cd_favorite_add),
                            tint = if (channelKey(selectedSeries!!) in favoriteIds) Orange else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            when (view) {
                SeriesView.BROWSE -> {
                    SeriesSearch(search, { search = it })
                    if (search.isNotBlank()) {
                        val results = seriesItems.filter { it.name.contains(search.trim(), true) }
                        SeriesGrid(
                            results, favoriteIds, ::toggleFavorite, ::openDetails, Modifier.weight(1f), landscape,
                            restoreFocusKey = restoreFocusKey, onRestoreHandled = { restoreFocusKey = null }
                        )
                    } else {
                        val sections = buildList {
                            if (continueWatching.isNotEmpty()) add("Continue watching" to continueWatching)
                            add("Recently watched" to recent)
                            add("Favorites" to favorites)
                            categories.forEach { category -> add(category to seriesItems.filter { it.group == category }) }
                        }
                        if (sections.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.no_series_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            LazyColumn(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                                contentPadding = PaddingValues(bottom = 20.dp)
                            ) {
                                items(sections) { (title, sectionItems) ->
                                    SeriesShelf(
                                        title = title,
                                        seriesItems = sectionItems,
                                        favoriteIds = favoriteIds,
                                        onSeeAll = {
                                            fun enter() { selectedCategory = title; view = SeriesView.CATEGORY }
                                            if (title in lockedCategories) requirePin(::enter) else enter()
                                        },
                                        onHide = if (title in setOf("Continue watching", "Recently watched", "Favorites")) null else {{
                                            val updated = hiddenCategories + title
                                            hiddenCategories = updated
                                            parental.edit().putStringSet("hidden_series_categories", updated).apply()
                                        }},
                                        onLongPressTitle = if (title in setOf("Continue watching", "Recently watched", "Favorites")) null else {{
                                            shelfMenuFor = title
                                        }},
                                        onFavorite = ::toggleFavorite,
                                        onSeries = ::openDetails
                                    )
                                }
                            }
                        }
                    }
                }

                SeriesView.CATEGORY -> {
                    SeriesSearch(search, { search = it })
                    val base = when (selectedCategory) {
                        "Continue watching" -> continueWatching
                        "Recently watched" -> recent
                        "Favorites" -> favorites
                        else -> seriesItems.filter { it.group == selectedCategory }
                    }
                    val results = if (search.isBlank()) base else seriesItems.filter { it.name.contains(search.trim(), true) }
                    SeriesGrid(
                        results, favoriteIds, ::toggleFavorite, ::openDetails, Modifier.weight(1f), landscape,
                        restoreFocusKey = restoreFocusKey, onRestoreHandled = { restoreFocusKey = null }
                    )
                }

                SeriesView.DETAILS -> selectedSeries?.let { series ->
                    SeriesDetails(
                        series = series,
                        details = details,
                        loading = detailsLoading,
                        error = detailsError,
                        selectedSeason = selectedSeason,
                        onSeason = {
                            selectedSeason = it
                            store.edit().putInt("last_season_${channelKey(series)}", it).apply()
                        },
                        favorite = channelKey(series) in favoriteIds,
                        progress = progress,
                        watchedEpisodeIds = watchedEpisodeIds,
                        onFavorite = { toggleFavorite(series) },
                        onEpisode = { episode ->
                            selectedEpisode = episode
                            selectedSeason = episode.seasonNumber
                            recordRecent(series)
                            view = SeriesView.PLAYER
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                // Rendered as a full-screen overlay above this BoxWithConstraints instead
                // (see the early return at the top of it) — see comment there for why.
                SeriesView.PLAYER -> Unit
            }
        }
    }
    }
}

@Composable
private fun LandscapeSeriesBrowser(
    seriesItems: List<PlaylistItem>,
    categories: List<String>,
    selectedCategory: String,
    search: String,
    favoriteIds: Set<String>,
    recent: List<PlaylistItem>,
    favorites: List<PlaylistItem>,
    continueWatching: List<PlaylistItem>,
    restoreFocusKey: String?,
    onRestoreHandled: () -> Unit,
    onCategory: (String) -> Unit,
    onSearch: (String) -> Unit,
    onFavorite: (PlaylistItem) -> Unit,
    onSeries: (PlaylistItem) -> Unit,
    onCategoriesReordered: () -> Unit,
    /** Hides a category from the main screens. Null on views that are not categories at all -
     *  Continue watching, Recently watched and Favorites have nothing behind them to hide. */
    onHideCategory: ((String) -> Unit)? = null,
    onBack: () -> Unit,
    // Owned by the caller, which outlives this browser - see the matching comment in
    // LandscapeMovieBrowser.
    categoryListState: LazyListState,
    // Drives the app-wide background from whichever poster the remote is on - see
    // [BackdropFollowsFocus]. Null leaves whatever artwork is already up alone.
    backdrop: BackdropState? = null,
    loadBio: (suspend (PlaylistItem) -> ItemBio?)? = null
) {
    val special = listOf("Continue watching", "Recently watched", "Favorites")
    val allCategories = special + categories
    val base = when (selectedCategory) {
        "Continue watching" -> continueWatching
        "Recently watched" -> recent
        "Favorites" -> favorites
        else -> seriesItems.filter { it.group == selectedCategory }
    }
    val displayed = if (search.isBlank()) base else seriesItems.filter { it.name.contains(search.trim(), true) }
    val context = LocalContext.current
    val isTv = remember { context.isTvDevice() }
    // The category currently being hand-moved after a long-press - Up/Down nudges it, OK drops it.
    var reorderingCategory by remember { mutableStateOf<String?>(null) }
    // The category whose long-press menu is open, if any. The gesture opens a menu now rather
    // than committing straight to hand-moving, which was one of four things somebody might want.
    var categoryMenuFor by remember { mutableStateOf<String?>(null) }
    categoryMenuFor?.let { menuCategory ->
        CategoryActionsDialog(
            category = menuCategory,
            onHide = onHideCategory?.let { hide -> { hide(menuCategory) } },
            onMoveManually = { reorderingCategory = menuCategory },
            onMoveToTop = { moveCategoryToEnd(context, MediaKind.SERIES, allCategories, menuCategory, toTop = true); onCategoriesReordered() },
            onMoveToBottom = { moveCategoryToEnd(context, MediaKind.SERIES, allCategories, menuCategory, toTop = false); onCategoriesReordered() },
            onDismiss = { categoryMenuFor = null }
        )
    }
    // Keeps the moving category in view as it's nudged past the edge of the visible list -
    // otherwise it scrolls out from under the user with no sign of where it went.
    LaunchedEffect(reorderingCategory, allCategories) {
        val index = reorderingCategory?.let(allCategories::indexOf) ?: return@LaunchedEffect
        if (index >= 0) categoryListState.animateScrollToItem(index)
    }
    val continueWatchingFocusRequester = remember { FocusRequester() }
    val selectedCategoryFocusRequester = remember { FocusRequester() }
    val categoryScope = rememberCoroutineScope()
    // Left from the grid's first column returns to the category being browsed - see the matching
    // comment in LandscapeMovieBrowser.
    fun focusSelectedCategory() {
        categoryScope.launch {
            val index = allCategories.indexOf(selectedCategory)
            if (index >= 0) runCatching { categoryListState.scrollToItem(index) }
            if (!requestFocusWithRetry(selectedCategoryFocusRequester)) {
                requestFocusWithRetry(continueWatchingFocusRequester)
            }
        }
    }
    // Skipped when returning from a details page - SeriesGrid is restoring focus to the poster the
    // user left from, and both requests racing would land focus back on the category list instead.
    // Lands on the category actually being browsed rather than always on Continue watching: when
    // the landing page opens this straight into Favorites, the ring belongs on Favorites. On the
    // ordinary way in the two are the same row, so nothing changes there.
    LaunchedEffect(isTv) {
        if (isTv && restoreFocusKey == null) focusSelectedCategory()
    }
    // Pressing OK on a category should move the remote's focus straight into that category's
    // grid rather than leaving it on the category button — 0 means "not from a press yet".
    //
    // Retried rather than asked once. The poster this points at is composed by the grid's own
    // layout pass, which has not run yet at the moment this effect starts, so a single request
    // only lands if the grid happens to win that race. It does on a fast panel; on a slower box
    // the request finds no node, fails silently, and focus is left sitting on the category.
    var categorySelectionTick by remember { mutableIntStateOf(0) }
    val firstItemFocusRequester = remember { FocusRequester() }
    LaunchedEffect(categorySelectionTick) {
        if (categorySelectionTick > 0 && isTv) requestFocusWithRetry(firstItemFocusRequester)
    }
    // The poster the remote is on, which the app-wide background follows.
    var focusedItem by remember { mutableStateOf<PlaylistItem?>(null) }
    BackdropFollowsFocus(backdrop, focusedItem, loadBio)
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // See COMPACT_TV_WIDTH: matches the Movies browser so both catalogues behave identically.
    val compact = maxWidth < COMPACT_TV_WIDTH
    Row(
        Modifier.fillMaxSize().padding(
            horizontal = if (compact) 14.dp else if (isTv) 40.dp else 18.dp,
            vertical = if (isTv) 22.dp else 8.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            Modifier.width(if (compact) 180.dp else 240.dp).fillMaxHeight(),
            shape = RoundedCornerShape(15.dp),
            color = Color.Black.copy(alpha = .34f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = .08f))
        ) {
            Column(Modifier.fillMaxSize().padding(9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedIconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.cd_back)) }
                    Text(stringResource(R.string.nav_series), fontSize = 19.sp, fontWeight = FontWeight.Black)
                }
                SeriesSearch(search, onSearch)
                LazyColumn(Modifier.weight(1f), state = categoryListState, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(allCategories, key = { it }) { category ->
                        val isReordering = category == reorderingCategory
                        Surface(
                            modifier = Modifier.fillMaxWidth()
                                .then(if (category == "Continue watching") Modifier.focusRequester(continueWatchingFocusRequester) else Modifier)
                                .then(if (category == selectedCategory) Modifier.focusRequester(selectedCategoryFocusRequester) else Modifier)
                                .categoryReorderKeys(
                                    active = isReordering,
                                    onMove = { up -> moveCategory(context, MediaKind.SERIES, categories, category, up); onCategoriesReordered() },
                                    onExit = { reorderingCategory = null }
                                )
                                .focusableClickable(
                                    cornerRadius = 11.dp,
                                    onLongClick = if (category in special) null else { { categoryMenuFor = category } }
                                ) {
                                    categorySelectionTick++
                                    onCategory(category)
                                },
                            shape = RoundedCornerShape(11.dp),
                            color = if (isReordering) Orange.copy(alpha = .3f) else if (category == selectedCategory) Cyan.copy(alpha = .24f) else Color.Transparent,
                            border = if (isReordering) BorderStroke(2.dp, Orange) else null
                        ) {
                            Row(Modifier.padding(start = 12.dp, top = 7.dp, bottom = 7.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(localizedSectionTitle(category), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = if (category == selectedCategory) FontWeight.Bold else FontWeight.Normal)
                                if (isReordering) Icon(Icons.Default.SwapVert, null, tint = Orange, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text(localizedSectionTitle(selectedCategory).ifBlank { stringResource(R.string.nav_series) }, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            SeriesGrid(
                displayed, favoriteIds, onFavorite, onSeries, Modifier.weight(1f), true,
                firstItemFocusRequester, restoreFocusKey, onRestoreHandled,
                onExitLeft = if (isTv) ({ focusSelectedCategory() }) else null,
                onItemFocused = if (backdrop != null) ({ focusedItem = it }) else null,
                openedCategoryTick = categorySelectionTick
            )
        }
    }
    }
}

@Composable
private fun SeriesSearch(value: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val isTv = remember { context.isTvDevice() }
    if (!isTv) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(15.dp),
            placeholder = { Text(stringResource(R.string.search_all_series)) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (value.isNotEmpty()) AnimatedIconButton(onClick = { onChange("") }) {
                    Icon(Icons.Default.Close, stringResource(R.string.cd_clear))
                }
            }
        )
        return
    }
    var active by remember { mutableStateOf(false) }
    var hasFocusedOnce by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    if (active) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                .onFocusChanged {
                    // See MainActivity's SearchField for why this can't just be !it.isFocused:
                    // the pre-requestFocus() unfocused callback would otherwise undo activation
                    // before the user gets a chance to type.
                    if (it.isFocused) hasFocusedOnce = true
                    else if (hasFocusedOnce) active = false
                },
            singleLine = true,
            shape = RoundedCornerShape(15.dp),
            placeholder = { Text(stringResource(R.string.search_all_series)) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (value.isNotEmpty()) AnimatedIconButton(onClick = { onChange("") }) {
                    Icon(Icons.Default.Close, stringResource(R.string.cd_clear))
                }
            },
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); active = false })
        )
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth().focusableClickable(cornerRadius = 15.dp) { hasFocusedOnce = false; active = true },
            shape = RoundedCornerShape(15.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text(value.ifBlank { stringResource(R.string.search_all_series) }, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun SeriesShelf(
    title: String,
    seriesItems: List<PlaylistItem>,
    favoriteIds: Set<String>,
    onSeeAll: () -> Unit,
    onHide: (() -> Unit)?,
    /** Held finger on the shelf's title, which opens the category menu. Null on the rows that are
     *  views rather than categories, where there is nothing to hide or reorder. */
    onLongPressTitle: (() -> Unit)? = null,
    onFavorite: (PlaylistItem) -> Unit,
    onSeries: (PlaylistItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                localizedSectionTitle(title),
                Modifier.weight(1f)
                    .then(
                        if (onLongPressTitle == null) Modifier
                        else Modifier.combinedClickable(onLongClick = onLongPressTitle, onClick = onSeeAll)
                    )
                    .padding(vertical = 4.dp),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            onHide?.let {
                AnimatedIconButton(onClick = it, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.VisibilityOff, stringResource(R.string.cd_hide_category, localizedSectionTitle(title)), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = onSeeAll) {
                Text(stringResource(R.string.action_see_all), color = Cyan)
                Icon(Icons.Default.ChevronRight, null, tint = Cyan)
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            if (seriesItems.isEmpty()) {
                item {
                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .72f), shape = RoundedCornerShape(13.dp)) {
                        Text(
                            if (title == "Favorites") stringResource(R.string.series_star_empty) else stringResource(R.string.series_watch_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
                        )
                    }
                }
            } else {
                items(seriesItems.take(16)) { series ->
                    SeriesPoster(
                        series,
                        channelKey(series) in favoriteIds,
                        { onFavorite(series) },
                        { onSeries(series) },
                        Modifier.width(128.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesGrid(
    seriesItems: List<PlaylistItem>,
    favoriteIds: Set<String>,
    onFavorite: (PlaylistItem) -> Unit,
    onSeries: (PlaylistItem) -> Unit,
    modifier: Modifier,
    landscape: Boolean,
    firstItemFocusRequester: FocusRequester? = null,
    // Set once, on the way back from a series' details page: the poster to scroll to and focus.
    restoreFocusKey: String? = null,
    onRestoreHandled: () -> Unit = {},
    // The outward key from the grid's outermost column, where there is nothing further that way
    // inside the grid. Which key that is depends on the language's direction - see `outward` below.
    onExitLeft: (() -> Unit)? = null,
    // Reports the poster the remote has landed on, so the caller can put its artwork up behind the
    // browser. Null wherever that background is not wanted.
    onItemFocused: ((PlaylistItem) -> Unit)? = null,
    // Bumped by the caller each time a category is opened; 0 means "not from a press yet".
    openedCategoryTick: Int = 0
) {
    val gridState = rememberLazyGridState()
    val restoreFocusRequester = remember { FocusRequester() }
    val restoreIndex = remember(seriesItems, restoreFocusKey) {
        if (restoreFocusKey == null) -1 else seriesItems.indexOfFirst { channelKey(it) == restoreFocusKey }
    }
    val context = LocalContext.current
    val isTvDevice = remember { context.isTvDevice() }
    LaunchedEffect(restoreFocusKey, restoreIndex) {
        if (restoreFocusKey == null) return@LaunchedEffect
        restoreListPosition(
            index = restoreIndex,
            scrollToItem = { gridState.scrollToItem(it) },
            focusRequester = restoreFocusRequester.takeIf { isTvDevice }
        )
        onRestoreHandled()
    }
    // One grid serves every category, and a lazy grid keeps whatever scroll position it had. Open
    // a category from halfway down another one and it opens halfway down - and the first poster,
    // the one the caller is about to ask for focus on, is not composed at all, which is the same
    // silent no-op described on restoreListPosition. Back to the top before that request is made.
    LaunchedEffect(openedCategoryTick) {
        if (openedCategoryTick > 0) runCatching { gridState.scrollToItem(0) }
    }
    if (seriesItems.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_series_match), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else BoxWithConstraints(modifier) {
        val columns = posterGridColumns(maxWidth, landscape)
        // The grid fills in reading order, so `index % columns == 0` is the column nearest the
        // category list whichever way the language runs - left in English, right in Arabic, because
        // the whole row mirrors. The key that leaves the grid mirrors with it; hard-coded to Left it
        // both swallowed Arabic's ordinary movement between posters and jumped to the wrong side.
        val outward = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
            Key.DirectionRight
        } else {
            Key.DirectionLeft
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(if (landscape) 7.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(if (landscape) 9.dp else 16.dp),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            // Keyed for the same reason as the Live TV channel list - see the comment there.
            gridItemsIndexed(
                seriesItems,
                key = { index, series -> "${channelKey(series)}#$index" }
            ) { index, series ->
                SeriesPoster(
                    series,
                    channelKey(series) in favoriteIds,
                    { onFavorite(series) },
                    { onSeries(series) },
                    modifier = Modifier
                        .then(if (index == 0 && firstItemFocusRequester != null) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                        .then(if (index == restoreIndex) Modifier.focusRequester(restoreFocusRequester) else Modifier)
                        .then(
                            if (onItemFocused != null) {
                                Modifier.onFocusChanged { if (it.hasFocus) onItemFocused(series) }
                            } else Modifier
                        )
                        // Only the outward column: elsewhere this key is ordinary movement between posters.
                        .then(
                            if (onExitLeft != null && index % columns == 0) {
                                Modifier.onPreviewKeyEvent { event ->
                                    if (event.isInitialKeyDown && event.key == outward) {
                                        onExitLeft(); true
                                    } else false
                                }
                            } else Modifier
                        ),
                    // A tap can never take focus, so the page would otherwise never learn what the
                    // finger picked and the backdrop would sit on whatever a remote last touched.
                    onSelected = onItemFocused?.let { notify -> { notify(series) } }
                )
            }
        }
    }
}

@Composable
private fun SeriesPoster(
    series: PlaylistItem,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onSelected: (() -> Unit)? = null
) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).focusableClickable(
            cornerRadius = 14.dp,
            selectFirstOnTouch = true,
            onSelected = onSelected,
            onClick = onClick
        )
    ) {
        Surface(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f),
            RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, Color.White.copy(alpha = .08f))
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.VideoLibrary, null, tint = BrandBlue.copy(alpha = .55f), modifier = Modifier.size(38.dp))
                if (!series.logoUrl.isNullOrBlank()) {
                    AsyncImage(series.logoUrl, series.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                AnimatedIconButton(
                    onClick = onFavorite,
                    modifier = Modifier.align(Alignment.TopEnd).size(34.dp)
                        .background(Color.Black.copy(alpha = .55f), RoundedCornerShape(10.dp))
                ) {
                    Icon(
                        if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                        if (favorite) stringResource(R.string.cd_favorite_remove) else stringResource(R.string.cd_favorite_add),
                        tint = if (favorite) Orange else Color.White,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(series.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
    }
}

@Composable
private fun SeriesDetails(
    series: PlaylistItem,
    details: SeriesDetailsInfo?,
    loading: Boolean,
    error: String?,
    selectedSeason: Int,
    onSeason: (Int) -> Unit,
    favorite: Boolean,
    progress: Map<String, Long>,
    watchedEpisodeIds: Set<String>,
    onFavorite: () -> Unit,
    onEpisode: (SeriesEpisode) -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val poster = details?.posterUrl ?: series.logoUrl
    val backdrop = details?.backdropUrl ?: poster
    val episodes = details?.episodes.orEmpty().filter { it.seasonNumber == selectedSeason }
    val seasons = details?.seasons.orEmpty()
    val displayTitle = details?.originalTitle ?: series.name
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Null unless the provider actually sent a trailer for this series, which is what gates the
    // button below - see trailerVideoId.
    val trailerId = remember(details?.trailerUrl) { trailerVideoId(details?.trailerUrl) }
    // Series details has no single Play/Resume button the way movies do - playback always starts
    // from a specific episode - so the nearest equivalent is landing D-pad focus on the first
    // episode row once the page (and its episode list) has actually loaded.
    val isTv = remember { context.isTvDevice() }
    val firstEpisodeFocusRequester = remember { FocusRequester() }
    LaunchedEffect(series, episodes.firstOrNull()?.id, isTv) {
        if (isTv && episodes.isNotEmpty()) runCatching { firstEpisodeFocusRequester.requestFocus() }
    }

    @Composable
    fun SeasonsAndEpisodes() {
        if (seasons.isNotEmpty()) {
            Text(stringResource(R.string.seasons_label), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                seasons.forEach { season ->
                    FilterChip(
                        selected = selectedSeason == season,
                        onClick = { onSeason(season) },
                        label = { Text(stringResource(R.string.season_number, season)) }
                    )
                }
            }
            Text(stringResource(R.string.episodes_label), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            episodes.forEachIndexed { index, episode ->
                EpisodeRow(
                    episode = episode,
                    progress = progress[episode.id] ?: 0L,
                    watched = episode.id in watchedEpisodeIds,
                    focusRequester = if (index == 0) firstEpisodeFocusRequester else null,
                    onClick = { onEpisode(episode) }
                )
            }
        } else if (error != null) {
            Text(stringResource(R.string.episodes_unavailable), color = MaterialTheme.colorScheme.error)
        } else {
            Text(stringResource(R.string.no_episodes_supplied), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (landscape) {
        // TV has the full screen width to work with, so poster/actions/pills sit in a fixed-width
        // left column instead of stacked above a single scrolling column that used to run the
        // full width of the screen for a title, some pills, and two buttons.
        Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.width(200.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 12.dp,
                    border = BorderStroke(2.dp, Color.White.copy(alpha = .18f))
                ) {
                    if (!poster.isNullOrBlank()) AsyncImage(poster, series.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    trailerId?.let { id ->
                        OutlinedButton(onClick = { openTrailer(context, id) }, modifier = Modifier.weight(1f).height(44.dp)) {
                            Icon(Icons.Default.SmartDisplay, null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.trailer_label), fontSize = 13.sp)
                        }
                    }
                    AnimatedFilledTonalIconButton(onClick = onFavorite, modifier = Modifier.size(44.dp)) {
                        Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder, if (favorite) stringResource(R.string.cd_favorite_remove) else stringResource(R.string.cd_favorite_add), tint = if (favorite) Orange else Cyan)
                    }
                }
            }
            // A television keeps title, pills, plot and credits fixed above an episode list that
            // scrolls by itself: a remote walks down into that list and the header stays put, which
            // is why the header is written so compactly - small fonts, two lines of plot - to leave
            // the list as much of the screen as it can.
            //
            // A phone cannot do that. The same fixed header left a single episode row along the
            // bottom edge and nothing to drag, so the rest of a series was unreachable. Here the
            // whole right-hand side is one list instead, and a finger scrolls all of it, plot and
            // credits included - which also means the plot need no longer be cut to two lines.
            //
            // The title goes with it: the page's own header carries it a few millimetres above,
            // and saying it twice only costs the episodes another line.
            if (BuildConfig.TOUCH_BUILD) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item {
                        FlowRowPills {
                            details?.rating?.takeIf { it != "0" && it != "0.0" }?.let { SeriesPill("★ $it/10", Orange) }
                            details?.year?.takeIf(String::isNotBlank)?.let { SeriesPill(it, Cyan) }
                            details?.genre?.takeIf(String::isNotBlank)?.let { SeriesPill(it, BrandBlue) }
                            SeriesPill(series.group, Cyan)
                        }
                    }
                    if (loading) {
                        item {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(stringResource(R.string.loading_seasons_episodes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        item {
                            Text(
                                details?.description?.takeIf(String::isNotBlank) ?: stringResource(R.string.no_series_details),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                lineHeight = 15.sp
                            )
                        }
                        details?.cast?.takeIf(String::isNotBlank)?.let { cast ->
                            item { SeriesCredit(Icons.Default.Groups, stringResource(R.string.cast_label), cast) }
                        }
                        details?.director?.takeIf(String::isNotBlank)?.let { director ->
                            item { SeriesCredit(Icons.Default.MovieCreation, stringResource(R.string.director_label), director) }
                        }
                        if (seasons.isNotEmpty()) {
                            item {
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    seasons.forEach { season ->
                                        FilterChip(
                                            selected = selectedSeason == season,
                                            onClick = { onSeason(season) },
                                            label = { Text(stringResource(R.string.season_number, season), fontSize = 12.sp) }
                                        )
                                    }
                                }
                            }
                            itemsIndexed(episodes, key = { _, episode -> episode.id }) { _, episode ->
                                EpisodeRow(
                                    episode = episode,
                                    progress = progress[episode.id] ?: 0L,
                                    watched = episode.id in watchedEpisodeIds,
                                    focusRequester = null,
                                    onClick = { onEpisode(episode) }
                                )
                            }
                        } else if (error != null) {
                            item { Text(stringResource(R.string.episodes_unavailable), color = MaterialTheme.colorScheme.error) }
                        } else {
                            item { Text(stringResource(R.string.no_episodes_supplied), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            } else {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(displayTitle, fontSize = 18.sp, fontWeight = FontWeight.Black, lineHeight = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!details?.originalTitle.isNullOrBlank() && details?.originalTitle != series.name) {
                    Text(series.name, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FlowRowPills {
                    details?.rating?.takeIf { it != "0" && it != "0.0" }?.let { SeriesPill("★ $it/10", Orange) }
                    details?.year?.takeIf(String::isNotBlank)?.let { SeriesPill(it, Cyan) }
                    details?.genre?.takeIf(String::isNotBlank)?.let { SeriesPill(it, BrandBlue) }
                    SeriesPill(series.group, Cyan)
                }
                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.loading_seasons_episodes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        details?.description?.takeIf(String::isNotBlank) ?: stringResource(R.string.no_series_details),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    details?.cast?.takeIf(String::isNotBlank)?.let { SeriesCredit(Icons.Default.Groups, stringResource(R.string.cast_label), it) }
                    details?.director?.takeIf(String::isNotBlank)?.let { SeriesCredit(Icons.Default.MovieCreation, stringResource(R.string.director_label), it) }
                    if (seasons.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            seasons.forEach { season ->
                                FilterChip(
                                    selected = selectedSeason == season,
                                    onClick = { onSeason(season) },
                                    label = { Text(stringResource(R.string.season_number, season), fontSize = 12.sp) }
                                )
                            }
                        }
                        LazyColumn(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            itemsIndexed(episodes, key = { _, episode -> episode.id }) { index, episode ->
                                EpisodeRow(
                                    episode = episode,
                                    progress = progress[episode.id] ?: 0L,
                                    watched = episode.id in watchedEpisodeIds,
                                    focusRequester = if (index == 0) firstEpisodeFocusRequester else null,
                                    onClick = { onEpisode(episode) }
                                )
                            }
                        }
                    } else if (error != null) {
                        Text(stringResource(R.string.episodes_unavailable), color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(stringResource(R.string.no_episodes_supplied), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            }
        }
        return
    }

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            shape = RoundedCornerShape(20.dp),
            color = Color.Black,
            shadowElevation = 10.dp
        ) {
            Box(Modifier.fillMaxSize()) {
                if (!backdrop.isNullOrBlank()) {
                    AsyncImage(backdrop, series.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .9f)))))
                Surface(
                    Modifier.align(Alignment.BottomStart).offset(x = 14.dp).width(104.dp).aspectRatio(2f / 3f),
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 12.dp,
                    border = BorderStroke(2.dp, Color.White.copy(alpha = .18f))
                ) {
                    if (!poster.isNullOrBlank()) {
                        AsyncImage(poster, series.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                Text(
                    displayTitle,
                    color = Color.White,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 3,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 132.dp, end = 14.dp, bottom = 16.dp)
                )
            }
        }

        if (!details?.originalTitle.isNullOrBlank() && details?.originalTitle != series.name) {
            Text(series.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            details?.rating?.takeIf { it != "0" && it != "0.0" }?.let { SeriesPill("★ $it/10", Orange) }
            details?.year?.takeIf(String::isNotBlank)?.let { SeriesPill(it, Cyan) }
            details?.genre?.takeIf(String::isNotBlank)?.let { SeriesPill(it, BrandBlue) }
            SeriesPill(series.group, Cyan)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            trailerId?.let { id ->
                OutlinedButton(
                    onClick = { openTrailer(context, id) },
                    modifier = Modifier.weight(1f).height(52.dp)
                ) {
                    Icon(Icons.Default.SmartDisplay, null)
                    Spacer(Modifier.width(7.dp))
                    Text(stringResource(R.string.trailer_label))
                }
            }
            AnimatedFilledTonalIconButton(onClick = onFavorite, modifier = Modifier.size(52.dp)) {
                Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder, if (favorite) stringResource(R.string.cd_favorite_remove) else stringResource(R.string.cd_favorite_add), tint = if (favorite) Orange else Cyan)
            }
        }

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.loading_seasons_episodes), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                details?.description?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.no_series_details),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
            details?.cast?.takeIf(String::isNotBlank)?.let { SeriesCredit(Icons.Default.Groups, stringResource(R.string.cast_label), it) }
            details?.director?.takeIf(String::isNotBlank)?.let { SeriesCredit(Icons.Default.MovieCreation, stringResource(R.string.director_label), it) }
            SeasonsAndEpisodes()
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun EpisodeRow(episode: SeriesEpisode, progress: Long, watched: Boolean, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    val durationMs = remember(episode.duration) { parseDurationToMillis(episode.duration) }
    val watchedFraction = if (progress > 0L && durationMs != null && durationMs > 0L) {
        (progress.toFloat() / durationMs).coerceIn(0f, 1f)
    } else null
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        shape = RoundedCornerShape(11.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .95f))
    ) {
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                Modifier.width(78.dp).aspectRatio(16f / 9f),
                shape = RoundedCornerShape(8.dp),
                color = Color.Black
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayCircle, null, tint = Cyan, modifier = Modifier.size(20.dp))
                    if (!episode.thumbnailUrl.isNullOrBlank()) {
                        AsyncImage(
                            episode.thumbnailUrl,
                            episode.title,
                            Modifier.fillMaxSize().alpha(if (watched) .55f else 1f),
                            contentScale = ContentScale.Crop
                        )
                    }
                    // YouTube-style watched indicator: a red strip along the bottom edge of the
                    // thumbnail sized to how far into the episode the user got.
                    if (watchedFraction != null) {
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = .35f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(watchedFraction).background(Color(0xFFE50914)))
                        }
                    }
                    if (watched) {
                        Surface(
                            modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(15.dp),
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = Color(0xFF2ECC71)
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(10.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.episode_title_format, episode.episodeNumber, episode.title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val resumeLabel = stringResource(R.string.resume_time, seriesProgressTime(progress))
                val watchedLabel = stringResource(R.string.watched_label)
                val detail = buildList {
                    episode.duration?.takeIf(String::isNotBlank)?.let(::add)
                    if (progress > 0L) add(resumeLabel)
                    if (watched) add(watchedLabel)
                }.joinToString(" • ")
                if (detail.isNotBlank()) {
                    Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1)
                }
            }
            Icon(Icons.Default.PlayArrow, stringResource(R.string.play_action), tint = Cyan, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SeriesPill(text: String, accent: Color) {
    Surface(
        color = accent.copy(alpha = .14f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, accent.copy(alpha = .35f))
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
    }
}

@Composable
private fun SeriesCredit(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = Cyan, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
        }
    }
}

private fun seriesProgressTime(milliseconds: Long): String {
    val totalMinutes = milliseconds.coerceAtLeast(0L) / 60_000L
    return if (totalMinutes >= 60L) "${totalMinutes / 60L}h ${totalMinutes % 60L}m" else "${totalMinutes}m"
}

/** Providers report episode duration as either "HH:MM:SS"/"MM:SS" or a plain seconds count —
 *  parses either into milliseconds, or null if it's neither (so the watched-progress strip can be
 *  skipped rather than drawn against a nonsense total). */
internal fun parseDurationToMillis(duration: String?): Long? {
    val text = duration?.trim()?.takeIf(String::isNotBlank) ?: return null
    if (':' in text) {
        val parts = text.split(':').map { it.toIntOrNull() ?: return null }
        var seconds = 0L
        for (part in parts) seconds = seconds * 60 + part
        return seconds * 1000L
    }
    return text.toLongOrNull()?.times(1000L)
}
