package com.fourkplus.tvplayer.ui.design

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The single source of truth for the cinematic redesign: colours, spacing, radii and motion.
 *
 * Every screen reads from here rather than hard-coding its own values, so the whole app can be
 * re-tuned from one place and no two pages can drift apart. Nothing in this file knows about
 * playlists, playback or navigation - it is presentation only.
 */
object Tone {
    /** Page base. The backdrop is drawn over this, so it only shows through where artwork does not. */
    val PageTop = Color(0xFF050B16)
    val PageBottom = Color(0xFF01030A)

    /** Focus and action accent, matching the cyan used throughout the mockups. */
    val Accent = Color(0xFF22D3EE)
    val AccentSoft = Color(0xFF38BDF8)
    val AccentDeep = Color(0xFF0E7BD8)

    /** Primary action buttons (Resume / Watch Live) run this gradient left to right. */
    val ActionGradient = listOf(Color(0xFF29C5F6), Color(0xFF1E88E5))

    /** Selected category pill. */
    val CategoryGradient = listOf(Color(0xFF4FD8FF), Color(0xFF1E88E5))

    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFC3D2E4)
    val TextMuted = Color(0xFF8CA0B8)
    val Star = Color(0xFFFFB547)
    val LiveRed = Color(0xFFE23434)

    /** Translucent "glass" surfaces: panels, rows, unfocused buttons. */
    val Glass = Color(0x14FFFFFF)
    val GlassStrong = Color(0x24FFFFFF)
    val GlassBorder = Color(0x1FFFFFFF)
    val GlassBorderFocused = Accent

    /**
     * The backdrop is real artwork, often bright, and every page writes white text over it. Two
     * scrims do that job together: this one darkens top and bottom, and [sideScrim] darkens the
     * left, where the titles, metadata and buttons live. The right stays comparatively clear, so
     * the artwork is still plainly visible rather than dimmed into a texture.
     */
    fun pageScrim(): Brush = Brush.verticalGradient(
        0f to Color(0xD90A1424),
        0.30f to Color(0x99060D1A),
        0.72f to Color(0xD9030711),
        1f to Color(0xFA01030A)
    )

    fun sideScrim(): Brush = Brush.horizontalGradient(
        0f to Color(0xF2030711),
        0.38f to Color(0xC2040A16),
        0.72f to Color(0x66020610),
        1f to Color(0x1A000000)
    )

    /** Darkens the bottom of a poster so its overlaid title/progress stays readable. */
    fun cardScrim(): Brush = Brush.verticalGradient(
        0f to Color(0x00000000),
        0.55f to Color(0x66000000),
        1f to Color(0xD9000000)
    )
}

/** Spacing and sizing measured for a 10-foot viewing distance. */
object Dims {
    /** TV-safe horizontal/vertical margins - nothing important is drawn outside these. */
    val SafeHorizontal: Dp = 40.dp
    val SafeVertical: Dp = 22.dp

    val GapXs: Dp = 4.dp
    val GapS: Dp = 8.dp
    val GapM: Dp = 16.dp
    val GapL: Dp = 24.dp
    val GapXl: Dp = 36.dp

    val RadiusCard: Dp = 10.dp
    val RadiusPanel: Dp = 18.dp
    val RadiusPill: Dp = 28.dp

    /**
     * Breathing room reserved inside every artwork card so the focus animation can grow into it.
     * Because the card grows into its own padding rather than past its bounds, a focused poster is
     * never clipped by the row that holds it and never pushes its neighbours around.
     */
    val CardBleed: Dp = 8.dp

    /**
     * Landscape artwork cards (16:9), sized so a row shows roughly five at once on a 1080p panel
     * rather than three. A row you can see the shape of is worth more than a row of larger
     * pictures you have to scroll to discover.
     */
    val CardWidth: Dp = 172.dp
    val CardWidthCompact: Dp = 144.dp
}

/**
 * Motion timings. Durations are deliberately short: on a TV the remote is the only input, and an
 * animation that outlasts the next key press is felt as lag rather than polish.
 */
object Motion {
    val FocusMs = 180
    val PosterScaleMs = 220
    val InfoFadeMs = 200
    val PageMs = 260
    val BackdropMs = 300

    /** A page arriving, and each band of content arriving within it. */
    val EnterMs = 240

    /** How far apart consecutive rows begin their entrance. Enough to read as a sequence, short
     *  enough that the last row is not still arriving when the viewer reaches for the remote. */
    val StaggerMs = 70

    /** Artwork fading up once decoded, instead of appearing between one frame and the next. */
    val ImageFadeMs = 240

    /**
     * How long a poster must hold focus before its artwork is committed to the background. Holding
     * D-pad down through twenty items should cost one image request, not twenty.
     */
    val BackdropDebounceMs = 180L

    /** Scale a focused artwork card grows to. Kept inside [Dims.CardBleed]. */
    const val PosterFocusScale = 1.06f

    val EaseOut: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EaseInOut: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    fun <T> focus() = tween<T>(FocusMs, easing = EaseOut)
    fun <T> poster() = tween<T>(PosterScaleMs, easing = EaseOut)
    fun <T> info() = tween<T>(InfoFadeMs, easing = EaseInOut)
    fun <T> page() = tween<T>(PageMs, easing = EaseOut)
    fun <T> backdrop() = tween<T>(BackdropMs, easing = EaseInOut)
}

/**
 * True when the viewer has turned animations off system-wide (Accessibility > Remove animations,
 * or Developer options > Animator duration scale = off). Honouring it is an accessibility
 * requirement, not a preference: motion is a migraine and nausea trigger for some people.
 */
val LocalReducedMotion: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/** Set once at the top of the app so every component can size itself for TV vs. a phone/tablet. */
val LocalIsTv = staticCompositionLocalOf { false }
