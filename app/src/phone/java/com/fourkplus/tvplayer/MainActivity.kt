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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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

private enum class PhoneTab(val label: String, val kind: MediaKind) {
    MOVIES("Movies", MediaKind.MOVIE),
    SERIES("Series", MediaKind.SERIES),
    LIVE("Live TV", MediaKind.LIVE)
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
    data class Episodes(val series: PlaylistItem) : Overlay
    data class Playing(val title: String, val url: String) : Overlay
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

    val items = remember(playlist, tab, query) {
        val ofKind = playlist.items.filter { it.kind == tab.kind }
        if (query.isBlank()) ofKind
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
                        overlay = if (item.kind == MediaKind.SERIES) {
                            Overlay.Episodes(item)
                        } else {
                            Overlay.Playing(item.name, item.streamUrl)
                        }
                    }
                }
            }
        }
    }

    when (val current = overlay) {
        null -> Unit
        is Overlay.Episodes -> EpisodeList(
            series = current.series,
            viewModel = viewModel,
            onPlay = { title, url -> overlay = Overlay.Playing(title, url) },
            onDismiss = { overlay = null }
        )
        is Overlay.Playing -> PhonePlayer(
            title = current.title,
            url = current.url,
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
 * A series' episodes, fetched on open. The catalogue listing carries no episodes - they arrive with
 * the details call, the same one the television build makes.
 */
@Composable
private fun EpisodeList(
    series: PlaylistItem,
    viewModel: PlaylistViewModel,
    onPlay: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var episodes by remember(series) { mutableStateOf<List<SeriesEpisode>?>(null) }
    var failed by remember(series) { mutableStateOf(false) }

    LaunchedEffect(series) {
        viewModel.seriesDetails(series)
            .onSuccess { episodes = it.episodes }
            .onFailure { failed = true }
    }

    BackHandler(onBack = onDismiss)

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xF2050B16))
            .systemBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Tone.TextPrimary)
            }
            Text(
                series.name,
                color = Tone.TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        when {
            failed -> Text(
                "This series' episodes could not be loaded.",
                color = Tone.TextSecondary,
                modifier = Modifier.padding(24.dp)
            )
            episodes == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Tone.Accent)
            }
            episodes!!.isEmpty() -> Text(
                "This series has no episodes listed.",
                color = Tone.TextSecondary,
                modifier = Modifier.padding(24.dp)
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(episodes!!, key = { it.id }) { episode ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPlay("${series.name} • S${episode.seasonNumber} E${episode.episodeNumber}", episode.streamUrl) }
                            .padding(horizontal = 20.dp, vertical = 14.dp)
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
    }
}

/**
 * Full-screen playback.
 *
 * The player itself comes from the shared engine - same HTTP clients, buffering and decoder
 * fallback as the television build - so a stream that plays on one plays on the other. Only the
 * controls differ: media3's own touch controls, rather than the focus-driven overlay a remote needs.
 */
@Composable
private fun PhonePlayer(title: String, url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val player = remember(url) {
        buildFourKPlusExoPlayer(context, skipSeconds = 10, muted = false).apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }
    var failure by remember(url) { mutableStateOf<String?>(null) }

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
