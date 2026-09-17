package com.fourkplus.tvplayer.ui.design

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage

/**
 * Reusable building blocks for the cinematic interface. Pages compose these rather than styling
 * their own boxes, which is what keeps spacing, focus behaviour and timing identical everywhere.
 */

/**
 * Makes anything focusable and clickable by remote, and reports its focus state, without drawing
 * an indicator of its own - each component decides how focus looks. The ripple is suppressed
 * because there is no touch on a TV and it reads as a stray flash.
 */
@Composable
fun Modifier.tvFocusable(
    onFocusChanged: (Boolean) -> Unit,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this
        .onFocusChanged { onFocusChanged(it.isFocused) }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = { onClick?.invoke() }
        )
}

/** A translucent dark panel - the surface every grouped control sits on. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    radius: Dp = Dims.RadiusPanel,
    content: @Composable BoxScope.() -> Unit
) {
    val border by animateColorAsState(
        if (focused) Tone.GlassBorderFocused else Tone.GlassBorder,
        Motion.focus(),
        label = "panelBorder"
    )
    Box(
        modifier
            .clip(RoundedCornerShape(radius))
            .background(Tone.Glass)
            .border(BorderStroke(if (focused) 2.dp else 1.dp, border), RoundedCornerShape(radius)),
        content = content
    )
}

@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        color = Tone.TextPrimary,
        fontSize = 19.sp,
        fontWeight = FontWeight.Bold
    )
}

/**
 * A piece of artwork - movie, series or channel - that communicates focus by growing instead of
 * drawing a border.
 *
 * The card reserves [Dims.CardBleed] of empty space around the image and the scale animation grows
 * into that reserve, so the focused artwork never spills past the card's own footprint. That is
 * what lets a row keep its layout perfectly still: nothing reflows, nothing shifts, and the list
 * that holds the card has nothing to clip.
 */
@Composable
fun ArtCard(
    imageUrl: String?,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = Dims.CardWidth,
    aspectRatio: Float = 16f / 9f,
    showTitle: Boolean = true,
    progress: Float? = null,
    cornerBadge: String? = null,
    overlayBadge: (@Composable BoxScope.() -> Unit)? = null,
    /** Channels and other non-artwork tiles keep a visible outline; posters never do. */
    useFocusBorder: Boolean = false,
    onFocusChanged: (Boolean) -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    val reducedMotion = LocalReducedMotion.current
    val targetScale = if (focused && !reducedMotion) Motion.PosterFocusScale else 1f
    val scale by animateFloatAsState(targetScale, Motion.poster(), label = "posterScale")
    val glow by animateFloatAsState(if (focused) 1f else 0f, Motion.focus(), label = "posterGlow")

    Box(
        modifier
            .width(width + Dims.CardBleed * 2)
            // A focused card is drawn above its neighbours, so the growth overlaps them rather
            // than disappearing underneath the next card in the row.
            .zIndex(if (focused) 1f else 0f)
            .tvFocusable(
                onFocusChanged = {
                    focused = it
                    onFocusChanged(it)
                },
                onClick = onClick
            )
    ) {
        Box(
            Modifier
                .padding(Dims.CardBleed)
                .width(width)
                .aspectRatio(aspectRatio)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(Dims.RadiusCard))
                .background(Color(0xFF0B1524))
                .then(
                    if (useFocusBorder && glow > 0f) {
                        Modifier.border(
                            BorderStroke(2.dp, Tone.Accent.copy(alpha = glow)),
                            RoundedCornerShape(Dims.RadiusCard)
                        )
                    } else Modifier
                )
        ) {
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            // Always laid over the artwork, never conditionally: a scrim that appears only for
            // some cards reads as a flicker as focus moves along the row.
            Box(Modifier.fillMaxSize().background(Tone.cardScrim()))
            // Focused artwork lifts very slightly out of the page rather than being outlined.
            if (glow > 0f && !useFocusBorder) {
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = .06f * glow)))
            }
            overlayBadge?.invoke(this)
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (showTitle) {
                    // One line only. Cards are small enough now that a wrapped title swallows the
                    // artwork, and the full name is always spelled out in the information block
                    // below the row for whichever card is focused.
                    Text(
                        title,
                        color = Tone.TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (progress != null && progress > 0f) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = .28f))
                        ) {
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                                    .background(Tone.Accent)
                            )
                        }
                        if (!cornerBadge.isNullOrBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(cornerBadge, color = Tone.TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else if (!cornerBadge.isNullOrBlank()) {
                    Text(cornerBadge, color = Tone.TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** The red LIVE flag used on channel artwork. */
@Composable
fun LiveFlag(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Tone.LiveRed)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text("LIVE", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * The primary/secondary action pair from the mockups. Primary runs the cyan-to-blue gradient;
 * secondary is glass with a cyan edge on focus. Both grow a touch when focused so the remote's
 * position is never in doubt.
 */
@Composable
fun ActionButton(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true
) {
    var focused by remember { mutableStateOf(false) }
    val reducedMotion = LocalReducedMotion.current
    val scale by animateFloatAsState(
        if (focused && !reducedMotion) 1.04f else 1f,
        Motion.focus(),
        label = "buttonScale"
    )
    val borderColor by animateColorAsState(
        when {
            focused -> Tone.Accent
            primary -> Color.Transparent
            else -> Tone.GlassBorder
        },
        Motion.focus(),
        label = "buttonBorder"
    )
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(Dims.RadiusPill))
            .then(
                if (primary) Modifier.background(Brush.horizontalGradient(Tone.ActionGradient))
                else Modifier.background(Tone.Glass)
            )
            .border(BorderStroke(2.dp, borderColor), RoundedCornerShape(Dims.RadiusPill))
            .tvFocusable(onFocusChanged = { focused = it }, enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else .45f)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) {
                Icon(icon, null, tint = Tone.TextPrimary, modifier = Modifier.size(18.dp))
            }
            Text(label, color = Tone.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * A category in the left-hand rail. Selected runs the cyan gradient with dark text; focused but
 * unselected gets the cyan outline, so "where I am" and "what is showing" stay distinguishable -
 * colour alone never carries the selected state.
 */
@Composable
fun CategoryPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocusChanged: (Boolean) -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        if (focused) Tone.Accent else Color.Transparent,
        Motion.focus(),
        label = "pillBorder"
    )
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dims.RadiusPill))
            .then(
                if (selected) Modifier.background(Brush.horizontalGradient(Tone.CategoryGradient))
                else Modifier.background(Color.Transparent)
            )
            .border(BorderStroke(2.dp, borderColor), RoundedCornerShape(Dims.RadiusPill))
            .tvFocusable(
                onFocusChanged = {
                    focused = it
                    onFocusChanged(it)
                },
                onClick = onClick
            )
            .padding(horizontal = 15.dp, vertical = 9.dp)
    ) {
        Text(
            label,
            color = if (selected) Color(0xFF04121F) else Tone.TextSecondary,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** One fact in a metadata row. [star] marks the rating, which carries its own icon. */
data class MetaItem(val text: String, val star: Boolean = false, val accent: Boolean = false)

/**
 * A row of "Movie | 2026 | ★ 8.2 | 2h 08m | Sci-Fi • Drama" facts, divided by thin rules rather
 * than dots so the genre list's own bullets stay readable as a list. Missing values are simply
 * left out - the row gets shorter rather than showing an empty slot.
 */
@Composable
fun MetadataRow(items: List<MetaItem>, modifier: Modifier = Modifier) {
    val shown = items.filter { it.text.isNotBlank() }
    if (shown.isEmpty()) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        shown.forEachIndexed { index, item ->
            if (index > 0) {
                Text("   |   ", color = Tone.TextMuted.copy(alpha = .55f), fontSize = 13.sp)
            }
            if (item.star) {
                Icon(Icons.Default.Star, null, tint = Tone.Star, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
            }
            Text(
                item.text,
                color = if (item.accent) Tone.Accent else Tone.TextSecondary,
                fontSize = 13.sp,
                fontWeight = if (item.accent || item.star) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}


/**
 * Wraps the block of information about whatever is focused, fading the old text out and the new
 * text in whenever [key] changes. Keyed on the item rather than on each field so a single move
 * produces one transition instead of one per line.
 */
@Composable
fun <T> FadingInfo(
    key: T,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit
) {
    Crossfade(targetState = key, animationSpec = Motion.info(), label = "info", modifier = modifier) {
        content(it)
    }
}

@Composable
fun LoadingState(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        CircularProgressIndicator(color = Tone.Accent)
        Text(message, color = Tone.TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Text(
        message,
        modifier = modifier,
        color = Tone.TextMuted,
        fontSize = 16.sp,
        textAlign = TextAlign.Center
    )
}
