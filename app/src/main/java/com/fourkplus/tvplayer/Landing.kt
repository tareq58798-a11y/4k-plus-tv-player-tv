package com.fourkplus.tvplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fourkplus.tvplayer.data.LoadedPlaylist
import com.fourkplus.tvplayer.data.MediaKind
import com.fourkplus.tvplayer.data.PlaylistItem
import com.fourkplus.tvplayer.ui.design.ActionButton
import com.fourkplus.tvplayer.ui.design.AppTopBar
import com.fourkplus.tvplayer.ui.design.ArtCard
import com.fourkplus.tvplayer.ui.design.BackdropState
import com.fourkplus.tvplayer.ui.design.Dims
import com.fourkplus.tvplayer.ui.design.EmptyState
import com.fourkplus.tvplayer.ui.design.FadingInfo
import com.fourkplus.tvplayer.ui.design.GlassPanel
import com.fourkplus.tvplayer.ui.design.LiveFlag
import com.fourkplus.tvplayer.ui.design.MetadataRow
import com.fourkplus.tvplayer.ui.design.NavDestination
import com.fourkplus.tvplayer.ui.design.PreloadBackdrops
import com.fourkplus.tvplayer.ui.design.SectionHeading
import com.fourkplus.tvplayer.ui.design.Tone
import com.fourkplus.tvplayer.ui.design.tvFocusable

/**
 * The shared skeleton behind Home, Live TV, Movies and Series.
 *
 * All four are the same page with different contents: a header, one or two rows of artwork, and a
 * block of information about whatever is focused. Writing them once means the focus behaviour,
 * spacing, background handling and fade timing cannot drift apart between sections - which is
 * exactly the drift that makes an interface feel assembled rather than designed.
 */

/** One card in a landing row, carrying only what the app genuinely knows about the item. */
internal data class LandingEntry(
    val item: PlaylistItem,
    val episodeId: String? = null,
    /** 0..1 watched fraction, or null when the app has no progress for this item. */
    val progress: Float? = null,
    /** A short caption such as "1h 02m left". Null when nothing truthful can be said. */
    val badge: String? = null
) {
    val key: String get() = channelKey(item)
}

internal data class LandingRow(
    val id: String,
    val title: String,
    val entries: List<LandingEntry>
)

/** The tile that opens a section's full category list, shown at the end of its first row. */
internal data class LandingTile(
    val title: String,
    val caption: String,
    val onClick: () -> Unit
)

@Composable
internal fun LandingScaffold(
    destination: NavDestination,
    rows: List<LandingRow>,
    tile: LandingTile?,
    backdrop: BackdropState,
    onSelect: (LandingEntry) -> Unit,
    isFavorite: (PlaylistItem) -> Boolean,
    onToggleFavorite: (PlaylistItem) -> Unit,
    onNavigate: (NavDestination) -> Unit,
    onSearch: () -> Unit,
    onLanguage: () -> Unit,
    onSettings: () -> Unit,
    emptyMessage: String,
    footer: (@Composable () -> Unit)? = null
) {
    var focused by remember { mutableStateOf<LandingEntry?>(null) }
    val firstCard = remember { FocusRequester() }
    val labels = mapOf(
        NavDestination.HOME to stringResource(R.string.nav_home),
        NavDestination.LIVE to stringResource(R.string.nav_live_tv),
        NavDestination.MOVIES to stringResource(R.string.nav_movies),
        NavDestination.SERIES to stringResource(R.string.nav_series)
    )

    // The first row's artwork is warmed as soon as the page appears, so the first move along it
    // shows its background immediately instead of after a round trip.
    PreloadBackdrops(
        remember(rows) {
            rows.firstOrNull()?.entries.orEmpty()
                .filter { it.item.kind != MediaKind.LIVE }
                .mapNotNull { it.item.logoUrl }
        }
    )

    val hasContent = rows.any { it.entries.isNotEmpty() }
    LaunchedEffect(hasContent) {
        if (hasContent) runCatching { firstCard.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Dims.GapM)
    ) {
        AppTopBar(
            selected = destination,
            onSelect = onNavigate,
            labels = labels,
            onSearch = onSearch,
            onLanguage = onLanguage,
            onSettings = onSettings
        )
        if (!hasContent && tile == null) {
            EmptyState(emptyMessage, Modifier.fillMaxWidth().padding(Dims.SafeHorizontal))
        }
        rows.forEachIndexed { rowIndex, row ->
            val showTile = tile != null && rowIndex == 0
            if (row.entries.isEmpty() && !showTile) return@forEachIndexed
            SectionHeading(row.title, Modifier.padding(horizontal = Dims.SafeHorizontal))
            LazyRow(
                Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = Dims.SafeHorizontal - Dims.CardBleed
                ),
                horizontalArrangement = Arrangement.spacedBy(Dims.GapXs)
            ) {
                items(row.entries, key = { "${row.id}_${it.key}" }) { entry ->
                    val isFirst = rowIndex == 0 && entry == row.entries.firstOrNull()
                    LandingCard(
                        entry = entry,
                        backdrop = backdrop,
                        modifier = if (isFirst) Modifier.focusRequester(firstCard) else Modifier,
                        onFocused = { focused = entry },
                        onClick = { onSelect(entry) }
                    )
                }
                if (showTile && tile != null) {
                    item(key = "${row.id}_all_categories") { CategoryTile(tile) }
                }
            }
            if (rowIndex == 0) {
                // Sits between the first row and everything below it, exactly as in the design:
                // the row you are working in, then what you have landed on.
                FadingInfo(
                    key = focused,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dims.SafeHorizontal)
                        // Fixed floor so a one-line description and a three-line one do not shunt
                        // the rows below up and down as focus moves.
                        .heightIn(min = 196.dp)
                ) { current ->
                    if (current == null) {
                        Spacer(Modifier.fillMaxWidth())
                    } else {
                        FocusedItemInfo(
                            entry = current,
                            favorite = isFavorite(current.item),
                            onPlay = { onSelect(current) },
                            onToggleFavorite = { onToggleFavorite(current.item) }
                        )
                    }
                }
            }
        }
        footer?.invoke()
        Spacer(Modifier.height(Dims.SafeVertical))
    }
}

@Composable
private fun LandingCard(
    entry: LandingEntry,
    backdrop: BackdropState,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    val live = entry.item.kind == MediaKind.LIVE
    ArtCard(
        imageUrl = entry.item.logoUrl,
        title = entry.item.name,
        onClick = onClick,
        modifier = modifier,
        progress = entry.progress,
        cornerBadge = entry.badge,
        // Channel tiles keep a visible outline: their artwork is a logo on a flat plate, and a
        // 6% growth on a small mark is far less legible than it is on a full-bleed still.
        useFocusBorder = live,
        overlayBadge = if (live) ({ LiveFlag(Modifier.align(Alignment.TopStart)) }) else null,
        onFocusChanged = { isFocused ->
            if (!isFocused) return@ArtCard
            onFocused()
            // Only films and series drive the cinematic background. A channel leaves whatever is
            // already there untouched, so moving through Live TV never strips the room bare.
            if (!live) backdrop.show(channelKey(entry.item), entry.item.logoUrl)
        }
    )
}

@Composable
private fun CategoryTile(tile: LandingTile) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .width(Dims.CardWidth + Dims.CardBleed * 2)
            .padding(Dims.CardBleed)
    ) {
        GlassPanel(
            Modifier
                .fillMaxWidth()
                .height(Dims.CardWidth * 9 / 16)
                .tvFocusable(onFocusChanged = { focused = it }, onClick = tile.onClick),
            focused = focused,
            radius = Dims.RadiusCard
        ) {
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Apps, null, tint = Tone.Accent, modifier = Modifier.size(34.dp))
                Text(
                    tile.title,
                    color = Tone.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tile.caption, color = Tone.TextMuted, fontSize = 12.sp, maxLines = 2)
                    Icon(Icons.Default.ChevronRight, null, tint = Tone.TextMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/**
 * Everything the app actually knows about the focused item. Every field is conditional: a provider
 * that supplies no year, rating or description simply produces a shorter block rather than a row
 * of invented placeholders.
 */
@Composable
private fun FocusedItemInfo(
    entry: LandingEntry,
    favorite: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val item = entry.item
    Column(verticalArrangement = Arrangement.spacedBy(Dims.GapS)) {
        Text(
            item.name,
            color = Tone.TextPrimary,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!item.rating.isNullOrBlank() && validMovieRating(item.rating) != null) {
                Icon(Icons.Default.Star, null, tint = Tone.Star, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            MetadataRow(
                parts = listOfNotNull(
                    when (item.kind) {
                        MediaKind.LIVE -> stringResource(R.string.nav_live_tv)
                        MediaKind.MOVIE -> stringResource(R.string.kind_movie)
                        MediaKind.SERIES -> stringResource(R.string.kind_series)
                    },
                    item.year?.takeIf { it.isNotBlank() },
                    validMovieRating(item.rating),
                    readableMovieDuration(item.duration),
                    item.group.takeIf { it.isNotBlank() },
                    entry.badge
                ),
                accentLast = entry.badge != null
            )
        }
        if (!item.description.isNullOrBlank()) {
            Text(
                item.description.orEmpty(),
                color = Tone.TextSecondary,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(.62f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dims.GapM), modifier = Modifier.padding(top = Dims.GapXs)) {
            ActionButton(
                label = when {
                    item.kind == MediaKind.LIVE -> stringResource(R.string.action_watch_live)
                    item.kind == MediaKind.SERIES && entry.episodeId != null -> stringResource(R.string.action_resume_episode)
                    (entry.progress ?: 0f) > 0f -> stringResource(R.string.action_resume)
                    else -> stringResource(R.string.action_play)
                },
                icon = Icons.Default.PlayArrow,
                onClick = onPlay,
                primary = true
            )
            ActionButton(
                label = stringResource(R.string.action_favorite),
                icon = if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                onClick = onToggleFavorite
            )
        }
    }
}

/**
 * Home's footer: the same account facts the old Home showed, in the same words and read from the
 * same places - only the styling has changed.
 */
@Composable
internal fun LandingDeviceStrip(playlist: LoadedPlaylist?) {
    val context = androidx.compose.ui.platform.LocalContext.current
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
    Column(Modifier.fillMaxWidth().padding(horizontal = Dims.SafeHorizontal, vertical = Dims.GapS)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = .10f)))
        Row(
            Modifier.fillMaxWidth().padding(top = Dims.GapM),
            horizontalArrangement = Arrangement.spacedBy(Dims.GapXl)
        ) {
            DeviceFact("Playlist expires", expiryText)
            DeviceFact("App MAC", appMac)
            DeviceFact("Device key", deviceKey)
        }
    }
}

@Composable
private fun DeviceFact(label: String, value: String) {
    Column {
        Text(label, color = Tone.TextMuted, fontSize = 13.sp)
        Text(value, color = Tone.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A rounded corner used by callers that need the landing card's shape. */
internal val LandingCardShape = RoundedCornerShape(Dims.RadiusCard)
