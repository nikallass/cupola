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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.dvedev.me.cupola.analysis.AnalysisViewModel
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
    val c = CupolaTheme.colors
    val band = vm.voiceType.band
    val baseline = vm.calibrationBaselineDb
    val harmonics = metrics?.harmonics ?: emptyList()

    BoxWithConstraints(Modifier.fillMaxSize().background(c.panel).systemBarsPadding()) {
        val landscape = maxWidth > maxHeight && maxWidth >= 600.dp
        val narrow = maxWidth < 420.dp
        Column(Modifier.fillMaxSize()) {
            TopBar(
                voiceType = vm.voiceType,
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
}
