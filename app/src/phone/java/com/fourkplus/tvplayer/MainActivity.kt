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
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import com.fourkplus.tvplayer.data.sourceId
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
import androidx.compose.runtime.mutableIntStateOf
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
    // Decided once, when the app opens. Re-checking on every recomposition would put the gate back
    // up the moment anything else changed.
    var locked by remember {
        mutableStateOf(Parental.pinHash(context) != null && Parental.askOnStartup(context))
    }

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

        // Over everything, including the catalogue, so nothing is readable behind it while it is up.
        if (locked) {
            PinGate(onUnlocked = { locked = false })
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
        val startOver: Boolean = false,
        /**
         * The channels this one was opened from, and where in them it sits. Live only: it is what
         * lets the player move to the next channel without going back to the grid to find it, and
         * what the guide is fetched for. Empty for films and episodes, which have no neighbours.
         */
        val channels: List<PlaylistItem> = emptyList(),
        val channelIndex: Int = 0
    ) : Overlay
}

/**
 * Hidden categories and the PIN, in the same store and under the same keys the television build
 * uses. A hidden category means the same thing in both, and a PIN is held the same way: as a hash,
 * never as the digits themselves.
 */
private object Parental {
    private const val PREFS = "parental_settings"

    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    private fun hiddenKey(kind: MediaKind) = when (kind) {
        MediaKind.MOVIE -> "hidden_movie_categories"
        MediaKind.SERIES -> "hidden_series_categories"
        MediaKind.LIVE -> "hidden_live_categories"
    }

    fun hidden(context: android.content.Context, kind: MediaKind): Set<String> =
        prefs(context).getStringSet(hiddenKey(kind), emptySet()).orEmpty().toSet()

    fun setHidden(context: android.content.Context, kind: MediaKind, categories: Set<String>) {
        prefs(context).edit().putStringSet(hiddenKey(kind), categories).apply()
    }

    fun pinHash(context: android.content.Context): String? = prefs(context).getString("pin_hash", null)

    /** Stored hashed, so the digits are not sitting in a preferences file in the clear. */
    fun setPin(context: android.content.Context, pin: String?) {
        prefs(context).edit().apply {
            if (pin == null) remove("pin_hash") else putString("pin_hash", hashPin(pin))
        }.apply()
    }

    fun matches(context: android.content.Context, pin: String): Boolean =
        pinHash(context) != null && pinHash(context) == hashPin(pin)

    fun askOnStartup(context: android.content.Context): Boolean =
        prefs(context).getBoolean("ask_pin_on_startup", false)

    fun setAskOnStartup(context: android.content.Context, ask: Boolean) {
        prefs(context).edit().putBoolean("ask_pin_on_startup", ask).apply()
    }

    private fun hashPin(pin: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(pin.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
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

/**
 * The two chips that are not provider categories. Prefixed so they cannot collide with a real
 * category a panel happens to have named "Continue".
 */
private const val CONTINUE_CATEGORY = " continue"
private const val FAVOURITES_CATEGORY = " favourites"

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
    val context = LocalContext.current
    var tab by remember { mutableStateOf(PhoneTab.MOVIES) }
    var query by remember { mutableStateOf("") }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    // Null means every category. Reset whenever the tab changes, because a category belongs to the
    // kind it was picked in and carrying it across would filter the next tab down to nothing.
    var category by remember(tab) { mutableStateOf<String?>(null) }
    // Bumped when something is starred or watched, so the two lists below are re-read rather than
    // staying as they were when the tab opened.
    var libraryVersion by remember { mutableIntStateOf(0) }

    // Hidden categories are taken out here rather than filtered at the chip row, so a hidden
    // category's titles cannot reach the grid through "All" or through a search either.
    val ofKind = remember(playlist, tab, libraryVersion) {
        val hidden = tab.kind?.let { Parental.hidden(context, it) }.orEmpty()
        playlist.items.filter { it.kind == tab.kind && it.group !in hidden }
    }
    val categories = remember(ofKind) { ofKind.map { it.group }.distinct().sorted() }

    // The two lists that are not categories at all: what the viewer starred, and what they started
    // and did not finish. They sit at the front of the same row because that is where someone looks
    // first, and because a phone has no room for rows of their own above the grid.
    val starred = remember(ofKind, libraryVersion, tab) {
        val keys = Favourites.read(context, tab.kind ?: MediaKind.MOVIE)
        ofKind.filter { channelKey(it) in keys }
    }
    val unfinished = remember(ofKind, libraryVersion, tab) {
        if (tab.kind != MediaKind.MOVIE) emptyList()
        else {
            val positions = context.getSharedPreferences(MOVIE_PREFS, android.content.Context.MODE_PRIVATE)
                .all.keys.filter { it.startsWith("progress_") }.map { it.removePrefix("progress_") }.toSet()
            ofKind.filter { channelKey(it) in positions }
        }
    }

    val items = remember(ofKind, category, query, starred, unfinished) {
        val inCategory = when (category) {
            null -> ofKind
            CONTINUE_CATEGORY -> unfinished
            FAVOURITES_CATEGORY -> starred
            else -> ofKind.filter { it.group == category }
        }
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
            PhoneSettings(playlist, viewModel, libraryVersion, { libraryVersion++ }, Modifier.padding(padding))
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
                    // Shown only when they have something in them. An empty Favourites chip is an
                    // invitation to press something that does nothing.
                    if (unfinished.isNotEmpty()) {
                        item {
                            FilterChip(
                                selected = category == CONTINUE_CATEGORY,
                                onClick = {
                                    category = if (category == CONTINUE_CATEGORY) null else CONTINUE_CATEGORY
                                },
                                label = { Text("Continue") }
                            )
                        }
                    }
                    if (starred.isNotEmpty()) {
                        item {
                            FilterChip(
                                selected = category == FAVOURITES_CATEGORY,
                                onClick = {
                                    category = if (category == FAVOURITES_CATEGORY) null else FAVOURITES_CATEGORY
                                },
                                label = { Text("Favourites") }
                            )
                        }
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
                            // Carries the list it was picked from, so the player can move along it.
                            Overlay.Playing(
                                item.name,
                                item.streamUrl,
                                channels = items,
                                channelIndex = items.indexOf(item).coerceAtLeast(0)
                            )
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
            // Starring something, or leaving a film part-watched, changes what the Favourites and
            // Continue chips should hold - so they are re-read when the page closes rather than
            // staying as they were when the tab was opened.
            onDismiss = { overlay = null; libraryVersion++ }
        )
        is Overlay.Playing -> PhonePlayer(
            title = current.title,
            url = current.url,
            progressPrefs = current.progressPrefs,
            progressKey = current.progressKey,
            startOver = current.startOver,
            channels = current.channels,
            startIndex = current.channelIndex,
            viewModel = viewModel,
            onDismiss = { overlay = null; libraryVersion++ }
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
    hiddenVersion: Int,
    onHiddenChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var language by remember { mutableStateOf(LocaleHelper.getLanguage(context)) }
    var pinHash by remember { mutableStateOf(Parental.pinHash(context)) }
    var askPinOnStart by remember { mutableStateOf(Parental.askOnStartup(context)) }
    var pinPrompt by remember { mutableStateOf(false) }
    var hiddenEditor by remember { mutableStateOf<MediaKind?>(null) }
    val hiddenCount = remember(hiddenVersion) {
        MediaKind.entries.sumOf { Parental.hidden(context, it).size }
    }

    if (pinPrompt) {
        PinDialog(
            title = "Set a PIN",
            onConfirm = { entered ->
                Parental.setPin(context, entered)
                pinHash = Parental.pinHash(context)
                pinPrompt = false
                note = "PIN set"
            },
            onDismiss = { pinPrompt = false }
        )
    }

    hiddenEditor?.let { kind ->
        HiddenCategoriesDialog(
            kind = kind,
            playlist = playlist,
            onDone = { hiddenEditor = null; onHiddenChanged() }
        )
    }

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

        // More than one saved account is common - a household with two subscriptions, or a spare
        // while one expires - so switching is a tap rather than removing and re-adding.
        if (state.savedPlaylists.size > 1) {
            item {
                SettingsCard("Saved playlists") {
                    state.savedPlaylists.forEach { saved ->
                        val active = saved.sourceId() == state.activeSource?.sourceId()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !active && busy == null) {
                                    busy = "switch"
                                    scope.launch {
                                        viewModel.switchTo(saved)
                                            .onSuccess { note = "Switched to ${saved.name}" }
                                            .onFailure { note = it.message ?: "Could not switch playlist" }
                                        busy = null
                                    }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = active, onClick = null)
                            Text(
                                "  ${saved.name}",
                                color = Tone.TextPrimary,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Hiding a category takes it out of the chips and out of the grid. Kept in the same store
        // the television uses, under the same keys, so the two mean the same thing by "hidden".
        item {
            SettingsCard("Category visibility") {
                Text(
                    "Hidden categories disappear from the chips and the grid.",
                    color = Tone.TextMuted,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                SettingsButton(
                    Icons.Default.VisibilityOff,
                    "Hidden categories",
                    if (hiddenCount == 0) "Nothing hidden" else "$hiddenCount hidden",
                    true
                ) { hiddenEditor = MediaKind.MOVIE }
            }
        }

        item {
            SettingsCard("Parental controls") {
                if (pinHash == null) {
                    Text(
                        "Set a PIN to hide categories behind it and to lock the app on opening.",
                        color = Tone.TextMuted,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    SettingsButton(Icons.Default.Lock, "Set a PIN", "Four digits", true) {
                        pinPrompt = true
                    }
                } else {
                    PlayerToggle("Ask for the PIN when the app opens", askPinOnStart) {
                        askPinOnStart = !askPinOnStart
                        Parental.setAskOnStartup(context, askPinOnStart)
                    }
                    SettingsButton(Icons.Default.LockOpen, "Remove the PIN", "Stops asking", true) {
                        Parental.setPin(context, null)
                        pinHash = null
                        askPinOnStart = false
                        Parental.setAskOnStartup(context, false)
                        note = "PIN removed"
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

/**
 * The PIN asked for on opening. Covers the whole app, and has no way past it other than the PIN -
 * Back leaves rather than dismisses, because a gate that can be waved away is not a gate.
 */
@Composable
private fun PinGate(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    var entry by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    BackHandler { (context as? android.app.Activity)?.finish() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF050B16))
            .systemBarsPadding()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Lock, null, tint = Tone.Accent, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(14.dp))
        Text("Enter your PIN", color = Tone.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = entry,
            onValueChange = {
                if (it.length <= 4 && it.all(Char::isDigit)) {
                    entry = it
                    wrong = false
                    if (it.length == 4) {
                        if (Parental.matches(context, it)) onUnlocked() else { wrong = true; entry = "" }
                    }
                }
            },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
            ),
            modifier = Modifier.fillMaxWidth()
        )
        if (wrong) {
            Spacer(Modifier.height(8.dp))
            Text("That PIN is not right.", color = Tone.LiveRed, fontSize = 13.sp)
        }
    }
}

/** Four digits, entered twice over - once to set, once to be sure it was not a slip. */
@Composable
private fun PinDialog(title: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val ready = first.length == 4 && first == second

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = first,
                    onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) first = it },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                    )
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = second,
                    onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) second = it },
                    label = { Text("Confirm") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                    )
                )
                if (second.isNotEmpty() && first != second) {
                    Spacer(Modifier.height(6.dp))
                    Text("The two do not match.", color = Tone.LiveRed, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(first) }, enabled = ready) { Text("Save") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Which of a kind's categories to keep out of the app. The list is the playlist's own categories,
 * so it can only ever hide something that exists.
 */
@Composable
private fun HiddenCategoriesDialog(
    kind: MediaKind,
    playlist: LoadedPlaylist,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    var shownKind by remember { mutableStateOf(kind) }
    var hidden by remember(shownKind) { mutableStateOf(Parental.hidden(context, shownKind)) }
    val categories = remember(playlist, shownKind) {
        playlist.items.filter { it.kind == shownKind }.map { it.group }.distinct().sorted()
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Hidden categories") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MediaKind.entries.forEach { option ->
                        FilterChip(
                            selected = option == shownKind,
                            onClick = { shownKind = option },
                            label = {
                                Text(
                                    when (option) {
                                        MediaKind.MOVIE -> "Films"
                                        MediaKind.SERIES -> "Series"
                                        MediaKind.LIVE -> "Channels"
                                    }
                                )
                            }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(320.dp)) {
                    items(categories, key = { it }) { name ->
                        val isHidden = name in hidden
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    hidden = if (isHidden) hidden - name else hidden + name
                                    Parental.setHidden(context, shownKind, hidden)
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (isHidden) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                null,
                                tint = if (isHidden) Tone.Accent else Tone.TextMuted,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                "  $name",
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDone) { Text("Done") } }
    )
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

@Composable
private fun PlayerToggle(label: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (on) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
            null,
            tint = if (on) Tone.Accent else Tone.TextMuted,
            modifier = Modifier.size(20.dp)
        )
        Text("  $label", color = Tone.TextPrimary, fontSize = 14.sp)
    }
}

/**
 * Shrinks the app to a floating window so playback carries on while something else is used.
 *
 * Guarded rather than assumed: picture-in-picture arrived in Android 8, and a device can still have
 * it switched off per app, in which case the request throws rather than being ignored.
 */
private fun enterPictureInPicture(context: android.content.Context) {
    val activity = context as? android.app.Activity ?: return
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
    runCatching {
        activity.enterPictureInPictureMode(
            android.app.PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(16, 9))
                .build()
        )
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
    channels: List<PlaylistItem>,
    startIndex: Int,
    viewModel: PlaylistViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    // Which channel is playing, for a live list. Films and episodes never move off their own item.
    var index by remember(url) { mutableIntStateOf(startIndex) }
    val channel = channels.getOrNull(index)
    val playingTitle = channel?.name ?: title
    val playingUrl = channel?.streamUrl ?: url

    val player = remember(url) {
        buildFourKPlusExoPlayer(context, skipSeconds = 10, muted = false).apply {
            setMediaItem(MediaItem.fromUri(playingUrl))
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

    // Moving along the channel list re-points the same player rather than building another, so the
    // warm connection and decoder are kept and the change costs only the new stream.
    LaunchedEffect(playingUrl) {
        if (channel == null) return@LaunchedEffect
        failure = null
        player.setMediaItem(MediaItem.fromUri(playingUrl))
        player.prepare()
        player.play()
    }

    // What is on now and next. Fetched per channel; a provider with no guide for it simply leaves
    // the line off rather than showing an apology for it.
    var guide by remember(channel?.let(::channelKey)) { mutableStateOf<String?>(null) }
    LaunchedEffect(channel?.let(::channelKey)) {
        guide = null
        val current = channel ?: return@LaunchedEffect
        viewModel.shortEpg(current).onSuccess { epg ->
            guide = listOfNotNull(
                epg.now?.title?.takeIf(String::isNotBlank)?.let { "Now  $it" },
                epg.next?.title?.takeIf(String::isNotBlank)?.let { "Next  $it" }
            ).joinToString("     ").takeIf(String::isNotBlank)
        }
    }

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

    // Remembered across sessions, the same keys the television build writes, so a viewer who turns
    // subtitles off does not have to turn them off again tomorrow.
    val settings = remember {
        context.getSharedPreferences("playback_settings", android.content.Context.MODE_PRIVATE)
    }
    var subtitlesOn by remember { mutableStateOf(settings.getBoolean("subtitles_enabled", true)) }
    var subtitleBox by remember { mutableStateOf(settings.getBoolean("subtitle_background", true)) }
    var videoMode by remember(playingUrl) {
        mutableStateOf(settings.getString("video_mode", "fit") ?: "fit")
    }
    var optionsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(player, subtitlesOn) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, !subtitlesOn)
            .setSelectUndeterminedTextLanguage(subtitlesOn)
            .build()
    }

    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    LaunchedEffect(playerView, subtitleBox, videoMode) {
        playerView?.let {
            applySubtitleBackground(it, subtitleBox)
            applyRequestedAspectRatio(it, videoMode)
            it.resizeMode = when (videoMode) {
                "zoom" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                "stretch" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
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
                    playerView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        Column(Modifier.fillMaxWidth().systemBarsPadding().padding(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                }
                Text(
                    playingTitle,
                    color = Color.White,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // Only where there is somewhere to go. One channel on its own gets no arrows.
                if (channels.size > 1) {
                    IconButton(
                        onClick = { index = (index - 1 + channels.size) % channels.size }
                    ) {
                        Icon(Icons.Default.SkipPrevious, "Previous channel", tint = Color.White)
                    }
                    IconButton(onClick = { index = (index + 1) % channels.size }) {
                        Icon(Icons.Default.SkipNext, "Next channel", tint = Color.White)
                    }
                }
                IconButton(onClick = { enterPictureInPicture(context) }) {
                    Icon(Icons.Default.PictureInPictureAlt, "Picture in picture", tint = Color.White)
                }
                IconButton(onClick = { optionsOpen = !optionsOpen }) {
                    Icon(Icons.Default.MoreVert, "Options", tint = Color.White)
                }
            }

            if (optionsOpen) {
                Column(
                    Modifier
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xE6101A2C))
                        .padding(12.dp)
                ) {
                    PlayerToggle("Subtitles", subtitlesOn) {
                        subtitlesOn = !subtitlesOn
                        settings.edit().putBoolean("subtitles_enabled", subtitlesOn).apply()
                    }
                    // The filled box behind each cue: helpful over bright footage, in the way over
                    // dark. The viewer's call, as on the television.
                    PlayerToggle("Subtitle background", subtitleBox) {
                        subtitleBox = !subtitleBox
                        settings.edit().putBoolean("subtitle_background", subtitleBox).apply()
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Picture size", color = Tone.TextMuted, fontSize = 12.sp)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("fit" to "Fit", "zoom" to "Fill", "stretch" to "Stretch").forEach { (value, label) ->
                            FilterChip(
                                selected = videoMode == value,
                                // Not persisted, matching the television: a stretch chosen for one
                                // title should not follow the viewer into the next one.
                                onClick = { videoMode = value },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
            guide?.let {
                Text(
                    it,
                    color = Color.White.copy(alpha = .8f),
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp)
                )
            }
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
