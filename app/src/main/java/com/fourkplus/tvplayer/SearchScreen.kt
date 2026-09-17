package com.fourkplus.tvplayer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fourkplus.tvplayer.data.LoadedPlaylist
import com.fourkplus.tvplayer.data.MediaKind
import com.fourkplus.tvplayer.data.PlaylistItem
import com.fourkplus.tvplayer.ui.design.ActionButton
import com.fourkplus.tvplayer.ui.design.ArtCard
import com.fourkplus.tvplayer.ui.design.BackdropState
import com.fourkplus.tvplayer.ui.design.Dims
import com.fourkplus.tvplayer.ui.design.EmptyState
import com.fourkplus.tvplayer.ui.design.FadingInfo
import com.fourkplus.tvplayer.ui.design.GlobalIconButton
import com.fourkplus.tvplayer.ui.design.LiveFlag
import com.fourkplus.tvplayer.ui.design.MetadataRow
import com.fourkplus.tvplayer.ui.design.Motion
import com.fourkplus.tvplayer.ui.design.Tone
import com.fourkplus.tvplayer.ui.design.tvFocusable

/**
 * Search on its own full page, sitting on whatever cinematic background the viewer arrived with.
 *
 * The search itself is untouched: the same substring match across the whole playlist, with the same
 * cap on results. The kind chips above the results only narrow what is shown - they do not change
 * what was searched, so switching between them never costs another pass over the catalogue.
 */
private enum class SearchFilter { ALL, LIVE, MOVIES, SERIES }

@Composable
internal fun SearchScreen(
    playlist: LoadedPlaylist?,
    backdrop: BackdropState,
    onBack: () -> Unit,
    onSelect: (PlaylistItem) -> Unit
) {
    BackHandler(onBack = onBack)
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(SearchFilter.ALL) }
    var focused by remember { mutableStateOf<PlaylistItem?>(null) }

    // Identical to the previous screen's search, deliberately: same match, same cap.
    val results = remember(playlist, query) {
        if (query.isBlank()) emptyList()
        else playlist?.items?.filter { it.name.contains(query.trim(), ignoreCase = true) }?.take(200).orEmpty()
    }
    val shown = remember(results, filter) {
        when (filter) {
            SearchFilter.ALL -> results
            SearchFilter.LIVE -> results.filter { it.kind == MediaKind.LIVE }
            SearchFilter.MOVIES -> results.filter { it.kind == MediaKind.MOVIE }
            SearchFilter.SERIES -> results.filter { it.kind == MediaKind.SERIES }
        }
    }
    val favorites = rememberMixedFavorites()

    Column(
        Modifier.fillMaxSize().padding(horizontal = Dims.SafeHorizontal, vertical = Dims.SafeVertical),
        verticalArrangement = Arrangement.spacedBy(Dims.GapM)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dims.GapM)) {
            GlobalIconButton(Icons.Default.ArrowBack, stringResource(R.string.cd_back), onBack)
            Text(
                stringResource(R.string.search_title),
                color = Tone.TextPrimary,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold
            )
        }
        DarkTvSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.search_placeholder),
            modifier = Modifier.fillMaxWidth(),
            fontSize = 20.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Dims.GapS)) {
            FilterChip(stringResource(R.string.search_filter_all), filter == SearchFilter.ALL) { filter = SearchFilter.ALL }
            FilterChip(stringResource(R.string.nav_live_tv), filter == SearchFilter.LIVE) { filter = SearchFilter.LIVE }
            FilterChip(stringResource(R.string.nav_movies), filter == SearchFilter.MOVIES) { filter = SearchFilter.MOVIES }
            FilterChip(stringResource(R.string.nav_series), filter == SearchFilter.SERIES) { filter = SearchFilter.SERIES }
        }
        when {
            query.isBlank() -> EmptyState(
                stringResource(R.string.search_hint),
                Modifier.fillMaxWidth().padding(top = Dims.GapXl)
            )
            shown.isEmpty() -> EmptyState(
                stringResource(R.string.search_no_results, query),
                Modifier.fillMaxWidth().padding(top = Dims.GapXl)
            )
            else -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.search_results_title),
                        color = Tone.TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        stringResource(R.string.search_results_count, shown.size),
                        color = Tone.TextMuted,
                        fontSize = 15.sp
                    )
                }
                LazyRow(
                    Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(Dims.GapXs)
                ) {
                    items(shown, key = { "${it.kind}_${channelKey(it)}" }) { item ->
                        val live = item.kind == MediaKind.LIVE
                        ArtCard(
                            imageUrl = item.logoUrl,
                            title = item.name,
                            onClick = { onSelect(item) },
                            useFocusBorder = live,
                            overlayBadge = if (live) ({ LiveFlag(Modifier.align(Alignment.TopStart)) }) else null,
                            onFocusChanged = { isFocused ->
                                if (!isFocused) return@ArtCard
                                focused = item
                                if (!live) backdrop.show(channelKey(item), item.logoUrl)
                            }
                        )
                    }
                }
                FadingInfo(
                    key = focused,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp)
                ) { current ->
                    if (current == null) Spacer(Modifier.fillMaxWidth())
                    else SearchResultInfo(
                        item = current,
                        favorite = favorites.contains(current),
                        onOpen = { onSelect(current) },
                        onToggleFavorite = { favorites.toggle(current) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultInfo(
    item: PlaylistItem,
    favorite: Boolean,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dims.GapS)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dims.GapM)) {
            Text(
                item.name,
                color = Tone.TextPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            if (item.kind == MediaKind.LIVE) LiveFlag()
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
                item.group.takeIf { it.isNotBlank() }
            )
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Dims.GapM), modifier = Modifier.padding(top = Dims.GapXs)) {
            ActionButton(
                label = if (item.kind == MediaKind.LIVE) stringResource(R.string.action_watch_live)
                else stringResource(R.string.action_play),
                icon = Icons.Default.PlayArrow,
                onClick = onOpen,
                primary = true
            )
            ActionButton(
                label = stringResource(R.string.action_favorite),
                icon = if (favorite) Icons.Default.Favorite
                else Icons.Default.FavoriteBorder,
                onClick = onToggleFavorite
            )
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val border by animateColorAsState(
        if (focused) Tone.Accent else Tone.GlassBorder,
        Motion.focus(),
        label = "chipBorder"
    )
    Box(
        Modifier
            .clip(RoundedCornerShape(Dims.RadiusPill))
            .then(
                if (selected) Modifier.background(Brush.horizontalGradient(Tone.CategoryGradient))
                else Modifier.background(Tone.Glass)
            )
            .border(BorderStroke(2.dp, border), RoundedCornerShape(Dims.RadiusPill))
            .tvFocusable(onFocusChanged = { focused = it }, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 9.dp)
    ) {
        Text(
            label,
            color = if (selected) Color(0xFF04121F) else Tone.TextSecondary,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}
