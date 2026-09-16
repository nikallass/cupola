package ru.dvedev.me.cupola.ui.analysis

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
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
fun AnalysisScreen(vm: AnalysisViewModel, onSettings: () -> Unit, onRoomNoise: () -> Unit) {
    val liveMetrics by vm.uiMetrics.collectAsStateWithLifecycle()
    val metrics = if (vm.paused) vm.frozenMetrics else liveMetrics
    val liveNote by vm.displayNote.collectAsStateWithLifecycle()
    val displayNote = if (vm.paused) (vm.frozenNote ?: liveNote) else liveNote
    val session by vm.session.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Android 13+: ask for notification permission once, right before the first session
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.startSession() }
    val startSession: () -> Unit = {
        val needsAsk = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsk) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.startSession()
    }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val c = CupolaTheme.colors
    val band = settings.band
    val harmonics = metrics?.harmonics ?: emptyList()
    val notation = settings.notation
    val accidentals = settings.accidentals
    vm.spectrogram.logScale = settings.logFrequencyAxis
    // zones fold by a tap on their header (owner 2026‑09‑16): graphs full-screen, or the note alone
    var noteFolded by rememberSaveable { mutableStateOf(false) }
    var spectrogramFolded by rememberSaveable { mutableStateOf(false) }
    var spectrumFolded by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize().background(c.panel).systemBarsPadding()) {
        val landscape = maxWidth > maxHeight && maxWidth >= 600.dp
        // a phone held sideways: the note and the arc side by side in a narrower column, the
        // spectrogram takes the rest, the spectrum only if there is room
        val shortLandscape = landscape && maxHeight < 480.dp
        val roomForSpectrum = maxHeight >= 400.dp
        val narrow = maxWidth < 420.dp
        Column(Modifier.fillMaxSize()) {
            TopBar(
                voiceType = settings.voiceType,
                band = band,
                customBand = settings.useCustomBand,
                session = session,
                onStartStop = { if (session.active) vm.stopSession() else startSession() },
                onPause = { vm.togglePause() },
                onSettings = onSettings,
                compact = narrow,
            )
            val noteZone: @Composable (Modifier, Boolean) -> Unit = { mod, compact ->
                NoteZone(
                    metrics = metrics, display = displayNote, session = session, targetNote = vm.targetNote,
                    notation = notation, accidentals = accidentals, hintsEnabled = settings.hints, pointsAnimation = settings.pointsAnimation,
                    onTapNote = { vm.toggleTarget(it) }, onLongPressArc = onRoomNoise,
                    modifier = mod, compact = compact,
                    collapsed = noteFolded, onToggle = { noteFolded = !noteFolded },
                )
            }
            val spectrogramZone: @Composable (Modifier) -> Unit = { mod ->
                SpectrogramZone(
                    history = vm.spectrogram, band = band, targetNote = vm.targetNote, harmonics = harmonics,
                    paused = vm.paused, viewEnd = vm.viewEnd, onScroll = vm::scrollBy,
                    collapsed = spectrogramFolded, onToggle = { spectrogramFolded = !spectrogramFolded },
                    modifier = mod,
                )
            }
            val spectrumZone: @Composable (Modifier, Boolean) -> Unit = { mod, legend ->
                SpectrumZone(
                    snapshot = vm.spectrum, band = band, sharePct = displayNote.ringSharePct, humpDb = displayNote.humpDb,
                    paused = vm.paused, topDb = { vm.spectrogram.topDb }, logScale = settings.logFrequencyAxis,
                    showLegend = legend, collapsed = spectrumFolded, onToggle = { spectrumFolded = !spectrumFolded },
                    modifier = mod,
                )
            }
            // graphs stacked: the open one(s) share the height; a folded zone is just its header
            val graphs: @Composable ColumnScope.(spectrogramWeight: Float, spectrumFixed: Dp?, legend: Boolean) -> Unit = { sgWeight, spFixed, legend ->
                spectrogramZone(if (spectrogramFolded) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().weight(sgWeight))
                ZoneDivider()
                val spectrumModifier = when {
                    spectrumFolded -> Modifier.fillMaxWidth()
                    spectrogramFolded || spFixed == null -> Modifier.fillMaxWidth().weight(if (spFixed == null) 1f - sgWeight else 1f)
                    else -> Modifier.fillMaxWidth().height(spFixed)
                }
                spectrumZone(spectrumModifier, legend)
            }
            if (landscape) {
                if (noteFolded) {
                    // the folded note is a strip over both graphs, which then take the whole width
                    Column(Modifier.fillMaxSize()) {
                        noteZone(Modifier.fillMaxWidth(), false)
                        ZoneDivider()
                        graphs(if (shortLandscape) 0.65f else 0.6f, null, !shortLandscape)
                    }
                } else {
                    Row(Modifier.fillMaxSize()) {
                        noteZone(Modifier.width(if (shortLandscape) CupolaDimens.shortLandscapeNoteWidth else CupolaDimens.landscapeNoteWidth).fillMaxHeight(), !shortLandscape)
                        Box(Modifier.width(CupolaDimens.divider).fillMaxHeight().background(c.line))
                        Column(Modifier.fillMaxSize()) {
                            if (shortLandscape && !roomForSpectrum) {
                                spectrogramZone(if (spectrogramFolded) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().weight(1f))
                            } else {
                                graphs(0.6f, if (shortLandscape) 130.dp else null, !shortLandscape)
                            }
                        }
                    }
                }
            } else {
                noteZone(Modifier.fillMaxWidth(), false)
                ZoneDivider()
                graphs(1f, if (narrow) 220.dp else CupolaDimens.spectrumHeight, !narrow)
            }
        }
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
