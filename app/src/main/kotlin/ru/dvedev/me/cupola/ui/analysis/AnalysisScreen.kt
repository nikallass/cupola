package ru.dvedev.me.cupola.ui.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.dvedev.me.cupola.R
import ru.dvedev.me.cupola.analysis.AnalysisViewModel
import ru.dvedev.me.cupola.dsp.session.SessionSummary
import ru.dvedev.me.cupola.ui.components.ZoneDivider
import ru.dvedev.me.cupola.ui.theme.CupolaDimens
import ru.dvedev.me.cupola.ui.theme.CupolaTheme

/**
 * The Analysis screen (SPEC §15.3): top bar, note, spectrogram, spectrum. Portrait stacks
 * them; landscape puts the note in a 360 dp column with spectrogram over spectrum on the
 * right (T-058).
 */
@Composable
fun AnalysisScreen(vm: AnalysisViewModel, onSettings: () -> Unit, onCalibrate: () -> Unit) {
    val metrics by vm.uiMetrics.collectAsStateWithLifecycle()
    val session by vm.session.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val calibration by vm.calibration.collectAsStateWithLifecycle()
    val c = CupolaTheme.colors
    val band = settings.band
    val baseline = calibration?.ringRatioDb
    val harmonics = metrics?.harmonics ?: emptyList()
    val notation = settings.notation
    val accidentals = settings.accidentals

    BoxWithConstraints(Modifier.fillMaxSize().background(c.panel).systemBarsPadding()) {
        val landscape = maxWidth > maxHeight && maxWidth >= 600.dp
        val narrow = maxWidth < 420.dp
        Column(Modifier.fillMaxSize()) {
            TopBar(
                voiceType = settings.voiceType,
                band = band,
                customBand = settings.useCustomBand,
                session = session,
                onStartStop = { if (session.active) vm.stopSession() else vm.startSession() },
                onPause = { vm.togglePause() },
                onSettings = onSettings,
                compact = narrow,
            )
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    NoteZone(
                        metrics = metrics, session = session, targetNote = vm.targetNote, baselineDb = baseline,
                        notation = notation, accidentals = accidentals, hintsEnabled = settings.hints,
                        onTapNote = { vm.toggleTarget(it) }, onLongPressArc = onCalibrate,
                        modifier = Modifier.width(CupolaDimens.landscapeNoteWidth).fillMaxHeight(),
                        compact = true,
                    )
                    Box(Modifier.width(CupolaDimens.divider).fillMaxHeight().background(c.line))
                    Column(Modifier.fillMaxSize()) {
                        SpectrogramZone(
                            history = vm.spectrogram, band = band, targetNote = vm.targetNote, harmonics = harmonics,
                            paused = session.paused, viewEnd = null,
                            modifier = Modifier.fillMaxWidth().weight(0.6f),
                        )
                        ZoneDivider()
                        SpectrumZone(
                            snapshot = vm.spectrum, band = band, ringNormDb = metrics?.ringRatioNorm, baselineDb = baseline,
                            paused = session.paused, topDb = { vm.spectrogram.topDb },
                            modifier = Modifier.fillMaxWidth().weight(0.4f),
                        )
                    }
                }
            } else {
                NoteZone(
                    metrics = metrics, session = session, targetNote = vm.targetNote, baselineDb = baseline,
                    notation = notation, accidentals = accidentals, hintsEnabled = settings.hints,
                    onTapNote = { vm.toggleTarget(it) }, onLongPressArc = onCalibrate,
                    modifier = Modifier.fillMaxWidth(),
                )
                ZoneDivider()
                SpectrogramZone(
                    history = vm.spectrogram, band = band, targetNote = vm.targetNote, harmonics = harmonics,
                    paused = session.paused, viewEnd = null,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                ZoneDivider()
                SpectrumZone(
                    snapshot = vm.spectrum, band = band, ringNormDb = metrics?.ringRatioNorm, baselineDb = baseline,
                    paused = session.paused, topDb = { vm.spectrogram.topDb },
                    modifier = Modifier.fillMaxWidth().height(CupolaDimens.spectrumHeight),
                )
            }
        }
    }

    if (vm.calibrationPrompt) {
        AlertDialog(
            onDismissRequest = { vm.dismissCalibrationPrompt() },
            title = { Text(stringResource(R.string.need_calibration_title)) },
            text = { Text(stringResource(R.string.need_calibration_body), style = CupolaTheme.type.body) },
            confirmButton = { TextButton(onClick = { vm.dismissCalibrationPrompt(); onCalibrate() }) { Text(stringResource(R.string.settings_calibrate)) } },
            dismissButton = { TextButton(onClick = { vm.dismissCalibrationPrompt() }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    session.summary?.let { summary -> SummaryDialog(summary, onDismiss = { vm.dismissSummary() }) }
}

@Composable
private fun SummaryDialog(s: SessionSummary, onDismiss: () -> Unit) {
    val c = CupolaTheme.colors
    val t = CupolaTheme.type
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
        title = { Text(stringResource(R.string.summary_title)) },
        text = {
            Column {
                SummaryRow(stringResource(R.string.summary_duration), formatTime(s.durationSec) + " · " + stringResource(R.string.summary_voiced, formatTime(s.voicedSec)))
                SummaryRow(stringResource(R.string.summary_points), s.points.toString(), c.goldInk)
                SummaryRow(stringResource(R.string.summary_mean_score), "%.2f".format(s.meanScore))
                SummaryRow(stringResource(R.string.summary_best_score), "%.2f".format(s.bestScore))
                SummaryRow(stringResource(R.string.summary_share07), "%.0f %%".format(s.shareAbove07 * 100))
                SummaryRow(stringResource(R.string.summary_best_streak), "%.1f %s".format(s.bestStreakSec, stringResource(R.string.unit_s)))
                SummaryRow(stringResource(R.string.summary_ring), "%.2f".format(s.meanRing))
                SummaryRow(stringResource(R.string.summary_pitch), "%.2f".format(s.meanPitch))
                SummaryRow(stringResource(R.string.summary_steady), "%.2f".format(s.meanSteady))
                Text(stringResource(R.string.summary_note), style = t.axis, color = c.dim, modifier = Modifier.height(30.dp))
            }
        },
    )
}

@Composable
private fun SummaryRow(label: String, value: String, color: androidx.compose.ui.graphics.Color = CupolaTheme.colors.ink) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = CupolaTheme.type.body, color = CupolaTheme.colors.mut, modifier = Modifier.weight(1f))
        Text(value, style = CupolaTheme.type.stats, color = color)
    }
}
