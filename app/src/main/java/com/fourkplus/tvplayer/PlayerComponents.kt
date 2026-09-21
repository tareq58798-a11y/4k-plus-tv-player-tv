package com.fourkplus.tvplayer

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.fourkplus.tvplayer.data.EpgNowNext
import com.fourkplus.tvplayer.data.LiveSnapshotCache
import com.fourkplus.tvplayer.data.PlaylistItem
import com.fourkplus.tvplayer.ui.theme.*
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The single place an ExoPlayer instance gets built for this app. Both [MoviePlayer] and
 * [LiveChannelPreview] previously hand-rolled this identically; consolidated here so any future
 * change to how streams are requested (headers, redirects, etc.) only needs to happen once.
 */
/**
 * One HTTP client for all playback, with a connection pool deliberately kept alive far longer than
 * the default 5 minutes. Every channel on a playlist lives on the same provider host, so switching
 * channels can reuse an already-open socket instead of paying for a fresh DNS lookup, TCP handshake
 * and slow-start on every change - which is most of the delay between pressing the button and
 * seeing the new picture. Idle sockets cost nothing; re-establishing them costs a visible pause.
 */
/** How long the Movies/Series transport controls stay up after the last button press. Every press
 *  restarts it, including presses on the Compose options row, so it can only ever expire when the
 *  viewer has genuinely stopped navigating. */
private const val CONTROLS_TIMEOUT_MS = 5_000

/** Longest the fullscreen channel strip waits for a stream to report its resolution before giving
 *  up and dismissing anyway - a stream that never reports a size must not pin the strip up. */
private const val RESOLUTION_WAIT_MS = 3_500L

/** How long the strip stays once the resolution is actually on screen, i.e. long enough to read. */
private const val RESOLUTION_READ_MS = 3_000L


/**
 * Prevents the device from sleeping/dimming while [player] is actively playing. A TV's system
 * idle-sleep and screensaver timers only reset on remote input, which a viewer watching a channel
 * or a movie without touching the remote never generates - without this, the screen goes dark
 * mid-playback. Turns itself off the moment playback pauses/stops or this leaves composition, so
 * it never keeps the screen awake outside of actual video playback.
 */
@Composable
private fun KeepScreenOnWhilePlaying(player: Player) {
    val view = LocalView.current
    DisposableEffect(player, view) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                view.keepScreenOn = isPlaying
            }
        }
        player.addListener(listener)
        view.keepScreenOn = player.isPlaying
        onDispose {
            player.removeListener(listener)
            view.keepScreenOn = false
        }
    }
}

/**
 * Silences [player] while the app sits in the background, without stopping it. Pressing Home on a
 * TV remote must not leave a channel's audio playing over the launcher, but tearing the stream down
 * would mean re-buffering from scratch on the way back in — muting keeps the picture live and
 * decoding, so returning to the app is instant.
 *
 * The volume to come back to is captured once, when this player is first observed, rather than
 * re-read on each stop: reading it at stop time means a second stop arriving before the matching
 * start (which happens when the system pauses and re-stops an already-backgrounded activity)
 * records the muted 0f as the "real" volume and the audio never returns.
 */
@Composable
private fun MutePlayerWhileBackgrounded(player: Player) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(player, lifecycleOwner) {
        var foregroundVolume = player.volume
        var backgrounded = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> if (!backgrounded) {
                    backgrounded = true
                    foregroundVolume = player.volume
                    player.volume = 0f
                }
                Lifecycle.Event.ON_START -> if (backgrounded) {
                    backgrounded = false
                    player.volume = foregroundVolume
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (backgrounded) player.volume = foregroundVolume
        }
    }
}

/**
 * Mounts a 1dp, invisible ExoPlayer+TextureView just long enough to grab one frame from
 * [streamUrl]'s live stream, then calls [onResult] with the captured bitmap (or null on failure
 * or after a short timeout) and tears the player down. Used by Home's "Recently Watched Live TV"
 * cards to show a real, recent frame instead of a static logo, without keeping anything playing.
 *
 * Uses TextureView.getBitmap(width, height) rather than a hand-built ImageReader capture: the
 * framework handles the GL readback/scaling itself, so a decoder outputting a different
 * resolution than expected can't corrupt memory the way a manually-sized pixel buffer can.
 *
 * Capture is triggered from Player.Listener.onRenderedFirstFrame() rather than the TextureView's
 * own SurfaceTextureListener: ExoPlayer.setVideoTextureView() silently takes over that listener
 * (logged as "Replacing existing SurfaceTextureListener"), so a listener set here would simply
 * stop being called the moment playback starts.
 */
@Composable
internal fun LiveSnapshotEffect(streamUrl: String, onResult: (Bitmap?) -> Unit) {
    val context = LocalContext.current
    var firstFrameAt by remember(streamUrl) { mutableStateOf(0L) }
    var textureView by remember(streamUrl) { mutableStateOf<TextureView?>(null) }
    val player = remember(streamUrl) { buildFourKPlusExoPlayer(context, skipSeconds = 10, muted = true) }
    val completion = remember(streamUrl) { CompletableDeferred<Bitmap?>() }

    fun finishOnce(bitmap: Bitmap?) {
        if (completion.isCompleted) return
        completion.complete(bitmap)
        onResult(bitmap)
    }

    DisposableEffect(player, streamUrl) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { finishOnce(null) }
            override fun onRenderedFirstFrame() { firstFrameAt = System.nanoTime() }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(firstFrameAt) {
        if (firstFrameAt == 0L) return@LaunchedEffect
        delay(150) // lets the SurfaceTexture consume the frame (updateTexImage) before we read it
        // 16:9 to match the card it lands in, and wide enough to stay sharp there: 320x200 was
        // both narrower than the card and the wrong shape, so every frame arrived slightly squashed
        // and slightly soft.
        finishOnce(runCatching { textureView?.getBitmap(480, 270) }.getOrNull())
    }
    // Waits its turn behind any other card's capture before connecting, so accounts limited to
    // one concurrent stream don't have every visible card's attempt rejected at once.
    LaunchedEffect(player, streamUrl) {
        LiveSnapshotCache.captureMutex.withLock {
            if (completion.isCompleted) return@withLock
            player.setMediaItem(MediaItem.fromUri(streamUrl))
            player.prepare()
            player.playWhenReady = true
            // Capture is serialized (see the mutex above) so a slow/dead channel here delays
            // every other card's thumbnail queued behind it - a shorter timeout keeps a single
            // bad stream from stalling the whole Recently Watched row for several seconds.
            withTimeoutOrNull(6_000) { completion.await() }
            finishOnce(null)
        }
    }
    AndroidView(
        modifier = Modifier.size(1.dp),
        factory = { ctx -> TextureView(ctx).also { textureView = it } },
        update = { view -> player.setVideoTextureView(view) }
    )
}

/** Horizontal strip of sibling items (other episodes of a series, other channels in a category) shown under the player, with the currently-playing one highlighted and every other one tappable to switch directly. */
@Composable
private fun RelatedItemsStrip(
    items: List<PlaylistItem>,
    currentKey: String,
    onSelect: (PlaylistItem) -> Unit,
    modifier: Modifier = Modifier,
    onCollapse: () -> Unit = {}
) {
    if (items.size <= 1) return
    LazyRow(
        modifier = modifier.onKeyEvent { keyEvent ->
            // Up is the strip's own "close" gesture, mirroring the swipe-down that collapses it
            // on touch - without this, D-pad up while an item here is focused does nothing at
            // all, since there's no focusable sibling above the strip for focus to land on.
            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionUp) {
                onCollapse()
                true
            } else false
        },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
    ) {
        items(items, key = { channelKey(it) }) { related ->
            val active = channelKey(related) == currentKey
            val focusRequester = remember { FocusRequester() }
            // The strip only mounts while expanded, so this fires fresh each time it opens —
            // landing the remote's focus on the currently-playing item so D-pad left/right
            // works immediately instead of requiring the user to navigate to the strip first.
            if (active) {
                LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
            }
            Column(
                // Wide enough for the thumbnail to read as a still from the episode rather than a
                // stamp. At 88dp these were about the size of a postage stamp on a television
                // across a room, and the title under them had space for three or four words.
                Modifier.width(158.dp).focusRequester(focusRequester)
                    .focusableClickable(cornerRadius = 8.dp) { if (!active) onSelect(related) },
                horizontalAlignment = Alignment.Start
            ) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = .1f))
                        .then(if (active) Modifier.border(2.dp, Cyan, RoundedCornerShape(8.dp)) else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    if (!related.logoUrl.isNullOrBlank()) {
                        AsyncImage(related.logoUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Icon(Icons.Default.PlayCircle, null, tint = Color.White.copy(alpha = .6f))
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    related.name,
                    color = if (active) Cyan else Color.White,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    // Two lines, because an episode's name is "Series • S01E04 • Title" and one
                    // line of that is all series and no episode - exactly the part being chosen
                    // between.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
internal fun MoviePlayer(
    movie: PlaylistItem,
    startPosition: Long,
    onProgress: (Long, Long) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier,
    relatedItems: List<PlaylistItem> = emptyList(),
    onRelatedItemChange: (PlaylistItem) -> Unit = {}
) {
    val context = LocalContext.current
    val settings = remember { context.getSharedPreferences("playback_settings", android.content.Context.MODE_PRIVATE) }
    val subtitleLanguages = settings.getString("subtitle_language", "ar,en").orEmpty()
        .split(',').map(String::trim).filter(String::isNotBlank)
    var videoMode by remember { mutableStateOf(settings.getString("video_mode", "fit") ?: "fit") }
    val videoResizeMode = when (videoMode) {
        "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
    var selectedPlayer by remember(movie) {
        mutableStateOf(settings.getString("player_engine", "default") ?: "default")
    }
    var missingExternalPlayer by remember(movie) { mutableStateOf<String?>(null) }
    if (selectedPlayer != "default") {
        LaunchedEffect(movie.streamUrl, selectedPlayer) {
            if (launchExternalPlayer(context, movie.streamUrl, selectedPlayer)) {
                onExit()
            } else {
                missingExternalPlayer = selectedPlayer
            }
        }
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (missingExternalPlayer == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Cyan)
                    Spacer(Modifier.height(10.dp))
                    Text("Opening external player…")
                }
            }
        }
        missingExternalPlayer?.let { missing ->
            val playerName = externalPlayerName(missing)
            AlertDialog(
                onDismissRequest = onExit,
                icon = { Icon(Icons.Default.InstallMobile, null) },
                title = { Text("$playerName is not installed") },
                text = { Text("Install $playerName from Google Play, then return and press Watch again.") },
                confirmButton = {
                    Button(onClick = {
                        openExternalPlayerStore(context, missing)
                        missingExternalPlayer = null
                        onExit()
                    }) { Text("Install $playerName") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        settings.edit().putString("player_engine", "default").apply()
                        selectedPlayer = "default"
                        missingExternalPlayer = null
                    }) { Text("Use default player") }
                }
            )
        }
        return
    }
    var error by remember(movie) { mutableStateOf<String?>(null) }
    var resolutionLabel by remember(movie) { mutableStateOf<String?>(null) }
    // What is actually being asked for, which starts as the catalogue's URL but can be re-pointed at
    // the same title in a different container - see the unsupported-container branch in
    // onPlayerError below, and [alternateContainerUrl].
    var playUrl by remember(movie) { mutableStateOf(movie.streamUrl) }
    var triedContainers by remember(movie) { mutableStateOf(emptySet<String>()) }
    DisposableEffect(Unit) {
        PictureInPictureCoordinator.eligible = true
        PictureInPictureCoordinator.aspectRatio = 16f / 9f
        onDispose { PictureInPictureCoordinator.eligible = false }
    }
    var controllerVisible by remember { mutableStateOf(true) }
    var relatedStripExpanded by remember { mutableStateOf(false) }
    // Read from inside the PlayerView's key handling, which is built once. A plain capture of
    // relatedItems there would freeze at whatever the episode list was when the view was created,
    // so switching episodes could leave Down opening a strip that no longer has anything in it.
    val relatedItemCount by rememberUpdatedState(relatedItems.size)
    // Portrait boxes the video to 16:9 with the episode switcher below it instead of overlaid on
    // top of it (see the dispatch at the end of this function) — skipped while in a
    // picture-in-picture window, which is always shown as a plain edge-to-edge rectangle.
    val portraitLayout = LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE &&
        !PictureInPictureCoordinator.active
    val isTv = remember { context.isTvDevice() }
    // The native ExoPlayer/media3 controller (play/pause, seek bar) owns its own show/hide state
    // internally; this reference is what lets the D-pad handling below actually drive it, since
    // just flipping the Compose `controllerVisible` var wouldn't touch the real View.
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    val rootFocusRequester = remember { FocusRequester() }
    // Re-requested whenever the strip closes too, since focus moves into it while it's open
    // (see RelatedItemsStrip) and this effect otherwise only reruns on controllerVisible.
    LaunchedEffect(isTv, controllerVisible, relatedStripExpanded) {
        if (isTv && !controllerVisible && !relatedStripExpanded) runCatching { rootFocusRequester.requestFocus() }
    }
    // True while the remote's focus sits on one of the Compose option buttons (mute, subtitles,
    // resolution…) drawn above the native controller. Those buttons and media3's own buttons live
    // in two different focus systems, and without this the effect below would drag focus back out
    // of the options row every time the controller reappeared.
    var optionsRowFocused by remember { mutableStateOf(false) }
    // Lands the remote's focus on the play/pause button itself whenever the controller becomes
    // visible - entering fullscreen, or bringing the controls back up with OK - instead of
    // leaving it wherever Android's default "first focusable view" guess happens to land. Skipped
    // while the user is working along the options row, which is a deliberate focus position rather
    // than a guess to correct.
    LaunchedEffect(isTv, controllerVisible, playerViewRef) {
        if (isTv && controllerVisible && !optionsRowFocused) {
            runCatching { playerViewRef?.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus() }
        }
    }
    // Back-hides-controls-first is implemented by overriding dispatchKeyEvent on the PlayerView
    // itself (see its factory below), not a Compose BackHandler here: media3's controller buttons
    // are real focusable native children, and once one of them holds Android focus, a raw Back
    // key press never reaches a BackHandler in this composable at all — it's consumed by that
    // native view hierarchy (or falls through to the app's normal back handling) first.
    var subtitlesEnabled by remember { mutableStateOf(settings.getBoolean("subtitles_enabled", true)) }
    var subtitleBackground by remember { mutableStateOf(settings.getBoolean("subtitle_background", true)) }
    var externalSubtitle by remember(movie) { mutableStateOf<Uri?>(null) }
    var skipSeconds by remember { mutableIntStateOf(settings.getInt("skip_seconds", 10).takeIf { it in listOf(5, 10, 15, 30, 60) } ?: 10) }
    var seekFeedback by remember { mutableStateOf<Pair<Boolean, Long>?>(null) }
    val nextRelatedItem = remember(movie, relatedItems) {
        val currentIndex = relatedItems.indexOfFirst { channelKey(it) == channelKey(movie) }
        relatedItems.getOrNull(currentIndex + 1)
    }
    LaunchedEffect(seekFeedback?.second) {
        if (seekFeedback != null) {
            delay(650)
            seekFeedback = null
        }
    }
    val player = remember(movie.streamUrl, skipSeconds) {
        buildFourKPlusExoPlayer(context, skipSeconds, muted = settings.getBoolean("muted", false))
    }
    KeepScreenOnWhilePlaying(player)
    MutePlayerWhileBackgrounded(player)
    // Leaving a movie or episode always goes through this confirmation, so a stray Back press
    // during playback can't throw away what's being watched. Playback pauses while the question is
    // on screen and resumes on "keep watching" only if it was actually playing beforehand.
    var confirmExit by remember { mutableStateOf(false) }
    var resumeAfterConfirm by remember { mutableStateOf(false) }
    fun requestExit() {
        if (confirmExit) return
        resumeAfterConfirm = player.playWhenReady
        player.playWhenReady = false
        confirmExit = true
    }
    fun cancelExit() {
        confirmExit = false
        if (resumeAfterConfirm) player.playWhenReady = true
    }
    // Registered after the host screen's own back handling, so it wins while the player is on
    // screen. It is only reached once the native controls are hidden — while they're up,
    // PlayerView.dispatchKeyEvent below consumes Back to hide them first.
    BackHandler {
        when {
            confirmExit -> cancelExit()
            relatedStripExpanded -> relatedStripExpanded = false
            // Back closes the controls before it closes the film, wherever focus happens to be
            // sitting inside them. PlayerView.dispatchKeyEvent below already covers media3's own
            // native buttons, but the options row along the top and the settings button in the
            // corner are Compose nodes - their Back presses never reach that view at all, so
            // without this branch pressing Back on either one asked to leave the film instead.
            controllerVisible -> runCatching { playerViewRef?.hideController() }
            else -> requestExit()
        }
    }
    LaunchedEffect(player, playUrl, externalSubtitle) {
        error = null
        val resumeAt = player.currentPosition.takeIf { it > 0L } ?: startPosition
        player.setMediaItem(mediaItemWithSubtitle(context, playUrl, externalSubtitle))
        if (resumeAt > 0L) player.seekTo(resumeAt)
        player.prepare()
        player.playWhenReady = true
        while (true) {
            delay(2_000)
            if (player.currentPosition > 0L) onProgress(player.currentPosition, player.duration)
        }
    }
    LaunchedEffect(player, subtitlesEnabled) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled)
            .setPreferredTextLanguages(*subtitleLanguages.toTypedArray())
            .setSelectUndeterminedTextLanguage(subtitlesEnabled)
            .build()
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(playbackException: PlaybackException) {
                // A panel that does not report the container leaves the catalogue guessing, and a
                // request for the wrong file is answered with an error page rather than video -
                // which arrives here as "unsupported container", because that is what the player
                // was handed. Try the other containers these panels use before saying so.
                if (playbackException.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
                    val tried = triedContainers + playUrl.substringAfterLast('.', "").lowercase()
                    val next = alternateContainerUrl(playUrl, tried)
                    if (next != null) {
                        triedContainers = tried
                        playUrl = next
                        return
                    }
                }
                error = playbackFailureMessage(playbackException)
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    resolutionLabel = "${videoSize.width} x ${videoSize.height}"
                }
            }
            override fun onRenderedFirstFrame() {
                player.videoSize.takeIf { it.width > 0 && it.height > 0 }?.let {
                    resolutionLabel = "${it.width} x ${it.height}"
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                    nextRelatedItem?.let(onRelatedItemChange)
                }
            }
        }
        player.addListener(listener)
        onDispose {
            if (player.currentPosition > 0L) onProgress(player.currentPosition, player.duration)
            player.removeListener(listener)
            player.release()
        }
    }
    val playerContent: @Composable (Modifier, Shape) -> Unit = { contentModifier, shape ->
        Surface(contentModifier, shape, color = Color.Black) {
            Box(
                Modifier.fillMaxSize()
                    .then(
                        if (isTv) {
                            Modifier.focusRequester(rootFocusRequester).focusable().onKeyEvent { keyEvent ->
                                if (!keyEvent.isInitialKeyDown || controllerVisible || relatedStripExpanded) return@onKeyEvent false
                                when (keyEvent.key) {
                                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { playerViewRef?.showController(); true }
                                    Key.DirectionDown -> {
                                        if (relatedItems.size > 1) { relatedStripExpanded = true; true } else false
                                    }
                                    else -> false
                                }
                            }
                        } else Modifier
                    )
            ) {
            AndroidView(
                factory = {
                    // A plain setOnKeyListener here would only ever fire while the PlayerView
                    // itself is the focused view — but media3's own controller buttons (play/
                    // pause, seek) are real focusable children, and once the controller is shown
                    // one of THEM holds actual Android focus instead. dispatchKeyEvent is called
                    // on this root view regardless of which descendant is focused, so overriding
                    // it here is the only reliable place to intercept Back before either that
                    // child or the system's default back handling ever sees it.
                    object : PlayerView(it) {
                        override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                            // Down walks the controls from top to bottom in the order they are
                            // drawn: the timeline, then the settings gear beneath it, then the
                            // episode strip below that. Handled here for the same reason Back is -
                            // once the controller is up one of media3's own buttons holds Android
                            // focus, so a listener on this view alone would never fire.
                            if (isTv && event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN &&
                                event.action == android.view.KeyEvent.ACTION_DOWN &&
                                event.repeatCount == 0 && controllerVisible && !relatedStripExpanded
                            ) {
                                if (advanceDownThroughControls(this, relatedItemCount > 1) { relatedStripExpanded = true }) {
                                    // Each step counts as interaction, or the controls time out
                                    // halfway down and the next press starts over from nothing.
                                    showController()
                                    return true
                                }
                            }
                            if (isTv && event.keyCode == android.view.KeyEvent.KEYCODE_BACK && controllerVisible) {
                                // Both halves of the press are consumed, and only its opening
                                // key-down acts: letting the key-up through after the controls are
                                // already gone lets the Activity's back handling run too, so one
                                // press both hid the controls and left the player.
                                if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                                    hideController()
                                }
                                return true
                            }
                            return super.dispatchKeyEvent(event)
                        }
                    }.apply {
                        useController = true
                        // Hide five seconds after the last interaction. The options row above this
                        // view is Compose, so its key presses never reach PlayerView and would not
                        // restart this timer on their own - which is why that row reports every
                        // press back through onUserInteraction below. Without that the controller
                        // vanishes partway along the row, mid-navigation.
                        setControllerShowTimeoutMs(CONTROLS_TIMEOUT_MS)
                        setShowPreviousButton(false)
                        setShowNextButton(false)
                        setControllerVisibilityListener(
                            PlayerView.ControllerVisibilityListener { visibility ->
                                controllerVisible = visibility == android.view.View.VISIBLE
                                if (isTv && visibility == android.view.View.VISIBLE) applyTvControlFocusHighlight(this)
                            }
                        )
                        resizeMode = videoResizeMode
                        applyRequestedAspectRatio(this, videoMode)
                        applySubtitleBackground(this, subtitleBackground)
                        this.player = player
                        installDoubleTapSeek(
                            this, player, skipSeconds,
                            onSwipeUp = { relatedStripExpanded = true },
                            onSwipeDown = { relatedStripExpanded = false }
                        ) { forward ->
                            seekFeedback = forward to System.nanoTime()
                        }
                        playerViewRef = this
                        if (isTv) applyTvControlFocusHighlight(this)
                    }
                },
                update = {
                    it.player = player
                    it.useController = !PictureInPictureCoordinator.active
                    it.resizeMode = videoResizeMode
                    applyRequestedAspectRatio(it, videoMode)
                    applySubtitleBackground(it, subtitleBackground)
                    installDoubleTapSeek(
                        it, player, skipSeconds,
                        onSwipeUp = { relatedStripExpanded = true },
                        onSwipeDown = { relatedStripExpanded = false }
                    ) { forward ->
                        seekFeedback = forward to System.nanoTime()
                    }
                    playerViewRef = it
                // The portrait layout already insets the whole player via safeDrawingPadding
                // below; landscape stays edge-to-edge and needs this to clear the nav bar itself.
                }, modifier = Modifier.fillMaxSize().then(if (portraitLayout) Modifier else Modifier.navigationBarsPadding())
            )
            seekFeedback?.let { feedback ->
                DoubleTapSeekFeedback(
                    forward = feedback.first,
                    seconds = skipSeconds,
                    eventId = feedback.second,
                    modifier = Modifier
                        .align(if (feedback.first) Alignment.CenterEnd else Alignment.CenterStart)
                        .padding(horizontal = 34.dp)
                )
            }
            // Part of the player's own chrome: what is playing, top-left, opposite the options
            // row - so pressing OK brings up the controls and the title together. An episode's
            // name already reads "Series • S1 E2 • Episode title", so one line covers both.
            if (controllerVisible && !PictureInPictureCoordinator.active) {
                Text(
                    movie.name,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    // No background box behind it, so the text carries its own drop shadow - the
                    // same treatment the Live TV channel banner uses to stay readable over bright
                    // video.
                    style = TextStyle(
                        shadow = Shadow(color = Color.Black.copy(alpha = .95f), offset = Offset(0f, 1f), blurRadius = 6f)
                    ),
                    modifier = Modifier.align(Alignment.TopStart)
                        .padding(start = 18.dp, top = 14.dp)
                        .fillMaxWidth(.55f)
                )
            }
            if (controllerVisible && seekFeedback == null && !PictureInPictureCoordinator.active) PlaybackOptionsOverlay(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                player = player,
                fullscreen = true,
                onFullscreenChange = { enabled ->
                    if (!enabled) requestExit()
                },
                subtitlesEnabled = subtitlesEnabled,
                onSubtitlesEnabledChange = {
                    subtitlesEnabled = it
                    settings.edit().putBoolean("subtitles_enabled", it).apply()
                },
                subtitleBackground = subtitleBackground,
                onSubtitleBackgroundChange = {
                    subtitleBackground = it
                    settings.edit().putBoolean("subtitle_background", it).apply()
                },
                externalSubtitle = externalSubtitle,
                onExternalSubtitleChange = { externalSubtitle = it },
                skipSeconds = skipSeconds,
                onSkipSecondsChange = {
                    skipSeconds = it
                    settings.edit().putInt("skip_seconds", it).apply()
                },
                videoMode = videoMode,
                showResolution = true,
                resolutionLabel = resolutionLabel,
                onRowFocusChanged = { optionsRowFocused = it },
                // Restarts the native controller's five-second countdown, so working along this
                // Compose row keeps the controls up exactly as pressing its own buttons would.
                onUserInteraction = { runCatching { playerViewRef?.showController() } },
                onExitDown = {
                    runCatching {
                        playerViewRef?.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus()
                    }
                },
                // Deliberately not persisted (unlike mute/subtitles/skip above): aspect ratio is
                // usually specific to whatever's currently playing (an old 4:3 show, say) - it
                // should reset to the real default (set in Settings) for the next thing watched,
                // not silently carry a stretch/zoom choice over into a different movie or into
                // Live TV/Series.
                onVideoModeChange = { videoMode = it },
                onBackPress = { runCatching { playerViewRef?.hideController() } }
            )
            error?.let {
                Surface(Modifier.align(Alignment.Center).padding(20.dp), RoundedCornerShape(12.dp), color = Color.Black.copy(alpha = .84f)) {
                    Text(it, color = Color.White, modifier = Modifier.padding(16.dp))
                }
            }
            // In portrait the equivalent hint/strip is shown below the boxed video instead (see
            // the render dispatch at the end of this function), not overlaid on top of it.
            if (controllerVisible && !relatedStripExpanded && relatedItems.size > 1 && !PictureInPictureCoordinator.active && !portraitLayout) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    "Swipe up for other episodes",
                    tint = Color.White.copy(alpha = .6f),
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 8.dp).size(22.dp)
                )
            }
            if (relatedStripExpanded && relatedItems.size > 1 && !PictureInPictureCoordinator.active && !portraitLayout) {
                Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 64.dp)) {
                    Text(
                        "Other episodes",
                        color = Color.White.copy(alpha = .75f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 10.dp, bottom = 2.dp)
                    )
                    RelatedItemsStrip(
                        items = relatedItems,
                        currentKey = channelKey(movie),
                        onSelect = onRelatedItemChange,
                        onCollapse = { relatedStripExpanded = false }
                    )
                }
            }
        }
    }
    }
    // Rendered directly in the Activity's own content (not a Dialog, which opens a separate
    // Android window) so entering picture-in-picture — which resizes the Activity's window —
    // actually carries the video into the floating window instead of leaving it blank. Portrait
    // still gets its own boxed-16:9-plus-suggestions layout, just inline rather than in a dialog.
    if (portraitLayout) {
        Column(
            modifier = modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            playerContent(Modifier.fillMaxWidth().aspectRatio(16f / 9f), RectangleShape)
            if (relatedItems.size > 1) {
                if (relatedStripExpanded) {
                    Text(
                        "Other episodes",
                        color = Color.White.copy(alpha = .75f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 10.dp, bottom = 2.dp)
                    )
                    RelatedItemsStrip(
                        items = relatedItems,
                        currentKey = channelKey(movie),
                        onSelect = onRelatedItemChange,
                        onCollapse = { relatedStripExpanded = false },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (controllerVisible) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        "Swipe up for other episodes",
                        tint = Color.White.copy(alpha = .6f),
                        modifier = Modifier.padding(top = 8.dp).size(22.dp)
                    )
                }
            }
        }
    } else {
        playerContent(modifier.fillMaxSize(), RectangleShape)
    }
    if (confirmExit) {
        val stopFocusRequester = remember { FocusRequester() }
        // The remote has no pointer, so the dialog opens with "stop watching" already focused:
        // Back then OK leaves in two presses, while Back on its own no longer leaves at all.
        LaunchedEffect(Unit) { runCatching { stopFocusRequester.requestFocus() } }
        AlertDialog(
            onDismissRequest = { cancelExit() },
            title = { Text(stringResource(R.string.stop_playback_title)) },
            text = { Text(stringResource(R.string.stop_playback_message)) },
            confirmButton = {
                Button(
                    onClick = { confirmExit = false; onExit() },
                    modifier = Modifier.focusRequester(stopFocusRequester)
                ) { Text(stringResource(R.string.stop_playback_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { cancelExit() }) { Text(stringResource(R.string.stop_playback_stay)) }
            }
        )
    }
}

@Composable
private fun DoubleTapSeekFeedback(
    forward: Boolean,
    seconds: Int,
    eventId: Long,
    modifier: Modifier = Modifier
) {
    val movement = remember(eventId) { Animatable(0f) }
    LaunchedEffect(eventId) {
        movement.animateTo(1f, animationSpec = tween(480))
    }
    Surface(
        modifier = modifier.graphicsLayer {
            translationX = (if (forward) 1f else -1f) * movement.value * 22f
            alpha = 1f - movement.value * .35f
        },
        color = Color.Black.copy(alpha = .55f),
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!forward) {
                Text("−${seconds}s", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.width(3.dp))
            }
            repeat(3) {
                Icon(
                    if (forward) Icons.Default.ChevronRight else Icons.Default.ChevronLeft,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            if (forward) {
                Spacer(Modifier.width(3.dp))
                Text("+${seconds}s", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

/**
 * Own the player window's bounds, not just the timeline's padding. Portrait lets
 * Android fit the entire window above system bars; landscape stays edge-to-edge.
 * Explicit MATCH_PARENT avoids a floating dialog measuring its content taller
 * than the available window. Insets belong to this dialog, not the host Scaffold.
 */
@Composable
private fun FullscreenPlayerDialog(
    onDismissRequest: () -> Unit,
    content: @Composable (Modifier, Boolean) -> Unit
) {
    val portrait = LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = portrait
        )
    ) {
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (window != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = if (portrait) {
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                        } else {
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        }
                    }
                }
                window.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            content(
                Modifier.fillMaxSize()
                    .then(if (portrait) Modifier.safeDrawingPadding() else Modifier)
                    .clipToBounds(),
                portrait
            )
        }
    }
}

/** Paints the D-pad focus ring on media3's native controller buttons (play/pause, rewind,
 *  fast-forward, and the settings gear that holds audio track and playback speed) - plain Android
 *  Views, so they can't use the app's usual Compose drawFocusRing modifier. Safe to call
 *  repeatedly: findViewById just returns null for any ID the current controller layout doesn't
 *  have. */
private fun applyTvControlFocusHighlight(view: PlayerView) {
    val ids = intArrayOf(
        androidx.media3.ui.R.id.exo_play_pause,
        androidx.media3.ui.R.id.exo_rew_with_amount,
        androidx.media3.ui.R.id.exo_ffwd_with_amount,
        androidx.media3.ui.R.id.exo_rew,
        androidx.media3.ui.R.id.exo_ffwd,
        // The gear is now a stop on the way down from the timeline, so it has to show focus as
        // plainly as the transport buttons do - it is a small grey icon in a corner otherwise.
        androidx.media3.ui.R.id.exo_settings,
        androidx.media3.ui.R.id.exo_subtitle,
        androidx.media3.ui.R.id.exo_audio_track
    )
    for (id in ids) {
        view.findViewById<android.view.View>(id)?.background = view.context.getDrawable(R.drawable.exo_control_focus_selector)
    }
    applyTvTimeBarFocusHighlight(view)
}

/**
 * Turns the seek bar's scrubber cyan while the remote is on it.
 *
 * The bar is a line the full width of the screen with a small dot on it, and a dot that does not
 * change cannot say whether the next press will seek or go somewhere else entirely. media3 grows
 * the scrubber while it is being dragged but does nothing for focus, so the colour is set here.
 *
 * Guarded on the view actually being a DefaultTimeBar, because a custom controller layout is free
 * to put anything at all under that ID.
 */
private fun applyTvTimeBarFocusHighlight(view: PlayerView) {
    val bar = view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_progress) as? DefaultTimeBar ?: return
    bar.isFocusable = true
    bar.setOnFocusChangeListener { _, hasFocus ->
        bar.setScrubberColor(if (hasFocus) TimeBarFocusedScrubber else TimeBarRestingScrubber)
        bar.setPlayedColor(if (hasFocus) TimeBarFocusedScrubber else TimeBarRestingScrubber)
    }
}

/** Cyan, the colour focus is everywhere else in the app. */
private const val TimeBarFocusedScrubber = 0xFF23D7EE.toInt()
private const val TimeBarRestingScrubber = 0xFFFFFFFF.toInt()

/**
 * Moves focus one step down through the player's controls, and says whether it did.
 *
 * Three stops, in the order they appear on screen: the timeline, the settings gear under it, then
 * the episode strip below that. [hasStrip] is false for a film, which has no other episodes to go
 * to - the third press then simply does nothing rather than opening an empty drawer.
 *
 * Derived from where focus actually is rather than from a counter of presses. A counter drifts the
 * moment anything else moves focus - the options row hands off to play/pause, the controller hides
 * and comes back - and then Down starts doing the wrong thing with no way for the viewer to get it
 * back in step.
 */
private fun advanceDownThroughControls(view: PlayerView, hasStrip: Boolean, onOpenStrip: () -> Unit): Boolean {
    // An episode goes straight there. Walking the timeline and the settings gear first is the
    // right order for a film, where there is nothing below them worth reaching; for a series the
    // thing under the controls is the rest of the season, and making somebody press three times to
    // see it turns the commonest thing they want into the furthest one away.
    if (hasStrip) {
        onOpenStrip()
        return true
    }
    val timeBar = view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_progress)
    val settings = view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_settings)
    return when (view.findFocus()) {
        null -> timeBar?.requestFocus() ?: false
        timeBar -> settings?.requestFocus() ?: false
        settings -> false
        // Anywhere else in the controller - play/pause, rewind, fast-forward - is the top row, so
        // the first press down from it lands on the timeline like it does from nowhere at all.
        else -> timeBar?.requestFocus() ?: false
    }
}


private fun installDoubleTapSeek(
    view: PlayerView,
    player: Player,
    skipSeconds: Int,
    onDoubleTapExit: (() -> Unit)? = null,
    onSingleTap: (() -> Unit)? = null,
    onSwipeUp: (() -> Unit)? = null,
    onSwipeDown: (() -> Unit)? = null,
    onSeekFeedback: (Boolean) -> Unit
) {
    val detector = android.view.GestureDetector(
        view.context,
        object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: android.view.MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(event: android.view.MotionEvent): Boolean {
                onSingleTap?.invoke()
                return onSingleTap != null
            }

            override fun onDoubleTap(event: android.view.MotionEvent): Boolean {
                if (onDoubleTapExit != null) {
                    view.hideController()
                    onDoubleTapExit()
                    return true
                }
                if (!player.isCurrentMediaItemSeekable) return false
                val intervalMs = skipSeconds * 1_000L
                val destination = if (event.x < view.width / 2f) {
                    (player.currentPosition - intervalMs).coerceAtLeast(0L)
                } else {
                    val forward = player.currentPosition + intervalMs
                    if (player.duration > 0L) forward.coerceAtMost(player.duration) else forward
                }
                val forward = event.x >= view.width / 2f
                player.seekTo(destination)
                view.postDelayed({ view.hideController() }, 80L)
                onSeekFeedback(forward)
                return true
            }
        }
    )
    var dragStartX = 0f
    var dragStartY = 0f
    var dragHandled = false
    view.setOnTouchListener { _, event ->
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                dragStartX = event.x
                dragStartY = event.y
                dragHandled = false
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (!dragHandled && (onSwipeUp != null || onSwipeDown != null)) {
                    val deltaY = event.y - dragStartY
                    val deltaX = event.x - dragStartX
                    if (kotlin.math.abs(deltaY) > 60 && kotlin.math.abs(deltaY) > kotlin.math.abs(deltaX)) {
                        if (deltaY < 0) onSwipeUp?.invoke() else onSwipeDown?.invoke()
                        dragHandled = true
                    }
                }
            }
        }
        detector.onTouchEvent(event)
        false
    }
}

@Composable
private fun PlaybackOptionsOverlay(
    modifier: Modifier = Modifier,
    player: Player,
    fullscreen: Boolean,
    showFullscreen: Boolean = true,
    onFullscreenChange: (Boolean) -> Unit,
    subtitlesEnabled: Boolean,
    onSubtitlesEnabledChange: (Boolean) -> Unit,
    subtitleBackground: Boolean,
    onSubtitleBackgroundChange: (Boolean) -> Unit,
    externalSubtitle: Uri?,
    onExternalSubtitleChange: (Uri?) -> Unit,
    skipSeconds: Int,
    onSkipSecondsChange: (Int) -> Unit,
    // Live TV has nothing to skip forward/back through - hidden there, shown for Movies/Series.
    showSkipInterval: Boolean = true,
    videoMode: String,
    onVideoModeChange: (String) -> Unit,
    // Live TV reports its resolution on the channel banner instead, so it leaves this off.
    showResolution: Boolean = false,
    resolutionLabel: String? = null,
    // TV fullscreen only: focus lands on the mute button (the first control) as soon as this
    // overlay appears, and pressing Left from there collapses it back - there's no touch
    // target to tap away from it the way the embedded/mobile overlay has.
    autoFocusFirstOnDpad: Boolean = false,
    onCollapseOnDpad: (() -> Unit)? = null,
    // Reports whether the remote's focus is anywhere in this row, so a host that also drives a
    // native media3 controller can avoid pulling focus back out from under the user.
    onRowFocusChanged: ((Boolean) -> Unit)? = null,
    // Down from this row hands focus back to the player's own transport controls; without it the
    // row is a dead end, since those controls are native views Compose will not find on its own.
    onExitDown: (() -> Unit)? = null,
    // Fired on every key press in this row. These are Compose nodes, so their presses never reach
    // the native PlayerView and cannot restart its auto-hide timer by themselves - the host uses
    // this to keep the controls up while the viewer is still working along the row.
    onUserInteraction: (() -> Unit)? = null,
    // Back, pressed anywhere in this row, puts the controls away rather than leaving what is
    // playing. See the preview key handler below for why the row answers this itself.
    onBackPress: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val collapseKey = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        Key.DirectionRight
    } else {
        Key.DirectionLeft
    }
    var subtitleMenu by remember { mutableStateOf(false) }
    var skipMenu by remember { mutableStateOf(false) }
    var sizeMenu by remember { mutableStateOf(false) }
    var resolutionMenu by remember { mutableStateOf(false) }
    var muted by remember(player) { mutableStateOf(player.volume == 0f) }
    val muteFocusRequester = remember { FocusRequester() }
    LaunchedEffect(autoFocusFirstOnDpad) {
        if (autoFocusFirstOnDpad) runCatching { muteFocusRequester.requestFocus() }
    }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onExternalSubtitleChange(uri)
            onSubtitlesEnabledChange(true)
        }
    }
    Surface(
        modifier = modifier
            .then(
                if (onRowFocusChanged != null) {
                    Modifier.onFocusChanged { onRowFocusChanged(it.hasFocus) }
                } else Modifier
            )
            .then(
                if (onExitDown != null || onUserInteraction != null || onBackPress != null) {
                    Modifier.onPreviewKeyEvent { event ->
                        if (event.isInitialKeyDown) onUserInteraction?.invoke()
                        when {
                            onExitDown != null && event.isInitialKeyDown && event.key == Key.DirectionDown -> {
                                onExitDown(); true
                            }
                            // Back closes the controls from anywhere in this row. It is handled
                            // here rather than left to a BackHandler because these buttons are
                            // focused Compose nodes: with one of them focused the first Back press
                            // is spent inside the focus system and never reaches a back dispatcher,
                            // so the viewer had to press Back twice to put the row away. A preview
                            // handler on the row itself sees the press before its own buttons do.
                            onBackPress != null && event.isInitialKeyDown && event.key == Key.Back -> {
                                onBackPress(); true
                            }
                            else -> false
                        }
                    }
                } else Modifier
            ),
        color = Color.Black.copy(alpha = .68f),
        shape = RoundedCornerShape(13.dp)
    ) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedIconButton(
                onClick = {
                    muted = !muted
                    player.volume = if (muted) 0f else 1f
                    context.getSharedPreferences("playback_settings", android.content.Context.MODE_PRIVATE)
                        .edit().putBoolean("muted", muted).apply()
                },
                modifier = Modifier.size(38.dp)
                    .focusRequester(muteFocusRequester)
                    .onKeyEvent { event ->
                        // Outward from this first button closes the bar. Which direction that is
                        // mirrors with the language: the row is anchored to the trailing edge and
                        // lays its buttons out in reading order, so mute sits at the inner end of
                        // it either way - on the left of the row in English, on the right in Arabic.
                        if (autoFocusFirstOnDpad && event.type == KeyEventType.KeyDown && event.key == collapseKey) {
                            onCollapseOnDpad?.invoke()
                            true
                        } else false
                    }
            ) {
                Icon(
                    if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    if (muted) "Unmute" else "Mute",
                    tint = if (muted) Cyan else Color.White
                )
            }
            Box {
                AnimatedIconButton(onClick = { subtitleMenu = true }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.Subtitles, "Subtitles", tint = if (subtitlesEnabled) Cyan else Color.White)
                }
                DropdownMenu(subtitleMenu, onDismissRequest = { subtitleMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (subtitlesEnabled) "Turn subtitles off" else "Turn subtitles on") },
                        leadingIcon = { Icon(Icons.Default.Subtitles, null) },
                        onClick = { onSubtitlesEnabledChange(!subtitlesEnabled); subtitleMenu = false }
                    )
                    // Some streams burn a filled box behind every cue. It helps over bright
                    // footage and gets in the way over dark footage, so it is the viewer's call;
                    // with the box off the text keeps a black outline so it stays readable.
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (subtitleBackground) R.string.subtitle_background_hide
                                    else R.string.subtitle_background_show
                                )
                            )
                        },
                        leadingIcon = {
                            Icon(
                                if (subtitleBackground) Icons.Default.FormatColorReset else Icons.Default.FormatColorFill,
                                null
                            )
                        },
                        onClick = { onSubtitleBackgroundChange(!subtitleBackground); subtitleMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Load SRT or VTT file") },
                        leadingIcon = { Icon(Icons.Default.NoteAdd, null) },
                        onClick = {
                            subtitleMenu = false
                            subtitlePicker.launch(arrayOf("application/x-subrip", "text/vtt", "text/plain", "application/octet-stream"))
                        }
                    )
                    if (externalSubtitle != null) {
                        DropdownMenuItem(
                            text = { Text("Remove external subtitles") },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                            onClick = { onExternalSubtitleChange(null); subtitleMenu = false }
                        )
                    }
                }
            }
            if (showSkipInterval) Box {
                AnimatedIconButton(onClick = { skipMenu = true }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.MoreTime, "Skip interval", tint = Color.White)
                }
                DropdownMenu(skipMenu, onDismissRequest = { skipMenu = false }) {
                    listOf(5, 10, 15, 30, 60).forEach { seconds ->
                        DropdownMenuItem(
                            text = { Text("Skip $seconds seconds") },
                            leadingIcon = { if (seconds == skipSeconds) Icon(Icons.Default.Check, null) },
                            onClick = { onSkipSecondsChange(seconds); skipMenu = false }
                        )
                    }
                }
            }
            if (showResolution) Box {
                AnimatedIconButton(onClick = { resolutionMenu = true }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.HighQuality, "Current resolution", tint = Color.White)
                }
                DropdownMenu(resolutionMenu, onDismissRequest = { resolutionMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(resolutionLabel ?: "Resolution not available yet") },
                        leadingIcon = { Icon(Icons.Default.HighQuality, null) },
                        onClick = { resolutionMenu = false }
                    )
                }
            }
            Box {
                AnimatedIconButton(onClick = { sizeMenu = true }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.AspectRatio, "Screen dimensions", tint = Cyan)
                }
                DropdownMenu(sizeMenu, onDismissRequest = { sizeMenu = false }) {
                    listOf(
                        "fit" to "Fit video",
                        "stretch" to "Stretch to screen",
                        "zoom" to "Fill and crop",
                        "16:9" to "16:9 Standard",
                        "4:3" to "4:3 Traditional",
                        "21:9" to "21:9 Ultrawide",
                        "1:1" to "1:1 Square"
                    ).forEach { (mode, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            leadingIcon = { if (videoMode == mode) Icon(Icons.Default.Check, null) },
                            onClick = { onVideoModeChange(mode); sizeMenu = false }
                        )
                    }
                }
            }
            if (showFullscreen) {
                AnimatedIconButton(onClick = { onFullscreenChange(!fullscreen) }, modifier = Modifier.size(38.dp)) {
                    Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Fullscreen", tint = Color.White)
                }
            }
        }
    }
}

private fun launchExternalPlayer(
    context: android.content.Context,
    streamUrl: String,
    preference: String
): Boolean {
    val packages = when (preference) {
        "vlc" -> listOf("org.videolan.vlc")
        "mx" -> listOf("com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro")
        else -> emptyList()
    }
    for (packageName in packages) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(streamUrl), "video/*")
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) return true
    }
    return false
}

private fun externalPlayerName(preference: String): String =
    if (preference == "vlc") "VLC" else "MX Player"

private fun openExternalPlayerStore(context: android.content.Context, preference: String) {
    val packageName = if (preference == "vlc") "org.videolan.vlc" else "com.mxtech.videoplayer.ad"
    val playStoreIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (!runCatching { context.startActivity(playStoreIntent); true }.getOrDefault(false)) {
        val browserIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { context.startActivity(browserIntent) }
    }
}

private fun mediaItemWithSubtitle(context: android.content.Context, streamUrl: String, subtitle: Uri?): MediaItem {
    val builder = MediaItem.Builder().setUri(streamUrl)
    if (subtitle != null) {
        val detected = context.contentResolver.getType(subtitle).orEmpty()
        val mime = if (detected.contains("vtt", true)) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
        builder.setSubtitleConfigurations(
            listOf(MediaItem.SubtitleConfiguration.Builder(subtitle).setMimeType(mime).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build())
        )
    }
    return builder.build()
}

/**
 * @param autoAdvanceOnFailure When true, a playback failure on the currently auto-selected
 * channel silently advances to the next entry in [channelList] (bounded, see `autoAdvanceAttempts`
 * below) instead of showing the error card. Only meant for previews still on their auto-selected
 * first channel — call sites where the user deliberately picked a channel should leave this false
 * so a failure there surfaces the normal per-channel error banner.
 */
@Composable
internal fun LiveChannelPreview(
    channel: PlaylistItem?,
    modifier: Modifier = Modifier,
    externalPlayback: Boolean = false,
    channelList: List<PlaylistItem> = emptyList(),
    onChannelChange: (PlaylistItem) -> Unit = {},
    autoAdvanceOnFailure: Boolean = false,
    hostedFullscreen: Boolean = false,
    onFullscreenDoubleTap: (() -> Unit)? = null,
    onRequestFullscreen: (() -> Unit)? = null,
    onExitFullscreen: (() -> Unit)? = null,
    showFullscreenButton: Boolean? = null,
    // Hoisted so a caller that also owns a fullscreen-exit BackHandler (Live TV) can resolve
    // "hide controls" vs "exit fullscreen" as a single decision in one place — two separate
    // BackHandlers at the same Activity-level dispatcher don't reliably prioritize the inner one,
    // so that split can't be made locally here the way [MoviePlayer]'s Dialog-scoped one can.
    controllerVisibleState: MutableState<Boolean>? = null,
    // Only used for the TV fullscreen channel banner's now/next lines - null elsewhere skips them.
    loadEpg: (suspend (PlaylistItem) -> Result<EpgNowNext>)? = null
) {
    val context = LocalContext.current
    val settings = remember { context.getSharedPreferences("playback_settings", android.content.Context.MODE_PRIVATE) }
    val subtitleLanguages = settings.getString("subtitle_language", "ar,en").orEmpty()
        .split(',').map(String::trim).filter(String::isNotBlank)
    // The options bar is anchored to the trailing edge, which mirrors with the language: it sits
    // top right in English and top left in Arabic. The keys that reach it and leave it have to
    // mirror with it, or in Arabic the bar opens with a press aimed at the opposite side of the
    // screen from where it appears, and cannot be closed again from its first button.
    val rtlLayout = LocalLayoutDirection.current == LayoutDirection.Rtl
    val towardsBar = if (rtlLayout) Key.DirectionLeft else Key.DirectionRight
    val awayFromBar = if (rtlLayout) Key.DirectionRight else Key.DirectionLeft
    // Keyed on the channel, so a stretch or zoom belongs to the channel it was chosen for and the
    // next one starts from the real default again. A 4:3 channel is worth zooming; the HD channel
    // after it is not, and carrying the choice across meant every later channel arrived cropped
    // until the viewer noticed and undid it by hand.
    var videoMode by remember(channel?.streamUrl) {
        mutableStateOf(settings.getString("video_mode", "fit") ?: "fit")
    }
    val videoResizeMode = when (videoMode) {
        "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
    val selectedPlayer = settings.getString("player_engine", "default") ?: "default"
    var externalAttempt by remember(channel?.streamUrl) { mutableIntStateOf(0) }
    var externalFailed by remember(channel?.streamUrl) { mutableStateOf(false) }
    if (externalPlayback && channel != null && selectedPlayer != "default") {
        LaunchedEffect(channel.streamUrl, selectedPlayer, externalAttempt) {
            externalFailed = !launchExternalPlayer(context, channel.streamUrl, selectedPlayer)
        }
        Box(
            modifier.background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (externalFailed) Icons.Default.ErrorOutline else Icons.Default.OpenInNew,
                    null,
                    tint = if (externalFailed) MaterialTheme.colorScheme.error else Cyan,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (externalFailed) "${externalPlayerName(selectedPlayer)} is not installed." else "Stream opened in external player.",
                    color = Color.White
                )
                Spacer(Modifier.height(10.dp))
                if (externalFailed) {
                    Button(onClick = { openExternalPlayerStore(context, selectedPlayer) }) {
                        Icon(Icons.Default.InstallMobile, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Install ${externalPlayerName(selectedPlayer)}")
                    }
                    TextButton(onClick = {
                        settings.edit().putString("player_engine", "default").apply()
                        externalAttempt++
                    }) { Text("Use default player", color = Color.White) }
                } else {
                    OutlinedButton(onClick = { externalAttempt++ }) {
                        Text("Open again")
                    }
                }
            }
        }
        return
    }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var resolutionLabel by remember { mutableStateOf<String?>(null) }
    // Plain var, not state: only the player listener reads it, and writing it must never
    // recompose. Measures prepare() -> first rendered frame, i.e. how long a channel change
    // actually takes to put a picture on screen.
    var switchStartedAt by remember { mutableLongStateOf(0L) }
    // Deliberately no next-channel preloading here, though ExoPlayer supports it. It was built and
    // measured, and on these streams it was consistently *slower* (median 1394ms against 1186ms
    // without) while also costing a second concurrent connection. Preloading assumes the queued
    // item starts at a fixed point; a live stream does not, so whatever was buffered ahead is
    // already stale by the time the viewer switches and the player re-syncs to live regardless -
    // paying the same keyframe wait, having spent the bandwidth for nothing.
    var fullscreen by remember { mutableStateOf(false) }
    val controllerVisibleHolder = controllerVisibleState ?: remember { mutableStateOf(true) }
    var controllerVisible by controllerVisibleHolder
    var controllerShownAt by remember { mutableLongStateOf(System.nanoTime()) }
    var stripExpanded by remember { mutableStateOf(false) }
    // Embedded previews must stay clean — suggestions are a fullscreen-only control. Unlike
    // MoviePlayer's portrait boxing, Live TV's hostedFullscreen (the single immersive block that
    // now covers both orientations) is always genuinely fullscreen, so no portrait exclusion here.
    val suggestionsEnabled = fullscreen || hostedFullscreen
    LaunchedEffect(suggestionsEnabled) {
        if (!suggestionsEnabled) stripExpanded = false
    }
    fun showControllerBriefly() {
        controllerVisible = true
        controllerShownAt = System.nanoTime()
    }
    // controllerVisible is shared with the embedded (non-fullscreen) preview and persists across
    // the transition — since the single player instance is never torn down (see hostedFullscreen
    // above), it's very likely already false from sitting idle in the background before the user
    // opened fullscreen. Force it back on at the moment fullscreen actually starts, the same as a
    // fresh player would, so there's a controls-visible window (and Back has something to hide).
    // Live TV's own TV fullscreen (hostedFullscreen) starts with the top bar hidden on purpose —
    // the Right d-pad key toggles it on demand (see the onKeyEvent handler below) instead of it
    // appearing automatically the way the touch/mobile Dialog fullscreen's controls do.
    LaunchedEffect(suggestionsEnabled, hostedFullscreen) {
        if (hostedFullscreen) controllerVisible = false
        else if (suggestionsEnabled) showControllerBriefly()
    }
    // D-pad focus target for the whole fullscreen surface, used once no overlay is claiming
    // input (top bar hidden, strip closed). Re-requested whenever the strip closes or the top
    // bar hides too, since focus moves into those while they're open (see RelatedItemsStrip /
    // PlaybackOptionsOverlay).
    val rootFocusRequester = remember { FocusRequester() }
    LaunchedEffect(suggestionsEnabled, controllerVisible, stripExpanded) {
        if (suggestionsEnabled && !stripExpanded && !controllerVisible) {
            runCatching { rootFocusRequester.requestFocus() }
        }
    }
    var subtitlesEnabled by remember { mutableStateOf(settings.getBoolean("subtitles_enabled", true)) }
    var subtitleBackground by remember { mutableStateOf(settings.getBoolean("subtitle_background", true)) }
    var externalSubtitle by remember(channel?.streamUrl) { mutableStateOf<Uri?>(null) }
    var skipSeconds by remember { mutableIntStateOf(settings.getInt("skip_seconds", 10).takeIf { it in listOf(5, 10, 15, 30, 60) } ?: 10) }
    var seekFeedback by remember { mutableStateOf<Pair<Boolean, Long>?>(null) }
    // name, logo URL, timestamp - the timestamp is only there to key the auto-clear effect below.
    var channelSwitchFeedback by remember { mutableStateOf<Triple<String, String?, Long>?>(null) }
    // Persists for this composable's lifetime (not reset per channel) so a chain of consecutive
    // auto-advances is bounded overall, not just per hop.
    var autoAdvanceAttempts by remember { mutableIntStateOf(0) }
    LaunchedEffect(seekFeedback?.second) {
        if (seekFeedback != null) {
            delay(650)
            seekFeedback = null
        }
    }
    LaunchedEffect(channelSwitchFeedback?.third, hostedFullscreen) {
        if (channelSwitchFeedback == null) return@LaunchedEffect
        if (!hostedFullscreen) {
            delay(1_200)
            channelSwitchFeedback = null
            return@LaunchedEffect
        }
        // A stream's resolution is only known once its first frame decodes, which on a slow-opening
        // channel lands well after a fixed timer would have taken this strip away - losing the
        // viewer the one line they were waiting to read. So the countdown does not start until the
        // resolution is actually on screen, and then runs long enough to read it. The wait is
        // capped: a stream that never reports a size must not pin the strip up indefinitely.
        val resolutionArrived = withTimeoutOrNull(RESOLUTION_WAIT_MS) {
            snapshotFlow { resolutionLabel }.first { it != null }
        } != null
        if (resolutionArrived) delay(RESOLUTION_READ_MS)
        channelSwitchFeedback = null
    }
    // TV fullscreen shows the channel banner as soon as it opens (not just on a subsequent
    // channel change) so the viewer always sees what they're watching, matching a normal remote.
    LaunchedEffect(hostedFullscreen, channel?.let(::channelKey)) {
        if (hostedFullscreen) channel?.let { channelSwitchFeedback = Triple(it.name, it.logoUrl, System.nanoTime()) }
    }
    // No separate "re-show once the resolution arrives" pass any more: the dismissal timer above
    // now waits for it, so the strip stays up the first time instead of vanishing and popping back.
    val nowNext = if (hostedFullscreen && loadEpg != null) rememberEpgNowNext(channel, loadEpg) else null
    // TV fullscreen's top bar stays up until the user explicitly presses Left from the mute
    // button (see onCollapseOnDpad) - no auto-hide timer there. Elsewhere (the embedded/touch
    // preview) it still hides itself after a few seconds since there's no equivalent dismiss key.
    LaunchedEffect(controllerShownAt, controllerVisible, hostedFullscreen) {
        if (controllerVisible && !hostedFullscreen) {
            delay(4_000)
            controllerVisible = false
        }
    }
    val player = remember(skipSeconds) {
        buildFourKPlusExoPlayer(context, skipSeconds, muted = settings.getBoolean("muted", false)).apply { playWhenReady = true }
    }
    KeepScreenOnWhilePlaying(player)
    MutePlayerWhileBackgrounded(player)
    fun switchChannel(forward: Boolean) {
        val current = channel ?: return
        val index = channelList.indexOfFirst { channelKey(it) == channelKey(current) }
        if (index < 0) return
        val next = channelList.getOrNull(if (forward) index + 1 else index - 1) ?: return
        onChannelChange(next)
        channelSwitchFeedback = Triple(next.name, next.logoUrl, System.nanoTime())
    }

    LaunchedEffect(channel?.streamUrl, externalSubtitle) {
        playbackError = null
        resolutionLabel = null
        if (channel == null) {
            player.clearMediaItems()
        } else {
            switchStartedAt = android.os.SystemClock.elapsedRealtime()
            runCatching {
                player.setMediaItem(mediaItemWithSubtitle(context, channel.streamUrl, externalSubtitle))
                player.prepare()
                player.play()
            }.onFailure {
                playbackError = "This channel could not be previewed."
                player.clearMediaItems()
            }
        }
    }
    LaunchedEffect(player, subtitlesEnabled) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled)
            .setPreferredTextLanguages(*subtitleLanguages.toTypedArray())
            .setSelectUndeterminedTextLanguage(subtitlesEnabled)
            .build()
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            // Adaptive streams re-report their size whenever the ladder switches rungs, so this
            // only refreshes the label — it deliberately does not re-show the channel banner.
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    resolutionLabel = "${videoSize.width} x ${videoSize.height}"
                }
            }
            // Switching channels clears the label, but onVideoSizeChanged only fires when the size
            // actually changes - hopping between two channels that share a resolution would leave
            // the label empty for the whole banner. Every new stream renders a first frame though,
            // so this re-reads the size the player already has.
            override fun onRenderedFirstFrame() {
                player.videoSize.takeIf { it.width > 0 && it.height > 0 }?.let {
                    resolutionLabel = "${it.width} x ${it.height}"
                }
                val startedAt = switchStartedAt
                if (startedAt > 0L) {
                    switchStartedAt = 0L
                    android.util.Log.i(
                        "LiveSwitch",
                        "first_frame_ms=${android.os.SystemClock.elapsedRealtime() - startedAt}"
                    )
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val advanceTo = if (autoAdvanceOnFailure && channel != null && autoAdvanceAttempts < 3) {
                    val currentIndex = channelList.indexOfFirst { channelKey(it) == channelKey(channel) }
                    channelList.getOrNull(currentIndex + 1)
                } else null
                if (advanceTo != null) {
                    autoAdvanceAttempts++
                    onChannelChange(advanceTo)
                } else {
                    playbackError = playbackFailureMessage(error)
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val fullscreenDoubleTapExit: (() -> Unit)? = if ((fullscreen || hostedFullscreen) && onFullscreenDoubleTap != null) {
        { fullscreen = false; onFullscreenDoubleTap() }
    } else null
    // On TV, the first Back press while fullscreen should just dismiss the overlay controls
    // (matching how the on-screen "hide" gesture works for touch) — only a second Back press,
    // once they're already hidden, should fall through to whatever exits fullscreen. In Live TV's
    // hostedFullscreen the top bar is toggled by Right instead (see the onKeyEvent handler below),
    // and Back always exits directly regardless of the bar — LiveTvScreen owns that single-press
    // BackHandler itself.
    if (suggestionsEnabled && !hostedFullscreen && controllerVisibleState == null) {
        BackHandler(enabled = controllerVisible) { controllerVisible = false }
    }
    val playerContent: @Composable (Modifier, Shape) -> Unit = { contentModifier, shape ->
        Surface(modifier = contentModifier, shape = shape, color = Color.Black, shadowElevation = 8.dp) {
            Box(
                Modifier.fillMaxSize()
                    .then(
                        if (suggestionsEnabled) {
                            Modifier.focusRequester(rootFocusRequester).focusable().onKeyEvent { keyEvent ->
                                // Once the strip is open, left/right/OK are its own D-pad
                                // navigation — the active item already has focus (see
                                // RelatedItemsStrip), so this must get out of the way instead of
                                // switching the channel the instant the user nudges the highlight.
                                if (!keyEvent.isInitialKeyDown || channel == null || stripExpanded) return@onKeyEvent false
                                // Live TV's TV fullscreen has no controls overlay to gate on (see
                                // PlaybackOptionsOverlay below); elsewhere (touch/mobile) these keys
                                // only fire once the on-screen controls are hidden.
                                if (!hostedFullscreen && controllerVisible) return@onKeyEvent false
                                when (keyEvent.key) {
                                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                        // OK always exits TV fullscreen back to the categories and
                                        // channel list - Back already does this, this just gives
                                        // OK the same result since it's the more natural button.
                                        if (hostedFullscreen) {
                                            if (onExitFullscreen != null) {
                                                // This composable stops handling keys the instant
                                                // fullscreen ends, so the key-up would otherwise be
                                                // delivered to the channel row focus lands on and
                                                // read there as a fresh press that reopens it.
                                                RemoteInputGate.consumeRestOfPress(keyEvent.nativeKeyEvent.keyCode)
                                                onExitFullscreen()
                                                true
                                            } else false
                                        } else { showControllerBriefly(); true }
                                    }
                                    // TV fullscreen has no channel strip (see suppressions around
                                    // stripExpanded below) - Up/Down change channel there instead
                                    // of Left/Right, freeing Right to toggle the top bar. Elsewhere
                                    // (the embedded preview) the strip still opens on Down and
                                    // channels still change with Left/Right, unchanged. While the
                                    // top bar is open, Up/Down are left unconsumed here (false) -
                                    // otherwise the channel would silently change underneath an
                                    // still-open bar meant for the channel you were just watching.
                                    Key.DirectionDown -> when {
                                        hostedFullscreen && controllerVisible -> false
                                        hostedFullscreen -> { if (channelList.size > 1) { switchChannel(forward = false); true } else false }
                                        channelList.size > 1 -> { stripExpanded = true; true }
                                        else -> false
                                    }
                                    Key.DirectionUp -> {
                                        if (hostedFullscreen && !controllerVisible && channelList.size > 1) { switchChannel(forward = true); true } else false
                                    }
                                    awayFromBar -> {
                                        if (!hostedFullscreen && channelList.size > 1) { switchChannel(forward = false); true } else false
                                    }
                                    // Only opens the top bar in TV fullscreen (focus lands on
                                    // mute); once it's open, this key must NOT keep being swallowed
                                    // here or the event never reaches Compose's default d-pad focus
                                    // search, which is what moves focus from mute to the next
                                    // control. The only way to hide the bar again is pressing
                                    // outward while mute has focus, handled in
                                    // PlaybackOptionsOverlay via onCollapseOnDpad.
                                    towardsBar -> when {
                                        hostedFullscreen && !controllerVisible -> { showControllerBriefly(); true }
                                        hostedFullscreen -> false
                                        channelList.size > 1 -> { switchChannel(forward = true); true }
                                        else -> false
                                    }
                                    else -> false
                                }
                            }
                        } else Modifier
                    )
            ) {
            if (channel == null) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.LiveTv, null, tint = Cyan, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.height(7.dp))
                    Text("Choose a channel", color = Color.White.copy(alpha = .75f))
                }
            } else {
                AndroidView(
                    factory = {
                        PlayerView(it).apply {
                            useController = false
                            // Switching channels re-prepares the same player, which takes as long as
                            // opening the new stream takes. This used to hold the previous
                            // channel's last frame across that gap, which read as the picture
                            // having frozen on the channel you had just left. Blanking to black
                            // instead says plainly that a switch is in progress - the channel name
                            // and logo are drawn over it (see channelSwitchFeedback) so the black
                            // is never bare.
                            setKeepContentOnPlayerReset(false)
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                            resizeMode = videoResizeMode
                            applySubtitleBackground(this, subtitleBackground)
                            this.player = player
                        }
                    },
                    update = {
                        it.player = player
                        it.resizeMode = videoResizeMode
                        applyRequestedAspectRatio(it, videoMode)
                        applySubtitleBackground(it, subtitleBackground)
                    },
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    Modifier.fillMaxSize()
                        .pointerInput(channel.streamUrl, skipSeconds, fullscreenDoubleTapExit) {
                            detectTapGestures(
                                onTap = {
                                    if (controllerVisible) controllerVisible = false else showControllerBriefly()
                                },
                                onDoubleTap = { offset ->
                                    if (fullscreenDoubleTapExit != null) {
                                        fullscreenDoubleTapExit()
                                    } else if (player.isCurrentMediaItemSeekable) {
                                        val intervalMs = skipSeconds * 1_000L
                                        val forward = offset.x >= size.width / 2f
                                        val destination = if (forward) {
                                            val target = player.currentPosition + intervalMs
                                            if (player.duration > 0L) target.coerceAtMost(player.duration) else target
                                        } else {
                                            (player.currentPosition - intervalMs).coerceAtLeast(0L)
                                        }
                                        player.seekTo(destination)
                                        seekFeedback = forward to System.nanoTime()
                                    }
                                }
                            )
                        }
                        .pointerInput(suggestionsEnabled, hostedFullscreen) {
                            // TV fullscreen has no channel strip - see the onKeyEvent handler above.
                            if (!suggestionsEnabled || hostedFullscreen) return@pointerInput
                            var accumulated = 0f
                            detectVerticalDragGestures(
                                onDragStart = { accumulated = 0f },
                                onVerticalDrag = { change, dragAmount ->
                                    accumulated += dragAmount
                                    if (accumulated < -60) {
                                        stripExpanded = true
                                        change.consume()
                                    } else if (accumulated > 60) {
                                        stripExpanded = false
                                        change.consume()
                                    }
                                }
                            )
                        }
                        .then(
                            if ((fullscreen || hostedFullscreen) && channelList.size > 1) {
                                Modifier.pointerInput(channel.streamUrl) {
                                    var accumulated = 0f
                                    detectHorizontalDragGestures(
                                        onDragStart = { accumulated = 0f },
                                        onHorizontalDrag = { change, dragAmount ->
                                            accumulated += dragAmount
                                            if (accumulated < -80) {
                                                switchChannel(forward = true)
                                                accumulated = 0f
                                                change.consume()
                                            } else if (accumulated > 80) {
                                                switchChannel(forward = false)
                                                accumulated = 0f
                                                change.consume()
                                            }
                                        }
                                    )
                                }
                            } else Modifier
                        )
                )
                if (!PictureInPictureCoordinator.active) channelSwitchFeedback?.let { feedback ->
                    val noInfo = stringResource(R.string.epg_no_info)
                    Surface(
                        modifier = Modifier
                            .align(if (hostedFullscreen) Alignment.BottomStart else Alignment.TopCenter)
                            .padding(if (hostedFullscreen) PaddingValues(start = 22.dp, bottom = 22.dp) else PaddingValues(top = 18.dp)),
                        color = Color.Transparent,
                        shape = RoundedCornerShape(if (hostedFullscreen) 14.dp else 20.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = if (hostedFullscreen) 10.dp else 8.dp)
                        ) {
                            if (!feedback.second.isNullOrBlank()) {
                                Surface(
                                    modifier = Modifier.size(if (hostedFullscreen) 56.dp else 28.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color.White.copy(alpha = .1f)
                                ) {
                                    AsyncImage(feedback.second, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                }
                                Spacer(Modifier.width(11.dp))
                            }
                            // The banner has no background box (transparent), so every line gets a
                            // drop shadow instead - otherwise white text disappears against bright
                            // video playing directly behind it.
                            val legibleShadow = Shadow(color = Color.Black.copy(alpha = .95f), offset = Offset(0f, 1f), blurRadius = 6f)
                            Column {
                                Text(
                                    feedback.first,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = if (hostedFullscreen) 16.sp else 13.sp,
                                    maxLines = 1,
                                    style = TextStyle(shadow = legibleShadow)
                                )
                                resolutionLabel?.let {
                                    Text(it, color = Cyan, fontSize = 12.sp, maxLines = 1, style = TextStyle(shadow = legibleShadow))
                                }
                                if (hostedFullscreen && loadEpg != null) {
                                    Text(nowNext?.now?.title ?: noInfo, color = Orange, fontSize = 12.sp, maxLines = 1, style = TextStyle(shadow = legibleShadow))
                                    Text(nowNext?.next?.title ?: noInfo, color = Color.White.copy(alpha = .75f), fontSize = 12.sp, maxLines = 1, style = TextStyle(shadow = legibleShadow))
                                }
                            }
                        }
                    }
                }
                seekFeedback?.let { feedback ->
                    DoubleTapSeekFeedback(
                        forward = feedback.first,
                        seconds = skipSeconds,
                        eventId = feedback.second,
                        modifier = Modifier
                            .align(if (feedback.first) Alignment.CenterEnd else Alignment.CenterStart)
                            .padding(horizontal = 34.dp)
                    )
                }
                // In TV fullscreen this is the top bar toggled by the Right d-pad key (see the
                // onKeyEvent handler above); elsewhere it's the tap-to-reveal touch controls.
                if (controllerVisible && seekFeedback == null && !PictureInPictureCoordinator.active) PlaybackOptionsOverlay(
                    modifier = Modifier.align(Alignment.TopEnd)
                        .then(
                            if (fullscreen) Modifier
                                .windowInsetsPadding(WindowInsets.displayCutout)
                                .padding(horizontal = 16.dp)
                            else Modifier
                        )
                        .padding(8.dp),
                    player = player,
                    fullscreen = fullscreen,
                    showFullscreen = showFullscreenButton ?: (!hostedFullscreen && onRequestFullscreen == null),
                    onFullscreenChange = { requested ->
                        if (requested && onRequestFullscreen != null) onRequestFullscreen() else fullscreen = requested
                    },
                    subtitlesEnabled = subtitlesEnabled,
                    onSubtitlesEnabledChange = {
                        subtitlesEnabled = it
                        settings.edit().putBoolean("subtitles_enabled", it).apply()
                    },
                    subtitleBackground = subtitleBackground,
                    onSubtitleBackgroundChange = {
                        subtitleBackground = it
                        settings.edit().putBoolean("subtitle_background", it).apply()
                    },
                    externalSubtitle = externalSubtitle,
                    onExternalSubtitleChange = { externalSubtitle = it },
                    skipSeconds = skipSeconds,
                    onSkipSecondsChange = {
                        skipSeconds = it
                        settings.edit().putInt("skip_seconds", it).apply()
                    },
                    showSkipInterval = false,
                    videoMode = videoMode,
                    // Neither persisted to disk nor carried across a channel change - the state
                    // itself is keyed on the channel, see where it is declared. A stretch/zoom
                    // belongs to the channel it was picked for; every other channel, and Movies and
                    // Series, start from the real default (set in Settings) again.
                    onVideoModeChange = { videoMode = it },
                    autoFocusFirstOnDpad = hostedFullscreen,
                    onCollapseOnDpad = { controllerVisible = false },
                    onBackPress = { controllerVisible = false }
                )
                if (playbackError != null) {
                    Surface(
                        modifier = Modifier.align(Alignment.Center).padding(18.dp),
                        color = Color.Black.copy(alpha = .82f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFFF5A67))
                            Spacer(Modifier.width(8.dp))
                            Text(playbackError.orEmpty(), color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
                if (suggestionsEnabled && !hostedFullscreen && controllerVisible && !stripExpanded && channelList.size > 1 && !PictureInPictureCoordinator.active) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        "Swipe up for other channels",
                        tint = Color.White.copy(alpha = .6f),
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .then(if (fullscreen) Modifier.navigationBarsPadding() else Modifier)
                            .padding(bottom = 8.dp)
                            .size(22.dp)
                    )
                }
                // TV fullscreen has no channel strip - stripExpanded can no longer become true
                // there (see the onKeyEvent handler above), but this guard is kept explicit anyway.
                if (suggestionsEnabled && !hostedFullscreen && stripExpanded && channelList.size > 1 && !PictureInPictureCoordinator.active) {
                    Column(
                        Modifier.align(Alignment.BottomCenter)
                            .then(if (fullscreen || hostedFullscreen) Modifier.navigationBarsPadding() else Modifier)
                            .padding(bottom = 8.dp)
                    ) {
                        Text(
                            "More in this category",
                            color = Color.White.copy(alpha = .75f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 10.dp, bottom = 2.dp)
                        )
                        RelatedItemsStrip(
                            items = channelList,
                            currentKey = channel.let(::channelKey),
                            onSelect = onChannelChange,
                            onCollapse = { stripExpanded = false }
                        )
                    }
                }
            }
        }
    }
    }
    if (fullscreen) {
        FullscreenPlayerDialog(onDismissRequest = { fullscreen = false }) { bounds, isPortrait ->
            if (isPortrait) {
                Column(
                    modifier = bounds.padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    playerContent(Modifier.fillMaxWidth().aspectRatio(16f / 9f), RectangleShape)
                    if (channelList.size > 1) {
                        if (stripExpanded) {
                            Text(
                                "More in this category",
                                color = Color.White.copy(alpha = .75f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 10.dp, bottom = 2.dp)
                            )
                            RelatedItemsStrip(
                                items = channelList,
                                currentKey = channel?.let(::channelKey).orEmpty(),
                                onSelect = onChannelChange,
                                onCollapse = { stripExpanded = false },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else if (controllerVisible) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                "Swipe up for other channels",
                                tint = Color.White.copy(alpha = .6f),
                                modifier = Modifier.padding(top = 8.dp).size(22.dp)
                            )
                        }
                    }
                }
            } else {
                playerContent(bounds, RectangleShape)
            }
        }
    } else {
        // TV fullscreen (hostedFullscreen) must be edge-to-edge with square corners - the rounded
        // shape is only for the embedded preview card sitting inside the browse screens.
        playerContent(modifier, if (hostedFullscreen) RectangleShape else RoundedCornerShape(18.dp))
    }
}

/** Only expose structured codes; exception messages may contain account URLs. */
/**
 * The containers these panels actually store films and episodes in, in the order worth trying.
 * mkv first because it is both the commonest and the one most often left unreported.
 */
private val containerCandidates = listOf("mkv", "mp4", "ts", "avi", "m3u8")

/**
 * The same title asked for in a different container, or null when there is nothing left to try.
 *
 * Only ever rewrites an extension this list knows: a URL ending in something else is one the panel
 * was specific about, and guessing over the top of that would turn a clear failure into a slower,
 * stranger one.
 */
private fun alternateContainerUrl(url: String, alreadyTried: Set<String>): String? {
    val dot = url.lastIndexOf('.')
    if (dot <= url.lastIndexOf('/')) return null
    val current = url.substring(dot + 1).lowercase()
    if (current !in containerCandidates) return null
    val next = containerCandidates.firstOrNull { it != current && it !in alreadyTried } ?: return null
    return url.substring(0, dot + 1) + next
}

private fun playbackFailureMessage(error: PlaybackException): String {
    var cause: Throwable? = error
    repeat(12) {
        val current = cause ?: return@repeat
        if (current is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
            return "Playback failed: HTTP ${current.responseCode} (code ${error.errorCode})."
        }
        cause = current.cause
    }
    return "Playback failed: ${error.errorCodeName} (code ${error.errorCode})."
}
