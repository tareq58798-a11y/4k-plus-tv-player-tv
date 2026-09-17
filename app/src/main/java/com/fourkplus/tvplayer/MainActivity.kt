package com.fourkplus.tvplayer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.fourkplus.tvplayer.ui.theme.*
import com.fourkplus.tvplayer.data.LoadedPlaylist
import com.fourkplus.tvplayer.data.MediaKind
import com.fourkplus.tvplayer.data.MovieDetailsInfo
import com.fourkplus.tvplayer.data.PlaylistItem
import com.fourkplus.tvplayer.data.PlaylistInput
import com.fourkplus.tvplayer.data.EpgNowNext
import com.fourkplus.tvplayer.data.EpgStore
import com.fourkplus.tvplayer.data.PlaylistKind
import com.fourkplus.tvplayer.viewmodel.PlaylistViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import coil.compose.AsyncImage

/** Bridges Compose's "is a video currently showing" state to the Activity's picture-in-picture
 *  callbacks, which live outside Compose. A player composable marks itself [eligible] while
 *  mounted and showing real playback (Live TV's immersive fullscreen, or the Movies/Series
 *  player); [MainActivity.onUserLeaveHint] reads that to decide whether leaving the app (Home,
 *  recents) should shrink into a floating window instead of just backgrounding. [active] is
 *  written back from [MainActivity.onPictureInPictureModeChanged] so those same composables can
 *  hide their own overlay chrome (buttons, headers) once the system is showing the floating
 *  window, leaving only the bare video visible — matching how YouTube's PiP looks. */
internal object PictureInPictureCoordinator {
    var eligible by mutableStateOf(false)
    var active by mutableStateOf(false)
    var aspectRatio by mutableFloatStateOf(16f / 9f)
}

/** Swallows the leftover events of a remote button press whose key-down already caused a screen
 *  change (entering/leaving Live TV fullscreen, say). Compose delivers the matching key-up to
 *  whatever holds focus *after* that change, which is a different widget than the one that handled
 *  the key-down — on TV that meant the OK press that closed fullscreen immediately re-activated the
 *  channel row it landed back on, so the menu appeared and vanished again. The handler that acts on
 *  the key-down calls [consumeRestOfPress]; [MainActivity.dispatchKeyEvent] then drops every
 *  further event of that same press before it reaches the new focus target. */
internal object RemoteInputGate {
    private var swallowedKeyCode: Int? = null
    private var armedAt = 0L

    fun consumeRestOfPress(keyCode: Int) {
        swallowedKeyCode = keyCode
        armedAt = android.os.SystemClock.uptimeMillis()
    }

    fun shouldSwallow(event: android.view.KeyEvent): Boolean {
        val code = swallowedKeyCode ?: return false
        // A key-up that never arrives (focus left the app mid-press) must not deafen the button
        // for the rest of the session.
        if (android.os.SystemClock.uptimeMillis() - armedAt > 1_500L) {
            swallowedKeyCode = null
            return false
        }
        if (event.keyCode != code) return false
        if (event.action == android.view.KeyEvent.ACTION_UP) swallowedKeyCode = null
        return true
    }
}

/** True only for the opening key-down of a physical press. A held remote button auto-repeats, and
 *  every repeat arrives as another key-down — acting on those turned one OK press into a burst of
 *  enter/exit fullscreen toggles. */
internal val androidx.compose.ui.input.key.KeyEvent.isInitialKeyDown: Boolean
    get() = type == KeyEventType.KeyDown && nativeKeyEvent.repeatCount == 0

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        setContent { App() }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (RemoteInputGate.shouldSwallow(event)) return true
        return super.dispatchKeyEvent(event)
    }

    // Called just before the app leaves the foreground for a user-initiated reason (Home,
    // recents, another app) — not on rotation, dialogs, or the system just backgrounding us.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!PictureInPictureCoordinator.eligible) return
        // On TV, Home means "leave the app" — shrinking into a floating window there would keep
        // the stream's audio playing over the launcher. The player instead stays alive and muted
        // in the background (see MutePlayerWhileBackgrounded), so returning resumes instantly.
        if (isTvDevice()) return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val ratio = PictureInPictureCoordinator.aspectRatio.coerceIn(0.42f, 2.39f)
        runCatching {
            enterPictureInPictureMode(
                android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational((ratio * 1000).toInt(), 1000))
                    .build()
            )
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PictureInPictureCoordinator.active = isInPictureInPictureMode
    }
}

private enum class Screen { LOADING, ACTIVATION, MANUAL, PLAYLISTS, HOME, LIVE_TV, MOVIES, SERIES, SETTINGS, SEARCH }
internal enum class ThemeChoice { SYSTEM, LIGHT, DARK }

/** A request to jump directly to a specific item in Live TV/Movies/Series, from Home's Continue
 *  Watching card (autoPlay = true) or from global search (autoPlay = false, opens details first). */
internal data class ResumeRequest(val itemKey: String, val episodeId: String? = null, val autoPlay: Boolean = false)

@Composable
private fun App() {
    var screen by remember { mutableStateOf(Screen.LOADING) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var resumeRequest by remember { mutableStateOf<ResumeRequest?>(null) }
    var themeChoice by remember { mutableStateOf(ThemeChoice.DARK) }
    val useDark = when (themeChoice) {
        ThemeChoice.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val landscapeApp = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    DisposableEffect(landscapeApp) {
        val activity = context as? Activity
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (landscapeApp) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (landscapeApp) controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    // The bars sit over the backdrop, so their icons follow the theme rather than staying light.
    val decorView = LocalView.current
    LaunchedEffect(useDark, decorView) {
        val window = (decorView.context as? Activity)?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, decorView).apply {
            isAppearanceLightStatusBars = !useDark
            isAppearanceLightNavigationBars = !useDark
        }
    }
    val appPreferences = remember { context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE) }
    val currentLanguage = remember { LocaleHelper.getLanguage(context) }
    val onLanguageChange: (AppLanguage) -> Unit = {
        LocaleHelper.setLanguage(context, it)
        (context as? Activity)?.recreate()
    }
    val parentalPrefs = remember { context.getSharedPreferences("parental_settings", android.content.Context.MODE_PRIVATE) }
    var parentalEnabled by remember { mutableStateOf(parentalPrefs.getBoolean("parental_enabled", false)) }
    var askPinOnStartup by remember { mutableStateOf(parentalPrefs.getBoolean("ask_pin_on_startup", false)) }
    var pinHash by remember { mutableStateOf(parentalPrefs.getString("pin_hash", null)) }
    // Entering the PIN once unlocks everything (startup gate, locked categories, locked
    // channels) for the rest of this process's lifetime — resets on the next cold launch.
    var parentalUnlockedThisSession by remember { mutableStateOf(false) }
    var pendingPinAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val requirePin: (() -> Unit) -> Unit = { action ->
        if (!parentalEnabled || pinHash == null || parentalUnlockedThisSession) action()
        else pendingPinAction = action
    }
    val startupGateActive = parentalEnabled && askPinOnStartup && pinHash != null && !parentalUnlockedThisSession
    var showRotateHint by remember { mutableStateOf(false) }
    val playlistViewModel: PlaylistViewModel = viewModel(factory = PlaylistViewModel.Factory(context.applicationContext))
    val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()
    val message: (String) -> Unit = { scope.launch { snackbar.showSnackbar(it) } }

    // Home has no "back" destination of its own — every other screen's own BackHandler
    // navigates back to it. Pressing back here would otherwise fall through to the system
    // default (exit the app) with no confirmation, unlike the Home screen of a launcher-adjacent
    // app users expect a prompt from.
    BackHandler(enabled = screen == Screen.HOME) { showExitConfirm = true }
    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text(stringResource(R.string.exit_app_title)) },
            text = { Text(stringResource(R.string.exit_app_message)) },
            confirmButton = {
                TextButton(onClick = { (context as? Activity)?.finish() }) {
                    Text(stringResource(R.string.exit_app_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    LaunchedEffect(Unit) {
        themeChoice = runCatching {
            ThemeChoice.valueOf(appPreferences.getString("theme", ThemeChoice.DARK.name).orEmpty())
        }.getOrDefault(ThemeChoice.DARK)
    }

    // Meaningless on TV: there's no touch screen to rotate and the device is permanently
    // landscape, so a hint about portrait/landscape differences would just be confusing.
    val isTvApp = remember { context.isTvDevice() }
    LaunchedEffect(screen, playlistUiState.bootstrapping) {
        if (!isTvApp && screen == Screen.HOME && !playlistUiState.bootstrapping &&
            !appPreferences.getBoolean("rotate_hint_seen", false)
        ) {
            delay(700)
            showRotateHint = true
        }
    }

    // Drives the initial LOADING -> ACTIVATION | HOME transition once the ViewModel's bootstrap
    // (restoring the saved playlist, if any) finishes.
    LaunchedEffect(playlistUiState.bootstrapping, playlistUiState.activeSource, playlistUiState.bootstrapFailed) {
        if (playlistUiState.bootstrapping || screen != Screen.LOADING) return@LaunchedEffect
        when {
            playlistUiState.bootstrapFailed -> {
                message("Saved playlist could not be refreshed. Please reconnect.")
                screen = Screen.ACTIVATION
            }
            playlistUiState.activeSource == null -> screen = Screen.ACTIVATION
            else -> screen = Screen.HOME
        }
    }

    FourKPlusTheme(darkTheme = useDark) {
        // The backdrop sits behind the Scaffold rather than inside it: Scaffold insets its content
        // past the status and navigation bars, so a backdrop drawn in there leaves those strips
        // showing the flat container colour instead of the artwork.
        Box(
            Modifier.fillMaxSize().background(if (useDark) Color(0xFF04070F) else Color(0xFFEFF6FF))
        ) {
        Image(
            painter = painterResource(
                when {
                    useDark && landscapeApp -> R.drawable.bg_aurora_dark_land
                    useDark -> R.drawable.bg_aurora_dark
                    landscapeApp -> R.drawable.bg_sky_light_land
                    else -> R.drawable.bg_sky_light
                }
            ),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // Landscape uses its own purpose-composed artwork (already framed with the glow in the
            // top-right corner), so it only needs top-end anchoring to keep that corner in frame on
            // screens wider than the image; portrait's taller artwork stays top-centered.
            alignment = if (landscapeApp) Alignment.TopEnd else Alignment.TopCenter,
            modifier = Modifier.fillMaxSize()
        )
        if (!useDark) SunRays(Modifier.fillMaxSize())
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = Color.Transparent,
            // Transparent has no mapping in the colour scheme, so Scaffold would otherwise hand
            // the content an unspecified colour and every default-coloured Text would go black.
            contentColor = MaterialTheme.colorScheme.onBackground,
            contentWindowInsets = if (landscapeApp) WindowInsets(0, 0, 0, 0) else WindowInsets.safeDrawing,
            modifier = Modifier.fillMaxSize()
        ) { scaffoldPadding ->
            Box(Modifier.fillMaxSize().padding(scaffoldPadding)) {
            when (screen) {
                Screen.LOADING -> PremiumBackground {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        CircularProgressIndicator(color = Cyan)
                        Text(stringResource(R.string.loading_your_playlist), fontWeight = FontWeight.SemiBold)
                        // Distinguishes "reading the saved copy" (should be quick) from "this
                        // restart has no saved copy and is genuinely re-fetching over the
                        // network" (only as fast as the provider responds) - both show the same
                        // spinner otherwise, so a slow startup is otherwise impossible to tell
                        // apart from a healthy cache read just taking its normal course.
                        if (playlistUiState.bootstrappingFromNetwork) {
                            Text(
                                stringResource(R.string.loading_playlist_from_network),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Screen.ACTIVATION -> ActivationScreen(
                    themeChoice = themeChoice,
                    onThemeChange = {
                        themeChoice = it
                        appPreferences.edit().putString("theme", it.name).apply()
                    },
                    onManual = { screen = Screen.MANUAL },
                    onMessage = message,
                    loadPlaylist = playlistViewModel::addPlaylist,
                    onConnected = {
                        screen = Screen.HOME
                        message("${it.items.size} items loaded")
                    }
                )
                Screen.MANUAL -> ManualPlaylistScreen(
                    onBack = { screen = if (playlistUiState.savedPlaylists.isEmpty()) Screen.ACTIVATION else Screen.PLAYLISTS },
                    loadPlaylist = playlistViewModel::addPlaylist,
                    onConnected = {
                        screen = Screen.HOME
                        message("${it.items.size} items loaded")
                    }
                )
                Screen.PLAYLISTS -> PlaylistManagerScreen(
                    sources = playlistUiState.savedPlaylists,
                    activeSource = playlistUiState.activeSource,
                    onBack = { screen = Screen.HOME },
                    onAdd = { screen = Screen.MANUAL },
                    onSelect = { source ->
                        scope.launch {
                            playlistViewModel.switchTo(source)
                                .onSuccess {
                                    screen = Screen.HOME
                                    message("${source.name} selected")
                                }
                                .onFailure { message(it.message ?: "Playlist could not be loaded") }
                        }
                    },
                    onRemove = { source ->
                        scope.launch {
                            playlistViewModel.removeSource(source)
                                .onSuccess {
                                    screen = if (playlistViewModel.uiState.value.activeSource == null) Screen.ACTIVATION else Screen.HOME
                                    message("Playlist removed")
                                }
                                .onFailure { message(it.message ?: "Playlist could not be removed") }
                        }
                    }
                )
                Screen.HOME -> HomeScreen(
                    playlist = playlistUiState.loadedPlaylist,
                    isDark = useDark,
                    currentLanguage = currentLanguage,
                    onLanguageChange = onLanguageChange,
                    onToggleTheme = {
                        // Commits to an explicit choice rather than leaving it on SYSTEM, so the
                        // tap sticks even when the device theme says otherwise.
                        val next = if (useDark) ThemeChoice.LIGHT else ThemeChoice.DARK
                        themeChoice = next
                        appPreferences.edit().putString("theme", next.name).apply()
                    },
                    onManage = { screen = Screen.SETTINGS },
                    onPlaylists = { screen = Screen.PLAYLISTS },
                    onOpenLive = { screen = Screen.LIVE_TV },
                    onOpenMovies = { screen = Screen.MOVIES },
                    onOpenSeries = { screen = Screen.SERIES },
                    onSearch = { screen = Screen.SEARCH },
                    onContinueWatching = { item, episodeId ->
                        resumeRequest = ResumeRequest(channelKey(item), episodeId, autoPlay = true)
                        screen = when (item.kind) {
                            MediaKind.LIVE -> Screen.LIVE_TV
                            MediaKind.MOVIE -> Screen.MOVIES
                            MediaKind.SERIES -> Screen.SERIES
                        }
                    },
                    onMessage = message
                )
                Screen.SEARCH -> GlobalSearchScreen(
                    playlist = playlistUiState.loadedPlaylist,
                    onBack = { screen = Screen.HOME },
                    onSelect = { item ->
                        resumeRequest = ResumeRequest(channelKey(item), autoPlay = false)
                        screen = when (item.kind) {
                            MediaKind.LIVE -> Screen.LIVE_TV
                            MediaKind.MOVIE -> Screen.MOVIES
                            MediaKind.SERIES -> Screen.SERIES
                        }
                    }
                )
                Screen.LIVE_TV -> LiveTvScreen(
                    playlist = playlistUiState.loadedPlaylist,
                    onBack = { screen = Screen.HOME },
                    onMessage = message,
                    loadEpg = playlistViewModel::shortEpg,
                    requirePin = requirePin,
                    resumeRequest = resumeRequest,
                    onResumeHandled = { resumeRequest = null }
                )
                Screen.MOVIES -> MoviesScreen(
                    playlist = playlistUiState.loadedPlaylist,
                    loadDetails = playlistViewModel::movieDetails,
                    onBack = { screen = Screen.HOME },
                    requirePin = requirePin,
                    resumeRequest = resumeRequest,
                    onResumeHandled = { resumeRequest = null }
                )
                Screen.SERIES -> SeriesScreen(
                    playlist = playlistUiState.loadedPlaylist,
                    loadDetails = playlistViewModel::seriesDetails,
                    source = playlistUiState.activeSource,
                    onBack = { screen = Screen.HOME },
                    requirePin = requirePin,
                    resumeRequest = resumeRequest,
                    onResumeHandled = { resumeRequest = null }
                )
                Screen.SETTINGS -> SettingsScreen(
                    playlist = playlistUiState.loadedPlaylist,
                    source = playlistUiState.activeSource,
                    themeChoice = themeChoice,
                    onThemeChange = {
                        themeChoice = it
                        appPreferences.edit().putString("theme", it.name).apply()
                    },
                    currentLanguage = currentLanguage,
                    onLanguageChange = onLanguageChange,
                    parentalEnabled = parentalEnabled,
                    onParentalEnabledChange = {
                        parentalEnabled = it
                        parentalPrefs.edit().putBoolean("parental_enabled", it).apply()
                    },
                    askPinOnStartup = askPinOnStartup,
                    onAskPinOnStartupChange = {
                        askPinOnStartup = it
                        parentalPrefs.edit().putBoolean("ask_pin_on_startup", it).apply()
                    },
                    pinHash = pinHash,
                    onPinHashChange = { pinHash = it },
                    requirePin = requirePin,
                    onBack = { screen = Screen.HOME },
                    onRefresh = {
                        scope.launch {
                            playlistViewModel.refreshActive()
                                .onSuccess { message("Playlist refreshed") }
                                .onFailure { message(it.message ?: "Playlist refresh failed") }
                        }
                    },
                    onRename = { name ->
                        scope.launch {
                            playlistViewModel.renameActive(name)
                                .onSuccess { message("Playlist renamed") }
                                .onFailure { message(it.message ?: "Playlist could not be renamed") }
                        }
                    },
                    onManagePlaylists = { screen = Screen.PLAYLISTS },
                    onReplace = { screen = Screen.MANUAL },
                    onRemove = {
                        val active = playlistUiState.activeSource
                        if (active == null) {
                            message("No saved playlist to remove")
                        } else {
                            scope.launch {
                                playlistViewModel.removeSource(active)
                                    .onSuccess {
                                        screen = if (playlistViewModel.uiState.value.activeSource == null) Screen.ACTIVATION else Screen.HOME
                                        message("Playlist removed")
                                    }
                                    .onFailure { message(it.message ?: "Playlist could not be removed") }
                            }
                        }
                    },
                    onMessage = message
                )
            }
            pendingPinAction?.let { action ->
                PinDialog(
                    mode = "unlock",
                    expectedHash = pinHash,
                    onDismiss = { pendingPinAction = null },
                    onSuccess = {
                        parentalUnlockedThisSession = true
                        pendingPinAction = null
                        action()
                    }
                )
            }
            if (screen in listOf(Screen.HOME, Screen.MOVIES, Screen.SERIES) &&
                (playlistUiState.loadingCatalogues || playlistUiState.catalogueLoadFailed)) {
                Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp), shape = RoundedCornerShape(12.dp)) {
                    Text(
                        stringResource(if (playlistUiState.loadingCatalogues) R.string.catalogues_loading else R.string.catalogues_load_failed),
                        modifier = Modifier.padding(12.dp), fontSize = 13.sp
                    )
                }
            }
            if (startupGateActive && screen != Screen.LOADING) {
                PinDialog(
                    mode = "unlock",
                    expectedHash = pinHash,
                    dismissible = false,
                    onDismiss = {},
                    onSuccess = { parentalUnlockedThisSession = true }
                )
            }
            if (showRotateHint) {
                RotateExperienceHint(
                    onDismiss = {
                        appPreferences.edit().putBoolean("rotate_hint_seen", true).apply()
                        showRotateHint = false
                    }
                )
            }
            }
        }
        }
    }
}

/** The brand logo, white-keyed to transparency so it sits directly on the app background. The
 *  supplied artwork is drawn for white backgrounds — its near-black "4K"/"PLUS" ink would vanish
 *  on the dark theme — so dark mode uses a variant with only that ink lifted to a soft white. */
@Composable
private fun BrandMark(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    Box(modifier) {
        Image(
            painter = painterResource(if (dark) R.drawable.brand_logo_dark else R.drawable.brand_logo),
            contentDescription = "4K Plus TV",
            contentScale = ContentScale.Fit,
            // Taller than the glyphs look: both assets carry a transparent margin for the bloom.
            modifier = Modifier.height(58.dp)
        )
    }
}

private class SunShaft(val degrees: Float, val spread: Float, val alpha: Float, val tint: Color)

/** Light shafts fanning out of the warm corner already present in the light backdrop artwork.
 *  Each carries its own pale tint so the fan reads as refracted light rather than a flat wash, and
 *  each is drawn as three nested widths — a stand-in for a blur pass, which Compose only offers
 *  from API 31 while this app targets 26 — so the beam falls off softly instead of showing an edge. */
@Composable
private fun SunRays(modifier: Modifier) {
    Canvas(modifier) {
        val origin = Offset(size.width * .94f, -size.height * .03f)
        // Off the longer edge, not the height: in landscape a height-scaled shaft dies out long
        // before it crosses the screen, leaving the fan stranded in the corner.
        val length = kotlin.math.max(size.width, size.height) * 1.25f
        val shafts = listOf(
            SunShaft(103f, 4.0f, .10f, Color(0xFFFFE6B8)),
            SunShaft(114f, 2.6f, .065f, Color(0xFFFFD9C4)),
            SunShaft(126f, 5.0f, .085f, Color(0xFFFFD6DE)),
            SunShaft(138f, 3.0f, .055f, Color(0xFFE6DDF8)),
            SunShaft(149f, 4.4f, .075f, Color(0xFFD2E9F8)),
            SunShaft(160f, 2.8f, .05f, Color(0xFFD8F0E6))
        )
        val layers = listOf(2.4f to .28f, 1.5f to .5f, 1f to 1f)
        shafts.forEach { shaft ->
            val axis = Math.toRadians(shaft.degrees.toDouble())
            val tip = Offset(
                origin.x + (kotlin.math.cos(axis) * length).toFloat(),
                origin.y + (kotlin.math.sin(axis) * length).toFloat()
            )
            layers.forEach { (widthScale, alphaScale) ->
                val spread = shaft.spread * widthScale
                val alpha = shaft.alpha * alphaScale
                val edgeA = Math.toRadians((shaft.degrees - spread).toDouble())
                val edgeB = Math.toRadians((shaft.degrees + spread).toDouble())
                val path = Path().apply {
                    moveTo(origin.x, origin.y)
                    lineTo(origin.x + (kotlin.math.cos(edgeA) * length).toFloat(), origin.y + (kotlin.math.sin(edgeA) * length).toFloat())
                    lineTo(origin.x + (kotlin.math.cos(edgeB) * length).toFloat(), origin.y + (kotlin.math.sin(edgeB) * length).toFloat())
                    close()
                }
                drawPath(
                    path,
                    Brush.linearGradient(
                        0f to shaft.tint.copy(alpha = alpha),
                        .35f to shaft.tint.copy(alpha = alpha * .55f),
                        .75f to shaft.tint.copy(alpha = alpha * .15f),
                        1f to Color.Transparent,
                        start = origin,
                        end = tip
                    )
                )
            }
        }
    }
}

@Composable
private fun RotateExperienceHint(onDismiss: () -> Unit) {
    var animateRotation by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(260)
        animateRotation = true
    }
    val rotation by animateFloatAsState(
        targetValue = if (animateRotation) 90f else 0f,
        animationSpec = tween(760),
        label = "rotate_hint"
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.padding(28.dp).then(pressFeedback(onDismiss)),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
            shadowElevation = 18.dp
        ) {
            Column(
                Modifier.padding(horizontal = 26.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    Icons.Default.ScreenRotation,
                    contentDescription = null,
                    tint = Cyan,
                    modifier = Modifier.size(44.dp).graphicsLayer { rotationZ = rotation }
                )
                Text("Two ways to enjoy 4K Plus TV", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(
                    "Browse in portrait. Rotate your phone for the wide-screen viewing experience.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
                Text("Tap to continue", color = Cyan, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** The themed backdrop is painted once, full-bleed, behind the Scaffold in [App]. This stays as the
 *  screens' BoxScope container (they position content with `align`) and deliberately paints nothing:
 *  drawing the artwork again here would show a second, differently-cropped copy. */
@Composable
private fun PremiumBackground(content: @Composable BoxScope.() -> Unit) {
    val light = MaterialTheme.colorScheme.background.red > .7f
    // No background() here: the aurora/sky artwork is already painted full-bleed behind the
    // Scaffold in App(). This used to also paint an opaque gradient of its own, which fully
    // hid that artwork behind every screen that uses this wrapper (i.e. nearly all of them) —
    // only the two glow blobs below are this composable's own contribution.
    Box(Modifier.fillMaxSize()) {
        // Light mode: controlled sunlight; dark mode: a quiet aurora—both stay behind content.
        Box(
            Modifier.size(360.dp).align(Alignment.TopEnd)
                .background(
                    Brush.radialGradient(
                        listOf(
                            if (light) Color(0xFFFFD887).copy(alpha = .26f) else Cyan.copy(alpha = .18f),
                            Color.Transparent
                        )
                    ),
                    RoundedCornerShape(180.dp)
                )
        )
        Box(
            Modifier.size(300.dp).align(Alignment.BottomStart)
                .background(
                    Brush.radialGradient(
                        listOf(
                            if (light) Color(0xFF7AD7FF).copy(alpha = .18f) else Color(0xFF9B7CFF).copy(alpha = .13f),
                            Color.Transparent
                        )
                    ),
                    RoundedCornerShape(150.dp)
                )
        )
        content()
    }
}

@Composable
private fun ActivationScreen(
    themeChoice: ThemeChoice,
    onThemeChange: (ThemeChoice) -> Unit,
    onManual: () -> Unit,
    onMessage: (String) -> Unit,
    loadPlaylist: suspend (PlaylistInput) -> Result<LoadedPlaylist>,
    onConnected: (LoadedPlaylist) -> Unit
) {
    val languageComingSoon = stringResource(R.string.language_selection_coming_soon)
    PremiumBackground {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val sidePadding = if (landscape) 34.dp else 20.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = sidePadding, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BrandMark(Modifier.weight(1f))
                ThemeMenu(themeChoice, onThemeChange)
                AnimatedIconButton(onClick = { onMessage(languageComingSoon) }) { Icon(Icons.Default.Language, stringResource(R.string.cd_language)) }
            }

            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(stringResource(R.string.welcome), fontSize = 34.sp, fontWeight = FontWeight.Black)
                Text(stringResource(R.string.activation_hero), color = Cyan, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.activation_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (landscape) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    RemoteActivationCard(Modifier.weight(1.15f), onMessage, loadPlaylist, onConnected)
                    ManualEntryCard(Modifier.weight(.85f), onManual)
                }
            } else {
                RemoteActivationCard(Modifier.fillMaxWidth(), onMessage, loadPlaylist, onConnected)
                ManualEntryCard(Modifier.fillMaxWidth(), onManual)
            }

            Text(
                stringResource(R.string.not_a_media_provider),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    }
}

@Composable
internal fun themeChoiceLabel(choice: ThemeChoice): String = when (choice) {
    ThemeChoice.SYSTEM -> stringResource(R.string.theme_system)
    ThemeChoice.LIGHT -> stringResource(R.string.theme_light)
    ThemeChoice.DARK -> stringResource(R.string.theme_dark)
}

@Composable
internal fun languageLabel(language: AppLanguage): String =
    if (language == AppLanguage.SYSTEM) stringResource(R.string.language_system_default) else language.nativeName

@Composable
internal fun LanguageDialog(current: AppLanguage, onSelect: (AppLanguage) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Language, null) },
        title = { Text(stringResource(R.string.cd_language)) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())
            ) {
                AppLanguage.entries.forEach { language ->
                    Surface(onClick = { onSelect(language) }, color = Color.Transparent) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = current == language, onClick = { onSelect(language) })
                            Text(languageLabel(language))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun ThemeMenu(choice: ThemeChoice, onChange: (ThemeChoice) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AnimatedIconButton(onClick = { open = true }) { Icon(Icons.Default.Contrast, stringResource(R.string.cd_appearance)) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            ThemeChoice.entries.forEach {
                DropdownMenuItem(
                    text = { Text(themeChoiceLabel(it)) },
                    leadingIcon = { if (choice == it) Icon(Icons.Default.Check, null) },
                    onClick = { onChange(it); open = false }
                )
            }
        }
    }
}

@Composable
private fun RemoteActivationCard(
    modifier: Modifier,
    onMessage: (String) -> Unit,
    loadPlaylist: suspend (PlaylistInput) -> Result<LoadedPlaylist>,
    onConnected: (LoadedPlaylist) -> Unit
) {
    val context = LocalContext.current
    var refreshing by remember { mutableStateOf(false) }
    val mac = remember { com.fourkplus.tvplayer.data.DeviceIdentity.mac(context) }
    val deviceKey = remember { com.fourkplus.tvplayer.data.DeviceIdentity.deviceKey(context) }
    val activatedPlaylistName = stringResource(R.string.activated_playlist_default_name)
    val noPlaylistAssignedYet = stringResource(R.string.no_playlist_assigned_yet)
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshSignal by remember { mutableIntStateOf(0) }
    var assigned by remember { mutableStateOf(false) }
    val currentLoad by rememberUpdatedState(loadPlaylist)
    val currentConnected by rememberUpdatedState(onConnected)
    val currentMessage by rememberUpdatedState(onMessage)
    LaunchedEffect(mac, deviceKey, lifecycleOwner, refreshSignal) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            var failures = 0
            while (!assigned) {
                refreshing = true
                val result = try {
                    currentLoad(PlaylistInput(activatedPlaylistName, PlaylistKind.DEVICE_ACTIVATION, "", mac, deviceKey))
                } finally { refreshing = false }
                if (result.isSuccess) {
                    assigned = true
                    currentConnected(result.getOrThrow())
                    break
                }
                val error = result.exceptionOrNull()
                if (error is com.fourkplus.tvplayer.data.ActivationPendingException) {
                    failures = 0
                } else {
                    failures++
                    if (failures == 1) currentMessage(error?.message ?: noPlaylistAssignedYet)
                }
                delay(if (failures == 0) 5_000L else (5_000L * failures).coerceAtMost(30_000L))
            }
        }
    }
    ElevatedCard(
        modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .96f)),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 10.dp)
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccentIcon(Icons.Default.Devices, Cyan)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.activate_via_app), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.recommended), color = Cyan, style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(stringResource(R.string.activation_instructions))
            DeviceCode(stringResource(R.string.device_id), mac, onMessage)
            DeviceCode(stringResource(R.string.device_key), deviceKey, onMessage)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = Orange.copy(alpha = .14f),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, Orange.copy(alpha = .28f))
                ) {
                    Row(Modifier.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = Orange)
                        Spacer(Modifier.width(7.dp))
                        Text(stringResource(R.string.waiting_for_activation), style = MaterialTheme.typography.labelMedium, color = Orange)
                    }
                }
                Spacer(Modifier.weight(1f))
                AnimatedFilledIconButton(
                    enabled = !refreshing,
                    onClick = { refreshSignal++ }
                ) {
                    val rotation by animateFloatAsState(if (refreshing) 360f else 0f, tween(650), label = "refresh")
                    Icon(Icons.Default.Refresh, stringResource(R.string.cd_refresh_activation), Modifier.graphicsLayer(rotationZ = rotation))
                }
            }
        }
    }
}

@Composable
private fun AccentIcon(icon: ImageVector, color: Color) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(color.copy(alpha = .28f), color.copy(alpha = .08f)))),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(25.dp))
    }
}

@Composable
private fun DeviceCode(label: String, value: String, onMessage: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.primary.copy(alpha = .12f))
                )
            ).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        AnimatedIconButton(onClick = {
            clipboard.setText(AnnotatedString(value))
            onMessage("$label copied")
        }) { Icon(Icons.Default.ContentCopy, "Copy $label") }
    }
}

@Composable
private fun ManualEntryCard(modifier: Modifier, onManual: () -> Unit) {
    val interactive = pressFeedback(onManual)
    ElevatedCard(
        modifier.then(interactive),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .96f)),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp)
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AccentIcon(Icons.Default.PlaylistAdd, Orange)
            Text(stringResource(R.string.add_playlist_manually), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.add_playlist_manually_desc))
            Button(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.add_playlist)) }
        }
    }
}

@Composable
private fun ManualPlaylistScreen(
    onBack: () -> Unit,
    loadPlaylist: suspend (PlaylistInput) -> Result<LoadedPlaylist>,
    onConnected: (LoadedPlaylist) -> Unit
) {
    BackHandler(onBack = onBack)
    var serverIndex by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = if (landscape) 34.dp else 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        val playlistCouldNotBeLoaded = stringResource(R.string.playlist_could_not_be_loaded)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedIconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.cd_back)) }
            Text(stringResource(R.string.add_playlist_title), fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedTextField(name, { name = it }, enabled = !loading, label = { Text(stringResource(R.string.playlist_name_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.choose_your_server), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            com.fourkplus.tvplayer.data.ApprovedServers.addresses.forEachIndexed { index, _ ->
                FilterChip(
                    selected = serverIndex == index,
                    onClick = { serverIndex = index; error = null },
                    enabled = !loading,
                    label = { Text(stringResource(R.string.server_index_label, index + 1)) }
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.username_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            RevealablePasswordField(
                value = password,
                onValueChange = { password = it },
                label = stringResource(R.string.password_label),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Text(stringResource(R.string.credentials_stored_securely), style = MaterialTheme.typography.bodySmall)
        AnimatedVisibility(error != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ErrorOutline, null)
                    Spacer(Modifier.width(10.dp))
                    Text(error.orEmpty(), modifier = Modifier.weight(1f))
                }
            }
        }
        AnimatedVisibility(loading) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.connecting_playlist), style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(
            onClick = {
                loading = true
                error = null
                val input = PlaylistInput(
                    name = name,
                    kind = PlaylistKind.PROVIDER_LOGIN,
                    address = com.fourkplus.tvplayer.data.ApprovedServers.addresses[serverIndex],
                    username = username,
                    password = password
                )
                scope.launch {
                    loadPlaylist(input)
                        .onSuccess(onConnected)
                        .onFailure { error = it.message ?: playlistCouldNotBeLoaded }
                    loading = false
                }
            },
            enabled = !loading && name.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            else Text(stringResource(R.string.test_and_add_playlist))
        }
        }
    }
}

@Composable
private fun PlaylistManagerScreen(
    sources: List<PlaylistInput>,
    activeSource: PlaylistInput?,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onSelect: (PlaylistInput) -> Unit,
    onRemove: (PlaylistInput) -> Unit
) {
    BackHandler(onBack = onBack)
    var removing by remember { mutableStateOf<PlaylistInput?>(null) }
    removing?.let { source ->
        AlertDialog(
            onDismissRequest = { removing = null },
            icon = { Icon(Icons.Default.DeleteForever, null) },
            title = { Text(stringResource(R.string.remove_playlist_confirm_title, source.name)) },
            text = { Text(stringResource(R.string.remove_playlist_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { removing = null; onRemove(source) }) {
                    Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
    PremiumBackground {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedIconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.cd_back)) }
                Text(stringResource(R.string.playlists_title), Modifier.weight(1f), fontSize = 27.sp, fontWeight = FontWeight.Black)
            }
            Text(
                stringResource(R.string.playlists_manager_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(sources) { source ->
                    val active = activeSource?.let {
                        source.kind == it.kind &&
                            source.address == it.address &&
                            source.username == it.username
                    } == true
                    ElevatedCard(
                        onClick = { if (!active) onSelect(source) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (active) Cyan.copy(alpha = .14f) else MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (source.kind == PlaylistKind.PROVIDER_LOGIN) Icons.Default.AccountCircle else Icons.Default.Link,
                                null,
                                tint = if (active) Cyan else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(30.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(source.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text(
                                    if (active) stringResource(R.string.active_playlist_status)
                                    else if (source.username.isNotBlank()) stringResource(R.string.provider_login_status, source.username)
                                    else stringResource(R.string.m3u_playlist_label),
                                    color = if (active) Cyan else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (!active) {
                                TextButton(onClick = { onSelect(source) }) { Text(stringResource(R.string.action_switch)) }
                            }
                            AnimatedIconButton(onClick = { removing = source }) {
                                Icon(Icons.Default.DeleteOutline, stringResource(R.string.remove_playlist_action), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Default.AddCircleOutline, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.add_another_playlist))
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    playlist: LoadedPlaylist?,
    isDark: Boolean,
    currentLanguage: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    onToggleTheme: () -> Unit,
    onManage: () -> Unit,
    onPlaylists: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenMovies: () -> Unit,
    onOpenSeries: () -> Unit,
    onSearch: () -> Unit,
    onContinueWatching: (PlaylistItem, String?) -> Unit,
    onMessage: (String) -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val context = LocalContext.current
    // A remote user needs an obvious starting point the instant Home appears — there's no cursor
    // or touch to fall back on. Skipped entirely on phones/tablets, touch or landscape, where an
    // unexplained focus ring on launch would just look like a bug.
    val isTv = remember { context.isTvDevice() }
    val liveTileFocusRequester = remember { FocusRequester() }
    LaunchedEffect(isTv) { if (isTv) runCatching { liveTileFocusRequester.requestFocus() } }
    val continueEntry = remember(playlist) { com.fourkplus.tvplayer.data.ContinueWatchingStore.read(context) }
    val continueItem = remember(playlist, continueEntry) {
        continueEntry?.let { entry -> playlist?.items?.firstOrNull { channelKey(it) == entry.itemKey } }
    }
    val greetingHour = remember { java.time.LocalTime.now().hour }
    val greeting = when (greetingHour) {
        in 5..11 -> stringResource(R.string.home_greeting_morning)
        in 12..16 -> stringResource(R.string.home_greeting_afternoon)
        else -> stringResource(R.string.home_greeting_evening)
    }
    val liveChannels = remember(playlist) { playlist?.items?.filter { it.kind == MediaKind.LIVE }.orEmpty() }
    // Same store LiveTvScreen writes to via rememberChannel(), so Home reflects real watch history.
    val liveHistoryStore = remember { context.getSharedPreferences("favorite_channels", android.content.Context.MODE_PRIVATE) }
    val recentLiveIds = remember(liveHistoryStore) {
        liveHistoryStore.getString("recent_ids_v3", "").orEmpty().split('').filter(String::isNotBlank)
    }
    val recentlyWatchedLive = remember(liveChannels, recentLiveIds) {
        recentLiveIds.mapNotNull { id -> liveChannels.firstOrNull { channelKey(it) == id } }
    }
    val featured = remember(recentlyWatchedLive) { recentlyWatchedLive.take(12) }
    // Duplicate logo URLs (common with low-quality playlists) collapse to a generated placeholder
    // per card below, so recently watched channels never look like copies of each other.
    val featuredPreviews = remember(featured) {
        val seen = mutableSetOf<String>()
        featured.map { channel -> channel to channel.logoUrl?.takeIf { it.isNotBlank() && seen.add(it) } }
    }
    PremiumBackground {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val sidePadding = if (landscape) 34.dp else 20.dp
        val noFavoritesYet = stringResource(R.string.home_no_favorites)
        val noViewingHistoryYet = stringResource(R.string.home_no_history)
        val nothingToContinueYet = stringResource(R.string.home_nothing_to_continue)
        var showLanguageDialog by remember { mutableStateOf(false) }
        if (showLanguageDialog) {
            LanguageDialog(
                current = currentLanguage,
                onSelect = { showLanguageDialog = false; onLanguageChange(it) },
                onDismiss = { showLanguageDialog = false }
            )
        }
        val header: @Composable RowScope.() -> Unit = {
            BrandMark(Modifier.weight(1f))
            AnimatedIconButton(onClick = onSearch) { Icon(Icons.Default.Search, stringResource(R.string.cd_search)) }
            AnimatedIconButton(onClick = { showLanguageDialog = true }) { Icon(Icons.Default.Language, stringResource(R.string.cd_language)) }
            AnimatedIconButton(onClick = onToggleTheme) {
                Icon(
                    if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                    if (isDark) stringResource(R.string.home_switch_to_light) else stringResource(R.string.home_switch_to_dark)
                )
            }
            AnimatedIconButton(onClick = onManage) { Icon(Icons.Default.Settings, stringResource(R.string.cd_settings)) }
        }
        val quickAccess: @Composable RowScope.() -> Unit = {
            QuickPill(stringResource(R.string.home_favorites_chip), Icons.Default.Favorite, true) { onMessage(noFavoritesYet) }
            QuickPill(stringResource(R.string.home_recently_watched_chip), Icons.Default.History, false) { onMessage(noViewingHistoryYet) }
            QuickPill(stringResource(R.string.home_playlists_chip), Icons.Default.Download, false, onPlaylists)
        }
        val liveShelfHeader: @Composable RowScope.() -> Unit = {
            Text(stringResource(R.string.home_recently_watched_live), Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.action_see_all),
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpenLive).padding(horizontal = 6.dp, vertical = 4.dp),
                // Cyan reads well on the dark ground but washes out on the light one.
                color = if (isDark) Cyan else BrandBlue,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (landscape) {
            // Landscape is only ~410dp tall, so the portrait stack cannot fit: split into a hero
            // column and a browse column and let both fill the height instead of scrolling.
            Column(
                Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = header)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AutoSizeText(greeting, Modifier.weight(1f), maxFontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.6).sp)
                    HomeDateTime(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // Both columns' first child is now the tile row / Continue Watching card, with
                    // no per-column heading above either one, so their top edges line up.
                    Column(Modifier.weight(1.05f).fillMaxHeight()) {
                        ContinueCard(
                            item = continueItem,
                            modifier = Modifier.fillMaxWidth().fillMaxHeight()
                        ) {
                            if (continueItem != null) onContinueWatching(continueItem, continueEntry?.episodeId)
                            else onMessage(nothingToContinueYet)
                        }
                    }
                    // Tiles and the shelf are fixed-size now (bigger than before, per the approved
                    // sketch) rather than stretch-to-fill, since a weighted tile row scaled down
                    // to almost nothing on real TV panels once the shelf/device-info claimed their
                    // fixed share of a shorter landscape height than the emulator's. verticalScroll
                    // is a safety net for devices where the fixed sizes below don't quite fit
                    // instead of clipping the device-info box off the bottom.
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().height(140.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            HomeTile(stringResource(R.string.nav_live_tv), playlist?.let { stringResource(R.string.home_live_tv_count, it.liveCount) } ?: stringResource(R.string.home_live_tv_default), Icons.Default.LiveTv, TileKind.LIVE, isDark, Modifier.weight(1f).fillMaxHeight().focusRequester(liveTileFocusRequester), height = null, onClick = onOpenLive)
                            HomeTile(stringResource(R.string.nav_movies), playlist?.let { stringResource(R.string.home_movies_count, it.movieCount) } ?: stringResource(R.string.home_movies_default), Icons.Default.Movie, TileKind.MOVIES, isDark, Modifier.weight(1f).fillMaxHeight(), height = null, onClick = onOpenMovies)
                            HomeTile(stringResource(R.string.nav_series), playlist?.let { stringResource(R.string.home_series_count, it.seriesCount) } ?: stringResource(R.string.home_series_default), Icons.Default.VideoLibrary, TileKind.SERIES, isDark, Modifier.weight(1f).fillMaxHeight(), height = null, onClick = onOpenSeries)
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = liveShelfHeader)
                        if (featuredPreviews.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(featuredPreviews, key = { (channel, _) -> "recent_live_" + channelKey(channel) }) { (channel, preview) ->
                                    RecentLiveCard(channel, preview) { onContinueWatching(channel, null) }
                                }
                            }
                        } else {
                            Text(noViewingHistoryYet, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HomeCompactDeviceInfo(playlist)
                    }
                }
            }
            return@BoxWithConstraints
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = sidePadding, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = header)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                AutoSizeText(greeting, Modifier.fillMaxWidth(), maxFontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.7).sp)
                Text(
                    stringResource(R.string.home_tagline),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HomeDateTime(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(450)) + slideInVertically(tween(450)) {
                    it / 4
                }
            ) {
                ContinueCard(
                    item = continueItem,
                    modifier = Modifier.fillMaxWidth().height(150.dp)
                ) {
                    if (continueItem != null) onContinueWatching(continueItem, continueEntry?.episodeId)
                    else onMessage(nothingToContinueYet)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeTile(stringResource(R.string.nav_live_tv), playlist?.let { stringResource(R.string.home_live_tv_count, it.liveCount) } ?: stringResource(R.string.home_live_tv_default), Icons.Default.LiveTv, TileKind.LIVE, isDark, Modifier.weight(1f), onClick = onOpenLive)
                HomeTile(stringResource(R.string.nav_movies), playlist?.let { stringResource(R.string.home_movies_count, it.movieCount) } ?: stringResource(R.string.home_movies_default), Icons.Default.Movie, TileKind.MOVIES, isDark, Modifier.weight(1f), onClick = onOpenMovies)
                HomeTile(stringResource(R.string.nav_series), playlist?.let { stringResource(R.string.home_series_count, it.seriesCount) } ?: stringResource(R.string.home_series_default), Icons.Default.VideoLibrary, TileKind.SERIES, isDark, Modifier.weight(1f), onClick = onOpenSeries)
            }
            Text(stringResource(R.string.home_quick_access), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = quickAccess
            )
            if (featuredPreviews.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = liveShelfHeader)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(featuredPreviews, key = { (channel, _) -> "recent_live_" + channelKey(channel) }) { (channel, preview) ->
                        RecentLiveCard(channel, preview) { onContinueWatching(channel, null) }
                    }
                }
            }
            HomeDeviceInfoBar(playlist = playlist)
        }
    }
    }
}

/** Live clock + date shown on Home. Ticks on a plain 30s delay loop rather than a
 *  once-a-minute-aligned timer — nothing here needs second-level precision, and this keeps the
 *  displayed minute from ever drifting more than 30s stale. */
@Composable
private fun HomeDateTime(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = 13.sp
) {
    var now by remember { mutableStateOf(java.time.LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = java.time.LocalDateTime.now()
        }
    }
    // The app's own language setting, not the device's: LocaleHelper rewrites the Activity's
    // configuration, so a user reading the app in Arabic must not get an English date beside it.
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) {
        java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM • h:mm a", locale)
    }
    Text(
        text = runCatching { now.format(formatter) }.getOrDefault(""),
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1
    )
}

private enum class TileKind { LIVE, MOVIES, SERIES }

/** Subtle translucent fill + hairline border — the chip treatment used across Home. */
@Composable
private fun chipColors(selected: Boolean): Pair<Color, Color> {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    return if (selected) BrandBlue to Color.Transparent
    else if (dark) Color.White.copy(alpha = .07f) to Color.White.copy(alpha = .12f)
    else Color.White to Color(0xFFDDE7F2)
}

/** Shrinks [text] down to [minFontSize] as needed to keep it on a single line — some languages
 *  render noticeably wider than English at the same font size and would otherwise wrap. */
@Composable
private fun AutoSizeText(
    text: String,
    modifier: Modifier = Modifier,
    maxFontSize: TextUnit,
    minFontSize: TextUnit = 14.sp,
    fontWeight: FontWeight? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    color: Color = Color.Unspecified
) {
    var fontSize by remember(text, maxFontSize) { mutableStateOf(maxFontSize) }
    var readyToDraw by remember(text, maxFontSize) { mutableStateOf(false) }
    Text(
        text = text,
        modifier = modifier.drawWithContent { if (readyToDraw) drawContent() },
        fontSize = fontSize,
        fontWeight = fontWeight,
        letterSpacing = letterSpacing,
        color = color,
        maxLines = 1,
        softWrap = false,
        onTextLayout = { result ->
            if (result.didOverflowWidth && fontSize.value > minFontSize.value) {
                fontSize = (fontSize.value - 1).sp
            } else {
                readyToDraw = true
            }
        }
    )
}

@Composable
private fun QuickPill(label: String, icon: ImageVector, filled: Boolean, onClick: () -> Unit) {
    val (fill, border) = chipColors(filled)
    val content = if (filled) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = .86f)
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(fill)
            .border(1.dp, border, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Icon(icon, null, tint = if (filled) Color.White else BrandBlue, modifier = Modifier.size(16.dp))
        Text(label, fontSize = 13.sp, fontWeight = if (filled) FontWeight.Bold else FontWeight.SemiBold, color = content)
    }
}

private val LiveCardPalette = listOf(BrandBlue, Orange, Cyan, DeepBlue)

@Composable
private fun RecentLiveCard(
    item: PlaylistItem,
    previewUrl: String?,
    width: Dp = 104.dp,
    /** Landscape gives the shelf a fixed height, so let the thumbnail absorb the slack instead of
     *  deriving card height from width — otherwise the title and LIVE row get clipped. */
    fillHeight: Boolean = false,
    onClick: () -> Unit
) {
    val key = remember(item) { channelKey(item) }
    var snapshot by remember(key) { mutableStateOf(com.fourkplus.tvplayer.data.LiveSnapshotCache.get(key)) }
    var captureDone by remember(key) { mutableStateOf(snapshot != null) }
    if (!captureDone) {
        LiveSnapshotEffect(item.streamUrl) { bitmap ->
            if (bitmap != null) {
                com.fourkplus.tvplayer.data.LiveSnapshotCache.put(key, bitmap)
                snapshot = bitmap
            }
            captureDone = true
        }
    }
    Column(
        Modifier.width(width).then(if (fillHeight) Modifier.fillMaxHeight() else Modifier),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            (if (fillHeight) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().aspectRatio(16f / 10f))
                .clip(RoundedCornerShape(10.dp))
                // Focus ring lives on the thumbnail itself now, matching its own 10dp clip,
                // instead of on the whole card - wrapping the card (image + name label below)
                // used a mismatched 16dp default radius and visibly didn't hug the image.
                .then(pressFeedback(onClick, cornerRadius = 10.dp))
        ) {
            val liveFrame = snapshot
            if (liveFrame != null) {
                // A real frame pulled from this channel's live stream a moment ago, not a static logo.
                Image(liveFrame.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else if (!previewUrl.isNullOrBlank()) {
                AsyncImage(previewUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                // No distinct logo for this channel (missing, or shared with another one in this
                // row) — a colored still with the channel's initial reads as intentional, not broken.
                val brandColor = LiveCardPalette[Math.floorMod(item.name.hashCode(), LiveCardPalette.size)]
                Box(
                    Modifier.fillMaxSize()
                        .background(Brush.linearGradient(listOf(brandColor, brandColor.copy(alpha = .65f)))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        item.name.trim().firstOrNull()?.uppercase() ?: "?",
                        color = Color.White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
        Text(item.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Compact device-info block for Home's landscape right column, filling the space the
 *  Recently Added Movies/Series shelves used to occupy — the same App MAC / device key /
 *  playlist expiry values as [HomeDeviceInfoBar] (portrait), laid out as tight label/value
 *  rows instead of a wide bar so it fits under the Live TV strip on the fixed-height screen. */
@Composable
private fun HomeCompactDeviceInfo(playlist: LoadedPlaylist?) {
    val context = LocalContext.current
    val appMac = remember { com.fourkplus.tvplayer.data.DeviceIdentity.mac(context) }
    val deviceKey = remember { com.fourkplus.tvplayer.data.DeviceIdentity.deviceKey(context) }
    val expiryText = remember(playlist?.expiryEpochSeconds) {
        playlist?.expiryEpochSeconds?.let { epochSeconds ->
            runCatching {
                val date = java.time.Instant.ofEpochSecond(epochSeconds)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate()
                val days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), date)
                when {
                    days > 0 -> "$date ($days days)"
                    days == 0L -> "$date (today)"
                    else -> "$date (expired)"
                }
            }.getOrNull()
        } ?: "Not provided"
    }
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .72f),
        border = BorderStroke(1.dp, Cyan.copy(alpha = .32f))
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            HomeCompactInfoRow("App MAC", appMac)
            HomeCompactInfoRow("Device key", deviceKey)
            HomeCompactInfoRow("Playlist expires", expiryText)
        }
    }
}

@Composable
private fun HomeCompactInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HomeDeviceInfoBar(playlist: LoadedPlaylist?) {
    val context = LocalContext.current
    val appMac = remember { com.fourkplus.tvplayer.data.DeviceIdentity.mac(context) }
    val deviceKey = remember { com.fourkplus.tvplayer.data.DeviceIdentity.deviceKey(context) }
    val expiryText = remember(playlist?.expiryEpochSeconds) {
        playlist?.expiryEpochSeconds?.let { epochSeconds ->
            runCatching {
                val date = java.time.Instant.ofEpochSecond(epochSeconds)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate()
                val days = java.time.temporal.ChronoUnit.DAYS.between(
                    java.time.LocalDate.now(),
                    date
                )
                when {
                    days > 0 -> "${date} (${days} days)"
                    days == 0L -> "${date} (today)"
                    else -> "${date} (expired)"
                }
            }.getOrNull()
        } ?: "Not provided"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, Cyan.copy(alpha = 0.32f))
    ) {
        BoxWithConstraints {
            val wide = maxWidth >= 650.dp
            if (wide) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HomeInfoValue("App MAC", appMac, Modifier.weight(1f))
                    VerticalDivider(Modifier.height(34.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    HomeInfoValue("Device key", deviceKey, Modifier.weight(1f))
                    VerticalDivider(Modifier.height(34.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    HomeInfoValue("Playlist expires", expiryText, Modifier.weight(1.25f))
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HomeInfoValue("App MAC", appMac, Modifier.weight(1.45f))
                        VerticalDivider(
                            Modifier.height(38.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
                        )
                        HomeInfoValue("Device key", deviceKey, Modifier.weight(1f))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    HomeInfoValue(
                        "Playlist expires",
                        expiryText,
                        Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeInfoValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

private enum class MovieView { BROWSE, CATEGORY, DETAILS, PLAYER }

@Composable
private fun MoviesScreen(
    playlist: LoadedPlaylist?,
    loadDetails: suspend (PlaylistItem) -> Result<MovieDetailsInfo>,
    onBack: () -> Unit,
    requirePin: (() -> Unit) -> Unit,
    resumeRequest: ResumeRequest? = null,
    onResumeHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    val parental = remember { context.getSharedPreferences("parental_settings", android.content.Context.MODE_PRIVATE) }
    var hiddenCategories by remember {
        mutableStateOf(parental.getStringSet("hidden_movie_categories", emptySet()).orEmpty().toSet())
    }
    val lockedCategories = remember { parental.getStringSet("locked_movie_categories", emptySet()).orEmpty().toSet() }
    val movies = remember(playlist, hiddenCategories) {
        playlist?.items?.filter { it.kind == MediaKind.MOVIE && it.group !in hiddenCategories }.orEmpty()
    }
    // Bumped after a long-press reorder so `categories` recomputes even though `movies` itself
    // (the applyCategoryOrder input) hasn't changed.
    var categoryOrderVersion by remember { mutableIntStateOf(0) }
    val categories = remember(movies, categoryOrderVersion) { applyCategoryOrder(context, MediaKind.MOVIE, movies.map { it.group }.distinct()) }
    val store = remember { context.getSharedPreferences("movie_library", android.content.Context.MODE_PRIVATE) }
    var view by remember { mutableStateOf(MovieView.BROWSE) }
    // Which list the open details page was reached from, and the poster to scroll back to and
    // focus once it closes - the grid is torn down while details are showing, so without these it
    // rebuilds scrolled to the top with focus on the first item.
    var detailsReturnView by remember { mutableStateOf(MovieView.BROWSE) }
    var restoreFocusKey by remember { mutableStateOf<String?>(null) }
    var selectedCategory by remember { mutableStateOf("Continue watching") }
    var selectedMovie by remember { mutableStateOf<PlaylistItem?>(null) }
    var details by remember { mutableStateOf<MovieDetailsInfo?>(null) }
    var detailsLoading by remember { mutableStateOf(false) }
    var detailsError by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var favoriteIds by remember { mutableStateOf(store.getStringSet("favorites", emptySet()).orEmpty().toSet()) }
    var recentIds by remember {
        mutableStateOf(store.getString("recent_v1", "").orEmpty().split('\u001F').filter(String::isNotBlank))
    }
    var progress by remember {
        mutableStateOf(
            store.all.mapNotNull { (key, value) ->
                if (key.startsWith("progress_") && value is Long && value > 0L) key.removePrefix("progress_") to value else null
            }.toMap()
        )
    }
    val byId = remember(movies) { movies.associateBy(::channelKey) }
    val favorites = remember(movies, favoriteIds) { movies.filter { channelKey(it) in favoriteIds } }
    val recent = remember(byId, recentIds) { recentIds.mapNotNull(byId::get) }
    val continueWatching = remember(movies, progress) {
        movies.filter { (progress[channelKey(it)] ?: 0L) >= 30_000L }
            .sortedByDescending { progress[channelKey(it)] ?: 0L }
    }

    LaunchedEffect(resumeRequest, byId) {
        val request = resumeRequest ?: return@LaunchedEffect
        byId[request.itemKey]?.let { movie ->
            selectedMovie = movie
            details = null
            detailsError = null
            view = if (request.autoPlay) MovieView.PLAYER else MovieView.DETAILS
        }
        onResumeHandled()
    }

    fun toggleFavorite(movie: PlaylistItem) {
        val id = channelKey(movie)
        val updated = if (id in favoriteIds) favoriteIds - id else favoriteIds + id
        favoriteIds = updated
        store.edit().putStringSet("favorites", updated).apply()
    }
    fun openDetails(movie: PlaylistItem) {
        detailsReturnView = if (view == MovieView.CATEGORY) MovieView.CATEGORY else MovieView.BROWSE
        selectedMovie = movie
        details = null
        detailsError = null
        view = MovieView.DETAILS
    }
    fun recordRecent(movie: PlaylistItem) {
        val id = channelKey(movie)
        val updated = (listOf(id) + recentIds.filterNot { it == id }).take(30)
        recentIds = updated
        store.edit().putString("recent_v1", updated.joinToString("\u001F")).apply()
    }
    fun saveProgress(movie: PlaylistItem, position: Long, duration: Long) {
        val id = channelKey(movie)
        val normalized = if (duration > 0L && position >= duration - 20_000L) 0L else position.coerceAtLeast(0L)
        progress = if (normalized == 0L) progress - id else progress + (id to normalized)
        store.edit().putLong("progress_$id", normalized).apply()
        if (normalized > 0L) {
            com.fourkplus.tvplayer.data.ContinueWatchingStore.record(context, MediaKind.MOVIE, id)
        } else {
            com.fourkplus.tvplayer.data.ContinueWatchingStore.clear(context, id)
        }
    }
    fun goBack() {
        when (view) {
            MovieView.BROWSE -> onBack()
            MovieView.CATEGORY -> { search = ""; view = MovieView.BROWSE }
            MovieView.DETAILS -> {
                restoreFocusKey = selectedMovie?.let(::channelKey)
                view = detailsReturnView
            }
            MovieView.PLAYER -> view = MovieView.DETAILS
        }
    }
    BackHandler(onBack = ::goBack)

    LaunchedEffect(selectedMovie, view) {
        val movie = selectedMovie
        if (movie != null && view == MovieView.DETAILS && details == null && !detailsLoading) {
            detailsLoading = true
            loadDetails(movie)
                .onSuccess { details = it }
                .onFailure { detailsError = it.message }
            detailsLoading = false
        }
    }

    PremiumBackground {
        if (view == MovieView.DETAILS) {
            val pageBackdrop = details?.backdropUrl ?: details?.posterUrl ?: selectedMovie?.logoUrl
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
            if (view == MovieView.PLAYER) {
                selectedMovie?.let { movie ->
                    MoviePlayer(
                        movie = movie,
                        startPosition = progress[channelKey(movie)] ?: 0L,
                        onProgress = { position, duration -> saveProgress(movie, position, duration) },
                        onExit = { view = MovieView.DETAILS },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                return@BoxWithConstraints
            }
            if (landscape && view in setOf(MovieView.BROWSE, MovieView.CATEGORY)) {
                LandscapeMovieBrowser(
                    movies = movies,
                    categories = categories,
                    selectedCategory = selectedCategory,
                    search = search,
                    favoriteIds = favoriteIds,
                    recent = recent,
                    favorites = favorites,
                    continueWatching = continueWatching,
                    restoreFocusKey = restoreFocusKey,
                    onRestoreHandled = { restoreFocusKey = null },
                    progress = progress,
                    onCategory = { category ->
                        fun enter() { selectedCategory = category; search = ""; view = MovieView.CATEGORY }
                        if (category in lockedCategories) requirePin(::enter) else enter()
                    },
                    onSearch = { search = it },
                    onFavorite = ::toggleFavorite,
                    onMovie = ::openDetails,
                    onCategoriesReordered = { categoryOrderVersion++ },
                    onBack = {
                        if (view == MovieView.CATEGORY) view = MovieView.BROWSE else onBack()
                    }
                )
                return@BoxWithConstraints
            }
            Column(
                Modifier.fillMaxSize().padding(horizontal = if (landscape) 34.dp else 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedIconButton(onClick = ::goBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.cd_back)) }
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (view) {
                                MovieView.BROWSE -> stringResource(R.string.nav_movies)
                                MovieView.CATEGORY -> localizedSectionTitle(selectedCategory)
                                else -> details?.originalTitle ?: selectedMovie?.name ?: stringResource(R.string.nav_movies)
                            },
                            fontSize = if (landscape) 23.sp else 22.sp,
                            fontWeight = FontWeight.Black,
                            lineHeight = if (landscape) 27.sp else 26.sp
                        )
                        if (view == MovieView.BROWSE) Text(stringResource(R.string.movies_count, movies.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (view == MovieView.CATEGORY && selectedCategory !in setOf("Continue watching", "Recently watched", "Favorites")) {
                        TextButton(onClick = {
                            hiddenCategories = hiddenCategories + selectedCategory
                            parental.edit().putStringSet("hidden_movie_categories", hiddenCategories).apply()
                            search = ""
                            view = MovieView.BROWSE
                        }) {
                            Icon(Icons.Default.VisibilityOff, null)
                            Spacer(Modifier.width(5.dp))
                            Text(stringResource(R.string.action_hide))
                        }
                    }
                    if (view == MovieView.DETAILS && selectedMovie != null) {
                        AnimatedIconButton(onClick = { toggleFavorite(selectedMovie!!) }) {
                            Icon(
                                if (channelKey(selectedMovie!!) in favoriteIds) Icons.Default.Star else Icons.Default.StarBorder,
                                if (channelKey(selectedMovie!!) in favoriteIds) stringResource(R.string.cd_favorite_remove) else stringResource(R.string.cd_favorite_add),
                                tint = if (channelKey(selectedMovie!!) in favoriteIds) Orange else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                when (view) {
                    MovieView.BROWSE -> {
                        SearchField(search, { search = it }, stringResource(R.string.search_all_movies))
                        if (search.isNotBlank()) {
                            val results = remember(movies, search) { movies.filter { it.name.contains(search.trim(), true) } }
                            MovieGrid(
                                results, favoriteIds, ::toggleFavorite, ::openDetails, Modifier.weight(1f), landscape, progress,
                                restoreFocusKey = restoreFocusKey, onRestoreHandled = { restoreFocusKey = null }
                            )
                        } else {
                            val sections = buildList {
                                if (continueWatching.isNotEmpty()) add("Continue watching" to continueWatching)
                                add("Recently watched" to recent)
                                add("Favorites" to favorites)
                                categories.forEach { category -> add(category to movies.filter { it.group == category }) }
                            }
                            if (sections.isEmpty()) {
                                MovieEmptyState(stringResource(R.string.no_movies_found))
                            } else {
                                LazyColumn(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(18.dp),
                                    contentPadding = PaddingValues(bottom = 20.dp)
                                ) {
                                    items(sections) { (title, sectionMovies) ->
                                        MovieShelf(
                                            title, sectionMovies, favoriteIds,
                                            onSeeAll = {
                                                fun enter() { selectedCategory = title; view = MovieView.CATEGORY }
                                                if (title in lockedCategories) requirePin(::enter) else enter()
                                            },
                                            onHide = if (title in setOf("Continue watching", "Recently watched", "Favorites")) null else {{
                                                val updated = hiddenCategories + title
                                                hiddenCategories = updated
                                                parental.edit().putStringSet("hidden_movie_categories", updated).apply()
                                            }},
                                            onFavorite = ::toggleFavorite,
                                            onMovie = ::openDetails,
                                            progress = progress
                                        )
                                    }
                                }
                            }
                        }
                    }
                    MovieView.CATEGORY -> {
                        SearchField(search, { search = it }, stringResource(R.string.search_all_movies))
                        val base = when (selectedCategory) {
                            "Continue watching" -> continueWatching
                            "Recently watched" -> recent
                            "Favorites" -> favorites
                            else -> movies.filter { it.group == selectedCategory }
                        }
                        val results = if (search.isBlank()) base else movies.filter { it.name.contains(search.trim(), true) }
                        MovieGrid(
                            results, favoriteIds, ::toggleFavorite, ::openDetails, Modifier.weight(1f), landscape, progress,
                            restoreFocusKey = restoreFocusKey, onRestoreHandled = { restoreFocusKey = null }
                        )
                    }
                    MovieView.DETAILS -> selectedMovie?.let { movie ->
                        MovieDetails(
                            movie = movie,
                            details = details,
                            loading = detailsLoading,
                            detailsError = detailsError,
                            favorite = channelKey(movie) in favoriteIds,
                            resumePosition = progress[channelKey(movie)] ?: 0L,
                            onFavorite = { toggleFavorite(movie) },
                            onPlay = { recordRecent(movie); view = MovieView.PLAYER },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // Rendered as a full-screen overlay above this BoxWithConstraints instead
                    // (see the early return at the top of it) — see comment there for why.
                    MovieView.PLAYER -> Unit
                }
            }
        }
    }
}

@Composable
private fun LandscapeMovieBrowser(
    movies: List<PlaylistItem>,
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
    onMovie: (PlaylistItem) -> Unit,
    onCategoriesReordered: () -> Unit,
    onBack: () -> Unit,
    progress: Map<String, Long> = emptyMap()
) {
    val special = listOf("Continue watching", "Recently watched", "Favorites")
    val allCategories = special + categories
    val base = when (selectedCategory) {
        "Continue watching" -> continueWatching
        "Recently watched" -> recent
        "Favorites" -> favorites
        else -> movies.filter { it.group == selectedCategory }
    }
    val displayed = if (search.isBlank()) base else movies.filter { it.name.contains(search.trim(), true) }
    val context = LocalContext.current
    val isTv = remember { context.isTvDevice() }
    // The category currently being hand-moved after a long-press - Up/Down nudges it, OK drops it.
    var reorderingCategory by remember { mutableStateOf<String?>(null) }
    val categoryListState = rememberLazyListState()
    // Keeps the moving category in view as it's nudged past the edge of the visible list -
    // otherwise it scrolls out from under the user with no sign of where it went.
    LaunchedEffect(reorderingCategory, allCategories) {
        val index = reorderingCategory?.let(allCategories::indexOf) ?: return@LaunchedEffect
        if (index >= 0) categoryListState.animateScrollToItem(index)
    }
    val continueWatchingFocusRequester = remember { FocusRequester() }
    val selectedCategoryFocusRequester = remember { FocusRequester() }
    val categoryScope = rememberCoroutineScope()
    // Left from the first column of the grid returns to the category actually being browsed,
    // scrolled into view first. Compose's own focus search would otherwise pick whichever category
    // sits at the same height on screen, which is rarely the one the user opened.
    fun focusSelectedCategory() {
        categoryScope.launch {
            val index = allCategories.indexOf(selectedCategory)
            if (index >= 0) runCatching { categoryListState.scrollToItem(index) }
            if (!requestFocusWithRetry(selectedCategoryFocusRequester)) {
                requestFocusWithRetry(continueWatchingFocusRequester)
            }
        }
    }
    // Skipped when returning from a details page - MovieGrid is restoring focus to the poster the
    // user left from, and both requests racing would land focus back on the category list instead.
    LaunchedEffect(isTv) {
        if (isTv && restoreFocusKey == null) requestFocusWithRetry(continueWatchingFocusRequester)
    }
    // Pressing OK on a category should move the remote's focus straight into that category's
    // grid rather than leaving it on the category button — 0 means "not from a press yet".
    var categorySelectionTick by remember { mutableIntStateOf(0) }
    val firstItemFocusRequester = remember { FocusRequester() }
    LaunchedEffect(categorySelectionTick) {
        if (categorySelectionTick > 0 && isTv) runCatching { firstItemFocusRequester.requestFocus() }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // See COMPACT_TV_WIDTH: a narrow panel gets a slimmer sidebar and tighter margins so the poster
    // grid beside it keeps enough room to stay readable.
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
                    Text(stringResource(R.string.nav_movies), fontSize = 19.sp, fontWeight = FontWeight.Black)
                }
                SearchField(search, onSearch, stringResource(R.string.search_movies))
                LazyColumn(Modifier.weight(1f), state = categoryListState, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(allCategories, key = { it }) { category ->
                        val isReordering = category == reorderingCategory
                        Surface(
                            modifier = Modifier.fillMaxWidth()
                                .then(if (category == "Continue watching") Modifier.focusRequester(continueWatchingFocusRequester) else Modifier)
                                .then(if (category == selectedCategory) Modifier.focusRequester(selectedCategoryFocusRequester) else Modifier)
                                .categoryReorderKeys(
                                    active = isReordering,
                                    onMove = { up -> moveCategory(context, MediaKind.MOVIE, categories, category, up); onCategoriesReordered() },
                                    onExit = { reorderingCategory = null }
                                )
                                .focusableClickable(
                                    cornerRadius = 11.dp,
                                    onLongClick = if (category in special) null else { { reorderingCategory = category } }
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
            Text(localizedSectionTitle(selectedCategory).ifBlank { stringResource(R.string.nav_movies) }, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            MovieGrid(
                displayed, favoriteIds, onFavorite, onMovie, Modifier.weight(1f), true, progress,
                firstItemFocusRequester, restoreFocusKey, onRestoreHandled,
                onExitLeft = if (isTv) ({ focusSelectedCategory() }) else null
            )
        }
    }
    }
}

@Composable
private fun LandscapeLiveBrowser(
    categories: List<String>,
    selectedCategory: String,
    channels: List<PlaylistItem>,
    // Only used once the user actually types a channel search - lets the search reach every
    // channel in the playlist instead of just the ones in the currently selected category.
    allChannels: List<PlaylistItem>,
    selectedChannel: PlaylistItem?,
    // False until the user has actually picked a channel or category this session. The preview
    // still plays [selectedChannel] either way; this only governs whether a row in the list is
    // drawn as the chosen one.
    channelChosen: Boolean,
    favoriteIds: Set<String>,
    returningFromFullscreen: Boolean,
    onCategory: (String) -> Unit,
    onChannel: (PlaylistItem) -> Unit,
    onChannelFullscreen: (PlaylistItem) -> Unit,
    onFavorite: (PlaylistItem) -> Unit,
    onCategoriesReordered: () -> Unit,
    onBack: () -> Unit,
    loadEpg: suspend (PlaylistItem) -> Result<EpgNowNext>
) {
    var channelSearch by remember { mutableStateOf("") }
    var categorySearch by remember { mutableStateOf("") }
    val searchedChannels = remember(channels, allChannels, channelSearch) {
        if (channelSearch.isBlank()) channels else allChannels.filter { it.name.contains(channelSearch.trim(), true) }
    }
    val searchedCategories = remember(categories, categorySearch) {
        if (categorySearch.isBlank()) categories else categories.filter { it.contains(categorySearch.trim(), true) }
    }
    val context = LocalContext.current
    val isTv = remember { context.isTvDevice() }
    // `categories` has "Recently watched"/"Favorites" prepended by the caller - those aren't
    // real playlist categories, so they're excluded from the reorderable list and its bounds.
    val reorderableCategories = remember(categories) { categories.filterNot { it in setOf("Recently watched", "Favorites") } }
    // The category currently being hand-moved after a long-press - Up/Down nudges it, OK drops it.
    var reorderingCategory by remember { mutableStateOf<String?>(null) }
    val categoryListState = rememberLazyListState()
    // Keeps the moving category in view as it's nudged past the edge of the visible list -
    // otherwise it scrolls out from under the user with no sign of where it went.
    LaunchedEffect(reorderingCategory, searchedCategories) {
        val index = reorderingCategory?.let(searchedCategories::indexOf) ?: return@LaunchedEffect
        if (index >= 0) categoryListState.animateScrollToItem(index)
    }
    val recentCategoryFocusRequester = remember { FocusRequester() }
    val selectedCategoryFocusRequester = remember { FocusRequester() }
    val selectedChannelFocusRequester = remember { FocusRequester() }
    val channelListState = rememberLazyListState()
    val backScope = rememberCoroutineScope()
    // Back walks out of this menu one level at a time rather than leaving Live TV outright: from
    // the channel list it steps across to the category list (landing on the category being
    // browsed), and only a further Back from there - when this handler switches itself off and
    // LiveTvScreen's own handler takes over - actually leaves Live TV.
    var categoryColumnFocused by remember { mutableStateOf(false) }
    // Puts focus back on the category actually being browsed, scrolling it into view first - a
    // focus requester pointing at a row the list has scrolled past belongs to no node and would
    // silently do nothing. Used both by Back and by Left from the channel list.
    fun focusSelectedCategory() {
        backScope.launch {
            val index = searchedCategories.indexOf(selectedCategory)
            if (index >= 0) runCatching { categoryListState.scrollToItem(index) }
            // Recently watched is always present, so it is a safe landing spot if the selected
            // category is filtered out of the list by an active search.
            if (!requestFocusWithRetry(selectedCategoryFocusRequester)) {
                requestFocusWithRetry(recentCategoryFocusRequester)
            }
        }
    }
    BackHandler(enabled = isTv && !categoryColumnFocused) { focusSelectedCategory() }
    // What the preview is playing is only treated as the chosen row once the user has picked
    // something - see channelChosen.
    val highlightedChannel = selectedChannel?.takeIf { channelChosen }
    val selectedChannelIndex = remember(searchedChannels, highlightedChannel) {
        val key = highlightedChannel?.let(::channelKey)
        searchedChannels.indexOfFirst { channelKey(it) == key }
    }
    // This whole menu is torn down while fullscreen is showing and rebuilt on the way back, so its
    // lists always return scrolled to the top. On first ever open, land the remote's focus on the
    // Recently watched category; after returning from fullscreen, put both lists back where they
    // were and land focus on the channel that was playing.
    LaunchedEffect(isTv) {
        if (!isTv) return@LaunchedEffect
        if (!returningFromFullscreen) {
            requestFocusWithRetry(recentCategoryFocusRequester)
            return@LaunchedEffect
        }
        val categoryIndex = searchedCategories.indexOf(selectedCategory)
        if (categoryIndex >= 0) runCatching { categoryListState.scrollToItem(categoryIndex) }
        val restored = restoreListPosition(
            index = selectedChannelIndex,
            scrollToItem = { channelListState.scrollToItem(it) },
            focusRequester = selectedChannelFocusRequester
        )
        // Leaving the screen with nothing focused is what makes a remote feel like it is being
        // ignored, so fall back to a target that is always present.
        if (!restored) requestFocusWithRetry(recentCategoryFocusRequester)
    }
    // Pressing OK on a category should move the remote's focus straight into that category's
    // channel list rather than leaving it sitting on the category button — 0 means "not from a
    // press yet" (the initial LaunchedEffect above owns focus at that point).
    var categorySelectionTick by remember { mutableIntStateOf(0) }
    val firstChannelFocusRequester = remember { FocusRequester() }
    LaunchedEffect(categorySelectionTick) {
        if (categorySelectionTick > 0 && isTv) runCatching { firstChannelFocusRequester.requestFocus() }
    }
        BoxWithConstraints(Modifier.fillMaxSize()) {
        // These two columns are fixed-width, so on a narrow panel they would between them leave the
        // video beside them almost nothing. Shrinking them below the standard TV width keeps the
        // picture watchable; at 960dp and above the sizes are unchanged.
        val compact = maxWidth < COMPACT_TV_WIDTH
        val categoryColumnWidth = if (compact) 180.dp else 240.dp
        val channelColumnWidth = if (compact) 240.dp else 310.dp
        Row(
            Modifier.fillMaxSize().padding(horizontal = if (compact) 10.dp else 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                Modifier.width(categoryColumnWidth).fillMaxHeight()
                    .onFocusChanged { categoryColumnFocused = it.hasFocus },
                shape = RoundedCornerShape(16.dp),
                color = Color.Transparent
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AnimatedIconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.cd_back), tint = Color.White) }
                        Text(stringResource(R.string.nav_live_tv), color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Black)
                    }
                    DarkTvSearchField(
                        value = categorySearch,
                        onValueChange = { categorySearch = it },
                        placeholder = stringResource(R.string.search_categories),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    )
                    LazyColumn(Modifier.weight(1f), state = categoryListState, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        items(searchedCategories, key = { it }) { category ->
                            val isReordering = category == reorderingCategory
                            Surface(
                                modifier = Modifier.fillMaxWidth()
                                    .then(if (category == "Recently watched") Modifier.focusRequester(recentCategoryFocusRequester) else Modifier)
                                    .then(if (category == selectedCategory) Modifier.focusRequester(selectedCategoryFocusRequester) else Modifier)
                                    .categoryReorderKeys(
                                        active = isReordering,
                                        onMove = { up -> moveCategory(context, MediaKind.LIVE, reorderableCategories, category, up); onCategoriesReordered() },
                                        onExit = { reorderingCategory = null }
                                    )
                                    .focusableClickable(
                                        cornerRadius = 9.dp,
                                        onLongClick = if (category in reorderableCategories) { { reorderingCategory = category } } else null
                                    ) {
                                        categorySelectionTick++
                                        onCategory(category)
                                    },
                                shape = RoundedCornerShape(9.dp),
                                color = if (isReordering) Cyan.copy(alpha = .55f) else if (category == selectedCategory) Orange.copy(alpha = .88f) else Color.Transparent,
                                border = if (isReordering) BorderStroke(2.dp, Cyan) else null
                            ) {
                                Row(Modifier.padding(start = 11.dp, top = 5.dp, bottom = 5.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(localizedSectionTitle(category), Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                    if (isReordering) Icon(Icons.Default.SwapVert, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
            Surface(
                Modifier.width(channelColumnWidth).fillMaxHeight(),
                shape = RoundedCornerShape(16.dp),
                color = Color.Transparent
            ) {
                Column {
                    DarkTvSearchField(
                        value = channelSearch,
                        onValueChange = { channelSearch = it },
                        placeholder = stringResource(R.string.search_channels),
                        fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().padding(8.dp)
                    )
                    LazyColumn(state = channelListState, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    // Keyed so a row's remembered state stays tied to its channel. Without this,
                    // lazy items are identified by position, so swapping category or searching
                    // hands slot N's remembered interaction source - and with it the focus ring's
                    // animated alpha - to whichever unrelated channel now sits at that position,
                    // leaving the highlight stranded on the wrong row. The index suffix only
                    // guarantees uniqueness: a playlist with no stream ids falls back to
                    // group:name in channelKey, which is not guaranteed distinct, and a duplicate
                    // key would crash the list outright.
                    itemsIndexed(
                        searchedChannels,
                        key = { index, channel -> "${channelKey(channel)}#$index" }
                    ) { index, channel ->
                        val selected = channelKey(channel) == highlightedChannel?.let(::channelKey)
                        // combinedClickable reports remote OK presses as individual clicks.
                        // Pair activations on this row so double-tap also opens fullscreen (touch);
                        // a single OK press does it directly on TV, see isTv below.
                        var lastActivationAt by remember(channelKey(channel)) { mutableLongStateOf(0L) }
                        val openFullscreen: () -> Unit = {
                            lastActivationAt = 0L
                            onChannelFullscreen(channel)
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth()
                                .then(if (index == 0) Modifier.focusRequester(firstChannelFocusRequester) else Modifier)
                                .then(if (selected) Modifier.focusRequester(selectedChannelFocusRequester) else Modifier)
                                // This is a single column, so Left always means "back to the
                                // categories" - and specifically to the one being browsed. Left to
                                // Compose's own focus search it would instead pick whichever
                                // category happens to sit at the same height on screen.
                                .onPreviewKeyEvent { event ->
                                    if (isTv && event.isInitialKeyDown && event.key == Key.DirectionLeft) {
                                        focusSelectedCategory(); true
                                    } else false
                                }
                                .onFocusChanged { if (!it.isFocused) lastActivationAt = 0L }
                                .focusableClickable(
                                    cornerRadius = 9.dp,
                                    // Press-and-hold OK toggles favorite - the star icon in this
                                    // row is a real Compose click target, but D-pad focus can't
                                    // reach into it independently of the row itself, so it was
                                    // effectively touch-only. Long-press already opening
                                    // fullscreen here was redundant on TV (a single OK press
                                    // already does that below), so this reassignment doesn't
                                    // lose that behavior - double-tap still covers it for touch.
                                    onLongClick = { onFavorite(channel) },
                                    onDoubleClick = openFullscreen
                                ) {
                                    if (isTv) {
                                        onChannel(channel)
                                        openFullscreen()
                                    } else {
                                        val now = android.os.SystemClock.uptimeMillis()
                                        if (lastActivationAt != 0L && now - lastActivationAt <= 500L) {
                                            openFullscreen()
                                        } else {
                                            lastActivationAt = now
                                            onChannel(channel)
                                        }
                                    }
                                },
                            shape = RoundedCornerShape(9.dp),
                            color = if (selected) Cyan.copy(alpha = .32f) else Color.Transparent
                        ) {
                            Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(channel.logoUrl, null, Modifier.size(28.dp), contentScale = ContentScale.Fit)
                                Spacer(Modifier.width(7.dp))
                                Text(channel.name, Modifier.weight(1f), color = Color.White, fontSize = 12.sp, maxLines = 1, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                AnimatedIconButton(onClick = { onFavorite(channel) }, modifier = Modifier.size(30.dp)) {
                                    Icon(
                                        if (channelKey(channel) in favoriteIds) Icons.Default.Star else Icons.Default.StarBorder,
                                        if (channelKey(channel) in favoriteIds) stringResource(R.string.cd_favorite_remove) else stringResource(R.string.cd_favorite_add),
                                        tint = if (channelKey(channel) in favoriteIds) Orange else Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                selectedChannel?.let {
                    val nowNext = rememberEpgNowNext(it, loadEpg)
                    // No panel behind it, matching the rest of this menu — every line carries its
                    // own drop shadow instead so it stays readable over a bright video frame.
                    val legibleShadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black.copy(alpha = .95f),
                        offset = Offset(0f, 1f),
                        blurRadius = 6f
                    )
                    Surface(
                        Modifier.align(Alignment.BottomEnd).padding(8.dp).widthIn(max = 260.dp),
                        color = Color.Transparent,
                        shape = RoundedCornerShape(11.dp)
                    ) {
                        Column(
                            Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                it.name,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                style = androidx.compose.ui.text.TextStyle(shadow = legibleShadow)
                            )
                            NowNextLine(
                                nowNext,
                                titleColor = Color.White,
                                nextColor = Color.White.copy(alpha = .75f),
                                textShadow = legibleShadow
                            )
                        }
                    }
                }
            }
        }
        }
}

@Composable
private fun MovieShelf(
    title: String,
    movies: List<PlaylistItem>,
    favoriteIds: Set<String>,
    onSeeAll: () -> Unit,
    onHide: (() -> Unit)?,
    onFavorite: (PlaylistItem) -> Unit,
    onMovie: (PlaylistItem) -> Unit,
    progress: Map<String, Long> = emptyMap()
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(localizedSectionTitle(title), Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            onHide?.let {
                AnimatedIconButton(onClick = it, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.VisibilityOff, stringResource(R.string.cd_hide_category, localizedSectionTitle(title)), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = onSeeAll) { Text(stringResource(R.string.action_see_all), color = Cyan); Icon(Icons.Default.ChevronRight, null, tint = Cyan) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            if (movies.isEmpty()) {
                item {
                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .72f), shape = RoundedCornerShape(13.dp)) {
                        Text(
                            if (title == "Favorites") stringResource(R.string.movies_star_empty) else stringResource(R.string.movies_watch_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
                        )
                    }
                }
            } else {
                items(movies.take(16)) { movie ->
                    MoviePoster(movie, channelKey(movie) in favoriteIds, { onFavorite(movie) }, { onMovie(movie) }, Modifier.width(128.dp), watchedFraction(movie, progress))
                }
            }
        }
    }
}

@Composable
private fun MovieGrid(
    movies: List<PlaylistItem>,
    favoriteIds: Set<String>,
    onFavorite: (PlaylistItem) -> Unit,
    onMovie: (PlaylistItem) -> Unit,
    modifier: Modifier,
    landscape: Boolean,
    progress: Map<String, Long> = emptyMap(),
    firstItemFocusRequester: FocusRequester? = null,
    // Set once, on the way back from a movie's details page: the poster to scroll to and focus.
    restoreFocusKey: String? = null,
    onRestoreHandled: () -> Unit = {},
    // Invoked when Left is pressed from the grid's first column, where there is nothing further to
    // the left inside the grid itself. Null on touch devices, which have no directional focus.
    onExitLeft: (() -> Unit)? = null
) {
    val gridState = rememberLazyGridState()
    val restoreFocusRequester = remember { FocusRequester() }
    val restoreIndex = remember(movies, restoreFocusKey) {
        if (restoreFocusKey == null) -1 else movies.indexOfFirst { channelKey(it) == restoreFocusKey }
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
    if (movies.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.no_movies_match), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    } else BoxWithConstraints(modifier) {
        val columns = posterGridColumns(maxWidth, landscape)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(), state = gridState,
            horizontalArrangement = Arrangement.spacedBy(if (landscape) 7.dp else 10.dp), verticalArrangement = Arrangement.spacedBy(if (landscape) 9.dp else 16.dp),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            // Keyed for the same reason as the Live TV channel list - see the comment there.
            gridItemsIndexed(
                movies,
                key = { index, movie -> "${channelKey(movie)}#$index" }
            ) { index, movie ->
                MoviePoster(
                    movie, channelKey(movie) in favoriteIds, { onFavorite(movie) }, { onMovie(movie) },
                    modifier = Modifier
                        .then(if (index == 0 && firstItemFocusRequester != null) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                        .then(if (index == restoreIndex) Modifier.focusRequester(restoreFocusRequester) else Modifier)
                        // Only the first column: everywhere else Left is ordinary movement between
                        // posters and must not be intercepted.
                        .then(
                            if (onExitLeft != null && index % columns == 0) {
                                Modifier.onPreviewKeyEvent { event ->
                                    if (event.isInitialKeyDown && event.key == Key.DirectionLeft) {
                                        onExitLeft(); true
                                    } else false
                                }
                            } else Modifier
                        ),
                    watchedFraction = watchedFraction(movie, progress)
                )
            }
        }
    }
}

@Composable
private fun MoviePoster(
    movie: PlaylistItem,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    watchedFraction: Float? = null
) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).focusableClickable(cornerRadius = 14.dp, onClick = onClick)) {
        Surface(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f), RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, Color.White.copy(alpha = .08f))
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Movie, null, tint = Orange.copy(alpha = .5f), modifier = Modifier.size(38.dp))
                if (!movie.logoUrl.isNullOrBlank()) AsyncImage(movie.logoUrl, movie.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                // YouTube-style watched indicator: a red strip along the bottom edge sized to how
                // far into the movie the user got.
                if (watchedFraction != null) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = .35f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(watchedFraction).background(Color(0xFFE50914)))
                    }
                }
                AnimatedIconButton(
                    onClick = onFavorite,
                    modifier = Modifier.align(Alignment.TopEnd).size(34.dp).background(Color.Black.copy(alpha = .55f), RoundedCornerShape(10.dp))
                ) {
                    Icon(
                        if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                        if (favorite) stringResource(R.string.cd_favorite_remove) else stringResource(R.string.cd_favorite_add),
                        tint = if (favorite) Orange else Color.White, modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(movie.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
        if (!movie.year.isNullOrBlank()) Text(movie.year, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MovieDetails(
    movie: PlaylistItem,
    details: MovieDetailsInfo?,
    loading: Boolean,
    detailsError: String?,
    favorite: Boolean,
    resumePosition: Long,
    onFavorite: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val poster = details?.posterUrl ?: movie.logoUrl
    val backdrop = details?.backdropUrl ?: poster
    val description = details?.description ?: movie.description
    val year = details?.year ?: movie.year
    val rating = validMovieRating(details?.rating ?: movie.rating)
    val duration = readableMovieDuration(details?.duration ?: movie.duration)
    val displayTitle = details?.originalTitle ?: movie.name
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Null unless the provider actually sent a trailer for this title, which is what gates the
    // button below - see trailerVideoId.
    val trailerId = remember(details?.trailerUrl) { trailerVideoId(details?.trailerUrl) }
    val isTv = remember { context.isTvDevice() }
    val playFocusRequester = remember { FocusRequester() }
    LaunchedEffect(movie, isTv) {
        if (isTv) runCatching { playFocusRequester.requestFocus() }
    }

    if (landscape) {
        // TV has the full screen width to work with, so poster/actions/pills sit in a fixed-width
        // left column instead of stacked above a single scrolling column — the same content used
        // to run the full width of the screen in one long strip.
        Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.width(200.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 12.dp,
                    border = BorderStroke(2.dp, Color.White.copy(alpha = .18f))
                ) {
                    if (!poster.isNullOrBlank()) AsyncImage(poster, movie.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Button(onClick = onPlay, modifier = Modifier.fillMaxWidth().height(46.dp).focusRequester(playFocusRequester)) {
                    Icon(if (resumePosition > 0L) Icons.Default.Replay else Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(7.dp))
                    Text(if (resumePosition > 0L) stringResource(R.string.resume_time, formatPlaybackTime(resumePosition)) else stringResource(R.string.play_action))
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
                        Icon(
                            if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                            if (favorite) stringResource(R.string.cd_favorite_remove) else stringResource(R.string.cd_favorite_add),
                            tint = if (favorite) Orange else Cyan
                        )
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(displayTitle, fontSize = 24.sp, fontWeight = FontWeight.Black, lineHeight = 28.sp)
                if (!details?.originalTitle.isNullOrBlank() && details?.originalTitle != movie.name) {
                    Text(movie.name, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, maxLines = 2)
                }
                FlowRowPills {
                    rating?.let { MovieInfoPill("★ $it/10", Orange) }
                    year?.takeIf(String::isNotBlank)?.let { MovieInfoPill(it, Cyan) }
                    duration?.let { MovieInfoPill(it, BrandBlue) }
                    details?.genre?.takeIf(String::isNotBlank)?.let { MovieInfoPill(it, Cyan) }
                    MovieInfoPill(movie.group, BrandBlue)
                }
                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.loading_movie_info), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        description?.takeIf(String::isNotBlank) ?: stringResource(R.string.no_movie_details),
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    details?.cast?.takeIf(String::isNotBlank)?.let { MovieCreditRow(Icons.Default.Groups, stringResource(R.string.cast_label), it) }
                    details?.director?.takeIf(String::isNotBlank)?.let { MovieCreditRow(Icons.Default.MovieCreation, stringResource(R.string.director_label), it) }
                    if (detailsError != null && description.isNullOrBlank() && details?.cast.isNullOrBlank()) {
                        Text(stringResource(R.string.no_additional_info), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(22.dp))
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
                    AsyncImage(backdrop, movie.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .88f)))))
                Text(
                    displayTitle,
                    color = Color.White,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 2,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 132.dp, end = 14.dp, bottom = 16.dp)
                )
                Surface(
                    Modifier.align(Alignment.BottomStart).offset(x = 14.dp).width(104.dp).aspectRatio(2f / 3f),
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 12.dp,
                    border = BorderStroke(2.dp, Color.White.copy(alpha = .18f))
                ) {
                    if (!poster.isNullOrBlank()) AsyncImage(poster, movie.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
        Spacer(Modifier.height(2.dp))

        if (!details?.originalTitle.isNullOrBlank() && details?.originalTitle != movie.name) {
            Text(movie.name, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, maxLines = 2)
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            rating?.let { MovieInfoPill("★ $it/10", Orange) }
            year?.takeIf(String::isNotBlank)?.let { MovieInfoPill(it, Cyan) }
            duration?.let { MovieInfoPill(it, BrandBlue) }
            details?.genre?.takeIf(String::isNotBlank)?.let { MovieInfoPill(it, Cyan) }
            MovieInfoPill(movie.group, BrandBlue)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onPlay, modifier = Modifier.weight(1f).height(54.dp)) {
                Icon(if (resumePosition > 0L) Icons.Default.Replay else Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(7.dp))
                Text(if (resumePosition > 0L) stringResource(R.string.resume_time, formatPlaybackTime(resumePosition)) else stringResource(R.string.play_action))
            }
            AnimatedFilledTonalIconButton(onClick = onFavorite, modifier = Modifier.size(54.dp)) {
                Icon(
                    if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                    if (favorite) stringResource(R.string.cd_favorite_remove) else stringResource(R.string.cd_favorite_add),
                    tint = if (favorite) Orange else Cyan
                )
            }
        }
        trailerId?.let { id ->
            OutlinedButton(
                onClick = { openTrailer(context, id) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.SmartDisplay, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.watch_trailer))
            }
        }

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.loading_movie_info), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                description?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.no_movie_details),
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            details?.cast?.takeIf(String::isNotBlank)?.let { MovieCreditRow(Icons.Default.Groups, stringResource(R.string.cast_label), it) }
            details?.director?.takeIf(String::isNotBlank)?.let { MovieCreditRow(Icons.Default.MovieCreation, stringResource(R.string.director_label), it) }
            if (detailsError != null && description.isNullOrBlank() && details?.cast.isNullOrBlank()) {
                Text(stringResource(R.string.no_additional_info), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(22.dp))
    }
}

/** Wraps pills onto multiple lines instead of scrolling horizontally off-screen — meant for the
 *  narrow fixed-width left column of the landscape/TV details layout. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun FlowRowPills(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) { content() }
}

@Composable
private fun MovieInfoPill(text: String, accent: Color) {
    Surface(
        color = accent.copy(alpha = .14f), shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, accent.copy(alpha = .35f))
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
    }
}

@Composable
private fun MovieCreditRow(icon: ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = Cyan, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
        }
    }
}

private fun validMovieRating(value: String?): String? = value?.trim()?.takeIf {
    it.isNotBlank() && it != "0" && it != "0.0" && !it.equals("null", true)
}

private fun readableMovieDuration(value: String?): String? {
    val text = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    val seconds = text.toLongOrNull()
    if (seconds != null && seconds > 300L) {
        val minutes = seconds / 60L
        return if (minutes >= 60L) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }
    return text
}

private fun watchedFraction(movie: PlaylistItem, progress: Map<String, Long>): Float? {
    val positionMs = progress[channelKey(movie)]?.takeIf { it > 0L } ?: return null
    val durationMs = parseDurationToMillis(movie.duration)?.takeIf { it > 0L } ?: return null
    return (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

private val youtubeVideoIdPattern = Regex("(?:v=|youtu\\.be/|/embed/)([\\w-]{11})")

/** The YouTube video id the provider supplied for this title, or null when it sent none. Callers
 *  use it both to decide whether a Trailer button is worth showing and to open it — a button that
 *  only appears when there is a real video behind it can never land the viewer on a search page
 *  hunting for their own trailer. */
internal fun trailerVideoId(trailerUrl: String?): String? =
    trailerUrl?.let(youtubeVideoIdPattern::find)?.groupValues?.get(1)?.takeIf(String::isNotBlank)

/** Plays [videoId] in the YouTube app if it is installed, otherwise its web player. Opened with
 *  FLAG_ACTIVITY_NO_HISTORY so a single Back press returns straight here rather than stepping back
 *  through YouTube's own navigation first. */
internal fun openTrailer(context: android.content.Context, videoId: String) {
    val opened = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")).addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        )
        true
    }.getOrDefault(false)
    if (opened) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId")).addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        )
    }
}


@Composable
private fun ColumnScope.MovieEmptyState(message: String) {
    Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AccentIcon(Icons.Default.Movie, Orange)
        Spacer(Modifier.height(12.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatPlaybackTime(milliseconds: Long): String {
    val totalMinutes = milliseconds.coerceAtLeast(0L) / 60_000L
    return if (totalMinutes >= 60) "${totalMinutes / 60}h ${totalMinutes % 60}m" else "${totalMinutes}m"
}

private enum class LiveView { BROWSE, CATEGORY, PLAYER }

@Composable
private fun LiveTvScreen(
    playlist: LoadedPlaylist?,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    loadEpg: suspend (PlaylistItem) -> Result<EpgNowNext>,
    requirePin: (() -> Unit) -> Unit,
    resumeRequest: ResumeRequest? = null,
    onResumeHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    val parental = remember { context.getSharedPreferences("parental_settings", android.content.Context.MODE_PRIVATE) }
    var hiddenCategories by remember {
        mutableStateOf(parental.getStringSet("hidden_live_categories", emptySet()).orEmpty().toSet())
    }
    val lockedCategories = remember { parental.getStringSet("locked_live_categories", emptySet()).orEmpty().toSet() }
    val lockedChannelKeys = remember { parental.getStringSet("locked_channels", emptySet()).orEmpty().toSet() }
    val playback = remember { context.getSharedPreferences("playback_settings", android.content.Context.MODE_PRIVATE) }
    val channelSort = remember { playback.getString("live_channel_sort", "default") ?: "default" }
    val channels = remember(playlist, hiddenCategories, channelSort) {
        val filtered = playlist?.items?.filter { it.kind == MediaKind.LIVE && it.group !in hiddenCategories }.orEmpty()
        when (channelSort) {
            "az" -> filtered.sortedBy { it.name.lowercase() }
            "za" -> filtered.sortedByDescending { it.name.lowercase() }
            else -> filtered
        }
    }
    // Bumped after a long-press reorder so `categories` recomputes even though `channels` itself
    // (the applyCategoryOrder input) hasn't changed.
    var categoryOrderVersion by remember { mutableIntStateOf(0) }
    val categories = remember(channels, categoryOrderVersion) { applyCategoryOrder(context, MediaKind.LIVE, channels.map { it.group }.distinct()) }
    val recentlyWatched = "Recently watched"
    val favorites = "Favorites"
    var view by remember { mutableStateOf(LiveView.BROWSE) }
    var selectedCategory by remember(channels) { mutableStateOf(recentlyWatched) }
    var categoryQuery by remember { mutableStateOf("") }
    var channelQuery by remember { mutableStateOf("") }
    var showRecentInPlayer by remember { mutableStateOf(false) }
    var previewChannel by remember(channels) { mutableStateOf(channels.firstOrNull()) }
    // The preview auto-plays the playlist's first channel so the screen is not dead on arrival,
    // but that is the app's choice, not the user's. Until they actually pick something this
    // session, no row is marked as chosen - otherwise that arbitrary first channel shows up
    // highlighted at whatever position it happens to occupy in whichever category is open,
    // typically somewhere meaningless like the bottom of Recently watched.
    var hasChosenChannel by remember(channels) { mutableStateOf(false) }
    val store = remember { context.getSharedPreferences("favorite_channels", android.content.Context.MODE_PRIVATE) }
    var favoriteIds by remember { mutableStateOf(store.getStringSet("ids", emptySet()).orEmpty().toSet()) }
    var recentIds by remember {
        // v3 starts clean because v2 provider history used non-unique EPG IDs,
        // which could collapse many watched channels into one unrelated item.
        mutableStateOf(store.getString("recent_ids_v3", "").orEmpty().split('\u001F').filter(String::isNotBlank))
    }
    val channelByKey = remember(channels) { channels.associateBy(::channelKey) }
    // Hoisted above the landscape/portrait split so the fullscreen player is a single,
    // orientation-independent composable — rotating no longer tears down and rebuilds
    // the ExoPlayer instance (it used to live inside whichever branch was active).
    var immersiveFullscreen by remember { mutableStateOf(false) }
    // Distinguishes "Live TV just opened" (focus should land on the Recently watched category)
    // from "returning from fullscreen" (focus should land back on the channel that was playing) —
    // see the landscape branch below.
    var hasOpenedFullscreenOnce by remember { mutableStateOf(false) }
    // Guards against a stray reactivation landing on the channel row right as it regains focus
    // when exiting fullscreen (OK/Back/double-tap) - the row's own OK press enters fullscreen
    // directly on TV (see LandscapeLiveBrowser below), and that row is exactly what focus moves
    // back onto, so a leftover key-up event from the very button that just closed fullscreen can
    // otherwise be read as a fresh press on it, reopening fullscreen a moment later.
    var lastFullscreenExitAt by remember { mutableLongStateOf(0L) }
    fun exitFullscreen() {
        lastFullscreenExitAt = android.os.SystemClock.uptimeMillis()
        immersiveFullscreen = false
    }
    fun enterFullscreen() {
        if (android.os.SystemClock.uptimeMillis() - lastFullscreenExitAt < 400L) return
        hasOpenedFullscreenOnce = true
        immersiveFullscreen = true
    }
    LaunchedEffect(resumeRequest, channelByKey) {
        val request = resumeRequest ?: return@LaunchedEffect
        channelByKey[request.itemKey]?.let { channel ->
            previewChannel = channel
            hasChosenChannel = true
            view = LiveView.PLAYER
            if (request.autoPlay) enterFullscreen()
        }
        onResumeHandled()
    }
    val recentChannels = remember(channelByKey, recentIds) { recentIds.mapNotNull(channelByKey::get) }
    val favoriteChannels = remember(channels, favoriteIds) { channels.filter { channelKey(it) in favoriteIds } }
    val serverCategories = remember(categories, categoryQuery) {
        if (categoryQuery.isBlank()) categories
        else categories.filter { it.contains(categoryQuery.trim(), ignoreCase = true) }
    }
    val browseSections = remember(serverCategories, categoryQuery, recentChannels, favoriteChannels, channels) {
        buildList {
            if (categoryQuery.isBlank()) {
                add(recentlyWatched to recentChannels)
                add(favorites to favoriteChannels)
            }
            serverCategories.forEach { group -> add(group to channels.filter { it.group == group }) }
        }
    }
    val selectedChannels = remember(selectedCategory, recentChannels, favoriteChannels, channels) {
        when (selectedCategory) {
            recentlyWatched -> recentChannels
            favorites -> favoriteChannels
            else -> channels.filter { it.group == selectedCategory }
        }
    }
    val searchedChannels = remember(selectedChannels, channels, channelQuery) {
        if (channelQuery.isBlank()) selectedChannels
        else channels.filter { it.name.contains(channelQuery.trim(), ignoreCase = true) }
    }

    fun rememberChannelUnchecked(channel: PlaylistItem) {
        previewChannel = channel
        hasChosenChannel = true
        val key = channelKey(channel)
        val updated = (listOf(key) + recentIds.filterNot { it == key }).take(20)
        recentIds = updated
        store.edit().putString("recent_ids_v3", updated.joinToString("\u001F")).apply()
    }
    fun rememberChannel(channel: PlaylistItem) {
        if (channelKey(channel) in lockedChannelKeys) requirePin { rememberChannelUnchecked(channel) }
        else rememberChannelUnchecked(channel)
    }
    fun toggleFavorite(channel: PlaylistItem) {
        val key = channelKey(channel)
        val updated = if (key in favoriteIds) favoriteIds - key else favoriteIds + key
        favoriteIds = updated
        store.edit().putStringSet("ids", updated).apply()
    }

    LaunchedEffect(view, selectedCategory) {
        if (view == LiveView.CATEGORY) {
            // Entering a category previews its first channel, but does not add
            // it to history until the user deliberately selects a channel.
            previewChannel = selectedChannels.firstOrNull()
        }
    }

    fun goBack() {
        when (view) {
            LiveView.BROWSE -> onBack()
            LiveView.CATEGORY -> { channelQuery = ""; view = LiveView.BROWSE }
            LiveView.PLAYER -> view = LiveView.BROWSE
        }
    }
    BackHandler(onBack = ::goBack)

    PremiumBackground {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            if (landscape) {
                // A single LiveChannelPreview instance (and its one ExoPlayer) is shared between
                // the embedded background preview and the fullscreen view — toggling fullscreen
                // used to swap in a second, independent LiveChannelPreview/ExoPlayer, which
                // re-buffered from zero and showed a black "cutout" flash on every transition.
                DisposableEffect(immersiveFullscreen) {
                    PictureInPictureCoordinator.eligible = immersiveFullscreen
                    PictureInPictureCoordinator.aspectRatio = 16f / 9f
                    onDispose { if (immersiveFullscreen) PictureInPictureCoordinator.eligible = false }
                }
                // Live TV's TV fullscreen has no on-screen controls (see PlaybackOptionsOverlay in
                // LiveChannelPreview), so Back always exits it directly — no "hide controls first"
                // stage needed the way movies/series playback has.
                if (immersiveFullscreen) BackHandler { exitFullscreen() }
                // Mirrors selectedChannels below: when browsing Recently watched/Favorites,
                // Up/Down in fullscreen must cycle through that same list, not the channel's own
                // (real) category - which is what filtering by previewChannel.group would do.
                val fullscreenChannelList = remember(channels, previewChannel, selectedCategory, recentChannels, favoriteChannels) {
                    when (selectedCategory) {
                        recentlyWatched -> recentChannels
                        favorites -> favoriteChannels
                        else -> channels.filter { it.group == (previewChannel?.group ?: selectedCategory) }
                    }
                }
                // Recently watched reorders itself (the just-watched channel jumps to the front)
                // every time switchChannel() records a channel via rememberChannel() - left live,
                // Up/Down would renavigate against a list that just reshuffled under it, looping
                // between the same two channels. Snapshotting it once on entry and holding that
                // snapshot for the whole fullscreen session avoids that.
                var lockedFullscreenChannelList by remember { mutableStateOf<List<PlaylistItem>?>(null) }
                LaunchedEffect(immersiveFullscreen) {
                    lockedFullscreenChannelList = if (immersiveFullscreen) fullscreenChannelList else null
                }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    LiveChannelPreview(
                        channel = previewChannel,
                        modifier = Modifier.fillMaxSize(),
                        channelList = if (immersiveFullscreen) (lockedFullscreenChannelList ?: fullscreenChannelList) else selectedChannels,
                        onChannelChange = { rememberChannel(it) },
                        autoAdvanceOnFailure = true,
                        hostedFullscreen = immersiveFullscreen,
                        onFullscreenDoubleTap = if (immersiveFullscreen) (::exitFullscreen) else null,
                        onRequestFullscreen = if (!immersiveFullscreen) (::enterFullscreen) else null,
                        onExitFullscreen = if (immersiveFullscreen) (::exitFullscreen) else null,
                        loadEpg = loadEpg
                    )
                    if (!immersiveFullscreen) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .36f)))
                        LandscapeLiveBrowser(
                            categories = buildList {
                                add(recentlyWatched)
                                add(favorites)
                                addAll(categories)
                            },
                            selectedCategory = selectedCategory,
                            channels = selectedChannels,
                            allChannels = channels,
                            selectedChannel = previewChannel,
                            channelChosen = hasChosenChannel,
                            favoriteIds = favoriteIds,
                            returningFromFullscreen = hasOpenedFullscreenOnce,
                            onCategory = { category ->
                                fun enter() {
                                    selectedCategory = category
                                    channelQuery = ""
                                    // Opening a category is a deliberate pick, so the channel it
                                    // switches the preview to is worth marking as chosen.
                                    hasChosenChannel = true
                                    previewChannel = when (category) {
                                        recentlyWatched -> recentChannels.firstOrNull()
                                        favorites -> favoriteChannels.firstOrNull()
                                        else -> channels.firstOrNull { it.group == category }
                                    } ?: previewChannel
                                }
                                if (category in lockedCategories) requirePin(::enter) else enter()
                            },
                            onChannel = { rememberChannel(it) },
                            onChannelFullscreen = { channel -> rememberChannel(channel); enterFullscreen() },
                            onFavorite = ::toggleFavorite,
                            onCategoriesReordered = { categoryOrderVersion++ },
                            onBack = onBack,
                            loadEpg = loadEpg
                        )
                    }
                }
                return@BoxWithConstraints
            }
            val sidePadding = 18.dp
            Column(
                Modifier.fillMaxSize().padding(horizontal = sidePadding, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LiveHeader(
                    title = when (view) {
                        LiveView.BROWSE -> stringResource(R.string.nav_live_tv)
                        LiveView.CATEGORY -> localizedSectionTitle(selectedCategory)
                        LiveView.PLAYER -> previewChannel?.name ?: stringResource(R.string.nav_live_tv)
                    },
                    subtitle = if (view == LiveView.BROWSE) stringResource(R.string.live_tv_channels_count, channels.size) else null,
                    onBack = ::goBack
                )

                when (view) {
                    LiveView.BROWSE -> {
                        LiveChannelPreview(
                            channel = previewChannel,
                            modifier = if (landscape) Modifier.fillMaxWidth().height(150.dp)
                            else Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                            channelList = channels,
                            onChannelChange = { rememberChannel(it) },
                            autoAdvanceOnFailure = previewChannel == channels.firstOrNull(),
                            onRequestFullscreen = { immersiveFullscreen = true },
                            showFullscreenButton = true
                        )
                        NowNextLine(rememberEpgNowNext(previewChannel, loadEpg), Modifier.fillMaxWidth())
                        SearchField(categoryQuery, { categoryQuery = it }, stringResource(R.string.search_categories))
                        if (browseSections.isEmpty()) {
                            EmptyLiveState(if (categoryQuery.isBlank()) stringResource(R.string.no_live_categories) else stringResource(R.string.no_categories_match))
                        } else {
                            LazyColumn(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                                contentPadding = PaddingValues(bottom = 18.dp)
                            ) {
                                items(browseSections) { (title, sectionChannels) ->
                                    ChannelCategorySection(
                                        title = title,
                                        channels = sectionChannels,
                                        favoriteIds = favoriteIds,
                                        onSeeAll = {
                                            fun enter() { selectedCategory = title; channelQuery = ""; view = LiveView.CATEGORY }
                                            if (title in lockedCategories) requirePin(::enter) else enter()
                                        },
                                        onHide = if (title in setOf(recentlyWatched, favorites)) null else {{
                                            val updated = hiddenCategories + title
                                            hiddenCategories = updated
                                            parental.edit().putStringSet("hidden_live_categories", updated).apply()
                                        }},
                                        onChannel = {
                                            selectedCategory = it.group
                                            showRecentInPlayer = false
                                            rememberChannel(it)
                                            view = LiveView.PLAYER
                                        },
                                        onFavorite = ::toggleFavorite
                                    )
                                }
                            }
                        }
                    }
                    LiveView.CATEGORY -> {
                        if (selectedCategory !in setOf(recentlyWatched, favorites)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = {
                                    hiddenCategories = hiddenCategories + selectedCategory
                                    parental.edit().putStringSet("hidden_live_categories", hiddenCategories).apply()
                                    channelQuery = ""
                                    view = LiveView.BROWSE
                                }) {
                                    Icon(Icons.Default.VisibilityOff, null)
                                    Spacer(Modifier.width(5.dp))
                                    Text(stringResource(R.string.hide_category_action))
                                }
                            }
                        }
                        LiveChannelPreview(
                            channel = previewChannel,
                            modifier = if (landscape) Modifier.fillMaxWidth().height(150.dp)
                            else Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                            channelList = searchedChannels,
                            onChannelChange = { rememberChannel(it) },
                            autoAdvanceOnFailure = previewChannel == searchedChannels.firstOrNull(),
                            onRequestFullscreen = { immersiveFullscreen = true },
                            showFullscreenButton = true
                        )
                        NowNextLine(rememberEpgNowNext(previewChannel, loadEpg), Modifier.fillMaxWidth())
                        SearchField(channelQuery, { channelQuery = it }, stringResource(R.string.search_channels))
                        if (searchedChannels.isEmpty()) {
                            EmptyLiveState(stringResource(R.string.no_channels_match))
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(if (landscape) 5 else 3),
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(9.dp),
                                verticalArrangement = Arrangement.spacedBy(13.dp),
                                contentPadding = PaddingValues(bottom = 18.dp)
                            ) {
                                gridItems(searchedChannels) { channel ->
                                    ChannelPoster(
                                        channel = channel,
                                        favorite = channelKey(channel) in favoriteIds,
                                        onFavorite = { toggleFavorite(channel) },
                                        onClick = {
                                            rememberChannel(channel)
                                            showRecentInPlayer = false
                                            view = LiveView.PLAYER
                                        }
                                    )
                                }
                            }
                        }
                    }
                    LiveView.PLAYER -> {
                        val playerChannels = if (showRecentInPlayer) recentChannels else channels.filter {
                            it.group == (previewChannel?.group ?: selectedCategory)
                        }
                        LiveChannelPreview(
                            channel = previewChannel,
                            channelList = playerChannels,
                            onChannelChange = { rememberChannel(it) },
                            hostedFullscreen = false,
                            onFullscreenDoubleTap = { view = LiveView.BROWSE },
                            onRequestFullscreen = { immersiveFullscreen = true },
                            showFullscreenButton = true,
                            externalPlayback = true,
                            modifier = if (landscape) Modifier.fillMaxWidth().height(230.dp)
                            else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        )
                        NowNextLine(rememberEpgNowNext(previewChannel, loadEpg), Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !showRecentInPlayer,
                                onClick = { showRecentInPlayer = false },
                                label = { Text(previewChannel?.group ?: localizedSectionTitle(selectedCategory), maxLines = 1) },
                                leadingIcon = { Icon(Icons.Default.Category, null, Modifier.size(17.dp)) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = showRecentInPlayer,
                                onClick = { showRecentInPlayer = true },
                                label = { Text(stringResource(R.string.section_recently_watched), maxLines = 1) },
                                leadingIcon = { Icon(Icons.Default.History, null, Modifier.size(17.dp)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        LazyColumn(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 18.dp)
                        ) {
                            items(playerChannels) { channel ->
                                CompactChannelRow(
                                    channel = channel,
                                    selected = channel == previewChannel,
                                    favorite = channelKey(channel) in favoriteIds,
                                    onFavorite = { toggleFavorite(channel) },
                                    onClick = { rememberChannel(channel) },
                                    loadEpg = loadEpg
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveHeader(title: String, subtitle: String?, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AnimatedIconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.cd_back)) }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 22.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Black
            )
            if (subtitle != null) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = Cyan.copy(alpha = .13f),
            border = BorderStroke(1.dp, Cyan.copy(alpha = .28f))
        ) {
            Row(Modifier.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(Color(0xFFFF3B4F)))
                Spacer(Modifier.width(7.dp))
                Text(stringResource(R.string.home_live_badge), color = Cyan, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** On TV, D-pad-focusing a search field must not pop the keyboard on its own — only an explicit
 *  OK press should, otherwise the keyboard covers the screen every time focus merely passes over
 *  the field while browsing. Touch devices keep the plain always-editable field, where tapping to
 *  focus and tapping to type are the same gesture anyway. */
@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    val context = LocalContext.current
    val isTv = remember { context.isTvDevice() }
    if (!isTv) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(15.dp),
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (value.isNotEmpty()) AnimatedIconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Default.Close, stringResource(R.string.cd_clear_search))
                }
            },
            placeholder = { Text(placeholder) }
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
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                .onFocusChanged {
                    // The field's own requestFocus() below hasn't run yet on the very first
                    // callback after activating (Compose reports the pre-request unfocused
                    // state first) - collapsing back to the summary view on THAT would undo
                    // activation before the user ever got a chance to type, which read as "OK
                    // does nothing" on the search bar. Only a real loss of focus (after it was
                    // actually gained once) should deactivate it.
                    if (it.isFocused) hasFocusedOnce = true
                    else if (hasFocusedOnce) active = false
                },
            singleLine = true,
            shape = RoundedCornerShape(15.dp),
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (value.isNotEmpty()) AnimatedIconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Default.Close, stringResource(R.string.cd_clear_search))
                }
            },
            placeholder = { Text(placeholder) },
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
                Text(value.ifBlank { placeholder }, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** Same tap-to-activate-before-typing behaviour as [SearchField], for Live TV's dark-on-black
 *  category/channel search fields, which use their own white-on-black colour scheme instead of
 *  the app's default Material field styling. */
@Composable
private fun DarkTvSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp
) {
    val context = LocalContext.current
    val isTv = remember { context.isTvDevice() }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
        focusedBorderColor = Cyan, unfocusedBorderColor = Color.White.copy(alpha = .3f),
        cursorColor = Cyan
    )
    if (!isTv) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = Color.White.copy(alpha = .55f), fontSize = fontSize) },
            singleLine = true,
            shape = RoundedCornerShape(15.dp),
            colors = colors,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = fontSize),
            modifier = modifier
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
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = Color.White.copy(alpha = .55f), fontSize = fontSize) },
            singleLine = true,
            shape = RoundedCornerShape(15.dp),
            colors = colors,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = fontSize),
            modifier = modifier.focusRequester(focusRequester).onFocusChanged {
                // See SearchField's identical guard: the pre-requestFocus() unfocused callback
                // must not be treated as "the user navigated away," or activating never sticks.
                if (it.isFocused) hasFocusedOnce = true
                else if (hasFocusedOnce) active = false
            },
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); active = false })
        )
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    } else {
        Surface(
            modifier = modifier.focusableClickable(cornerRadius = 15.dp) { hasFocusedOnce = false; active = true },
            shape = RoundedCornerShape(15.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, Color.White.copy(alpha = .3f))
        ) {
            Text(
                value.ifBlank { placeholder },
                color = Color.White.copy(alpha = if (value.isBlank()) .55f else 1f),
                fontSize = fontSize,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp)
            )
        }
    }
}

@Composable
private fun ChannelCategorySection(
    title: String,
    channels: List<PlaylistItem>,
    favoriteIds: Set<String>,
    onSeeAll: () -> Unit,
    onHide: (() -> Unit)?,
    onChannel: (PlaylistItem) -> Unit,
    onFavorite: (PlaylistItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(localizedSectionTitle(title), Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
            onHide?.let {
                AnimatedIconButton(onClick = it, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.VisibilityOff, stringResource(R.string.cd_hide_category, localizedSectionTitle(title)), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = onSeeAll) {
                Text(stringResource(R.string.action_see_all), color = Cyan)
                Icon(Icons.Default.ChevronRight, null, tint = Cyan, modifier = Modifier.size(18.dp))
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (channels.isEmpty()) {
                item {
                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .7f), shape = RoundedCornerShape(12.dp)) {
                        Text(stringResource(R.string.no_channels_yet), color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp))
                    }
                }
            } else {
                items(channels.take(12)) { channel ->
                    ChannelPoster(
                        channel = channel,
                        favorite = channelKey(channel) in favoriteIds,
                        onFavorite = { onFavorite(channel) },
                        onClick = { onChannel(channel) },
                        modifier = Modifier.width(118.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelPoster(
    channel: PlaylistItem,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)) {
        Surface(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = .08f))
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.LiveTv, null, tint = Cyan.copy(alpha = .55f), modifier = Modifier.size(36.dp))
                if (!channel.logoUrl.isNullOrBlank()) {
                    AsyncImage(channel.logoUrl, null, Modifier.fillMaxSize().padding(7.dp), contentScale = ContentScale.Fit)
                }
                AnimatedIconButton(
                    onClick = onFavorite,
                    modifier = Modifier.align(Alignment.TopEnd).size(34.dp).background(Color.Black.copy(alpha = .42f), RoundedCornerShape(10.dp))
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
        Text(channel.name, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2)
    }
}

@Composable
private fun CategoryRailItem(label: String, icon: ImageVector?, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        color = if (selected) BrandBlue.copy(alpha = .9f) else MaterialTheme.colorScheme.surface.copy(alpha = .82f),
        border = BorderStroke(1.dp, if (selected) Cyan.copy(alpha = .55f) else Color.White.copy(alpha = .08f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(16.dp), tint = if (selected) Color.White else Cyan)
                Spacer(Modifier.width(6.dp))
            }
            Text(label, maxLines = 2, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        }
    }
}

@Composable
private fun CompactChannelRow(
    channel: PlaylistItem,
    selected: Boolean,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onClick: () -> Unit,
    loadEpg: (suspend (PlaylistItem) -> Result<EpgNowNext>)? = null
) {
    val nowNext = if (loadEpg != null) rememberEpgNowNext(channel, loadEpg) else null
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        color = if (selected) BrandBlue.copy(alpha = .34f) else MaterialTheme.colorScheme.surface.copy(alpha = .9f),
        border = BorderStroke(1.dp, if (selected) Cyan.copy(alpha = .55f) else Color.Transparent),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.LiveTv, null, tint = Cyan.copy(alpha = .65f), modifier = Modifier.size(22.dp))
                if (!channel.logoUrl.isNullOrBlank()) {
                    AsyncImage(channel.logoUrl, null, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(channel.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2)
                Text(channel.group, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1)
                nowNext?.now?.let { program ->
                    Text(
                        stringResource(R.string.epg_now_format, program.title),
                        color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1
                    )
                }
            }
            AnimatedIconButton(onClick = onFavorite, modifier = Modifier.size(34.dp)) {
                Icon(
                    if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                    if (favorite) stringResource(R.string.cd_favorite_remove) else stringResource(R.string.cd_favorite_add),
                    tint = if (favorite) Orange else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

internal fun channelKey(channel: PlaylistItem): String = channel.channelId ?: "${channel.group}:${channel.name}"

private const val CATEGORY_ORDER_PREFS = "category_order_settings"

private fun categoryOrderPrefsKey(kind: MediaKind): String = when (kind) {
    MediaKind.LIVE -> "order_live"
    MediaKind.MOVIE -> "order_movie"
    MediaKind.SERIES -> "order_series"
}

/** Applies a user-customized category order (persisted as a delimited list of names, moved via
 *  long-press on a category row) on top of whatever categories are actually present - saved
 *  names come first in their saved order, anything new or not yet ordered keeps its original
 *  position at the end. */
internal fun applyCategoryOrder(context: Context, kind: MediaKind, categories: List<String>): List<String> {
    val saved = context.getSharedPreferences(CATEGORY_ORDER_PREFS, Context.MODE_PRIVATE)
        .getString(categoryOrderPrefsKey(kind), "").orEmpty()
        .split('').filter(String::isNotBlank)
    if (saved.isEmpty()) return categories
    val remaining = categories.toMutableList()
    val ordered = saved.mapNotNull { name -> if (remaining.remove(name)) name else null }
    return ordered + remaining
}

/** Swaps [category] with its neighbor in the current order and persists the result. */
internal fun moveCategory(context: Context, kind: MediaKind, categories: List<String>, category: String, up: Boolean) {
    val current = applyCategoryOrder(context, kind, categories).toMutableList()
    val index = current.indexOf(category)
    if (index < 0) return
    val swapWith = if (up) index - 1 else index + 1
    if (swapWith !in current.indices) return
    val moved = current[swapWith]
    current[swapWith] = current[index]
    current[index] = moved
    context.getSharedPreferences(CATEGORY_ORDER_PREFS, Context.MODE_PRIVATE).edit()
        .putString(categoryOrderPrefsKey(kind), current.joinToString(""))
        .apply()
}

/** True on an actual Android TV / Fire TV / set-top box, false on phones and tablets — including
 *  a phone in landscape, which reuses the exact same UI but must never auto-focus anything (a
 *  cyan ring appearing on launch with no D-pad to explain it would just look like a UI bug). Used
 *  to gate the one-time initial focus request on Home, since a remote user needs an obvious
 *  starting point but a touch user does not. */
/** Android TV standardises on roughly 960dp of width whatever the panel is - 1080p at 320dpi, 720p
 *  at tvdpi and 4K at 640dpi all land there - and the fixed navigation columns in the browsers are
 *  sized for it. Boxes that misreport their density arrive far narrower, where those same columns
 *  would leave the content beside them a sliver. Below this width the columns shrink so the content
 *  keeps a usable share; above it nothing changes. */
internal val COMPACT_TV_WIDTH = 820.dp

/** Poster width the media grids aim for. Column count is derived from the space actually available
 *  rather than fixed, so a narrow box gets fewer, readable posters instead of a row of slivers and
 *  a very wide one does not get twenty. The target is chosen so a standard 960dp TV still lands on
 *  the seven columns the grids have always shown. */
private val TARGET_POSTER_WIDTH = 90.dp

/** Columns for a media grid across [availableWidth], clamped so neither extreme degenerates. */
internal fun posterGridColumns(availableWidth: Dp, landscape: Boolean): Int =
    if (!landscape) 3 else (availableWidth / TARGET_POSTER_WIDTH).toInt().coerceIn(3, 9)

internal fun Context.isTvDevice(): Boolean {
    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
    if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    // Branded TVs and Google-certified boxes set the UI mode above, but plenty of cheap AOSP-based
    // boxes leave it at "normal" - and every remote-driven behaviour in this app hangs off this one
    // answer, so getting it wrong there leaves a viewer with no focus indicator and no way to
    // navigate. Leanback is Google's documented TV signal, and a device with no touchscreen at all
    // can only be driven by a remote whatever it calls itself.
    val features = packageManager ?: return false
    return features.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
        features.hasSystemFeature("android.hardware.type.television") ||
        !features.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
}

/** Asks for focus across the next few frames instead of once. A [FocusRequester] only works after
 *  the node it is attached to has been composed and laid out; asking in the same frame the owning
 *  screen appears is routinely too early, and a single failed attempt leaves the screen with
 *  nothing focused — which on a remote reads as the app ignoring the first button presses. */
internal suspend fun requestFocusWithRetry(requester: FocusRequester, attempts: Int = 6): Boolean {
    repeat(attempts) {
        if (runCatching { requester.requestFocus() }.isSuccess) return true
        withFrameNanos {}
    }
    return false
}

/** Puts a lazy list back exactly where the user left it: scrolls [index] into view, then focuses
 *  it. The scroll has to come first — an item a lazy list has scrolled past is not composed at all,
 *  so a focus requester pointing at it belongs to no node and silently does nothing. Pass a null
 *  [focusRequester] to restore the scroll position only, as on a touch device where an unexplained
 *  focus ring would just look like a glitch. */
internal suspend fun restoreListPosition(
    index: Int,
    scrollToItem: suspend (Int) -> Unit,
    focusRequester: FocusRequester?
): Boolean {
    if (index < 0) return false
    runCatching { scrollToItem(index) }
    if (focusRequester == null) return true
    return requestFocusWithRetry(focusRequester)
}

/** Looks up (and caches) the now/next programme for [channel], gated behind [EpgStore]'s shared
 *  semaphore so scrolling a long channel list can't fire dozens of EPG requests at once. Returns
 *  null silently for M3U playlists, channels without an id, or providers with no EPG data —
 *  callers simply render nothing in that case rather than an error. */
@Composable
internal fun rememberEpgNowNext(channel: PlaylistItem?, loadEpg: suspend (PlaylistItem) -> Result<EpgNowNext>): EpgNowNext? {
    val key = channel?.channelId?.takeIf(String::isNotBlank)
    var state by remember(key) { mutableStateOf(key?.let(EpgStore::get)) }
    if (channel != null && key != null) {
        LaunchedEffect(key) {
            if (state != null) return@LaunchedEffect
            EpgStore.gate.withPermit {
                // Another card for the same channel may have populated the cache while this one
                // waited for a permit — re-check before spending a network call.
                EpgStore.get(key)?.let { state = it; return@withPermit }
                loadEpg(channel).getOrNull()?.let { result ->
                    EpgStore.put(key, result)
                    state = result
                }
            }
        }
    }
    return state
}

/** Renders "Now: <title>" with a live elapsed-time progress bar, and "Next: <title>" beneath it,
 *  from whichever of the two are available. Draws nothing if both are null, so callers can use it
 *  unconditionally without an extra visibility check. */
@Composable
private fun NowNextLine(
    nowNext: EpgNowNext?,
    modifier: Modifier = Modifier,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    nextColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleFontSize: androidx.compose.ui.unit.TextUnit = 12.sp,
    nextFontSize: androidx.compose.ui.unit.TextUnit = 10.sp,
    // Set where this sits directly on video with no panel behind it, so the text stays readable
    // against a bright frame.
    textShadow: androidx.compose.ui.graphics.Shadow? = null
) {
    val now = nowNext?.now
    val next = nowNext?.next
    if (now == null && next == null) return
    val shadowStyle = androidx.compose.ui.text.TextStyle(shadow = textShadow)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        now?.let { program ->
            Text(
                stringResource(R.string.epg_now_format, program.title),
                color = titleColor, fontSize = titleFontSize, fontWeight = FontWeight.SemiBold, maxLines = 1,
                style = shadowStyle
            )
            val nowEpoch = System.currentTimeMillis() / 1000
            val total = (program.endEpochSeconds - program.startEpochSeconds).coerceAtLeast(1)
            val elapsed = (nowEpoch - program.startEpochSeconds).coerceIn(0, total)
            LinearProgressIndicator(
                progress = { elapsed.toFloat() / total.toFloat() },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = Cyan,
                trackColor = nextColor.copy(alpha = .25f)
            )
        }
        next?.let { program ->
            Text(
                stringResource(R.string.epg_next_format, program.title),
                color = nextColor, fontSize = nextFontSize, maxLines = 1,
                style = shadowStyle
            )
        }
    }
}

/** The three built-in shelf titles ("Continue watching", "Recently watched", "Favorites") double
 *  as internal lookup keys (category-hiding, section filtering) throughout Movies/Series/Live TV,
 *  so those keys stay in English everywhere in the code. This translates ONLY what gets rendered,
 *  at the point it's rendered — a real provider category name falls through [title] unchanged. */
@Composable
internal fun localizedSectionTitle(title: String): String = when (title) {
    "Continue watching" -> stringResource(R.string.section_continue_watching)
    "Recently watched" -> stringResource(R.string.section_recently_watched)
    "Favorites" -> stringResource(R.string.section_favorites)
    else -> title
}

@Composable
private fun ChannelRow(channel: PlaylistItem, favorite: Boolean, onFavorite: () -> Unit, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .96f)),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 64.dp, height = 52.dp).clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.LiveTv, null, tint = Cyan.copy(alpha = .7f))
                if (!channel.logoUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(6.dp)
                    )
                }
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(channel.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text(channel.group, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            AnimatedIconButton(onClick = onFavorite) {
                Icon(
                    if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                    if (favorite) "Remove favorite" else "Add favorite",
                    tint = if (favorite) Orange else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ColumnScope.EmptyLiveState(message: String) {
    Column(
        Modifier.fillMaxWidth().weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AccentIcon(Icons.Default.LiveTv, Cyan)
        Spacer(Modifier.height(12.dp))
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ContinueCard(item: PlaylistItem?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val onScrim = if (dark) Color.White else Color(0xFF0B1B2E)
    // Callers size this: portrait gives it a fixed height (it sits in a verticalScroll, where an
    // unbounded max height would make fillMaxSize() on the scrim resolve to zero), landscape
    // gives it the leftover column height.
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = .10f), RoundedCornerShape(16.dp))
            .then(pressFeedback(onClick))
    ) {
        if (!item?.logoUrl.isNullOrBlank()) {
            AsyncImage(
                model = item?.logoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(DeepBlue, BrandBlue, Cyan.copy(alpha = .8f)))
                )
            )
        }
        // Scrim runs left-to-right so the text side stays legible while the artwork still reads.
        // Channel artwork often has its own baked-in text, so the reading side stays nearly opaque.
        val scrim = if (dark) Color(0xFF030912) else Color(0xFFEEF6FD)
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to scrim.copy(alpha = .78f),
                    .52f to scrim.copy(alpha = .68f),
                    .78f to scrim.copy(alpha = .32f),
                    1f to scrim.copy(alpha = .08f)
                )
            )
        )
        Row(
            Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    stringResource(R.string.continue_watching_title),
                    color = onScrim.copy(alpha = .72f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    item?.name ?: stringResource(R.string.continue_watching_placeholder_title),
                    color = onScrim,
                    fontWeight = FontWeight.Black,
                    fontSize = 19.sp,
                    maxLines = 1
                )
                Text(
                    item?.group ?: stringResource(R.string.continue_watching_placeholder_subtitle),
                    color = onScrim.copy(alpha = .66f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(if (dark) Color.White.copy(alpha = .14f) else Color.White)
                    .border(if (dark) 2.dp else 0.dp, if (dark) Color.White.copy(alpha = .92f) else Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    null,
                    tint = if (dark) Color.White else Color(0xFF0B1B2E),
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@Composable
private fun GlobalSearchScreen(
    playlist: LoadedPlaylist?,
    onBack: () -> Unit,
    onSelect: (PlaylistItem) -> Unit
) {
    BackHandler(onBack = onBack)
    var query by remember { mutableStateOf("") }
    val results = remember(playlist, query) {
        if (query.isBlank()) emptyList()
        else playlist?.items?.filter { it.name.contains(query.trim(), ignoreCase = true) }?.take(200).orEmpty()
    }
    val live = remember(results) { results.filter { it.kind == MediaKind.LIVE } }
    val movies = remember(results) { results.filter { it.kind == MediaKind.MOVIE } }
    val series = remember(results) { results.filter { it.kind == MediaKind.SERIES } }

    PremiumBackground {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedIconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.cd_back)) }
                Text(stringResource(R.string.search_title), fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(15.dp),
                modifier = Modifier.fillMaxWidth()
            )
            when {
                query.isBlank() -> Text(
                    stringResource(R.string.search_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                results.isEmpty() -> Text(
                    stringResource(R.string.search_no_results, query),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (live.isNotEmpty()) {
                        item { Text(stringResource(R.string.nav_live_tv), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall) }
                        items(live, key = { "live_" + channelKey(it) }) { SearchResultRow(it, onSelect) }
                    }
                    if (movies.isNotEmpty()) {
                        item { Text(stringResource(R.string.nav_movies), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall) }
                        items(movies, key = { "movie_" + channelKey(it) }) { SearchResultRow(it, onSelect) }
                    }
                    if (series.isNotEmpty()) {
                        item { Text(stringResource(R.string.nav_series), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall) }
                        items(series, key = { "series_" + channelKey(it) }) { SearchResultRow(it, onSelect) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(item: PlaylistItem, onSelect: (PlaylistItem) -> Unit) {
    Surface(
        onClick = { onSelect(item) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .7f)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = .08f)),
                contentAlignment = Alignment.Center
            ) {
                if (!item.logoUrl.isNullOrBlank()) {
                    AsyncImage(item.logoUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(Icons.Default.PlayCircle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(item.group, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private class TilePalette(
    val container: Brush,
    val border: Color,
    val icon: Color,
    val title: Color,
    val subtitle: Color
)

private fun tilePalette(kind: TileKind, isDark: Boolean): TilePalette = when (kind) {
    TileKind.LIVE -> if (isDark) TilePalette(
        Brush.linearGradient(listOf(Color(0xFF1D5FD8), Color(0xFF0E3A8F))),
        Color(0xFF78B4FF).copy(alpha = .32f), Color(0xFF9FD2FF), Color.White, Color(0xFFAFCDF2)
    ) else TilePalette(
        Brush.linearGradient(listOf(Color(0xFFE9F3FF), Color(0xFFD6E8FF))),
        Color(0xFFBDD9FB), BrandBlue, Color(0xFF0B1B2E), Color(0xFF5B7186)
    )
    TileKind.MOVIES -> if (isDark) TilePalette(
        Brush.linearGradient(listOf(Color(0xFFA2661F), Color(0xFF5E3A11))),
        Color(0xFFFFBA64).copy(alpha = .32f), Color(0xFFFFBB55), Color.White, Color(0xFFEFC795)
    ) else TilePalette(
        Brush.linearGradient(listOf(Color(0xFFFFF4E4), Color(0xFFFFE7C7))),
        Color(0xFFFBD7A6), Color(0xFFEE861A), Color(0xFF0B1B2E), Color(0xFF5B7186)
    )
    TileKind.SERIES -> if (isDark) TilePalette(
        Brush.linearGradient(listOf(Color(0xFF11596F), Color(0xFF0A3143))),
        Color(0xFF46CDF0).copy(alpha = .30f), Color(0xFF4FD8F5), Color.White, Color(0xFF9AD5E8)
    ) else TilePalette(
        Brush.linearGradient(listOf(Color(0xFFE4F6FD), Color(0xFFCDEDF9))),
        Color(0xFFA7DDF1), Color(0xFF1284C4), Color(0xFF0B1B2E), Color(0xFF5B7186)
    )
}

@Composable
private fun HomeTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    kind: TileKind,
    isDark: Boolean,
    modifier: Modifier,
    height: Dp? = 132.dp,
    onClick: () -> Unit
) {
    val palette = remember(kind, isDark) { tilePalette(kind, isDark) }
    Box(
        modifier
            .then(if (height != null) Modifier.height(height) else Modifier)
            .clip(RoundedCornerShape(18.dp))
            .background(palette.container)
            .border(1.dp, palette.border, RoundedCornerShape(18.dp))
            .then(pressFeedback(onClick))
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, null, tint = palette.icon, modifier = Modifier.size(40.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = palette.title, maxLines = 1)
                Text(subtitle, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = palette.subtitle, maxLines = 1)
            }
        }
    }
}

/** Like [Modifier.clickable]/[Modifier.combinedClickable], but also paints a steady focus ring
 *  while a D-pad/keyboard moves focus onto it. Regular touch users never see it (nothing gains
 *  focus from a tap), but it's the only way a remote-control user can tell which poster, row, or
 *  list item will be selected next — Android TV has no cursor or hover state to fall back on. */
/** Cyan focus ring with a dark contrast halo drawn just behind it, so the ring stays readable
 *  over any background colour — a plain cyan stroke can disappear against bright or similarly-toned
 *  poster art. Shared by every D-pad-focusable rectangle in the app; see [drawFocusRing] for the
 *  circular icon-button equivalent. */
private fun DrawScope.drawContrastRoundRect(alpha: Float, cornerRadius: Dp, strokeWidth: Dp = 3.dp) {
    if (alpha <= 0f) return
    val stroke = strokeWidth.toPx()
    val inset = stroke / 2f
    val topLeft = Offset(inset, inset)
    val boxSize = Size(size.width - stroke, size.height - stroke)
    val radius = CornerRadius(cornerRadius.toPx())
    drawRoundRect(color = Color.Black.copy(alpha = alpha * .8f), topLeft = topLeft, size = boxSize, cornerRadius = radius, style = Stroke(width = stroke + 2.5.dp.toPx()))
    drawRoundRect(color = Cyan.copy(alpha = alpha), topLeft = topLeft, size = boxSize, cornerRadius = radius, style = Stroke(width = stroke))
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.focusableClickable(
    cornerRadius: Dp = 12.dp,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusAlpha by animateFloatAsState(if (focused) 1f else 0f, tween(150), label = "focusRing")
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, tween(150), label = "focusScale")
    // combinedClickable's own onLongClick is driven by detectTapGestures, which only recognizes a
    // held *touch* pointer - a remote's OK/DPad-center button held down never triggers it. This
    // times the key hold itself (via onPreviewKeyEvent, so it sees the key before the clickable
    // node's own Enter-key click handling does) and calls onLongClick when it's held past the
    // threshold, consuming the key-up so a plain onClick doesn't also fire right after.
    var keyDownAt by remember { mutableLongStateOf(0L) }
    return this
        .graphicsLayer(scaleX = scale, scaleY = scale)
        .drawWithContent {
            drawContent()
            drawContrastRoundRect(focusAlpha, cornerRadius)
        }
        .then(
            if (onLongClick != null) Modifier.onPreviewKeyEvent { event ->
                if (event.key != Key.DirectionCenter && event.key != Key.Enter && event.key != Key.NumPadEnter) {
                    return@onPreviewKeyEvent false
                }
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (keyDownAt == 0L) keyDownAt = System.currentTimeMillis()
                        false
                    }
                    KeyEventType.KeyUp -> {
                        val downAt = keyDownAt
                        keyDownAt = 0L
                        if (downAt != 0L && System.currentTimeMillis() - downAt >= 500L) {
                            onLongClick()
                            true
                        } else false
                    }
                    else -> false
                }
            } else Modifier
        )
        .combinedClickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            onLongClick = onLongClick,
            onDoubleClick = onDoubleClick,
            onClick = onClick
        )
}

/** While [active] (this row is the one currently being hand-moved after a long-press), Up/Down
 *  move it one place at a time via [onMove] and OK/Enter ends reorder mode via [onExit] - both
 *  consumed here so they don't also shift d-pad focus to a sibling row or fire the row's own
 *  onClick right after confirming. Must sit before [focusableClickable] in the modifier chain so
 *  onPreviewKeyEvent (capture phase, top-down) sees these keys before the clickable node's own
 *  Enter-key handling does. */
internal fun Modifier.categoryReorderKeys(active: Boolean, onMove: (up: Boolean) -> Unit, onExit: () -> Unit): Modifier {
    if (!active) return this
    return this.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionUp -> { onMove(true); true }
            Key.DirectionDown -> { onMove(false); true }
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onExit(); true }
            else -> false
        }
    }
}

@Composable
private fun pressFeedback(onClick: () -> Unit, cornerRadius: Dp = 16.dp): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (pressed) .975f else if (focused) 1.03f else 1f, tween(120), label = "press")
    // Every card this is applied to clips to its own, differently-rounded shape, but a D-pad user
    // still needs SOME visible sign of which card gets selected next — a close-enough rounded
    // outline overlaid on top reads clearly as "this one" even when it doesn't hug the exact corner.
    val focusAlpha by animateFloatAsState(if (focused) 1f else 0f, tween(150), label = "cardFocus")
    val haptic = LocalHapticFeedback.current
    return Modifier
        .graphicsLayer(scaleX = scale, scaleY = scale)
        .drawWithContent {
            drawContent()
            drawContrastRoundRect(focusAlpha, cornerRadius)
        }
        .clickable(interactionSource = interaction, indication = LocalIndication.current) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        }
}

/** Scale to shrink to while a tap is held, shared by every icon-button flavor below so the
 *  whole app's icons squeeze by the same amount on press. */
private const val ICON_PRESS_SCALE = .8f

/** Duration of the ring burst that fires on every icon tap, in milliseconds. */
private const val ICON_BURST_DURATION_MS = 500

/** Paints the brand-cyan "pulse" that [rememberIconBurst] drives: a soft filled disc plus a
 *  brighter ring riding its leading edge, both expanding from the button's center and fading out
 *  together — a small signature flourish rather than a generic ripple. Drawn on the *outside* of
 *  the press-scale [graphicsLayer] (see call sites) so it expands past the icon's own bounds
 *  instead of shrinking along with it. */
private fun DrawScope.drawIconBurst(progress: Float) {
    if (progress <= 0f || progress >= 1f) return
    val fade = 1f - progress
    val radius = size.minDimension * .95f * progress
    drawCircle(color = Cyan.copy(alpha = fade * .30f), radius = radius, center = center)
    drawCircle(color = Cyan.copy(alpha = fade * .85f), radius = radius, center = center, style = Stroke(width = 1.6.dp.toPx()))
}

/** Steady cyan ring shown for as long as a D-pad/keyboard moves focus onto this icon — there is
 *  no cursor or hover state on Android TV, so this is the only way a remote user can see which of
 *  several icons will fire on the next "select" press. Invisible (alpha driven to 0) on touch
 *  devices, where nothing ever gains keyboard/D-pad focus in the first place. */
private fun DrawScope.drawFocusRing(alpha: Float) {
    if (alpha <= 0f) return
    val radius = size.minDimension * .66f
    drawCircle(color = Cyan.copy(alpha = alpha * .16f), radius = radius, center = center)
    // Dark halo behind the bright ring so it stays visible over light icons/backgrounds too.
    drawCircle(color = Color.Black.copy(alpha = alpha * .8f), radius = radius, center = center, style = Stroke(width = 4.4.dp.toPx()))
    drawCircle(color = Cyan.copy(alpha = alpha * .95f), radius = radius, center = center, style = Stroke(width = 2.6.dp.toPx()))
}

/** Drives the expanding-ring tap animation: an [Animatable] restarted from 0 on every [fire]
 *  call, eased out to 1 over [ICON_BURST_DURATION_MS]. Shared by every icon-button flavor below. */
@Composable
private fun rememberIconBurst(): Pair<Animatable<Float, *>, () -> Unit> {
    val burst = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val fire: () -> Unit = {
        scope.launch {
            burst.snapTo(0f)
            burst.animateTo(1f, tween(ICON_BURST_DURATION_MS, easing = FastOutSlowInEasing))
        }
    }
    return burst to fire
}

/**
 * A masked text field with a reveal toggle. Every password in this app is typed on a remote, one
 * character at a time through an on-screen keyboard, which is exactly the situation where a
 * mistyped character is both most likely and least visible - so the value can be checked rather
 * than retyped from scratch. Starts masked, and the toggle is an ordinary focusable button so a
 * D-pad can reach it.
 */
@Composable
internal fun RevealablePasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (revealed) {
            androidx.compose.ui.text.input.VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            AnimatedIconButton(onClick = { revealed = !revealed }) {
                Icon(
                    if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    stringResource(if (revealed) R.string.hide_password else R.string.show_password)
                )
            }
        },
        modifier = modifier
    )
}

/** Drop-in replacement for Material3's [IconButton]: squeezes the icon down on press, springing
 *  back on release like [pressFeedback] does for the home tile cards, and fires an expanding
 *  cyan ring on every tap for a livelier, more "branded" touch reaction than a plain ripple.
 *  Signature matches [IconButton] exactly so existing call sites work unchanged. */
@Composable
internal fun AnimatedIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) {
    val actualInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by actualInteractionSource.collectIsPressedAsState()
    val focused by actualInteractionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (pressed) ICON_PRESS_SCALE else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "iconPress")
    val focusAlpha by animateFloatAsState(if (focused) 1f else 0f, tween(150), label = "iconFocus")
    val (burst, fireBurst) = rememberIconBurst()
    IconButton(
        onClick = { fireBurst(); onClick() },
        modifier = modifier
            .drawBehind { drawFocusRing(focusAlpha); drawIconBurst(burst.value) }
            .graphicsLayer(scaleX = scale, scaleY = scale),
        enabled = enabled,
        colors = colors,
        interactionSource = actualInteractionSource,
        content = content
    )
}

/** [FilledIconButton] counterpart of [AnimatedIconButton]. */
@Composable
internal fun AnimatedFilledIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = IconButtonDefaults.filledShape,
    colors: IconButtonColors = IconButtonDefaults.filledIconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) {
    val actualInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by actualInteractionSource.collectIsPressedAsState()
    val focused by actualInteractionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (pressed) ICON_PRESS_SCALE else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "iconPress")
    val focusAlpha by animateFloatAsState(if (focused) 1f else 0f, tween(150), label = "iconFocus")
    val (burst, fireBurst) = rememberIconBurst()
    FilledIconButton(
        onClick = { fireBurst(); onClick() },
        modifier = modifier
            .drawBehind { drawFocusRing(focusAlpha); drawIconBurst(burst.value) }
            .graphicsLayer(scaleX = scale, scaleY = scale),
        enabled = enabled,
        shape = shape,
        colors = colors,
        interactionSource = actualInteractionSource,
        content = content
    )
}

/** [FilledTonalIconButton] counterpart of [AnimatedIconButton]. */
@Composable
internal fun AnimatedFilledTonalIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = IconButtonDefaults.filledShape,
    colors: IconButtonColors = IconButtonDefaults.filledTonalIconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) {
    val actualInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by actualInteractionSource.collectIsPressedAsState()
    val focused by actualInteractionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (pressed) ICON_PRESS_SCALE else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "iconPress")
    val focusAlpha by animateFloatAsState(if (focused) 1f else 0f, tween(150), label = "iconFocus")
    val (burst, fireBurst) = rememberIconBurst()
    FilledTonalIconButton(
        onClick = { fireBurst(); onClick() },
        modifier = modifier
            .drawBehind { drawFocusRing(focusAlpha); drawIconBurst(burst.value) }
            .graphicsLayer(scaleX = scale, scaleY = scale),
        enabled = enabled,
        shape = shape,
        colors = colors,
        interactionSource = actualInteractionSource,
        content = content
    )
}
