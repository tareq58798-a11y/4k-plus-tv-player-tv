package com.fourkplus.tvplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.rememberCoroutineScope
import com.fourkplus.tvplayer.data.ActivationPendingException
import com.fourkplus.tvplayer.data.PlaylistInput
import com.fourkplus.tvplayer.data.PlaylistKind
import kotlinx.coroutines.launch
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.fourkplus.tvplayer.data.DeviceIdentity
import com.fourkplus.tvplayer.data.LoadedPlaylist
import com.fourkplus.tvplayer.data.MediaKind
import com.fourkplus.tvplayer.data.PlaylistItem
import com.fourkplus.tvplayer.data.SeriesEpisode
import com.fourkplus.tvplayer.ui.design.Tone
import com.fourkplus.tvplayer.ui.theme.FourKPlusTheme
import com.fourkplus.tvplayer.viewmodel.PlaylistViewModel

/**
 * The phone and tablet app.
 *
 * Deliberately its own screens rather than the television ones. A remote and a fingertip are not
 * the same instrument: the TV app is built around a focus ring moving between fixed targets, with
 * cards sized to be legible across a room and margins set for overscan. None of that is right in
 * the hand, so none of it is reused.
 *
 * What *is* reused is everything below the surface - the provider client, the catalogue, the cache,
 * the stores and the translations all live in src/main and are shared with the television build.
 * A fix to any of them reaches both apps; a change on this screen reaches neither.
 */
class MainActivity : ComponentActivity() {
    // Same as the television build: the chosen language has to be applied before any resource is
    // read, which is here and nowhere later.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            FourKPlusTheme(darkTheme = true) {
                PhoneApp()
            }
        }
    }
}

/** A null [kind] is the settings page rather than a catalogue of something. */
private enum class PhoneTab(val label: String, val kind: MediaKind?) {
    MOVIES("Movies", MediaKind.MOVIE),
    SERIES("Series", MediaKind.SERIES),
    LIVE("Live TV", MediaKind.LIVE),
    SETTINGS("Settings", null)
}

@Composable
private fun PhoneApp() {
    val context = LocalContext.current
    val viewModel: PlaylistViewModel =
        viewModel(factory = PlaylistViewModel.Factory(context.applicationContext))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        // The same artwork the television build uses, so the two apps are recognisably one product
        // even though they share no screen code.
        Image(
            painter = painterResource(R.drawable.bg_app_default),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(Modifier.fillMaxSize().background(Tone.pageScrim()))

        val playlist = state.loadedPlaylist
        when {
            state.bootstrapping -> Loading()
            playlist == null || playlist.items.isEmpty() -> NoPlaylist()
            else -> Catalogue(playlist, viewModel)
        }
    }
}

/**
 * What the phone is currently showing over the catalogue: nothing, a series' episode list, or the
 * player. Kept as one value so only one of them can ever be up, and so Back always has a single
 * obvious step to take.
 */
private sealed interface Overlay {
    /** A title's own page: artwork, what it is about, and the way in to playing it. */
    data class Details(val item: PlaylistItem) : Overlay

    /**
     * [progressKey] is where this title's resume position is kept, and [progressPrefs] which store
     * holds it - films and episodes are recorded separately, exactly as the television build does,
     * so the two never overwrite one another. Null for live channels, which have no position to
     * return to.
     */
    data class Playing(
        val title: String,
        val url: String,
        val progressPrefs: String? = null,
        val progressKey: String? = null,
        /** Set by "Start over", which ignores the saved position without forgetting it. */
        val startOver: Boolean = false
    ) : Overlay
}

/**
 * Starred titles. Same stores and keys the television build uses, so the two describe a favourite
 * the same way - though each app keeps its own, being a separate install.
 */
private object Favourites {
    private fun prefsName(kind: MediaKind) = when (kind) {
        MediaKind.MOVIE -> MOVIE_PREFS
        MediaKind.SERIES -> SERIES_PREFS
        MediaKind.LIVE -> "favorite_channels"
    }

    private fun setKey(kind: MediaKind) = if (kind == MediaKind.LIVE) "ids" else "favorites"

    fun read(context: android.content.Context, kind: MediaKind): Set<String> =
        context.getSharedPreferences(prefsName(kind), android.content.Context.MODE_PRIVATE)
            .getStringSet(setKey(kind), emptySet()).orEmpty().toSet()

    fun toggle(context: android.content.Context, item: PlaylistItem): Set<String> {
        val key = channelKey(item)
        val current = read(context, item.kind)
        val updated = if (key in current) current - key else current + key
        context.getSharedPreferences(prefsName(item.kind), android.content.Context.MODE_PRIVATE)
            .edit().putStringSet(setKey(item.kind), updated).apply()
        return updated
    }
}

/** Films. Matches the television build's store and key, so a position means the same thing in both. */
private const val MOVIE_PREFS = "movie_library"

/** Series episodes, kept apart from films for the same reason. */
private const val SERIES_PREFS = "series_library"

/**
 * Anything within half a minute of the end counts as finished rather than paused, so starting it
 * again begins at the beginning instead of the closing seconds.
 */
private const val NEARLY_FINISHED_MS = 30_000L

private fun readProgress(context: android.content.Context, prefs: String?, key: String?): Long {
    if (prefs == null || key == null) return 0L
    return context.getSharedPreferences(prefs, android.content.Context.MODE_PRIVATE)
        .getLong(key, 0L)
}

private fun writeProgress(
    context: android.content.Context,
    prefs: String?,
    key: String?,
    position: Long,
    duration: Long
) {
    if (prefs == null || key == null) return
    val store = context.getSharedPreferences(prefs, android.content.Context.MODE_PRIVATE)
    val finished = duration > 0L && position >= duration - NEARLY_FINISHED_MS
    if (finished || position <= 0L) store.edit().remove(key).apply()
    else store.edit().putLong(key, position).apply()
}

@Composable
private fun Loading() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = Tone.Accent)
        Spacer(Modifier.height(16.dp))
        Text("Loading your playlist…", color = Tone.TextSecondary)
    }
}

/**
 * No playlist yet. The phone shows the same device codes the television does, because a playlist is
 * assigned to a device in the dashboard rather than typed in here - so the useful thing this screen
 * can do is show the codes and offer to look again.
 */
@Composable
private fun NoPlaylist() {
    val context = LocalContext.current
    val deviceId = remember { DeviceIdentity.mac(context) }
    val deviceKey = remember { DeviceIdentity.deviceKey(context) }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Welcome", color = Tone.TextPrimary, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Assign a playlist to this device in your 4K Plus TV dashboard, then come back.",
            color = Tone.TextSecondary,
            fontSize = 15.sp
        )
        Spacer(Modifier.height(28.dp))
        CodeRow("Device ID", deviceId)
        Spacer(Modifier.height(12.dp))
        CodeRow("Device Key", deviceKey)
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = { (context as? android.app.Activity)?.recreate() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(0.dp))
            Text("  Check again")
        }
    }
}

@Composable
private fun CodeRow(label: String, value: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Tone.Glass)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(label, color = Tone.TextMuted, fontSize = 12.sp)
        Text(value, color = Tone.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Catalogue(playlist: LoadedPlaylist, viewModel: PlaylistViewModel) {
    var tab by remember { mutableStateOf(PhoneTab.MOVIES) }
    var query by remember { mutableStateOf("") }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    // Null means every category. Reset whenever the tab changes, because a category belongs to the
    // kind it was picked in and carrying it across would filter the next tab down to nothing.
    var category by remember(tab) { mutableStateOf<String?>(null) }

    val ofKind = remember(playlist, tab) { playlist.items.filter { it.kind == tab.kind } }
    val categories = remember(ofKind) { ofKind.map { it.group }.distinct().sorted() }

    val items = remember(ofKind, category, query) {
        val inCategory = if (category == null) ofKind else ofKind.filter { it.group == category }
        // A search reaches the whole kind, not just the open category: someone typing a title wants
        // the title, and having to find the right category first would defeat the point of typing.
        if (query.isBlank()) inCategory
        else ofKind.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(containerColor = Color(0xCC050B16)) {
                PhoneTab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = entry == tab,
                        onClick = { tab = entry },
                        icon = {
                            Icon(
                                when (entry) {
                                    PhoneTab.MOVIES -> Icons.Default.Movie
                                    PhoneTab.SERIES -> Icons.Default.Tv
                                    PhoneTab.LIVE -> Icons.Default.LiveTv
                                    PhoneTab.SETTINGS -> Icons.Default.Settings
                                },
                                null
                            )
                        },
                        label = { Text(entry.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Tone.Accent,
                            selectedTextColor = Tone.Accent,
                            unselectedIconColor = Tone.TextMuted,
                            unselectedTextColor = Tone.TextMuted,
                            indicatorColor = Color(0x2222D3EE)
                        )
                    )
                }
            }
        }
    ) { padding ->
        if (tab.kind == null) {
            PhoneSettings(playlist, viewModel, Modifier.padding(padding))
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search ${tab.label.lowercase()}") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            // A catalogue runs to thousands of titles, so a flat grid is only usable with a search
            // term already in mind. The categories the provider ships are the way through it, and a
            // scrolling row of chips is the touch equivalent of the television's category column.
            // Hidden while searching, which reaches across all of them anyway.
            if (query.isBlank() && categories.size > 1) {
                LazyRow(
                    Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = category == null,
                            onClick = { category = null },
                            label = { Text("All") }
                        )
                    }
                    items(categories, key = { it }) { name ->
                        FilterChip(
                            selected = category == name,
                            onClick = { category = if (category == name) null else name },
                            label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            // Live channels are wide logos, films and series are tall posters, so the two are laid
            // out differently rather than forced into one shape.
            val portraitArt = tab != PhoneTab.LIVE
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (portraitArt) 110.dp else 160.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp, end = 12.dp, bottom = 24.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(items, key = { "${it.kind}:${it.channelId ?: it.streamUrl}" }) { item ->
                    PosterTile(item, portraitArt) {
                        // A film or a channel is one stream and plays straight away. A series is a
                        // list of episodes, so it opens that list first - there is nothing to play
                        // until one of them has been picked.
                        // A channel is one thing you either watch or do not, so it plays. A film or
                        // a series has something to read first, and a position to decide what to do
                        // about, so it opens its own page.
                        overlay = if (item.kind == MediaKind.LIVE) {
                            Overlay.Playing(item.name, item.streamUrl)
                        } else {
                            Overlay.Details(item)
                        }
                    }
                }
            }
        }
    }

    when (val current = overlay) {
        null -> Unit
        is Overlay.Details -> DetailsPage(
            item = current.item,
            viewModel = viewModel,
            onPlayMovie = { resume ->
                overlay = Overlay.Playing(
                    current.item.name,
                    current.item.streamUrl,
                    MOVIE_PREFS,
                    "progress_${channelKey(current.item)}",
                    startOver = !resume
                )
            },
            onPlayEpisode = { episode ->
                overlay = Overlay.Playing(
                    "${current.item.name} • S${episode.seasonNumber} E${episode.episodeNumber}",
                    episode.streamUrl,
                    SERIES_PREFS,
                    "episode_progress_${episode.id}"
                )
            },
            onDismiss = { overlay = null }
        )
        is Overlay.Playing -> PhonePlayer(
            title = current.title,
            url = current.url,
            progressPrefs = current.progressPrefs,
            progressKey = current.progressKey,
            startOver = current.startOver,
            onDismiss = { overlay = null }
        )
    }
}

@Composable
private fun PosterTile(item: PlaylistItem, portraitArt: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (portraitArt) 2f / 3f else 16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(Tone.Glass),
            contentAlignment = Alignment.Center
        ) {
            if (!item.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.logoUrl,
                    contentDescription = item.name,
                    contentScale = if (portraitArt) ContentScale.Crop else ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Default.Movie, null, tint = Tone.TextMuted, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.name,
            color = Tone.TextPrimary,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Settings.
 *
 * Only what a phone can act on. Everything here goes through the shared layer, so a playlist
 * refreshed, replaced or checked for here is the same operation the television performs - the two
 * apps keep their own copies of it, because they are separate installs with separate storage.
 */
@Composable
private fun PhoneSettings(
    playlist: LoadedPlaylist,
    viewModel: PlaylistViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var language by remember { mutableStateOf(LocaleHelper.getLanguage(context)) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Settings", color = Tone.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }

        note?.let { text ->
            item {
                Text(
                    text,
                    color = Tone.Accent,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Tone.Glass)
                        .padding(12.dp)
                )
            }
        }

        item {
            SettingsCard("Playlist") {
                InfoLine("Name", state.activeSource?.name ?: playlist.name)
                state.activeSource?.username?.takeIf(String::isNotBlank)?.let {
                    InfoLine("Account", it)
                }
                playlist.accountStatus?.takeIf(String::isNotBlank)?.let { InfoLine("Status", it) }
                InfoLine("Channels", playlist.liveCount.toString())
                InfoLine("Films", playlist.movieCount.toString())
                InfoLine("Series", playlist.seriesCount.toString())
            }
        }

        item {
            SettingsCard("Actions") {
                SettingsButton(
                    Icons.Default.Refresh,
                    "Refresh playlist",
                    "Fetch the newest channels, films and series",
                    busy == null
                ) {
                    busy = "refresh"
                    scope.launch {
                        viewModel.refreshActive()
                            .onSuccess { note = "Playlist refreshed" }
                            .onFailure { note = it.message ?: "Playlist refresh failed" }
                        busy = null
                    }
                }
                // The same question the television's Settings asks: this device's codes are already
                // registered, so the only thing left to find out is whether anything has been put
                // against them. "Nothing yet" is an answer, not a failure.
                SettingsButton(
                    Icons.Default.Sync,
                    "Check for a playlist",
                    if (busy == "activation") "Checking with the activation service…"
                    else "Ask whether a playlist has been assigned to this device",
                    busy == null
                ) {
                    busy = "activation"
                    scope.launch {
                        viewModel.addPlaylist(
                            PlaylistInput(
                                name = "Activated playlist",
                                kind = PlaylistKind.DEVICE_ACTIVATION,
                                address = "",
                                username = DeviceIdentity.mac(context),
                                password = DeviceIdentity.deviceKey(context)
                            )
                        )
                            .onSuccess { note = "A playlist was assigned to this device" }
                            .onFailure { error ->
                                note = if (error is ActivationPendingException) {
                                    "No playlist has been assigned to this device yet"
                                } else {
                                    error.message ?: "The activation service could not be reached"
                                }
                            }
                        busy = null
                    }
                }
            }
        }

        item {
            SettingsCard("This device") {
                InfoLine("Device ID", DeviceIdentity.mac(context))
                InfoLine("Device Key", DeviceIdentity.deviceKey(context))
            }
        }

        item {
            SettingsCard("Language") {
                // Applied at attachBaseContext, so the activity is recreated to pick it up rather
                // than half the screen re-reading strings and half not.
                AppLanguage.entries.forEach { option ->
                    val label = option.nativeName.ifBlank { "System default" }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                LocaleHelper.setLanguage(context, option)
                                language = option
                                (context as? android.app.Activity)?.recreate()
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = option == language, onClick = null)
                        Spacer(Modifier.height(0.dp))
                        Text("  $label", color = Tone.TextPrimary, fontSize = 15.sp)
                    }
                }
            }
        }

        item {
            Text(
                "4K Plus TV Player • phone",
                color = Tone.TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            // Darker than the glass used elsewhere. A poster grid can afford a light wash because
            // the artwork is the content; a column of small labels and values cannot, with the
            // astronaut's helmet behind it.
            .background(Color(0xD9060E1C))
            .padding(16.dp)
    ) {
        Text(title, color = Tone.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = Tone.TextMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            color = Tone.TextPrimary,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingsButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            null,
            tint = if (enabled) Tone.Accent else Tone.TextMuted,
            modifier = Modifier.size(22.dp)
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, color = Tone.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(description, color = Tone.TextMuted, fontSize = 12.sp)
        }
    }
}

/**
 * A title's own page: its artwork, what it is about, and the way in to playing it.
 *
 * The catalogue listing carries almost none of this - a series never carries a plot, and many
 * panels omit one for films too - so the page asks for the details the same way the television
 * build does, once, when it opens.
 *
 * A film offers Resume where there is a position to resume from, and Start over beside it, because
 * the saved position is a convenience and not a sentence. A series offers its episodes instead:
 * there is nothing to play until one has been chosen.
 */
@Composable
private fun DetailsPage(
    item: PlaylistItem,
    viewModel: PlaylistViewModel,
    onPlayMovie: (resume: Boolean) -> Unit,
    onPlayEpisode: (SeriesEpisode) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var plot by remember(item) { mutableStateOf<String?>(null) }
    var meta by remember(item) { mutableStateOf<List<String>>(emptyList()) }
    var artwork by remember(item) { mutableStateOf(item.logoUrl) }
    var episodes by remember(item) { mutableStateOf<List<SeriesEpisode>?>(null) }
    var loading by remember(item) { mutableStateOf(true) }
    var starred by remember(item) { mutableStateOf(channelKey(item) in Favourites.read(context, item.kind)) }

    val resumeAt = remember(item) {
        if (item.kind == MediaKind.MOVIE) {
            readProgress(context, MOVIE_PREFS, "progress_${channelKey(item)}")
        } else 0L
    }

    LaunchedEffect(item) {
        if (item.kind == MediaKind.SERIES) {
            viewModel.seriesDetails(item).onSuccess { details ->
                plot = details.description
                artwork = details.backdropUrl ?: details.posterUrl ?: item.logoUrl
                meta = listOfNotNull(details.year, details.rating?.let { "$it/10" }, details.genre)
                episodes = details.episodes
            }.onFailure { episodes = emptyList() }
        } else {
            viewModel.movieDetails(item).onSuccess { details ->
                plot = details.description
                artwork = details.backdropUrl ?: details.posterUrl ?: item.logoUrl
                meta = listOfNotNull(
                    details.year,
                    details.rating?.let { "$it/10" },
                    details.duration,
                    details.genre
                )
            }
        }
        loading = false
    }

    BackHandler(onBack = onDismiss)

    // Opaque, not translucent. This is a page about one title, and the grid showing faintly through
    // its synopsis reads as a rendering fault rather than as depth.
    Box(Modifier.fillMaxSize().background(Color(0xFF050B16))) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                    if (!artwork.isNullOrBlank()) {
                        AsyncImage(
                            model = artwork,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    // The title sits on the artwork, so the artwork has to give way under it.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    0f to Color(0x66050B16),
                                    1f to Color(0xFF050B16)
                                )
                            )
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.systemBarsPadding().padding(4.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                }
            }

            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        item.name,
                        color = Tone.TextPrimary,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (meta.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(meta.joinToString("  •  "), color = Tone.TextMuted, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(14.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item.kind == MediaKind.MOVIE) {
                            Button(onClick = { onPlayMovie(resumeAt > 0L) }) {
                                Text(if (resumeAt > 0L) "Resume  ${formatPosition(resumeAt)}" else "Play")
                            }
                            if (resumeAt > 0L) {
                                Spacer(Modifier.size(10.dp))
                                Button(onClick = { onPlayMovie(false) }) { Text("Start over") }
                            }
                            Spacer(Modifier.size(10.dp))
                        }
                        IconButton(onClick = { starred = channelKey(item) in Favourites.toggle(context, item) }) {
                            Icon(
                                if (starred) Icons.Default.Star else Icons.Default.StarBorder,
                                if (starred) "Remove from favourites" else "Add to favourites",
                                tint = if (starred) Tone.Star else Tone.TextMuted
                            )
                        }
                    }

                    plot?.takeIf(String::isNotBlank)?.let {
                        Spacer(Modifier.height(14.dp))
                        Text(it, color = Tone.TextSecondary, fontSize = 14.sp)
                    }
                    if (loading) {
                        Spacer(Modifier.height(14.dp))
                        CircularProgressIndicator(color = Tone.Accent, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.height(18.dp))
                }
            }

            if (item.kind == MediaKind.SERIES) {
                val loaded = episodes
                when {
                    loaded == null -> Unit
                    loaded.isEmpty() -> item {
                        Text(
                            "This series has no episodes listed.",
                            color = Tone.TextSecondary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    }
                    else -> items(loaded, key = { it.id }) { episode ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPlayEpisode(episode) }
                                .padding(horizontal = 20.dp, vertical = 13.dp)
                        ) {
                            Text(
                                "S${episode.seasonNumber} E${episode.episodeNumber}  ${episode.title}",
                                color = Tone.TextPrimary,
                                fontSize = 15.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            episode.duration?.takeIf(String::isNotBlank)?.let {
                                Text(it, color = Tone.TextMuted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

/** A resume position as a viewer reads it, not as milliseconds. */
private fun formatPosition(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

/**
 * Full-screen playback.
 *
 * The player itself comes from the shared engine - same HTTP clients, buffering and decoder
 * fallback as the television build - so a stream that plays on one plays on the other. Only the
 * controls differ: media3's own touch controls, rather than the focus-driven overlay a remote needs.
 */
@Composable
private fun PhonePlayer(
    title: String,
    url: String,
    progressPrefs: String?,
    progressKey: String?,
    startOver: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val player = remember(url) {
        buildFourKPlusExoPlayer(context, skipSeconds = 10, muted = false).apply {
            setMediaItem(MediaItem.fromUri(url))
            // Read before prepare, so a resumed title opens at its position rather than starting
            // from the beginning and jumping a moment later. Skipped when the viewer asked to start
            // over, which ignores the saved position without erasing it.
            if (!startOver) {
                readProgress(context, progressPrefs, progressKey).takeIf { it > 0L }?.let(::seekTo)
            }
            prepare()
            playWhenReady = true
        }
    }
    var failure by remember(url) { mutableStateOf<String?>(null) }

    // Written while playing rather than only on the way out, so a position survives the app being
    // killed in the background - which on a phone is the ordinary way a video ends.
    LaunchedEffect(player, progressKey) {
        if (progressKey == null) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(5_000)
            val position = player.currentPosition
            if (position > 0L) writeProgress(context, progressPrefs, progressKey, position, player.duration)
        }
    }
    DisposableEffect(player, progressKey) {
        onDispose {
            if (progressKey != null && player.currentPosition > 0L) {
                writeProgress(context, progressPrefs, progressKey, player.currentPosition, player.duration)
            }
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failure = "Playback failed: ${error.errorCodeName} (code ${error.errorCode})."
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Playback is the one thing on a phone worth keeping the screen awake for.
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(player, view) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { view.keepScreenOn = isPlaying }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            view.keepScreenOn = false
        }
    }

    BackHandler(onBack = onDismiss)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    this.player = player
                    useController = true
                    setShowPreviousButton(false)
                    setShowNextButton(false)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        Row(
            Modifier.fillMaxWidth().systemBarsPadding().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
            }
            Text(
                title,
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        failure?.let {
            Text(
                it,
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC000000), RoundedCornerShape(10.dp))
                    .padding(16.dp)
            )
        }
    }
}
