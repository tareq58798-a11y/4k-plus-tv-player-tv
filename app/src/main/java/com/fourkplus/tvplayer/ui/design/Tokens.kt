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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
        0f to Color(0x990A1424),
        0.32f to Color(0x3D060D1A),
        0.74f to Color(0x8C030711),
        1f to Color(0xE001030A)
    )

    fun sideScrim(): Brush = Brush.horizontalGradient(
        0f to Color(0xD9030711),
        0.38f to Color(0x8C040A16),
        0.72f to Color(0x33020610),
        1f to Color(0x00000000)
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
     * Landscape artwork cards (16:9), sized so exactly five fit across a 1080p panel inside the
     * safe margins - five cards plus their bleed and the gaps between them come to 886 of the 896
     * available. Five is the number the rows are built around: it is how many places a row sets,
     * and the category tile holds the last of them, so a card any wider would push that tile off
     * the edge of the screen.
     */
    val CardWidth: Dp = 158.dp
    val CardWidthCompact: Dp = 134.dp

    /**
     * The left-hand category column on Movies, Series and Live TV, and beside it on Live TV the
     * column of channels. Fixed widths: the grid takes whatever is left, and what is left is what
     * decides how many columns it gets - see [PosterWidthTarget].
     */
    val PaneWidth: Dp = 240.dp
    val ChannelPaneWidth: Dp = 310.dp

    /**
     * Portrait poster grids size themselves: columns are the available width divided by this,
     * so a narrow panel gets fewer readable posters instead of a row of slivers. On the standard
     * 960dp television it works out at exactly seven - 960 less the 40dp margins, the 240dp
     * category column and the 10dp between them leaves 630, and 630/90 is 7.
     */
    val PosterWidthTarget: Dp = 90.dp
    val GridGapH: Dp = 7.dp
    val GridGapV: Dp = 9.dp
    /** Between the category column and the grid beside it. */
    val PaneGap: Dp = 10.dp

    val RadiusPoster: Dp = 14.dp
    val RadiusPane: Dp = 15.dp
    val RadiusCategory: Dp = 11.dp
}

/**
 * The type scale, in sp, and the one place it is written down.
 *
 * It lives here for the same reason the colours and spacing do: the Tizen port reads this file and
 * cannot otherwise know what size anything is. Every size below was already in the app - scattered
 * across the components that used it - and the port had been guessing at them from screenshots,
 * which is how its titles ended up half again as large as these and its headings half as large.
 *
 * sp, not dp, but at the font scale a television reports the two convert identically: the extractor
 * multiplies both by the same density.
 */
object Type {
    /** Top bar: the section tabs and the clock beside them. */
    val Tab: TextUnit = 15.sp
    val Clock: TextUnit = 15.sp

    /** "Continue watching", "Recently added" - the heading over a row. */
    val SectionHeading: TextUnit = 19.sp

    /** Landscape artwork cards. The title is set over the foot of the artwork, on one line. */
    val CardTitle: TextUnit = 11.sp
    val CardBadge: TextUnit = 10.sp
    val LiveFlag: TextUnit = 9.sp

    /** The category box that ends each row on a section page. */
    val TileTitle: TextUnit = 11.sp
    val TileTitleLine: TextUnit = 13.sp
    val TileCaption: TextUnit = 8.sp
    val TileCaptionLine: TextUnit = 10.sp

    /** The block of information under the row holding focus. */
    val InfoTitle: TextUnit = 28.sp
    val InfoMeta: TextUnit = 13.sp
    val InfoBody: TextUnit = 13.sp
    val InfoBodyLine: TextUnit = 19.sp

    val ActionLabel: TextUnit = 14.sp

    /** The browse pages: the pane heading, its categories, and the posters in the grid. */
    val PaneHeading: TextUnit = 19.sp
    val CategoryLabel: TextUnit = 14.sp
    val PosterTitle: TextUnit = 12.sp
    val PosterYear: TextUnit = 10.sp

    val Hint: TextUnit = 12.sp
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
    val BackdropMs = 240

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
     *
     * Short, because the neighbours of the focused card are fetched and decoded ahead of time: the
     * wait this guards against is mostly gone, so the pause before committing can be too.
     */
    /**
     * How long a title has to stay the focused one before the background becomes its artwork.
     *
     * Three seconds, which is far longer than a debounce usually is, and deliberately so. At 110ms
     * this fired on almost every move, and moving along a row of twenty films meant twenty
     * decodes of a 4K still - each one cheap on its own and jointly enough to make the highlight
     * itself feel late. The picture now changes for somebody who has stopped to look at
     * something, and not for somebody passing through, which is also the only time it is any use.
     */
    val BackdropDebounceMs = 3000L

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
