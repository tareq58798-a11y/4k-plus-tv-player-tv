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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.fourkplus.tvplayer.BuildConfig
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.fourkplus.tvplayer.R
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

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
    internal var requestedModel by mutableStateOf<String?>(null)
        private set

    /**
     * Whether the background follows what the viewer is looking at.
     *
     * False is Classic: the app's own artwork stays up everywhere and nothing replaces it. Some
     * people want the room to hold still - a picture that changes every time the highlight moves
     * is the single busiest thing on screen, and on a television that is across the room from you
     * it is movement in the corner of your eye all evening.
     *
     * Gated here rather than at the callers. Every page asks for artwork in the ordinary way and
     * this decides whether the request is honoured, so Classic cannot be defeated by a screen that
     * forgot to check.
     */
    var followsFocus by mutableStateOf(true)

    /**
     * Ask for [model] (a URL) to become the background. A blank or null [model] is a no-op: an item
     * with no artwork keeps whatever is already there instead of dropping the viewer onto the
     * default image, which would read as a flash every time an untagged title is focused.
     */
    fun show(key: String?, model: String?) {
        if (!followsFocus) return
        if (model.isNullOrBlank() || key.isNullOrBlank()) return
        if (model == requestedModel) return
        requestedModel = model
    }

    /**
     * Asks for the app's own default artwork back, fading whatever is up back out to it.
     *
     * Home uses this: it is where the app starts over, so it shows the app's own picture rather
     * than whichever title the viewer was last looking at somewhere else. A reset immediately
     * followed by a real request is collapsed by the same debounce every other change goes through,
     * so arriving somewhere that puts a picture up straight away never flashes the default first.
     */
    fun reset() {
        requestedModel = null
    }
}

@Composable
fun rememberBackdropState(): BackdropState = remember { BackdropState() }

/**
 * The app-wide background: the default artwork at the bottom, the settled image over it, and the
 * incoming image fading in over both.
 *
 * The settled layer is a [Painter] kept from the last image that finished loading - not a second
 * request for the same URL. Nothing ever asks Coil to re-fetch what is already on screen, so the
 * visible image cannot blink while a new one arrives. The incoming layer only becomes visible once
 * Coil reports it fully decoded, and a failed load simply never fades in, leaving the previous
 * artwork exactly where it was.
 *
 * The handover at the end of a fade is ordered so that every intermediate frame shows the new
 * image, from one layer or the other or both: the settled painter is replaced first, and only then
 * is the incoming layer released.
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

    var settled by remember { mutableStateOf<Painter?>(null) }
    var settledModel by remember { mutableStateOf<String?>(null) }
    var incomingModel by remember { mutableStateOf<String?>(null) }
    val fade = remember { Animatable(0f) }
    // Fades the settled layer back out when the app asks for its own artwork again, so returning to
    // the default looks like every other change of background rather than a cut.
    val settledFade = remember { Animatable(1f) }

    val context = LocalContext.current
    // Backdrops are decoded at up to 4K rather than at the size of the view they land in. Coil
    // sizes a request to its composable by default, which on a 1080p panel throws away every pixel
    // beyond 1920 wide before the image is ever drawn - so a 4K still and a 1080p one ended up
    // identical on screen, and identical again on a 4K panel where the difference would show.
    //
    // It never invents detail: this is a ceiling, not a target. A source smaller than 4K is decoded
    // whole, at its own resolution, because that is all there is to decode.
    val incomingRequest = remember(incomingModel) {
        incomingModel?.let {
            ImageRequest.Builder(context)
                .data(it)
                .size(BackdropWidthPx, BackdropHeightPx)
                .scale(Scale.FILL)
                .precision(Precision.INEXACT)
                .build()
        }
    }
    val incomingPainter = rememberAsyncImagePainter(model = incomingRequest, contentScale = ContentScale.Crop)

    // Rapid D-pad movement produces a request per item. Debouncing means only the item the viewer
    // actually settles on costs an image load, while the ones they scrolled past cost nothing.
    LaunchedEffect(state) {
        snapshotFlow { state.requestedModel }
            .distinctUntilChanged()
            .debounce(Motion.BackdropDebounceMs)
            .collect { model ->
                // A null model is a request for the app's own artwork back - see [BackdropState.
                // reset]. The settled layer fades out and the default drawn underneath is what is
                // left. Note this arrives through the same debounce as everything else, so a reset
                // immediately followed by a real request never shows: the viewer goes straight to
                // the new picture instead of flashing through the default on the way.
                if (model == null) {
                    if (settled != null) {
                        incomingModel = null
                        if (reducedMotion) {
                            settledFade.snapTo(0f)
                        } else {
                            settledFade.animateTo(0f, Motion.backdrop())
                        }
                        settled = null
                        settledModel = null
                        settledFade.snapTo(1f)
                    }
                    return@collect
                }
                // Already showing it, or already loading it: starting the transition again would
                // fade the picture out and back into itself for no reason.
                if (model == settledModel || model == incomingModel) return@collect
                fade.snapTo(0f)
                incomingModel = model
            }
    }

    // One pass per requested image: wait for that image to resolve, fade it up, hand it over.
    // Keyed on the model alone, so the effect cannot be restarted by the handover it performs -
    // an earlier version keyed it on derived state and re-ran on its own completion, which made
    // the background oscillate between the last two images forever.
    LaunchedEffect(incomingModel) {
        val requested = incomingModel ?: return@LaunchedEffect
        val result = snapshotFlow { incomingPainter.state }
            .first { it is AsyncImagePainter.State.Success || it is AsyncImagePainter.State.Error }
        if (result !is AsyncImagePainter.State.Success) {
            // Not an error the viewer should see: the artwork simply never appears.
            incomingModel = null
            return@LaunchedEffect
        }
        if (reducedMotion) fade.snapTo(1f) else fade.animateTo(1f, Motion.backdrop())
        // Order matters. The settled layer takes the finished image first, so the frame in which
        // the incoming layer disappears is already showing that same image underneath.
        settled = result.painter
        settledModel = requested
        incomingModel = null
        fade.snapTo(0f)
    }

    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Tone.PageTop, Tone.PageBottom)))) {
        // Always present underneath, so the screen is never empty even before any artwork loads.
        //
        // The artwork is 16:9, which is a television exactly and nothing like a phone held
        // sideways - a 20:9 handset is far wider for its height, so filling the width leaves the
        // picture taller than the screen and Crop takes the difference off both ends. Centred,
        // half of that came off the top, which is where the astronaut's helmet is. Aligning to the
        // top instead spends the whole overlap at the bottom, on empty ground, and brings the head
        // back down into view. The television is 16:9 itself, has no overlap to place, and keeps
        // the centring it has always had.
        Image(
            painter = painterResource(R.drawable.bg_app_default),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = if (BuildConfig.TOUCH_BUILD) Alignment.TopCenter else Alignment.Center,
            modifier = Modifier.fillMaxSize()
        )
        settled?.let { current ->
            Image(
                current,
                null,
                Modifier.fillMaxSize().alpha(settledFade.value),
                contentScale = ContentScale.Crop
            )
        }
        if (incomingModel != null) {
            Image(
                incomingPainter,
                null,
                Modifier.fillMaxSize().alpha(fade.value),
                contentScale = ContentScale.Crop
            )
        }
        Box(Modifier.fillMaxSize().background(Tone.sideScrim()))
        Box(Modifier.fillMaxSize().background(scrim))
        content()
    }
}

/** The ceiling a backdrop is decoded at. Sources below it are decoded whole; nothing is upscaled. */
private const val BackdropWidthPx = 3840
private const val BackdropHeightPx = 2160

/**
 * Warms Coil's cache with artwork the viewer is about to reach, so a deliberate move along a row
 * shows its background immediately instead of after a round trip. Fire-and-forget: failures are
 * irrelevant, and the requests are plain cache fills with no UI attached.
 *
 * Sized to match the backdrop's own request, so the cached entry is the one the backdrop will ask
 * for. A preload at a different size fills the cache with a bitmap the backdrop then ignores.
 */
@Composable
fun PreloadBackdrops(urls: List<String>) {
    val context = LocalContext.current
    LaunchedEffect(urls) {
        urls.filter { it.isNotBlank() }.take(12).forEach { url ->
            runCatching {
                context.imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(url)
                        .size(BackdropWidthPx, BackdropHeightPx)
                        .scale(Scale.FILL)
                        .precision(Precision.INEXACT)
                        .build()
                )
            }
        }
    }
}

/** A flat scrim for screens that sit on top of the backdrop without their own artwork. */
val PanelScrim: Brush = Brush.verticalGradient(listOf(Color(0xD90A1120), Color(0xF2050A14)))
