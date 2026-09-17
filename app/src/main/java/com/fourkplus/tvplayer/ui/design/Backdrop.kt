package com.fourkplus.tvplayer.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.imageLoader
import coil.request.ImageRequest
import com.fourkplus.tvplayer.R
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * What the app is currently asking the cinematic background to show.
 *
 * Only movies and series drive the background. Channels, Search, Settings and Language deliberately
 * leave it alone - they call nothing, and whatever artwork was last shown stays up. That is what
 * makes moving through the app feel like one continuous room rather than a slideshow.
 */
@Stable
class BackdropState {
    /** The artwork the most recently focused eligible item wants shown. */
    internal var requestedKey by mutableStateOf<String?>(null)
        private set
    internal var requestedModel by mutableStateOf<Any?>(null)
        private set

    /**
     * Ask for [model] (a URL) to become the background. A blank or null [model] is a no-op: an item
     * with no artwork keeps whatever is already there instead of dropping the viewer onto the
     * default image, which would read as a flash every time an untagged title is focused.
     */
    fun show(key: String?, model: String?) {
        if (model.isNullOrBlank() || key.isNullOrBlank()) return
        if (key == requestedKey) return
        requestedKey = key
        requestedModel = model
    }

    /** Explicitly returns to the app's own default artwork (used when a session has no content yet). */
    fun reset() {
        requestedKey = null
        requestedModel = null
    }
}

@Composable
fun rememberBackdropState(): BackdropState = remember { BackdropState() }

/**
 * The app-wide background: the default artwork at the bottom, two crossfading artwork layers above
 * it, and a gradient scrim above those.
 *
 * The two layers ping-pong rather than one being overwritten. The layer showing the current image
 * is never touched while the next one loads, so there is no moment where the old image has been
 * released and the new one has not arrived - no black frame, no placeholder, no flash back to the
 * default. The incoming layer only becomes visible once Coil reports it fully decoded, and a failed
 * load simply never fades in, leaving the previous artwork in place.
 */
@OptIn(FlowPreview::class)
@Composable
fun CinematicBackdrop(
    state: BackdropState,
    modifier: Modifier = Modifier,
    scrim: Brush = Tone.pageScrim(),
    content: @Composable BoxScope.() -> Unit
) {
    val reducedMotion = LocalReducedMotion.current

    // Two artwork slots. `frontIsA` says which one is currently the visible image; the other is
    // where the next request is loaded. They swap on every successful crossfade.
    var modelA by remember { mutableStateOf<Any?>(null) }
    var modelB by remember { mutableStateOf<Any?>(null) }
    var frontIsA by remember { mutableStateOf(true) }
    var committedKey by remember { mutableStateOf<String?>(null) }
    val fade = remember { Animatable(0f) }

    val painterA = rememberAsyncImagePainter(model = modelA, contentScale = ContentScale.Crop)
    val painterB = rememberAsyncImagePainter(model = modelB, contentScale = ContentScale.Crop)

    // Rapid D-pad movement produces a request per item. Debouncing means only the item the viewer
    // actually settles on costs an image load, while the ones they scrolled past cost nothing.
    LaunchedEffect(state) {
        snapshotFlow { state.requestedKey to state.requestedModel }
            .distinctUntilChanged()
            .debounce(Motion.BackdropDebounceMs)
            .collect { (key, model) ->
                if (model == null || key == null || key == committedKey) return@collect
                // Load into whichever slot is currently hidden.
                if (frontIsA) modelB = model else modelA = model
                committedKey = key
            }
    }

    // Fade the hidden slot in once - and only once - its image is fully decoded.
    val incomingPainter = if (frontIsA) painterB else painterA
    val incomingModel = if (frontIsA) modelB else modelA
    LaunchedEffect(incomingModel, incomingPainter) {
        if (incomingModel == null) return@LaunchedEffect
        snapshotFlow { incomingPainter.state }
            .collect { painterState ->
                when (painterState) {
                    is AsyncImagePainter.State.Success -> {
                        fade.snapTo(0f)
                        if (reducedMotion) fade.snapTo(1f)
                        else fade.animateTo(1f, Motion.backdrop())
                        frontIsA = !frontIsA
                        fade.snapTo(0f)
                    }
                    // A failed request is not an error the viewer should see: the artwork simply
                    // never appears and the background they already had stays exactly as it is.
                    is AsyncImagePainter.State.Error -> if (frontIsA) modelB = null else modelA = null
                    else -> Unit
                }
            }
    }

    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Tone.PageTop, Tone.PageBottom)))) {
        // Always present underneath, so the screen is never empty even before any artwork loads.
        Image(
            painter = painterResource(R.drawable.bg_app_default),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // Draw the settled image first, then the incoming one over it as it fades up.
        val backPainter = if (frontIsA) painterB else painterA
        val frontPainter = if (frontIsA) painterA else painterB
        val backModel = if (frontIsA) modelB else modelA
        val frontModel = if (frontIsA) modelA else modelB
        if (frontModel != null) {
            Image(frontPainter, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (backModel != null) {
            Image(backPainter, null, Modifier.fillMaxSize().alpha(fade.value), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Tone.sideScrim()))
        Box(Modifier.fillMaxSize().background(scrim))
        content()
    }
}

/**
 * Warms Coil's cache with artwork the viewer is about to reach, so a deliberate move along a row
 * shows its background immediately instead of after a round trip. Fire-and-forget: failures are
 * irrelevant, and the requests are plain cache fills with no UI attached.
 */
@Composable
fun PreloadBackdrops(urls: List<String>) {
    val context = LocalContext.current
    LaunchedEffect(urls) {
        urls.filter { it.isNotBlank() }.take(6).forEach { url ->
            runCatching {
                context.imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
            }
        }
    }
}

/** A flat scrim for screens that sit on top of the backdrop without their own artwork. */
val PanelScrim: Brush = Brush.verticalGradient(listOf(Color(0xD90A1120), Color(0xF2050A14)))
