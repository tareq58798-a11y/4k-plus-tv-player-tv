package com.fourkplus.tvplayer.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fourkplus.tvplayer.R
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The four destinations the app's top navigation offers. Favorites is deliberately not one of
 *  them: it lives inside each section's own category list, where its contents actually belong. */
enum class NavDestination { HOME, LIVE, MOVIES, SERIES }

/**
 * The persistent header: brand mark, the four sections, and the global controls on the right.
 *
 * There is no theme toggle. The app has one look now - a dark cinematic one - and a control that
 * let the viewer break it was worth less than the consistency of not having it.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AppTopBar(
    selected: NavDestination,
    onSelect: (NavDestination) -> Unit,
    labels: Map<NavDestination, String>,
    onSearch: (() -> Unit)?,
    onLanguage: (() -> Unit)?,
    onSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /**
     * True while the viewer is working along the bar itself. Moving onto a section switches to it
     * immediately, which replaces the whole page under the remote - including this bar. Focus has
     * to be put back on the section they landed on, or a single press would drop them into the new
     * page's content and they could never reach the tab after it.
     */
    keepFocus: Boolean = false,
    /**
     * Points at the tab for the section currently open. Hoisted so the page below can put focus
     * there itself rather than hoping a geometric focus search picks the right thing.
     */
    selectedTabFocus: FocusRequester? = null
) {
    val ownTabFocus = remember { FocusRequester() }
    val selectedTab = selectedTabFocus ?: ownTabFocus
    LaunchedEffect(selected, keepFocus) {
        if (keepFocus) runCatching { selectedTab.requestFocus() }
    }
    Row(
        modifier
            .fillMaxWidth()
            // Coming up from the page below, focus lands on the section you are already in -
            // whatever is directly above the card you left. Without this, Compose picks the
            // nearest focusable overhead, so a card on the right of a row would jump to the
            // settings icon rather than to the section's own tab. Left and right entries are left
            // to the normal search, so stepping back from the search icon still lands beside it.
            .focusGroup()
            .focusProperties {
                enter = { direction ->
                    if (direction == FocusDirection.Up || direction == FocusDirection.Down) selectedTab
                    else FocusRequester.Default
                }
            }
            .padding(horizontal = Dims.SafeHorizontal - 12.dp, vertical = Dims.SafeVertical - 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.brand_logo_dark),
            contentDescription = "4K Plus TV",
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(38.dp)
        )
        Spacer(Modifier.width(Dims.GapM))
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dims.GapS),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavDestination.entries.forEach { destination ->
                NavTab(
                    label = labels[destination].orEmpty(),
                    selected = destination == selected,
                    modifier = if (destination == selected) Modifier.focusRequester(selectedTab) else Modifier,
                    // Reaching a section is enough to open it. On a remote there is no hover, so
                    // requiring OK as well would mean two presses to do what the movement already
                    // said - and the viewer can see the page they are choosing while they choose.
                    onFocused = { onSelect(destination) },
                    onClick = { onSelect(destination) }
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(Dims.GapS), verticalAlignment = Alignment.CenterVertically) {
            if (onSearch != null) GlobalIconButton(Icons.Default.Search, "Search", onSearch)
            if (onLanguage != null) GlobalIconButton(Icons.Default.Language, "Language", onLanguage)
            if (onSettings != null) GlobalIconButton(Icons.Default.Settings, "Settings", onSettings)
            // Separates the controls you can act on from the clock, which you cannot.
            Box(
                Modifier
                    .padding(horizontal = Dims.GapS)
                    .width(1.dp)
                    .height(22.dp)
                    .background(Color.White.copy(alpha = .22f))
            )
            ClockLabel()
        }
    }
}

@Composable
private fun NavTab(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val border by animateColorAsState(
        when {
            focused -> Tone.Accent
            selected -> Tone.Accent.copy(alpha = .55f)
            else -> Color.Transparent
        },
        Motion.focus(),
        label = "tabBorder"
    )
    val textColor by animateColorAsState(
        when {
            selected -> Tone.TextPrimary
            focused -> Tone.TextPrimary
            else -> Tone.TextSecondary
        },
        Motion.focus(),
        label = "tabText"
    )
    // The section you are in is ringed. That is a difference in shape, not only in colour, so it
    // still reads as the active one for anyone who cannot separate the cyan from the grey.
    Box(
        modifier
            .clip(RoundedCornerShape(Dims.RadiusPill))
            .then(if (selected) Modifier.background(Tone.Accent.copy(alpha = .10f)) else Modifier)
            .border(
                BorderStroke(if (selected || focused) 2.dp else 0.dp, border),
                RoundedCornerShape(Dims.RadiusPill)
            )
            .tvFocusable(
                onFocusChanged = {
                    focused = it
                    if (it) onFocused()
                },
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Text(label, color = textColor, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
fun GlobalIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (focused && !LocalReducedMotion.current) 1.1f else 1f,
        Motion.focus(),
        label = "iconScale"
    )
    val border by animateColorAsState(
        if (focused) Tone.Accent else Color.Transparent,
        Motion.focus(),
        label = "iconBorder"
    )
    Box(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(34.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(BorderStroke(2.dp, border), RoundedCornerShape(18.dp))
            .tvFocusable(onFocusChanged = { focused = it }, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, description, tint = Tone.TextPrimary, modifier = Modifier.size(20.dp))
    }
}

/** Day, date and time, refreshed once a minute rather than on a ticking timer. */
@Composable
fun ClockLabel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val locale = remember(configuration) {
        @Suppress("DEPRECATION")
        configuration.locales[0] ?: Locale.getDefault()
    }
    val pattern = remember(locale) {
        if (android.text.format.DateFormat.is24HourFormat(context)) "EEE, d MMM • HH:mm"
        else "EEE, d MMM • h:mm a"
    }
    val formatter = remember(pattern, locale) { SimpleDateFormat(pattern, locale) }
    var now by remember { mutableStateOf(formatter.format(Date())) }
    LaunchedEffect(formatter) {
        while (true) {
            now = formatter.format(Date())
            delay(20_000)
        }
    }
    Text(
        now,
        modifier = modifier,
        color = Tone.TextSecondary,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        // Never wraps: a two-line clock pushes the whole header out of shape on narrower panels.
        softWrap = false,
        maxLines = 1
    )
}
