package com.fourkplus.tvplayer

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * A trailer played inside the app, over the film's or series' page.
 *
 * Handing it to the YouTube app, as the app used to, left Back to YouTube: it steps back through
 * its own screens before it gives the viewer back, and on some boxes lands on YouTube's home page
 * instead. Here the trailer is a layer over the page, and one press of Back takes it away. This is
 * the Samsung app's trailer player (web/src/ui/trailerPlayer.ts) brought across, and it plays the
 * same page: YouTube's embedded player is the only way its videos may be shown in another app, and
 * it will only play in a page with a web address of its own, so the player lives on a page the
 * activation server serves (server/trailerPage.js). That page is given the YouTube id and nothing
 * else - no login, no stream, nothing about the viewer.
 *
 * The WebView never takes focus. The remote's keys stay here: OK pauses and plays, Left and Right
 * seek ten seconds, Back closes. The page reports what the player is doing, and takes commands, by
 * postMessage; loaded as a page of its own rather than in a frame, its messages go to its own
 * window, where a listener added after it loads hands them to [Bridge].
 *
 * If the trailer will not play here - YouTube refuses it, or nothing has started after a while,
 * which is also what a server still waking from sleep looks like - the viewer is told, and OK opens
 * it in the YouTube app instead, as before.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun TrailerPlayer(videoId: String, title: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var started by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    // The title is up whenever the trailer is not playing, and steps out of the way two seconds
    // after it starts or resumes - the web player's `fading` class. OK brings it back for a moment.
    var playing by remember { mutableStateOf(false) }
    var titleTick by remember { mutableStateOf(0) }
    var titleVisible by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val focusRequester = remember { FocusRequester() }

    fun send(action: String, seconds: Int = 0) {
        webView?.evaluateJavascript(
            "window.postMessage({target:'fourkplus-trailer',action:'$action',seconds:$seconds},'*')",
            null
        )
    }

    // Read at the moment of closing: the page's messages arrive through a bridge built once.
    val currentOnClose by rememberUpdatedState(onClose)
    fun close() {
        currentOnClose()
    }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        delay(TRAILER_START_TIMEOUT_MS)
        if (!started) failed = true
    }
    LaunchedEffect(playing, titleTick) {
        titleVisible = true
        if (playing) {
            delay(2_000)
            titleVisible = false
        }
    }
    val titleAlpha by animateFloatAsState(if (titleVisible) 1f else 0f, tween(600), label = "trailerTitle")

    val bridge = remember {
        Bridge { type, state, position ->
            when {
                type == "error" -> if (!started) failed = true
                type == "state" && state == "playing" -> { started = true; failed = false; playing = true }
                type == "state" && state == "paused" -> playing = false
                type == "state" && state == "ended" -> close()
                // The listener is added once the page has loaded, and the player may already have
                // said "playing" by then. The clock moving says the same thing.
                type == "time" && position > 0.0 -> if (!started) { started = true; failed = false; playing = true }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Emptied first, so the video stops at once rather than when the view is collected.
            webView?.apply {
                loadUrl("about:blank")
                removeJavascriptInterface(BRIDGE_NAME)
                destroy()
            }
            webView = null
        }
    }

    Dialog(
        onDismissRequest = ::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent event.key != Key.Back
                    when (event.key) {
                        Key.Back -> false // the Dialog's own dismissal closes it
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
                        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                            if (failed) {
                                close()
                                openTrailer(context, videoId)
                            } else {
                                send("toggle")
                                titleTick++
                            }
                            true
                        }
                        Key.DirectionLeft, Key.MediaRewind -> { send("seek", -TRAILER_SEEK_SECONDS); true }
                        Key.DirectionRight, Key.MediaFastForward -> { send("seek", TRAILER_SEEK_SECONDS); true }
                        Key.MediaStop -> { close(); true }
                        // Nothing else reaches the page underneath while the trailer is up.
                        else -> true
                    }
                }
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        isFocusable = false
                        isFocusableInTouchMode = false
                        setBackgroundColor(AndroidColor.BLACK)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        webChromeClient = WebChromeClient()
                        addJavascriptInterface(bridge, BRIDGE_NAME)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                view.evaluateJavascript(
                                    "window.addEventListener('message',function(e){var d=e.data;" +
                                        "if(d&&d.source==='fourkplus-trailer'){try{$BRIDGE_NAME.post(JSON.stringify(d))}catch(x){}}});",
                                    null
                                )
                            }
                        }
                        loadUrl("$TRAILER_PAGE?v=${android.net.Uri.encode(videoId)}")
                        webView = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            Text(
                "${stringResource(R.string.trailer_label)} · $title",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                style = TextStyle(shadow = TrailerTextShadow),
                modifier = Modifier.align(Alignment.TopStart).padding(start = 40.dp, top = 24.dp).alpha(titleAlpha)
            )
            if (!started || failed) {
                Text(
                    stringResource(if (failed) R.string.trailer_failed_here else R.string.trailer_loading),
                    color = Color.White,
                    fontSize = 17.sp,
                    textAlign = TextAlign.Center,
                    style = TextStyle(shadow = TrailerTextShadow),
                    modifier = Modifier.align(Alignment.Center).widthIn(max = 600.dp)
                        .then(
                            if (failed) {
                                Modifier.background(Color.Black.copy(alpha = .75f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 24.dp, vertical = 16.dp)
                            } else Modifier
                        )
                )
            }
        }
    }
}

/** Receives the trailer page's messages on the WebView's own thread and hands them to the UI thread. */
private class Bridge(private val onMessage: (type: String, state: String?, position: Double) -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun post(json: String) {
        val data = runCatching { JSONObject(json) }.getOrNull() ?: return
        val type = data.optString("type")
        val state = data.optString("state").takeIf(String::isNotEmpty)
        val position = data.optDouble("position", 0.0)
        main.post { onMessage(type, state, position) }
    }
}

private const val BRIDGE_NAME = "FourKTrailer"

/** The activation server's player page - the same one the Samsung app frames. */
private const val TRAILER_PAGE = "https://fourk-plus-tv-player.onrender.com/trailer"

/** How long to wait for the trailer to start before offering the YouTube app. */
private const val TRAILER_START_TIMEOUT_MS = 25_000L

private const val TRAILER_SEEK_SECONDS = 10

private val TrailerTextShadow = Shadow(Color.Black.copy(alpha = .9f), Offset(0f, 2f), 8f)
