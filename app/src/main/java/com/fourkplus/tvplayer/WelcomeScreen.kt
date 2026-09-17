package com.fourkplus.tvplayer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.fourkplus.tvplayer.data.LoadedPlaylist
import com.fourkplus.tvplayer.data.PlaylistInput
import com.fourkplus.tvplayer.data.PlaylistKind
import com.fourkplus.tvplayer.ui.design.ActionButton
import com.fourkplus.tvplayer.ui.design.Dims
import com.fourkplus.tvplayer.ui.design.GlassPanel
import com.fourkplus.tvplayer.ui.design.GlobalIconButton
import com.fourkplus.tvplayer.ui.design.Tone
import com.fourkplus.tvplayer.ui.design.tvFocusable
import kotlinx.coroutines.delay

/**
 * The first screen a new device sees: brand, welcome, and the two ways to attach a playlist.
 *
 * Only the presentation is new. The activation poll below is the same loop as before, talking to
 * the same endpoint with the same device identifiers and the same backoff, and "Add Playlist"
 * still opens the existing manual flow untouched.
 */
@Composable
internal fun ActivationScreen(
    currentLanguage: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    onManual: () -> Unit,
    onMessage: (String) -> Unit,
    loadPlaylist: suspend (PlaylistInput) -> Result<LoadedPlaylist>,
    onConnected: (LoadedPlaylist) -> Unit
) {
    var showLanguage by remember { mutableStateOf(false) }
    if (showLanguage) {
        LanguageDialog(
            current = currentLanguage,
            onSelect = { showLanguage = false; onLanguageChange(it) },
            onDismiss = { showLanguage = false }
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 820.dp
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dims.SafeHorizontal, vertical = Dims.SafeVertical),
            verticalArrangement = Arrangement.spacedBy(Dims.GapL)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.brand_logo_dark),
                    contentDescription = "4K Plus TV",
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.height(52.dp)
                )
                Spacer(Modifier.weight(1f))
                // Language is the only global control that means anything before a playlist
                // exists; search, settings and the sections have nothing to act on yet.
                GlobalIconButton(Icons.Default.Language, stringResource(R.string.cd_language)) {
                    showLanguage = true
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Dims.GapXs)) {
                Text(
                    stringResource(R.string.welcome),
                    color = Tone.TextPrimary,
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.activation_hero),
                    color = Tone.Accent,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.activation_subtitle),
                    color = Tone.TextSecondary,
                    fontSize = 17.sp
                )
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(Dims.GapL)) {
                    RemoteActivationPanel(Modifier.weight(1.1f), onMessage, loadPlaylist, onConnected)
                    ManualEntryPanel(Modifier.weight(.9f), onManual)
                }
            } else {
                RemoteActivationPanel(Modifier.fillMaxWidth(), onMessage, loadPlaylist, onConnected)
                ManualEntryPanel(Modifier.fillMaxWidth(), onManual)
            }
            Text(
                stringResource(R.string.not_a_media_provider),
                color = Tone.TextMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun RemoteActivationPanel(
    modifier: Modifier,
    onMessage: (String) -> Unit,
    loadPlaylist: suspend (PlaylistInput) -> Result<LoadedPlaylist>,
    onConnected: (LoadedPlaylist) -> Unit
) {
    val context = LocalContext.current
    var refreshing by remember { mutableStateOf(false) }
    val mac = remember { com.fourkplus.tvplayer.data.DeviceIdentity.mac(context) }
    val deviceKey = remember { com.fourkplus.tvplayer.data.DeviceIdentity.deviceKey(context) }
    val activatedPlaylistName = stringResource(R.string.activated_playlist_default_name)
    val noPlaylistAssignedYet = stringResource(R.string.no_playlist_assigned_yet)
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshSignal by remember { mutableIntStateOf(0) }
    var assigned by remember { mutableStateOf(false) }
    val currentLoad by rememberUpdatedState(loadPlaylist)
    val currentConnected by rememberUpdatedState(onConnected)
    val currentMessage by rememberUpdatedState(onMessage)
    // Unchanged from the previous screen: poll while the page is on screen, back off after real
    // failures, and treat "not assigned yet" as a normal state rather than an error to shout about.
    LaunchedEffect(mac, deviceKey, lifecycleOwner, refreshSignal) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            var failures = 0
            while (!assigned) {
                refreshing = true
                val result = try {
                    currentLoad(PlaylistInput(activatedPlaylistName, PlaylistKind.DEVICE_ACTIVATION, "", mac, deviceKey))
                } finally { refreshing = false }
                if (result.isSuccess) {
                    assigned = true
                    currentConnected(result.getOrThrow())
                    break
                }
                val error = result.exceptionOrNull()
                if (error is com.fourkplus.tvplayer.data.ActivationPendingException) {
                    failures = 0
                } else {
                    failures++
                    if (failures == 1) currentMessage(error?.message ?: noPlaylistAssignedYet)
                }
                delay(if (failures == 0) 5_000L else (5_000L * failures).coerceAtMost(30_000L))
            }
        }
    }

    val refreshFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { refreshFocus.requestFocus() } }
    GlassPanel(modifier, focused = true) {
        Column(Modifier.padding(Dims.GapL), verticalArrangement = Arrangement.spacedBy(Dims.GapM)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PanelIcon(Icons.Default.Devices)
                Spacer(Modifier.width(Dims.GapM))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.activate_via_app),
                        color = Tone.TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        Modifier
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Tone.Accent.copy(alpha = .18f))
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(
                            stringResource(R.string.recommended),
                            color = Tone.Accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            Text(stringResource(R.string.activation_instructions), color = Tone.TextSecondary, fontSize = 15.sp)
            DeviceCodeRow(stringResource(R.string.device_id), mac, onMessage)
            DeviceCodeRow(stringResource(R.string.device_key), deviceKey, onMessage)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Tone.Star)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.waiting_for_activation),
                        color = Tone.Star,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.weight(1f))
                ActionButton(
                    label = stringResource(R.string.cd_refresh_activation),
                    icon = Icons.Default.Refresh,
                    onClick = { refreshSignal++ },
                    modifier = Modifier.focusRequester(refreshFocus),
                    enabled = !refreshing
                )
            }
        }
    }
}

@Composable
private fun ManualEntryPanel(modifier: Modifier, onManual: () -> Unit) {
    GlassPanel(modifier) {
        Column(Modifier.padding(Dims.GapL), verticalArrangement = Arrangement.spacedBy(Dims.GapM)) {
            PanelIcon(Icons.Default.PlaylistAdd)
            Text(
                stringResource(R.string.add_playlist_manually),
                color = Tone.TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.add_playlist_manually_desc),
                color = Tone.TextSecondary,
                fontSize = 15.sp
            )
            Spacer(Modifier.height(Dims.GapXs))
            ActionButton(
                label = stringResource(R.string.add_playlist),
                icon = null,
                onClick = onManual,
                modifier = Modifier.fillMaxWidth(),
                primary = true
            )
        }
    }
}

@Composable
private fun PanelIcon(icon: ImageVector) {
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Tone.Accent.copy(alpha = .30f), Tone.AccentDeep.copy(alpha = .16f)))),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = Tone.TextPrimary, modifier = Modifier.size(26.dp))
    }
}

/** A code the viewer has to read off the screen and type into the dashboard, so it is set large,
 *  spaced, and copyable for anyone using the app on a phone or tablet. */
@Composable
private fun DeviceCodeRow(label: String, value: String, onMessage: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dims.RadiusCard))
            .background(Color(0x1F0B213A))
            .border(
                BorderStroke(if (focused) 2.dp else 1.dp, if (focused) Tone.Accent else Tone.GlassBorder),
                RoundedCornerShape(Dims.RadiusCard)
            )
            .tvFocusable(
                onFocusChanged = { focused = it },
                onClick = {
                    clipboard.setText(AnnotatedString(value))
                    onMessage("$label copied")
                }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Tone.TextMuted, fontSize = 13.sp)
            Text(
                value,
                color = Tone.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
        Icon(Icons.Default.ContentCopy, "Copy $label", tint = Tone.TextSecondary, modifier = Modifier.size(22.dp))
    }
}
