package ru.dvedev.me.cupola.ui.roomnoise

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.dvedev.me.cupola.R
import ru.dvedev.me.cupola.dsp.metrics.RingBand
import ru.dvedev.me.cupola.dsp.metrics.RoomNoise
import ru.dvedev.me.cupola.roomnoise.RoomNoiseStep
import ru.dvedev.me.cupola.roomnoise.RoomNoiseViewModel
import ru.dvedev.me.cupola.ui.analysis.SpectrumZone
import ru.dvedev.me.cupola.ui.components.Badge
import ru.dvedev.me.cupola.ui.components.Label
import ru.dvedev.me.cupola.ui.components.PillButton
import ru.dvedev.me.cupola.ui.components.PillStyle
import ru.dvedev.me.cupola.ui.components.ZoneDivider
import ru.dvedev.me.cupola.ui.components.ZoneHeader
import ru.dvedev.me.cupola.ui.theme.CupolaDimens
import ru.dvedev.me.cupola.ui.theme.CupolaTheme
import kotlin.math.ceil

/**
 * Room noise measurement: intro → 3 s of silence → result. Reached from settings,
 * onboarding and a long press on the cupola arc. Replaces the calibration of v0.1.0.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoomNoiseScreen(vm: RoomNoiseViewModel, band: RingBand, onDone: () -> Unit, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val c = CupolaTheme.colors
    val t = CupolaTheme.type
    LaunchedEffect(s.saved) { if (s.saved) onDone() }

    Column(Modifier.fillMaxSize().background(c.panel).systemBarsPadding()) {
        ZoneHeader(
            left = {
                Label(stringResource(R.string.noise_title), color = c.ink)
                Spacer(Modifier.width(8.dp))
                Badge(
                    when (s.step) {
                        RoomNoiseStep.INTRO -> stringResource(R.string.noise_step_intro)
                        RoomNoiseStep.MEASURING -> stringResource(R.string.noise_step_measuring)
                        RoomNoiseStep.RESULT -> stringResource(R.string.noise_step_result)
                    },
                )
            },
            right = { Label("%.1f–%.1f ".format(band.loHz / 1000, band.hiHz / 1000) + stringResource(R.string.unit_khz)) },
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(CupolaDimens.paddingH),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (s.step) {
                RoomNoiseStep.INTRO -> {
                    Text(stringResource(R.string.noise_intro_title), style = t.title, color = c.ink)
                    Text(stringResource(R.string.noise_intro_body), style = t.body, color = c.mut)
                    Text(stringResource(R.string.noise_intro_why), style = t.body, color = c.mut)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton(stringResource(R.string.noise_begin), onClick = { vm.begin() }, style = PillStyle.Primary)
                        PillButton(stringResource(R.string.action_back), onClick = onBack, style = PillStyle.Outline)
                    }
                }
                RoomNoiseStep.MEASURING -> {
                    Text(stringResource(R.string.noise_measuring_title), style = t.title, color = c.ink)
                    Text(stringResource(R.string.noise_measuring_body), style = t.body, color = c.mut)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text(ceil(s.secondsLeft).toInt().toString(), style = t.note, color = c.ink)
                        Column(Modifier.weight(1f)) {
                            Label(stringResource(R.string.noise_level))
                            LevelMeter(s.levelDbfs, Modifier.fillMaxWidth().height(14.dp).padding(top = 4.dp))
                            Spacer(Modifier.height(8.dp))
                            ProgressBar(s.progress, Modifier.fillMaxWidth().height(4.dp))
                        }
                    }
                    PillButton(stringResource(R.string.action_cancel), onClick = { vm.restart() }, style = PillStyle.Outline)
                }
                RoomNoiseStep.RESULT -> {
                    val r = s.result
                    Text(stringResource(R.string.noise_result_title), style = t.title, color = c.ink)
                    if (r != null) {
                        ResultRow(stringResource(R.string.noise_result_level), "%.0f dBFS".format(r.rmsDbfs))
                        if (r.rmsDbfs > RoomNoise.NOISY_ROOM_DBFS) Text(stringResource(R.string.noise_warn_noisy), style = t.body, color = c.warn)
                        Text(stringResource(R.string.noise_result_body), style = t.body, color = c.mut)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (r != null) PillButton(stringResource(R.string.noise_save), onClick = { vm.save() }, style = PillStyle.Primary)
                        PillButton(stringResource(R.string.noise_repeat), onClick = { vm.restart(); vm.begin() }, style = PillStyle.Outline)
                        PillButton(stringResource(R.string.action_back), onClick = { vm.restart(); onBack() }, style = PillStyle.Outline)
                    }
                }
            }
        }
        ZoneDivider()
        SpectrumZone(
            snapshot = vm.spectrum, band = band, sharePct = null, humpDb = null, paused = false,
            topDb = { -30f },
            modifier = Modifier.fillMaxWidth().height(220.dp),
        )
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    val c = CupolaTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = CupolaTheme.type.body, color = c.mut, modifier = Modifier.weight(1f))
        Text(value, style = CupolaTheme.type.stats, color = c.ink)
    }
}

@Composable
private fun LevelMeter(levelDbfs: Double, modifier: Modifier = Modifier) {
    val c = CupolaTheme.colors
    Canvas(modifier) {
        val frac = ((levelDbfs + 70.0) / 70.0).coerceIn(0.0, 1.0).toFloat()
        drawRect(c.panel2, size = size)
        drawRect(if (levelDbfs > RoomNoise.NOISY_ROOM_DBFS) c.warn else c.gold, size = Size(size.width * frac, size.height))
        // −45 dBFS mark: the noisy-room threshold
        val x = size.width * (25f / 70f)
        drawRect(c.line2, topLeft = Offset(x, 0f), size = Size(1.5f, size.height))
    }
}

@Composable
private fun ProgressBar(progress: Float, modifier: Modifier = Modifier) {
    val c = CupolaTheme.colors
    Canvas(modifier) {
        drawRect(c.line, size = size)
        drawRect(c.violetInk, size = Size(size.width * progress.coerceIn(0f, 1f), size.height))
    }
}
