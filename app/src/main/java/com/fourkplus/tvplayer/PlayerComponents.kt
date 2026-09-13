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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.fourkplus.tvplayer.data.LiveSnapshotCache
import com.fourkplus.tvplayer.data.PlaylistItem
import com.fourkplus.tvplayer.ui.theme.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The single place an ExoPlayer instance gets built for this app. Both [MoviePlayer] and
 * [LiveChannelPreview] previously hand-rolled this identically; consolidated here so any future
 * change to how streams are requested (headers, redirects, etc.) only needs to happen once.
 */
internal fun buildFourKPlusExoPlayer(context: android.content.Context, skipSeconds: Int, muted: Boolean): ExoPlayer {
    val dataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("VLC/3.0.20 LibVLC/3.0.20")
        .setAllowCrossProtocolRedirects(true)
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
        .setSeekBackIncrementMs(skipSeconds * 1_000L)
        .setSeekForwardIncrementMs(skipSeconds * 1_000L)
        .build()
        .apply { volume = if (muted) 0f else 1f }
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
        finishOnce(runCatching { textureView?.getBitmap(320, 200) }.getOrNull())
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
                Modifier.width(88.dp).focusRequester(focusRequester)
                    .focusableClickable(cornerRadius = 8.dp) { if (!active) onSelect(related) },
                horizontalAlignment = Alignment.CenterHorizontally
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
                Spacer(Modifier.height(4.dp))
                Text(
                    related.name,
                    color = if (active) Cyan else Color.White,
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
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
    DisposableEffect(Unit) {
        PictureInPictureCoordinator.eligible = true
        PictureInPictureCoordinator.aspectRatio = 16f / 9f
        onDispose { PictureInPictureCoordinator.eligible = false }
    }
    var controllerVisible by remember { mutableStateOf(true) }
    var relatedStripExpanded by remember { mutableStateOf(false) }
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
    // Lands the remote's focus on the play/pause button itself whenever the controller becomes
    // visible - entering fullscreen, or bringing the controls back up with OK - instead of
    // leaving it wherever Android's default "first focusable view" guess happens to land.
    LaunchedEffect(isTv, controllerVisible, playerViewRef) {
        if (isTv && controllerVisible) {
            runCatching { playerViewRef?.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus() }
        }
    }
    // Back-hides-controls-first is implemented by overriding dispatchKeyEvent on the PlayerView
    // itself (see its factory below), not a Compose BackHandler here: media3's controller buttons
    // are real focusable native children, and once one of them holds Android focus, a raw Back
    // key press never reaches a BackHandler in this composable at all — it's consumed by that
    // native view hierarchy (or falls through to the app's normal back handling) first.
    var subtitlesEnabled by remember { mutableStateOf(settings.getBoolean("subtitles_enabled", true)) }
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
    LaunchedEffect(player, movie.streamUrl, externalSubtitle) {
        error = null
        val resumeAt = player.currentPosition.takeIf { it > 0L } ?: startPosition
        player.setMediaItem(mediaItemWithSubtitle(context, movie.streamUrl, externalSubtitle))
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
            override fun onPlayerError(playbackException: PlaybackException) { error = playbackFailureMessage(playbackException) }
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
                                if (keyEvent.type != KeyEventType.KeyDown || controllerVisible || relatedStripExpanded) return@onKeyEvent false
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
                            if (isTv && event.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                                event.action == android.view.KeyEvent.ACTION_UP && controllerVisible
                            ) {
                                hideController()
                                return true
                            }
                            return super.dispatchKeyEvent(event)
                        }
                    }.apply {
                        useController = true
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
            if (controllerVisible && seekFeedback == null && !PictureInPictureCoordinator.active) PlaybackOptionsOverlay(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                player = player,
                fullscreen = true,
                onFullscreenChange = { enabled ->
                    if (!enabled) onExit()
                },
                subtitlesEnabled = subtitlesEnabled,
                onSubtitlesEnabledChange = {
                    subtitlesEnabled = it
                    settings.edit().putBoolean("subtitles_enabled", it).apply()
                },
                externalSubtitle = externalSubtitle,
                onExternalSubtitleChange = { externalSubtitle = it },
                skipSeconds = skipSeconds,
                onSkipSecondsChange = {
                    skipSeconds = it
                    settings.edit().putInt("skip_seconds", it).apply()
                },
                videoMode = videoMode,
                onVideoModeChange = {
                    videoMode = it
                    settings.edit().putString("video_mode", it).apply()
                }
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

/** Paints a blue D-pad focus ring on media3's native controller buttons (play/pause, rewind,
 *  fast-forward) - plain Android Views, so they can't use the app's usual cyan Compose
 *  drawFocusRing modifier. Safe to call repeatedly: findViewById just returns null for any ID
 *  the current controller layout doesn't have. */
private fun applyTvControlFocusHighlight(view: PlayerView) {
    val ids = intArrayOf(
        androidx.media3.ui.R.id.exo_play_pause,
        androidx.media3.ui.R.id.exo_rew_with_amount,
        androidx.media3.ui.R.id.exo_ffwd_with_amount,
        androidx.media3.ui.R.id.exo_rew,
        androidx.media3.ui.R.id.exo_ffwd
    )
    for (id in ids) {
        view.findViewById<android.view.View>(id)?.background = view.context.getDrawable(R.drawable.exo_control_focus_selector)
    }
}

private fun applyRequestedAspectRatio(view: PlayerView, mode: String) {
    val targetRatio = when (mode) {
        "16:9" -> 16f / 9f
        "4:3" -> 4f / 3f
        "21:9" -> 21f / 9f
        "1:1" -> 1f
        else -> null
    }
    val surface = view.videoSurfaceView ?: return
    if (targetRatio == null) {
        surface.scaleX = 1f
        surface.scaleY = 1f
        return
    }
    view.post {
        val width = view.width.toFloat().coerceAtLeast(1f)
        val height = view.height.toFloat().coerceAtLeast(1f)
        val containerRatio = width / height
        if (targetRatio > containerRatio) {
            surface.scaleX = 1f
            surface.scaleY = containerRatio / targetRatio
        } else {
            surface.scaleX = targetRatio / containerRatio
            surface.scaleY = 1f
        }
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
    externalSubtitle: Uri?,
    onExternalSubtitleChange: (Uri?) -> Unit,
    skipSeconds: Int,
    onSkipSecondsChange: (Int) -> Unit,
    videoMode: String,
    onVideoModeChange: (String) -> Unit
) {
    val context = LocalContext.current
    var subtitleMenu by remember { mutableStateOf(false) }
    var skipMenu by remember { mutableStateOf(false) }
    var sizeMenu by remember { mutableStateOf(false) }
    var muted by remember(player) { mutableStateOf(player.volume == 0f) }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onExternalSubtitleChange(uri)
            onSubtitlesEnabledChange(true)
        }
    }
    Surface(
        modifier = modifier,
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
            Box {
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
    controllerVisibleState: MutableState<Boolean>? = null
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
    // Live TV's own TV fullscreen (hostedFullscreen) has no on-screen controls at all — see
    // PlaybackOptionsOverlay below — so this only matters for the touch/mobile Dialog fullscreen.
    LaunchedEffect(suggestionsEnabled, hostedFullscreen) {
        if (suggestionsEnabled && !hostedFullscreen) showControllerBriefly()
    }
    // D-pad focus target for the whole fullscreen surface: on TV Live TV there's no controls
    // overlay to gate on, so this is always the target; elsewhere it's only needed once the
    // (touch-oriented) controls are hidden. Re-requested whenever the strip closes too, since
    // focus moves into it while it's open (see RelatedItemsStrip).
    val rootFocusRequester = remember { FocusRequester() }
    LaunchedEffect(suggestionsEnabled, controllerVisible, stripExpanded, hostedFullscreen) {
        if (suggestionsEnabled && !stripExpanded && (hostedFullscreen || !controllerVisible)) {
            runCatching { rootFocusRequester.requestFocus() }
        }
    }
    var subtitlesEnabled by remember { mutableStateOf(settings.getBoolean("subtitles_enabled", true)) }
    var externalSubtitle by remember(channel?.streamUrl) { mutableStateOf<Uri?>(null) }
    var skipSeconds by remember { mutableIntStateOf(settings.getInt("skip_seconds", 10).takeIf { it in listOf(5, 10, 15, 30, 60) } ?: 10) }
    var seekFeedback by remember { mutableStateOf<Pair<Boolean, Long>?>(null) }
    var channelSwitchFeedback by remember { mutableStateOf<Pair<String, Long>?>(null) }
    // Persists for this composable's lifetime (not reset per channel) so a chain of consecutive
    // auto-advances is bounded overall, not just per hop.
    var autoAdvanceAttempts by remember { mutableIntStateOf(0) }
    LaunchedEffect(seekFeedback?.second) {
        if (seekFeedback != null) {
            delay(650)
            seekFeedback = null
        }
    }
    LaunchedEffect(channelSwitchFeedback?.second) {
        if (channelSwitchFeedback != null) {
            delay(1_200)
            channelSwitchFeedback = null
        }
    }
    fun switchChannel(forward: Boolean) {
        val current = channel ?: return
        val index = channelList.indexOfFirst { channelKey(it) == channelKey(current) }
        if (index < 0) return
        val next = channelList.getOrNull(if (forward) index + 1 else index - 1) ?: return
        onChannelChange(next)
        channelSwitchFeedback = next.name to System.nanoTime()
    }
    LaunchedEffect(controllerShownAt, controllerVisible) {
        if (controllerVisible) {
            delay(4_000)
            controllerVisible = false
        }
    }
    val player = remember(skipSeconds) {
        buildFourKPlusExoPlayer(context, skipSeconds, muted = settings.getBoolean("muted", false)).apply { playWhenReady = true }
    }

    LaunchedEffect(channel?.streamUrl, externalSubtitle) {
        playbackError = null
        if (channel == null) {
            player.clearMediaItems()
        } else {
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
    // once they're already hidden, should fall through to whatever exits fullscreen. Live TV's
    // hostedFullscreen has no controls at all (see PlaybackOptionsOverlay below) so Back there
    // always exits directly instead — LiveTvScreen owns that single-press BackHandler itself.
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
                                if (keyEvent.type != KeyEventType.KeyDown || channel == null || stripExpanded) return@onKeyEvent false
                                // Live TV's TV fullscreen has no controls overlay to gate on (see
                                // PlaybackOptionsOverlay below); elsewhere (touch/mobile) these keys
                                // only fire once the on-screen controls are hidden.
                                if (!hostedFullscreen && controllerVisible) return@onKeyEvent false
                                when (keyEvent.key) {
                                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                        // Live TV's TV fullscreen has no controls to bring up with
                                        // OK, so here OK instead exits back to the categories and
                                        // channel list - Back already does this, this just gives
                                        // OK the same result since it's the more natural button.
                                        if (hostedFullscreen) {
                                            if (onExitFullscreen != null) { onExitFullscreen(); true } else false
                                        } else { showControllerBriefly(); true }
                                    }
                                    Key.DirectionDown -> {
                                        if (channelList.size > 1) { stripExpanded = true; true } else false
                                    }
                                    Key.DirectionLeft -> {
                                        if (channelList.size > 1) { switchChannel(forward = false); true } else false
                                    }
                                    Key.DirectionRight -> {
                                        if (channelList.size > 1) { switchChannel(forward = true); true } else false
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
                            resizeMode = videoResizeMode
                            this.player = player
                        }
                    },
                    update = {
                        it.player = player
                        it.resizeMode = videoResizeMode
                        applyRequestedAspectRatio(it, videoMode)
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
                        .pointerInput(suggestionsEnabled) {
                            if (!suggestionsEnabled) return@pointerInput
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
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp),
                        color = Color.Black.copy(alpha = .68f),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            feedback.first,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                        )
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
                // Live TV's TV fullscreen has no on-screen controls at all — remote users get
                // there entirely via D-pad (left/right to change channel, down for the channel
                // strip), so there's nothing here to show or dismiss.
                if (!hostedFullscreen && controllerVisible && seekFeedback == null && !PictureInPictureCoordinator.active) PlaybackOptionsOverlay(
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
                    externalSubtitle = externalSubtitle,
                    onExternalSubtitleChange = { externalSubtitle = it },
                    skipSeconds = skipSeconds,
                    onSkipSecondsChange = {
                        skipSeconds = it
                        settings.edit().putInt("skip_seconds", it).apply()
                    },
                    videoMode = videoMode,
                    onVideoModeChange = {
                        videoMode = it
                        settings.edit().putString("video_mode", it).apply()
                    }
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
                if (suggestionsEnabled && controllerVisible && !stripExpanded && channelList.size > 1 && !PictureInPictureCoordinator.active) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        "Swipe up for other channels",
                        tint = Color.White.copy(alpha = .6f),
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .then(if (fullscreen || hostedFullscreen) Modifier.navigationBarsPadding() else Modifier)
                            .padding(bottom = 8.dp)
                            .size(22.dp)
                    )
                }
                if (suggestionsEnabled && stripExpanded && channelList.size > 1 && !PictureInPictureCoordinator.active) {
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
        playerContent(modifier, RoundedCornerShape(18.dp))
    }
}

/** Only expose structured codes; exception messages may contain account URLs. */
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
