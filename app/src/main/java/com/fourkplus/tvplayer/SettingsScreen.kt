package com.fourkplus.tvplayer

import android.content.Context
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.fourkplus.tvplayer.data.LoadedPlaylist
import com.fourkplus.tvplayer.data.MediaKind
import com.fourkplus.tvplayer.data.PlaylistInput
import com.fourkplus.tvplayer.ui.theme.*

private enum class SettingsPage { ROOT, PLAYLIST, INFO, PLAYBACK, APPEARANCE, LANGUAGE, HISTORY, CATEGORIES, PARENTAL, LOCK_CATEGORIES, LOCK_CHANNELS }

@Composable
private fun settingsPageTitle(page: SettingsPage): String = when (page) {
    SettingsPage.ROOT -> stringResource(R.string.settings_title)
    SettingsPage.PLAYLIST -> stringResource(R.string.settings_playlists)
    SettingsPage.INFO -> stringResource(R.string.settings_app_info)
    SettingsPage.PLAYBACK -> stringResource(R.string.settings_playback)
    SettingsPage.APPEARANCE -> stringResource(R.string.settings_appearance)
    SettingsPage.LANGUAGE -> stringResource(R.string.cd_language)
    SettingsPage.HISTORY -> stringResource(R.string.settings_privacy_history)
    SettingsPage.CATEGORIES -> stringResource(R.string.settings_category_visibility)
    SettingsPage.PARENTAL -> stringResource(R.string.settings_parental_controls)
    SettingsPage.LOCK_CATEGORIES -> stringResource(R.string.lock_categories)
    SettingsPage.LOCK_CHANNELS -> stringResource(R.string.lock_channels)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    playlist: LoadedPlaylist?,
    source: PlaylistInput?,
    themeChoice: ThemeChoice,
    onThemeChange: (ThemeChoice) -> Unit,
    currentLanguage: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    parentalEnabled: Boolean,
    onParentalEnabledChange: (Boolean) -> Unit,
    askPinOnStartup: Boolean,
    onAskPinOnStartupChange: (Boolean) -> Unit,
    pinHash: String?,
    onPinHashChange: (String?) -> Unit,
    requirePin: (() -> Unit) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRename: (String) -> Unit,
    onManagePlaylists: () -> Unit,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
    onMessage: (String) -> Unit
) {
    var settingsPage by remember { mutableStateOf(SettingsPage.ROOT) }
    BackHandler {
        if (settingsPage == SettingsPage.ROOT) onBack() else settingsPage = SettingsPage.ROOT
    }
    val context = LocalContext.current
    val unknownLabel = stringResource(R.string.unknown_label)
    val appVersion = remember(context, unknownLabel) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { unknownLabel }
    }
    val playback = remember { context.getSharedPreferences("playback_settings", Context.MODE_PRIVATE) }
    val parental = remember { context.getSharedPreferences("parental_settings", Context.MODE_PRIVATE) }
    var playlistName by remember(source?.name, playlist?.name) { mutableStateOf(source?.name ?: playlist?.name.orEmpty()) }
    var skipSeconds by remember { mutableIntStateOf(playback.getInt("skip_seconds", 10)) }
    var subtitlesEnabled by remember { mutableStateOf(playback.getBoolean("subtitles_enabled", true)) }
    var muted by remember { mutableStateOf(playback.getBoolean("muted", false)) }
    var preferredSubtitle by remember { mutableStateOf(playback.getString("subtitle_language", "ar,en") ?: "ar,en") }
    var videoMode by remember { mutableStateOf(playback.getString("video_mode", "fit") ?: "fit") }
    var playerEngine by remember { mutableStateOf(playback.getString("player_engine", "default") ?: "default") }
    var connectionMode by remember { mutableStateOf(ConnectionMode.read(context)) }
    var liveChannelSort by remember { mutableStateOf(playback.getString("live_channel_sort", "default") ?: "default") }
    var autoUpdateInterval by remember { mutableStateOf(playback.getString("auto_update_interval", "daily") ?: "daily") }
    var hiddenLive by remember {
        mutableStateOf(parental.getStringSet("hidden_live_categories", emptySet()).orEmpty().toSet())
    }
    var hiddenMovies by remember {
        mutableStateOf(parental.getStringSet("hidden_movie_categories", emptySet()).orEmpty().toSet())
    }
    var hiddenSeries by remember {
        mutableStateOf(parental.getStringSet("hidden_series_categories", emptySet()).orEmpty().toSet())
    }
    var visibilitySection by remember { mutableStateOf(MediaKind.LIVE) }
    var categorySearch by remember { mutableStateOf("") }
    var hiddenOnly by remember { mutableStateOf(false) }
    var lockedLive by remember {
        mutableStateOf(parental.getStringSet("locked_live_categories", emptySet()).orEmpty().toSet())
    }
    var lockedMovies by remember {
        mutableStateOf(parental.getStringSet("locked_movie_categories", emptySet()).orEmpty().toSet())
    }
    var lockedSeries by remember {
        mutableStateOf(parental.getStringSet("locked_series_categories", emptySet()).orEmpty().toSet())
    }
    var lockVisibilitySection by remember { mutableStateOf(MediaKind.LIVE) }
    var lockCategorySearch by remember { mutableStateOf("") }
    var lockedCategoriesOnly by remember { mutableStateOf(false) }
    var lockedChannels by remember {
        mutableStateOf(parental.getStringSet("locked_channels", emptySet()).orEmpty().toSet())
    }
    var channelLockSearch by remember { mutableStateOf("") }
    var lockedChannelsOnly by remember { mutableStateOf(false) }
    var showPinSetupDialog by remember { mutableStateOf(false) }
    var pendingEnableAfterPinSetup by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    val parentalPinCreatedMessage = stringResource(R.string.parental_pin_created)
    val movieActivityClearedMessage = stringResource(R.string.movie_activity_cleared)
    val seriesActivityClearedMessage = stringResource(R.string.series_activity_cleared)
    val liveActivityClearedMessage = stringResource(R.string.live_activity_cleared)
    val allCategoriesVisibleMessage = stringResource(R.string.all_categories_visible)
    val allCategoriesUnlockedMessage = stringResource(R.string.all_categories_unlocked)
    val allChannelsUnlockedMessage = stringResource(R.string.all_channels_unlocked)
    val categoriesByKind = remember(playlist) {
        MediaKind.entries.associateWith { kind ->
            playlist?.items?.filter { it.kind == kind }?.map { it.group }?.distinct()?.sorted().orEmpty()
        }
    }

    if (showPinSetupDialog) {
        PinDialog(
            mode = "set",
            expectedHash = pinHash,
            onDismiss = { showPinSetupDialog = false; pendingEnableAfterPinSetup = false },
            onSuccess = { newHash ->
                parental.edit().putString("pin_hash", newHash).apply()
                onPinHashChange(newHash)
                onMessage(parentalPinCreatedMessage)
                if (pendingEnableAfterPinSetup) onParentalEnabledChange(true)
                pendingEnableAfterPinSetup = false
                showPinSetupDialog = false
            }
        )
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            icon = { Icon(Icons.Default.DeleteForever, null) },
            title = { Text(stringResource(R.string.remove_playlist_dialog_title)) },
            text = { Text(stringResource(R.string.remove_playlist_dialog_body)) },
            confirmButton = {
                TextButton(onClick = { showRemoveConfirm = false; onRemove() }) {
                    Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showRemoveConfirm = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedIconButton(onClick = { if (settingsPage == SettingsPage.ROOT) onBack() else settingsPage = SettingsPage.ROOT }) { Icon(Icons.Default.ArrowBack, stringResource(R.string.cd_back)) }
            Text(settingsPageTitle(settingsPage), fontSize = 27.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(8.dp))
        if (settingsPage == SettingsPage.ROOT) {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 30.dp)
            ) {
                item {
                    SettingsMenuGroup {
                        SettingsMenuRow(Icons.Default.PlaylistPlay, stringResource(R.string.settings_playlists), Orange) { settingsPage = SettingsPage.PLAYLIST }
                        SettingsMenuRow(Icons.Default.Info, stringResource(R.string.settings_app_info), BrandBlue) { settingsPage = SettingsPage.INFO }
                        SettingsMenuRow(Icons.Default.PlayCircle, stringResource(R.string.settings_playback), Cyan) { settingsPage = SettingsPage.PLAYBACK }
                        SettingsMenuRow(Icons.Default.Palette, stringResource(R.string.settings_appearance), BrandBlue) { settingsPage = SettingsPage.APPEARANCE }
                        SettingsMenuRow(Icons.Default.Language, stringResource(R.string.cd_language), Cyan) { settingsPage = SettingsPage.LANGUAGE }
                    }
                }
                item {
                    SettingsMenuGroup {
                        SettingsMenuRow(Icons.Default.History, stringResource(R.string.settings_privacy_history), Cyan) { settingsPage = SettingsPage.HISTORY }
                        SettingsMenuRow(Icons.Default.VisibilityOff, stringResource(R.string.settings_category_visibility), Orange) { settingsPage = SettingsPage.CATEGORIES }
                        SettingsMenuRow(Icons.Default.AdminPanelSettings, stringResource(R.string.settings_parental_controls), BrandBlue) { settingsPage = SettingsPage.PARENTAL }
                    }
                }
                item {
                    Text(
                        stringResource(R.string.footer_version, appVersion),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 30.dp)
        ) {
            if (settingsPage == SettingsPage.PLAYLIST) item {
                SettingsSection(stringResource(R.string.settings_playlists), Icons.Default.PlaylistPlay) {
                    SettingsAction(
                        Icons.Default.PlaylistPlay,
                        stringResource(R.string.manage_saved_playlists),
                        stringResource(R.string.manage_saved_playlists_desc),
                        onManagePlaylists
                    )
                    HorizontalDivider()
                    Text(stringResource(R.string.current_playlist), fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = playlistName,
                        onValueChange = { playlistName = it },
                        label = { Text(stringResource(R.string.playlist_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onRename(playlistName.trim()) },
                        enabled = playlistName.isNotBlank() && playlistName.trim() != source?.name,
                        modifier = Modifier.fillMaxWidth()
                    ) { Icon(Icons.Default.DriveFileRenameOutline, null); Spacer(Modifier.width(7.dp)); Text(stringResource(R.string.rename_action)) }
                    SettingsAction(Icons.Default.Refresh, stringResource(R.string.refresh_playlist), stringResource(R.string.refresh_playlist_desc), onRefresh)
                    HorizontalDivider()
                    Text(stringResource(R.string.auto_update_title), fontWeight = FontWeight.Bold)
                    listOf(
                        "everytime" to stringResource(R.string.auto_update_everytime),
                        "daily" to stringResource(R.string.auto_update_daily),
                        "every_2_days" to stringResource(R.string.auto_update_every_2_days)
                    ).forEach { option ->
                        RadioSetting(
                            title = option.second,
                            selected = autoUpdateInterval == option.first,
                            onClick = {
                                autoUpdateInterval = option.first
                                playback.edit().putString("auto_update_interval", option.first).apply()
                            }
                        )
                    }
                    HorizontalDivider()
                    SettingsAction(Icons.Default.AddCircleOutline, stringResource(R.string.add_another_playlist), stringResource(R.string.add_another_playlist_desc), onReplace)
                    SettingsAction(
                        Icons.Default.DeleteForever,
                        stringResource(R.string.remove_playlist_action),
                        stringResource(R.string.remove_playlist_desc),
                        { showRemoveConfirm = true },
                        destructive = true
                    )
                    source?.let {
                        Text(
                            if (it.username.isNotBlank()) stringResource(R.string.provider_account_label, it.username) else stringResource(R.string.m3u_playlist_label),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (settingsPage == SettingsPage.INFO) item {
                SettingsSection(stringResource(R.string.settings_app_info), Icons.Default.Info) {
                    Text(stringResource(R.string.application_label), fontWeight = FontWeight.Bold)
                    InformationRow(stringResource(R.string.app_name_label), stringResource(R.string.app_name))
                    InformationRow(stringResource(R.string.version_label), appVersion)
                    InformationRow(stringResource(R.string.android_label), android.os.Build.VERSION.RELEASE)
                    HorizontalDivider()
                    Text(stringResource(R.string.active_playlist_label), fontWeight = FontWeight.Bold)
                    InformationRow(stringResource(R.string.name_label), playlist?.name ?: stringResource(R.string.no_active_playlist))
                    InformationRow(
                        stringResource(R.string.type_label),
                        when (source?.kind) {
                            com.fourkplus.tvplayer.data.PlaylistKind.PROVIDER_LOGIN -> stringResource(R.string.provider_login_type)
                            com.fourkplus.tvplayer.data.PlaylistKind.M3U_URL -> stringResource(R.string.m3u_url_type)
                            com.fourkplus.tvplayer.data.PlaylistKind.DEVICE_ACTIVATION -> stringResource(R.string.provider_login_type)
                            null -> stringResource(R.string.not_available)
                        }
                    )
                    InformationRow(stringResource(R.string.username_row_label), source?.username?.takeIf(String::isNotBlank) ?: stringResource(R.string.not_applicable))
                    InformationRow(stringResource(R.string.status_label), playlist?.accountStatus ?: stringResource(R.string.not_provided))
                    InformationRow(
                        stringResource(R.string.expiry_date_label),
                        playlist?.expiryEpochSeconds?.let {
                            DateTimeFormatter.ofPattern("dd MMM yyyy")
                                .withZone(ZoneId.systemDefault())
                                .format(Instant.ofEpochSecond(it))
                        } ?: stringResource(R.string.not_provided)
                    )
                    InformationRow(stringResource(R.string.items_label), playlist?.items?.size?.toString() ?: "0")
                }
            }

            if (settingsPage == SettingsPage.PLAYBACK) item {
                SettingsSection(stringResource(R.string.settings_playback), Icons.Default.PlayCircle) {
                    Text(stringResource(R.string.skip_interval), fontWeight = FontWeight.Bold)
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        listOf(5, 10, 15, 30, 60).forEach { seconds ->
                            FilterChip(
                                selected = skipSeconds == seconds,
                                onClick = {
                                    skipSeconds = seconds
                                    playback.edit().putInt("skip_seconds", seconds).apply()
                                },
                                label = { Text(stringResource(R.string.seconds_format, seconds)) }
                            )
                        }
                    }
                    SettingsSwitch(
                        title = stringResource(R.string.start_muted),
                        description = stringResource(R.string.start_muted_desc),
                        checked = muted,
                        onChecked = {
                            muted = it
                            playback.edit().putBoolean("muted", it).apply()
                        }
                    )
                    SettingsSwitch(
                        title = stringResource(R.string.embedded_subtitles),
                        description = stringResource(R.string.embedded_subtitles_desc),
                        checked = subtitlesEnabled,
                        onChecked = {
                            subtitlesEnabled = it
                            playback.edit().putBoolean("subtitles_enabled", it).apply()
                        }
                    )
                    Text(stringResource(R.string.preferred_subtitle_language), fontWeight = FontWeight.Bold)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("ar,en" to stringResource(R.string.subtitle_lang_arabic), "en,ar" to stringResource(R.string.subtitle_lang_english)).forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = preferredSubtitle == option.first,
                                onClick = {
                                    preferredSubtitle = option.first
                                    playback.edit().putString("subtitle_language", option.first).apply()
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, 2)
                            ) { Text(option.second) }
                        }
                    }
                    Text(stringResource(R.string.video_scaling), fontWeight = FontWeight.Bold)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("fit" to stringResource(R.string.video_fit), "zoom" to stringResource(R.string.video_fill), "stretch" to stringResource(R.string.video_stretch)).forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = videoMode == option.first,
                                onClick = {
                                    videoMode = option.first
                                    playback.edit().putString("video_mode", option.first).apply()
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, 3)
                            ) { Text(option.second) }
                        }
                    }
                    Text(
                        when (videoMode) {
                            "zoom" -> stringResource(R.string.video_fill_desc)
                            "stretch" -> stringResource(R.string.video_stretch_desc)
                            else -> stringResource(R.string.video_fit_desc)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    HorizontalDivider()
                    Text(stringResource(R.string.live_channel_sort), fontWeight = FontWeight.Bold)
                    listOf(
                        "default" to stringResource(R.string.sort_default),
                        "az" to stringResource(R.string.sort_az),
                        "za" to stringResource(R.string.sort_za)
                    ).forEach { option ->
                        RadioSetting(
                            title = option.second,
                            selected = liveChannelSort == option.first,
                            onClick = {
                                liveChannelSort = option.first
                                playback.edit().putString("live_channel_sort", option.first).apply()
                            }
                        )
                    }
                    HorizontalDivider()
                    Text(stringResource(R.string.connection_mode), fontWeight = FontWeight.Bold)
                    listOf(
                        ConnectionMode.FAST to (stringResource(R.string.connection_fast_name) to stringResource(R.string.connection_fast_desc)),
                        ConnectionMode.SLOW to (stringResource(R.string.connection_slow_name) to stringResource(R.string.connection_slow_desc))
                    ).forEach { option ->
                        RadioSetting(
                            title = option.second.first,
                            description = option.second.second,
                            selected = connectionMode == option.first,
                            onClick = {
                                connectionMode = option.first
                                playback.edit().putString(ConnectionMode.KEY, option.first.name).apply()
                            }
                        )
                    }
                    Text(
                        stringResource(R.string.connection_mode_restart_note),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    HorizontalDivider()
                    Text(stringResource(R.string.player_engine), fontWeight = FontWeight.Bold)
                    listOf(
                        "default" to (stringResource(R.string.player_default_name) to stringResource(R.string.player_default_desc)),
                        "vlc" to ("VLC" to stringResource(R.string.player_vlc_desc)),
                        "mx" to ("MX Player" to stringResource(R.string.player_mx_desc))
                    ).forEach { option ->
                        RadioSetting(
                            title = option.second.first,
                            description = option.second.second,
                            selected = playerEngine == option.first,
                            onClick = {
                                playerEngine = option.first
                                playback.edit().putString("player_engine", option.first).apply()
                            }
                        )
                    }
                }
            }

            if (settingsPage == SettingsPage.APPEARANCE) item {
                SettingsSection(stringResource(R.string.settings_appearance), Icons.Default.Palette) {
                    Text(stringResource(R.string.theme_label), fontWeight = FontWeight.Bold)
                    ThemeChoice.entries.forEach { choice ->
                        RadioSetting(
                            title = themeChoiceLabel(choice),
                            selected = themeChoice == choice,
                            onClick = { onThemeChange(choice) }
                        )
                    }
                }
            }

            if (settingsPage == SettingsPage.LANGUAGE) item {
                SettingsSection(stringResource(R.string.cd_language), Icons.Default.Language) {
                    AppLanguage.entries.forEach { language ->
                        RadioSetting(
                            title = languageLabel(language),
                            selected = currentLanguage == language,
                            onClick = { onLanguageChange(language) }
                        )
                    }
                }
            }

            if (settingsPage == SettingsPage.HISTORY) item {
                SettingsSection(stringResource(R.string.settings_privacy_history), Icons.Default.History) {
                    SettingsAction(Icons.Default.Movie, stringResource(R.string.clear_movie_activity), stringResource(R.string.clear_movie_activity_desc), {
                        context.getSharedPreferences("movie_library", Context.MODE_PRIVATE).edit().clear().apply()
                        onMessage(movieActivityClearedMessage)
                    })
                    SettingsAction(Icons.Default.VideoLibrary, stringResource(R.string.clear_series_activity), stringResource(R.string.clear_series_activity_desc), {
                        context.getSharedPreferences("series_library", Context.MODE_PRIVATE).edit().clear().apply()
                        onMessage(seriesActivityClearedMessage)
                    })
                    SettingsAction(Icons.Default.LiveTv, stringResource(R.string.clear_live_activity), stringResource(R.string.clear_live_activity_desc), {
                        context.getSharedPreferences("favorite_channels", Context.MODE_PRIVATE).edit().clear().apply()
                        onMessage(liveActivityClearedMessage)
                    })
                }
            }

            if (settingsPage == SettingsPage.CATEGORIES) item {
                SettingsSection(stringResource(R.string.settings_category_visibility), Icons.Default.VisibilityOff) {
                    Text(
                        stringResource(R.string.category_visibility_desc),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            MediaKind.LIVE to stringResource(R.string.nav_live_tv),
                            MediaKind.MOVIE to stringResource(R.string.nav_movies),
                            MediaKind.SERIES to stringResource(R.string.nav_series)
                        ).forEach { (kind, label) ->
                            FilterChip(
                                selected = visibilitySection == kind,
                                onClick = { visibilitySection = kind; categorySearch = "" },
                                label = { Text(label) },
                                leadingIcon = {
                                    Icon(
                                        when (kind) {
                                            MediaKind.LIVE -> Icons.Default.LiveTv
                                            MediaKind.MOVIE -> Icons.Default.Movie
                                            MediaKind.SERIES -> Icons.Default.VideoLibrary
                                        },
                                        null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = categorySearch,
                        onValueChange = { categorySearch = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.search_categories)) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (categorySearch.isNotEmpty()) {
                                AnimatedIconButton(onClick = { categorySearch = "" }) {
                                    Icon(Icons.Default.Close, stringResource(R.string.cd_clear))
                                }
                            }
                        }
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.show_hidden_only), Modifier.weight(1f))
                        Switch(checked = hiddenOnly, onCheckedChange = { hiddenOnly = it })
                    }
                    val activeHidden = when (visibilitySection) {
                        MediaKind.LIVE -> hiddenLive
                        MediaKind.MOVIE -> hiddenMovies
                        MediaKind.SERIES -> hiddenSeries
                    }
                    val activeKey = when (visibilitySection) {
                        MediaKind.LIVE -> "hidden_live_categories"
                        MediaKind.MOVIE -> "hidden_movie_categories"
                        MediaKind.SERIES -> "hidden_series_categories"
                    }
                    val displayedCategories = categoriesByKind[visibilitySection].orEmpty().filter { category ->
                        (!hiddenOnly || category in activeHidden) &&
                            (categorySearch.isBlank() || category.contains(categorySearch.trim(), ignoreCase = true))
                    }
                    if (displayedCategories.isEmpty()) {
                        Text(
                            if (hiddenOnly) stringResource(R.string.no_hidden_categories) else stringResource(R.string.no_categories_match),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 10.dp)
                        )
                    }
                    displayedCategories.forEach { category ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = if (category in activeHidden) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f)
                            } else {
                                Cyan.copy(alpha = .11f)
                            }
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(category, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (category in activeHidden) stringResource(R.string.hidden_label) else stringResource(R.string.visible_label),
                                        color = if (category in activeHidden) MaterialTheme.colorScheme.onSurfaceVariant else Cyan,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                Switch(
                                    checked = category !in activeHidden,
                                    onCheckedChange = { visible ->
                                        val updated = if (visible) activeHidden - category else activeHidden + category
                                        when (visibilitySection) {
                                            MediaKind.LIVE -> hiddenLive = updated
                                            MediaKind.MOVIE -> hiddenMovies = updated
                                            MediaKind.SERIES -> hiddenSeries = updated
                                        }
                                        parental.edit().putStringSet(activeKey, updated).apply()
                                    }
                                )
                            }
                        }
                    }
                    if (activeHidden.isNotEmpty()) {
                        Button(
                            onClick = {
                                when (visibilitySection) {
                                    MediaKind.LIVE -> hiddenLive = emptySet()
                                    MediaKind.MOVIE -> hiddenMovies = emptySet()
                                    MediaKind.SERIES -> hiddenSeries = emptySet()
                                }
                                parental.edit().remove(activeKey).apply()
                                onMessage(allCategoriesVisibleMessage)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Visibility, null)
                            Spacer(Modifier.width(7.dp))
                            Text(stringResource(R.string.show_all_hidden))
                        }
                    }
                }
            }

            if (settingsPage == SettingsPage.PARENTAL) item {
                SettingsSection(stringResource(R.string.settings_parental_controls), Icons.Default.AdminPanelSettings) {
                    SettingsSwitch(
                        title = stringResource(R.string.enable_parental_control),
                        description = stringResource(R.string.enable_parental_control_desc),
                        checked = parentalEnabled,
                        onChecked = { enable ->
                            if (enable && pinHash == null) {
                                pendingEnableAfterPinSetup = true
                                showPinSetupDialog = true
                            } else if (enable) {
                                onParentalEnabledChange(true)
                            } else {
                                requirePin { onParentalEnabledChange(false) }
                            }
                        }
                    )
                    SettingsSwitch(
                        title = stringResource(R.string.ask_pin_on_startup),
                        description = stringResource(R.string.ask_pin_on_startup_desc),
                        checked = askPinOnStartup,
                        enabled = parentalEnabled,
                        onChecked = { onAskPinOnStartupChange(it) }
                    )
                    HorizontalDivider()
                    SettingsAction(Icons.Default.Pin, stringResource(R.string.change_pin), stringResource(R.string.change_pin_desc), {
                        requirePin { showPinSetupDialog = true }
                    })
                    SettingsAction(
                        Icons.Default.VisibilityOff,
                        stringResource(R.string.settings_category_visibility),
                        stringResource(R.string.category_visibility_desc),
                        { settingsPage = SettingsPage.CATEGORIES }
                    )
                    SettingsAction(
                        Icons.Default.Lock,
                        stringResource(R.string.lock_categories),
                        stringResource(R.string.lock_categories_desc),
                        { requirePin { settingsPage = SettingsPage.LOCK_CATEGORIES } }
                    )
                    SettingsAction(
                        Icons.Default.Lock,
                        stringResource(R.string.lock_channels),
                        stringResource(R.string.lock_channels_desc),
                        { requirePin { settingsPage = SettingsPage.LOCK_CHANNELS } }
                    )
                }
            }

            if (settingsPage == SettingsPage.LOCK_CATEGORIES) item {
                SettingsSection(stringResource(R.string.lock_categories), Icons.Default.Lock) {
                    Text(
                        stringResource(R.string.lock_categories_desc),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            MediaKind.LIVE to stringResource(R.string.nav_live_tv),
                            MediaKind.MOVIE to stringResource(R.string.nav_movies),
                            MediaKind.SERIES to stringResource(R.string.nav_series)
                        ).forEach { (kind, label) ->
                            FilterChip(
                                selected = lockVisibilitySection == kind,
                                onClick = { lockVisibilitySection = kind; lockCategorySearch = "" },
                                label = { Text(label) },
                                leadingIcon = {
                                    Icon(
                                        when (kind) {
                                            MediaKind.LIVE -> Icons.Default.LiveTv
                                            MediaKind.MOVIE -> Icons.Default.Movie
                                            MediaKind.SERIES -> Icons.Default.VideoLibrary
                                        },
                                        null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = lockCategorySearch,
                        onValueChange = { lockCategorySearch = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.search_categories)) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (lockCategorySearch.isNotEmpty()) {
                                AnimatedIconButton(onClick = { lockCategorySearch = "" }) {
                                    Icon(Icons.Default.Close, stringResource(R.string.cd_clear))
                                }
                            }
                        }
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.show_locked_only), Modifier.weight(1f))
                        Switch(checked = lockedCategoriesOnly, onCheckedChange = { lockedCategoriesOnly = it })
                    }
                    val activeLocked = when (lockVisibilitySection) {
                        MediaKind.LIVE -> lockedLive
                        MediaKind.MOVIE -> lockedMovies
                        MediaKind.SERIES -> lockedSeries
                    }
                    val activeKey = when (lockVisibilitySection) {
                        MediaKind.LIVE -> "locked_live_categories"
                        MediaKind.MOVIE -> "locked_movie_categories"
                        MediaKind.SERIES -> "locked_series_categories"
                    }
                    val displayedCategories = categoriesByKind[lockVisibilitySection].orEmpty().filter { category ->
                        (!lockedCategoriesOnly || category in activeLocked) &&
                            (lockCategorySearch.isBlank() || category.contains(lockCategorySearch.trim(), ignoreCase = true))
                    }
                    if (displayedCategories.isEmpty()) {
                        Text(
                            if (lockedCategoriesOnly) stringResource(R.string.no_locked_categories) else stringResource(R.string.no_categories_match),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 10.dp)
                        )
                    }
                    displayedCategories.forEach { category ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = if (category in activeLocked) Orange.copy(alpha = .16f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(category, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (category in activeLocked) stringResource(R.string.locked_label) else stringResource(R.string.unlocked_label),
                                        color = if (category in activeLocked) Orange else MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                Switch(
                                    checked = category in activeLocked,
                                    onCheckedChange = { locked ->
                                        val updated = if (locked) activeLocked + category else activeLocked - category
                                        when (lockVisibilitySection) {
                                            MediaKind.LIVE -> lockedLive = updated
                                            MediaKind.MOVIE -> lockedMovies = updated
                                            MediaKind.SERIES -> lockedSeries = updated
                                        }
                                        parental.edit().putStringSet(activeKey, updated).apply()
                                    }
                                )
                            }
                        }
                    }
                    if (activeLocked.isNotEmpty()) {
                        Button(
                            onClick = {
                                when (lockVisibilitySection) {
                                    MediaKind.LIVE -> lockedLive = emptySet()
                                    MediaKind.MOVIE -> lockedMovies = emptySet()
                                    MediaKind.SERIES -> lockedSeries = emptySet()
                                }
                                parental.edit().remove(activeKey).apply()
                                onMessage(allCategoriesUnlockedMessage)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.LockOpen, null)
                            Spacer(Modifier.width(7.dp))
                            Text(stringResource(R.string.unlock_all_categories))
                        }
                    }
                }
            }

            if (settingsPage == SettingsPage.LOCK_CHANNELS) item {
                SettingsSection(stringResource(R.string.lock_channels), Icons.Default.Lock) {
                    Text(
                        stringResource(R.string.lock_channels_desc),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = channelLockSearch,
                        onValueChange = { channelLockSearch = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.search_channels)) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (channelLockSearch.isNotEmpty()) {
                                AnimatedIconButton(onClick = { channelLockSearch = "" }) {
                                    Icon(Icons.Default.Close, stringResource(R.string.cd_clear))
                                }
                            }
                        }
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.show_locked_only), Modifier.weight(1f))
                        Switch(checked = lockedChannelsOnly, onCheckedChange = { lockedChannelsOnly = it })
                    }
                    val liveChannels = remember(playlist) {
                        playlist?.items?.filter { it.kind == MediaKind.LIVE }.orEmpty()
                    }
                    val displayedChannels = remember(liveChannels, channelLockSearch, lockedChannelsOnly, lockedChannels) {
                        liveChannels.filter { channel ->
                            val key = channelKey(channel)
                            (!lockedChannelsOnly || key in lockedChannels) &&
                                (channelLockSearch.isBlank() || channel.name.contains(channelLockSearch.trim(), ignoreCase = true))
                        }
                    }
                    if (displayedChannels.isEmpty()) {
                        Text(
                            if (lockedChannelsOnly) stringResource(R.string.no_locked_channels) else stringResource(R.string.no_channels_match),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 10.dp)
                        )
                    }
                    displayedChannels.take(300).forEach { channel ->
                        val key = channelKey(channel)
                        val locked = key in lockedChannels
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = if (locked) Orange.copy(alpha = .16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(channel.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(
                                        channel.group,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1
                                    )
                                }
                                Switch(
                                    checked = locked,
                                    onCheckedChange = { isLocked ->
                                        val updated = if (isLocked) lockedChannels + key else lockedChannels - key
                                        lockedChannels = updated
                                        parental.edit().putStringSet("locked_channels", updated).apply()
                                    }
                                )
                            }
                        }
                    }
                    if (lockedChannels.isNotEmpty()) {
                        Button(
                            onClick = {
                                lockedChannels = emptySet()
                                parental.edit().remove("locked_channels").apply()
                                onMessage(allChannelsUnlockedMessage)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.LockOpen, null)
                            Spacer(Modifier.width(7.dp))
                            Text(stringResource(R.string.unlock_all_channels))
                        }
                    }
                }
            }

            item {
                Text(
                    stringResource(R.string.footer_version, appVersion),
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun InformationRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SettingsMenuGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
        tonalElevation = 3.dp
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun SettingsMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Surface(onClick = onClick, color = androidx.compose.ui.graphics.Color.Transparent) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 17.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(23.dp))
            Spacer(Modifier.width(16.dp))
            Text(title, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsSection(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .96f))
    ) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = Cyan)
                Spacer(Modifier.width(9.dp))
                Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
private fun SettingsAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    destructive: Boolean = false
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (destructive) MaterialTheme.colorScheme.error else Cyan)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsSwitch(title: String, description: String, checked: Boolean, enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    val contentAlpha = if (enabled) 1f else .5f
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = LocalContentColor.current.copy(alpha = contentAlpha))
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

@Composable
private fun RadioSetting(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    description: String? = null
) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface.copy(alpha = 0f)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Column {
                Text(title)
                description?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun PinDialog(
    mode: String,
    expectedHash: String?,
    dismissible: Boolean = true,
    onDismiss: () -> Unit,
    onSuccess: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val pinLengthError = stringResource(R.string.pin_length_error)
    val pinsDoNotMatchError = stringResource(R.string.pins_do_not_match)
    val incorrectPinError = stringResource(R.string.incorrect_pin)
    AlertDialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible),
        icon = { Icon(Icons.Default.Pin, null) },
        title = { Text(if (mode == "set") stringResource(R.string.create_parental_pin) else stringResource(R.string.enter_parental_pin)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RevealablePasswordField(
                    value = pin,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pin = it },
                    label = stringResource(R.string.pin_digit_hint)
                )
                if (mode == "set") {
                    RevealablePasswordField(
                        value = confirmation,
                        onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) confirmation = it },
                        label = stringResource(R.string.confirm_pin)
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    pin.length !in 4..6 -> error = pinLengthError
                    mode == "set" && pin != confirmation -> error = pinsDoNotMatchError
                    mode != "set" && pinSha256(pin) != expectedHash -> error = incorrectPinError
                    else -> onSuccess(pinSha256(pin))
                }
            }) { Text(if (mode == "set") stringResource(R.string.create_action) else stringResource(R.string.unlock_action)) }
        },
        dismissButton = if (dismissible) {{ TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }} else null
    )
}

private fun pinSha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
