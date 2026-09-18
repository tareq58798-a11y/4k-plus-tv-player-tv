package com.fourkplus.tvplayer

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fourkplus.tvplayer.data.LiveSnapshotCache
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
import com.fourkplus.tvplayer.ui.design.LocalReducedMotion
import com.fourkplus.tvplayer.ui.design.MetaItem
import com.fourkplus.tvplayer.ui.design.MetadataRow
import com.fourkplus.tvplayer.ui.design.Motion
import com.fourkplus.tvplayer.ui.design.NavDestination
import com.fourkplus.tvplayer.ui.design.PreloadBackdrops
import com.fourkplus.tvplayer.ui.design.RevealOnAppear
import com.fourkplus.tvplayer.ui.design.SectionHeading
import com.fourkplus.tvplayer.ui.design.Tone
import com.fourkplus.tvplayer.ui.design.tvFocusable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val entries: List<LandingEntry>,
    /**
     * The number of card positions the row always shows, padding with empty slots after whatever
     * it actually holds. Favorites uses this: a row that vanishes when empty teaches the viewer it
     * does not exist, and a row holding one lonely card looks broken rather than new. Places set
     * at the table say the same thing in both cases - this is where things go.
     */
    val minSlots: Int = 0,
    /**
     * One line saying what the row is for, shown only while it still has empty places. Once the
     * row fills up it explains itself and the line disappears rather than becoming furniture.
     */
    val hint: String? = null
)

/**
 * Details fetched for whichever item is focused, for the lines the catalogue listing does not
 * carry. Series never arrive with a plot or a year at list level, and many panels omit them for
 * films too, so without this the information block is a title and a category and nothing else.
 */
internal data class ItemBio(
    val description: String? = null,
    val year: String? = null,
    val rating: String? = null,
    val duration: String? = null,
    val genre: String? = null,
    /**
     * The title's own landscape artwork. Catalogue listings only carry a poster, which is a tall
     * crop a few hundred pixels wide - stretched across a television it is the weakest picture in
     * the app. This is the image the backdrop actually wants, and it arrives with the details.
     */
    val backdropUrl: String? = null
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
    /** True when this page was reached by moving along the top bar rather than by opening it. */
    arrivedFromNavBar: Boolean = false,
    /** Fetches the plot and other details for the focused item. Null disables the lookup. */
    loadBio: (suspend (PlaylistItem) -> ItemBio?)? = null,
    /** True when the viewer has just stepped back out of the full category browser. */
    returningFromCategories: Boolean = false,
    onReturnHandled: () -> Unit = {},
    footer: (@Composable () -> Unit)? = null
) {
    var focused by remember { mutableStateOf<LandingEntry?>(null) }
    /** Which row the focused card belongs to, and so which row shows the information block. */
    var focusedRow by remember { mutableIntStateOf(0) }
    // Keyed by item, so moving back onto something already looked up costs nothing.
    val bios = remember { mutableStateMapOf<String, ItemBio>() }
    /**
     * The cards the remote can reach in one press from wherever it is: the next along the row, the
     * one before it, the one after that, and the same position in the rows above and below. Every
     * direction is covered, so whichever way the viewer moves the picture is already in hand.
     *
     * Before anything holds focus this is simply the start of the first row, which is where Down
     * off the navigation lands.
     */
    val neighbours = remember(focused, focusedRow, rows) {
        val current = rows.getOrNull(focusedRow)
        val here = current?.entries?.indexOfFirst { it.key == focused?.key } ?: -1
        buildList {
            if (current != null && here >= 0) {
                listOf(here + 1, here - 1, here + 2, here + 3).forEach { at ->
                    current.entries.getOrNull(at)?.let(::add)
                }
            }
            rows.forEachIndexed { index, other ->
                if (index == focusedRow) return@forEachIndexed
                other.entries.getOrNull(here.coerceAtLeast(0))?.let(::add)
                if (here < 0) addAll(other.entries.take(2))
            }
            if (here < 0) current?.entries?.take(4)?.let(::addAll)
        }.filter { it.item.kind != MediaKind.LIVE }.distinctBy { it.key }
    }

    // Their details are fetched together rather than one after another. There are only ever a
    // handful, they are the ones about to be needed, and waiting for each in turn is exactly the
    // delay this is meant to remove.
    LaunchedEffect(neighbours, loadBio) {
        val fetch = loadBio ?: return@LaunchedEffect
        coroutineScope {
            neighbours.filterNot { bios.containsKey(it.key) }.forEach { entry ->
                launch {
                    runCatching { fetch(entry.item) }.getOrNull()?.let { bios[entry.key] = it }
                }
            }
        }
    }

    // Keyed on the focused entry, so moving on cancels the wait before it ever becomes a request:
    // running a row costs one lookup for the title you stop at, not one for every title you pass.
    LaunchedEffect(focused, loadBio) {
        val entry = focused ?: return@LaunchedEffect
        val fetch = loadBio ?: return@LaunchedEffect
        if (entry.item.kind == MediaKind.LIVE || bios.containsKey(entry.key)) return@LaunchedEffect
        delay(400)
        val bio = runCatching { fetch(entry.item) }.getOrNull() ?: return@LaunchedEffect
        bios[entry.key] = bio
        // The poster went up the moment this card was focused, so the page was never blank; now
        // the title's proper landscape artwork has arrived, the backdrop upgrades to it. The
        // crossfade handles the swap, and if the two are the same image nothing happens at all.
        bio.backdropUrl?.takeIf { it.isNotBlank() }?.let { backdrop.show(entry.key, it) }
    }
    // A handle on each focusable position in the top row, in the order they are laid out: its
    // cards, then the category tile. Both rows use the same card width and start at the same
    // margin, so position N in the row below sits directly under position N here - which is what
    // lets Up from the second row step straight up into the artwork above it instead of stopping
    // at the Play button that happens to sit between them.
    val topRowSlots = ((rows.firstOrNull()?.entries?.size ?: 0) + (if (tile != null) 1 else 0))
        .coerceAtLeast(1)
    val topRowFocus = remember(topRowSlots) { List(topRowSlots) { FocusRequester() } }
    val firstCard = topRowFocus.first()
    /** The category tile sits after the row's cards, so its slot is the one at that index. */
    val tileFocus = topRowFocus.getOrNull(rows.firstOrNull()?.entries?.size ?: 0)
    // Up from anything in the page goes to the tab of the page it is on, and stays there: Home to
    // Home, Series to Series, and so on. Up is how you get back to the navigation, so it always
    // does the same thing wherever it is pressed - it never changes section under the viewer.
    //
    // It is declared rather than searched for. Compose's focus search picks whatever focusable
    // sits nearest overhead, so the answer would otherwise depend on how far along the row the
    // card happened to be, and from the right-hand end it reached the header icons instead.
    val tabFocus = remember { NavDestination.entries.associateWith { FocusRequester() } }
    val ownTab = tabFocus.getValue(destination)
    val labels = mapOf(
        NavDestination.HOME to stringResource(R.string.nav_home),
        NavDestination.LIVE to stringResource(R.string.nav_live_tv),
        NavDestination.MOVIES to stringResource(R.string.nav_movies),
        NavDestination.SERIES to stringResource(R.string.nav_series)
    )

    // The first row's artwork is warmed as soon as the page appears, so the first move along it
    // shows its background immediately instead of after a round trip.
    // Warmed with the picture each neighbour will actually ask for - its real backdrop once the
    // details have arrived, its poster until then - so arriving at a card finds the image decoded
    // rather than starting a round trip. The list follows focus, so the warming moves with it.
    PreloadBackdrops(
        remember(neighbours, bios.size) {
            neighbours.mapNotNull {
                bios[it.key]?.backdropUrl?.takeIf(String::isNotBlank) ?: it.item.logoUrl
            }
        }
    )

    val hasContent = rows.any { it.entries.isNotEmpty() }
    // Only claim focus for the content when the viewer actually opened this page. If they are
    // still moving along the top bar, pulling focus down would end their journey along it after
    // one step - the bar keeps focus and the page just changes underneath.
    LaunchedEffect(hasContent, arrivedFromNavBar, returningFromCategories) {
        when {
            // Coming back out of the full browser puts focus on the door you came through, rather
            // than on the start of the row - you leave a place standing where you were standing.
            returningFromCategories && tileFocus != null -> {
                runCatching { tileFocus.requestFocus() }
                onReturnHandled()
            }
            hasContent && !arrivedFromNavBar -> runCatching { firstCard.requestFocus() }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Dims.GapM)
    ) {
        RevealOnAppear {
            AppTopBar(
                selected = destination,
                onSelect = onNavigate,
                labels = labels,
                onSearch = onSearch,
                onLanguage = onLanguage,
                onSettings = onSettings,
                keepFocus = arrivedFromNavBar,
                tabFocus = tabFocus,
                downTarget = firstCard
            )
        }
        if (!hasContent && tile == null) {
            RevealOnAppear(delayMs = Motion.StaggerMs) {
                EmptyState(emptyMessage, Modifier.fillMaxWidth().padding(Dims.SafeHorizontal))
            }
        }
        rows.forEachIndexed { rowIndex, row ->
            val showTile = tile != null && rowIndex == 0
            val placeholders = (row.minSlots - row.entries.size).coerceAtLeast(0)
            if (row.entries.isEmpty() && !showTile && placeholders == 0) return@forEachIndexed
            // Each band starts a little after the one above it, so the page assembles top to
            // bottom instead of appearing all at once.
            RevealOnAppear(
                modifier = Modifier.fillMaxWidth(),
                delayMs = Motion.StaggerMs * (rowIndex + 1)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Dims.GapS)) {
                    SectionHeading(row.title, Modifier.padding(horizontal = Dims.SafeHorizontal))
                    LazyRow(
                        Modifier.fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = Dims.SafeHorizontal - Dims.CardBleed
                        ),
                        horizontalArrangement = Arrangement.spacedBy(Dims.GapXs)
                    ) {
                        itemsIndexed(row.entries, key = { _, it -> "${row.id}_${it.key}" }) { index, entry ->
                            LandingCard(
                                entry = entry,
                                backdrop = backdrop,
                                modifier = if (rowIndex == 0 && index < topRowFocus.size) {
                                    Modifier.focusRequester(topRowFocus[index])
                                } else Modifier,
                                // The top row answers Up with the navigation; every row beneath it
                                // answers with the position above it in that top row.
                                upTarget = if (rowIndex == 0) {
                                    ownTab
                                } else {
                                    topRowFocus.getOrNull(index) ?: topRowFocus.last()
                                },
                                onFocused = {
                                    focused = entry
                                    focusedRow = rowIndex
                                    // The title's real backdrop where it is already known, and
                                    // only otherwise the poster. Asking for the better picture up
                                    // front is what makes this a single transition rather than a
                                    // poster that is replaced a moment later.
                                    if (entry.item.kind != MediaKind.LIVE) {
                                        backdrop.show(
                                            entry.key,
                                            bios[entry.key]?.backdropUrl?.takeIf { it.isNotBlank() }
                                                ?: entry.item.logoUrl
                                        )
                                    }
                                },
                                onClick = { onSelect(entry) }
                            )
                        }
                        // The way into the full catalogue comes before the empty places, never
                        // after them: five unfilled slots would otherwise push it off the right
                        // of the screen on a new install, hiding the one control that fills them.
                        if (showTile && tile != null) {
                            // The tile belongs to the page it is on, so Up from it goes to that
                            // page's tab. Without this it was the one focusable in the row with no
                            // answer for Up - and on Live TV, where nothing has been watched yet,
                            // it is the only thing in the row at all.
                            item(key = "${row.id}_all_categories") {
                                // The tile is the last focusable position in the top row, so it
                                // takes the requester for that slot - which is also the entry
                                // point Down aims at when nothing has been watched yet.
                                CategoryTile(
                                    tile,
                                    upTarget = ownTab,
                                    modifier = topRowFocus.getOrNull(row.entries.size)
                                        ?.let { Modifier.focusRequester(it) } ?: Modifier
                                )
                            }
                        }
                        if (placeholders > 0) {
                            items(placeholders, key = { "${row.id}_placeholder_$it" }) { EmptySlot() }
                        }
                    }
                    if (placeholders > 0 && row.hint != null) {
                        Text(
                            row.hint,
                            color = Tone.TextMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = Dims.SafeHorizontal)
                        )
                    }
                }
            }
            // Every row has a slot for the information block beneath it, but only the row holding
            // focus ever fills its own. Moving between rows therefore closes one slot and opens
            // the other, which is what carries the rows above up the screen to make room.
            //
            // Both slots are always in the layout so both can animate: were the block simply
            // removed from one place and added to another it would jump, where this collapses and
            // expands. And it takes only the height it needs - reserving room for the tallest
            // possible description left a hole on the page whenever nothing was focused at all.
            FadingInfo(
                key = focused?.takeIf { rowIndex == focusedRow },
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(
                        if (LocalReducedMotion.current) snap() else Motion.info()
                    )
                    .padding(horizontal = Dims.SafeHorizontal)
            ) { current ->
                if (current == null) {
                    // Nothing at all, not an empty box: a zero-height branch is what lets the
                    // next row sit directly under the artwork when this row does not hold focus.
                    Spacer(Modifier.fillMaxWidth().height(0.dp))
                } else {
                    FocusedItemInfo(
                        entry = current,
                        bio = bios[current.key],
                        favorite = isFavorite(current.item),
                        onPlay = { onSelect(current) },
                        onToggleFavorite = { onToggleFavorite(current.item) }
                    )
                }
            }
        }
        footer?.let {
            RevealOnAppear(delayMs = Motion.StaggerMs * 3) { it() }
        }
        Spacer(Modifier.height(Dims.SafeVertical))
    }
}

@Composable
private fun LandingCard(
    entry: LandingEntry,
    backdrop: BackdropState,
    modifier: Modifier,
    /** Where Up goes from this card. Null leaves Up to the normal focus search. */
    upTarget: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    val live = entry.item.kind == MediaKind.LIVE
    // A channel shows what is actually on air rather than its station logo.
    //
    // Whatever was saved for this channel goes up at once, however old it is - from memory if it
    // is there, otherwise read from disk - so the card has a picture in it immediately instead of
    // waiting on a stream connection. Only when that saved frame has aged does a fresh capture run
    // behind it, and it swaps in when it arrives. Captures stay serialised app-wide, because most
    // IPTV accounts cap concurrent streams and a row opening one per card would see them refused.
    val context = LocalContext.current
    var snapshot by remember(entry.key) { mutableStateOf(LiveSnapshotCache.memory(entry.key)) }
    var captureDone by remember(entry.key) { mutableStateOf(false) }
    if (live) {
        LaunchedEffect(entry.key) {
            if (snapshot == null) snapshot = LiveSnapshotCache.fromDisk(context, entry.key)
        }
        if (!captureDone && !LiveSnapshotCache.isFresh(entry.key)) {
            LiveSnapshotEffect(entry.item.streamUrl) { bitmap ->
                if (bitmap != null) {
                    LiveSnapshotCache.put(context, entry.key, bitmap)
                    snapshot = bitmap
                }
                captureDone = true
            }
        }
    }
    ArtCard(
        imageUrl = entry.item.logoUrl,
        title = entry.item.name,
        onClick = onClick,
        modifier = modifier.then(
            if (upTarget != null) {
                Modifier.onPreviewKeyEvent { event ->
                    if (event.isInitialKeyDown && event.key == Key.DirectionUp) {
                        runCatching { upTarget.requestFocus() }.isSuccess
                    } else false
                }
            } else Modifier
        ),
        progress = entry.progress,
        cornerBadge = entry.badge,
        liveFrame = snapshot.takeIf { live },
        // Channel tiles keep a visible outline: their artwork is a logo on a flat plate, and a
        // 6% growth on a small mark is far less legible than it is on a full-bleed still.
        useFocusBorder = live,
        overlayBadge = if (live) ({ LiveFlag(Modifier.align(Alignment.TopStart)) }) else null,
        // Which picture the background takes is decided by the page, which knows whether this
        // title's real backdrop has arrived yet. Channels never call it at all, so moving through
        // Live TV leaves whatever was last shown in place rather than stripping the room bare.
        onFocusChanged = { isFocused -> if (isFocused) onFocused() }
    )
}

/**
 * A drawn but empty card, exactly the size of a real one. Used to keep Favorites visible before
 * anything has been added to it: the row then reads as a place things go, rather than as a heading
 * with nothing underneath.
 */
@Composable
private fun EmptySlot() {
    Box(
        Modifier
            .width(Dims.CardWidth + Dims.CardBleed * 2)
            .padding(Dims.CardBleed)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(Dims.CardWidth * 9 / 16)
                .clip(RoundedCornerShape(Dims.RadiusCard))
                .background(Color.White.copy(alpha = .04f))
                .border(
                    BorderStroke(1.dp, Color.White.copy(alpha = .10f)),
                    RoundedCornerShape(Dims.RadiusCard)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.FavoriteBorder,
                null,
                tint = Color.White.copy(alpha = .16f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun CategoryTile(
    tile: LandingTile,
    upTarget: FocusRequester?,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .width(Dims.CardWidth + Dims.CardBleed * 2)
            .padding(Dims.CardBleed)
            .then(
                if (upTarget != null) {
                    Modifier.onPreviewKeyEvent { event ->
                        if (event.isInitialKeyDown && event.key == Key.DirectionUp) {
                            runCatching { upTarget.requestFocus() }.isSuccess
                        } else false
                    }
                } else Modifier
            )
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
                Modifier.align(Alignment.Center).padding(horizontal = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Default.Apps, null, tint = Tone.Accent, modifier = Modifier.size(20.dp))
                Text(
                    tile.title,
                    color = Tone.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tile.caption,
                        color = Tone.TextMuted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(Icons.Default.ChevronRight, null, tint = Tone.TextMuted, modifier = Modifier.size(12.dp))
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
    bio: ItemBio?,
    favorite: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val item = entry.item
    // The catalogue listing wins where it has a value; the fetched details fill the gaps. Neither
    // invents anything - a field both leave empty is simply left out of the line.
    val year = item.year?.takeIf { it.isNotBlank() } ?: bio?.year?.takeIf { it.isNotBlank() }
    val rating = validMovieRating(item.rating) ?: validMovieRating(bio?.rating)
    val duration = readableMovieDuration(item.duration) ?: readableMovieDuration(bio?.duration)
    val genre = bio?.genre?.takeIf { it.isNotBlank() }
    val description = item.description?.takeIf { it.isNotBlank() }
        ?: bio?.description?.takeIf { it.isNotBlank() }
    Column(verticalArrangement = Arrangement.spacedBy(Dims.GapS)) {
        Text(
            item.name,
            color = Tone.TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        MetadataRow(
            items = listOfNotNull(
                MetaItem(
                    when (item.kind) {
                        MediaKind.LIVE -> stringResource(R.string.nav_live_tv)
                        MediaKind.MOVIE -> stringResource(R.string.kind_movie)
                        MediaKind.SERIES -> stringResource(R.string.kind_series)
                    }
                ),
                year?.let { MetaItem(it) },
                rating?.let { MetaItem(it, star = true) },
                duration?.let { MetaItem(it) },
                (genre ?: item.group.takeIf { it.isNotBlank() })?.let { MetaItem(it) },
                entry.badge?.let { MetaItem(it, accent = true) }
            )
        )
        if (description != null) {
            Text(
                description,
                color = Tone.TextSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(.55f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dims.GapS), modifier = Modifier.padding(top = Dims.GapXs)) {
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
            Modifier.fillMaxWidth().padding(top = Dims.GapM, bottom = Dims.GapXs),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DeviceFact(Icons.Default.Schedule, "Playlist expires", expiryText)
            FactDivider()
            DeviceFact(Icons.Default.DeviceHub, "App MAC", appMac)
            FactDivider()
            DeviceFact(Icons.Default.VpnKey, "Device key", deviceKey)
        }
    }
}

@Composable
private fun DeviceFact(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dims.GapS)) {
        Icon(icon, null, tint = Tone.TextMuted, modifier = Modifier.size(22.dp))
        Column {
            Text(label, color = Tone.TextMuted, fontSize = 11.sp)
            Text(value, color = Tone.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun FactDivider() {
    Box(Modifier.width(1.dp).height(34.dp).background(Color.White.copy(alpha = .14f)))
}

/** A rounded corner used by callers that need the landing card's shape. */
internal val LandingCardShape = RoundedCornerShape(Dims.RadiusCard)
