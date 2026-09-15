package ru.dvedev.me.cupola.ui.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.dvedev.me.cupola.R
import ru.dvedev.me.cupola.analysis.SessionUiState
import ru.dvedev.me.cupola.dsp.metrics.VoiceType
import ru.dvedev.me.cupola.ui.components.Label
import ru.dvedev.me.cupola.ui.components.PillButton
import ru.dvedev.me.cupola.ui.components.PillStyle
import ru.dvedev.me.cupola.ui.components.ZoneDivider
import ru.dvedev.me.cupola.ui.theme.CupolaDimens
import ru.dvedev.me.cupola.ui.theme.CupolaTheme

@Composable
fun voiceTypeName(type: VoiceType): String = stringResource(
    when (type) {
        VoiceType.BASS -> R.string.voice_bass
        VoiceType.BARITONE -> R.string.voice_baritone
        VoiceType.TENOR -> R.string.voice_tenor
        VoiceType.ALTO -> R.string.voice_alto
        VoiceType.SOPRANO -> R.string.voice_soprano
        VoiceType.UNSET -> R.string.voice_unset
    },
)

/** Top bar (SPEC §15.3 ①): brand · voice · band | time · points · Pause · Start/Stop · gear. */
@Composable
fun TopBar(
    voiceType: VoiceType,
    session: SessionUiState,
    onStartStop: () -> Unit,
    onPause: () -> Unit,
    onSettings: () -> Unit,
    compact: Boolean = false,
) {
    val c = CupolaTheme.colors
    val t = CupolaTheme.type
    val band = voiceType.band
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.zoneHeader)
            .padding(horizontal = CupolaDimens.paddingH, vertical = CupolaDimens.topBarPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(stringResource(R.string.app_name), color = c.ink)
        if (!compact) {
            Label(" · ")
            Label(voiceTypeName(voiceType))
            Label(" · ")
            Label("%.1f–%.1f ".format(band.loHz / 1000, band.hiHz / 1000) + stringResource(R.string.unit_khz))
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(formatTime(session.elapsedSec), style = t.stats, color = if (session.active) c.ink else c.dim)
            Text(session.points.toString(), style = t.stats, color = c.goldInk)
            if (session.active) {
                PillButton(
                    text = stringResource(if (session.paused) R.string.action_resume else R.string.action_pause),
                    onClick = onPause,
                    style = PillStyle.Outline,
                    active = session.paused,
                )
            }
            PillButton(
                text = stringResource(if (session.active) R.string.action_stop else R.string.action_start),
                onClick = onStartStop,
                style = if (session.active) PillStyle.Muted else PillStyle.Primary,
            )
            Box(
                Modifier
                    .size(CupolaDimens.gearSize)
                    .clip(CircleShape)
                    .background(c.panel)
                    .border(1.dp, c.line, CircleShape)
                    .clickable(onClick = onSettings),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.action_settings), tint = c.mut, modifier = Modifier.size(16.dp))
            }
        }
    }
    ZoneDivider()
}
