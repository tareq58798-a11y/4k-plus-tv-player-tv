package com.fourkplus.tvplayer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fourkplus.tvplayer.ui.design.Dims
import com.fourkplus.tvplayer.ui.design.GlassPanel
import com.fourkplus.tvplayer.ui.design.GlobalIconButton
import com.fourkplus.tvplayer.ui.design.Motion
import com.fourkplus.tvplayer.ui.design.Tone
import com.fourkplus.tvplayer.ui.design.tvFocusable

/**
 * Language selection as its own page rather than a dialog, so it gets the room a remote needs and
 * keeps the cinematic background behind it.
 *
 * Nothing about how languages are applied has changed: this still calls straight through to the
 * same handler the Settings list used, which writes the tag and recreates the activity.
 */
@Composable
internal fun LanguageScreen(
    current: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val firstRow = remember { FocusRequester() }
    val languages = remember { AppLanguage.entries.toList() }
    LaunchedEffect(Unit) { runCatching { firstRow.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(horizontal = Dims.SafeHorizontal, vertical = Dims.SafeVertical)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dims.GapM)) {
            GlobalIconButton(Icons.Default.ArrowBack, stringResource(R.string.cd_back), onBack)
            Column {
                Text(
                    stringResource(R.string.cd_language),
                    color = Tone.TextPrimary,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.language_choose_subtitle),
                    color = Tone.TextSecondary,
                    fontSize = 16.sp
                )
            }
        }
        GlassPanel(Modifier.fillMaxWidth().padding(top = Dims.GapXl)) {
            Column(Modifier.padding(Dims.GapL)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(Dims.GapM),
                    verticalArrangement = Arrangement.spacedBy(Dims.GapS)
                ) {
                    items(languages, key = { it.name }) { language ->
                        LanguageRow(
                            label = languageLabel(language),
                            selected = language == current,
                            modifier = if (language == languages.first()) Modifier.focusRequester(firstRow) else Modifier,
                            onClick = { onSelect(language) }
                        )
                    }
                }
                Text(
                    stringResource(R.string.language_press_ok),
                    color = Tone.TextMuted,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = Dims.GapL),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun LanguageRow(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val border by animateColorAsState(
        if (focused) Tone.Accent else Tone.GlassBorder,
        Motion.focus(),
        label = "languageBorder"
    )
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dims.RadiusCard))
            .background(if (focused) Tone.GlassStrong else Tone.Glass)
            .border(BorderStroke(if (focused) 2.dp else 1.dp, border), RoundedCornerShape(Dims.RadiusCard))
            .tvFocusable(onFocusChanged = { focused = it }, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = Tone.TextPrimary,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        // The tick, not the highlight, is what says "this is the language in use" - the highlight
        // only ever means "this is where the remote is".
        if (selected) {
            Icon(Icons.Default.Check, null, tint = Tone.Accent, modifier = Modifier.size(24.dp))
        } else {
            Box(Modifier.size(24.dp).background(Color.Transparent))
        }
    }
}
