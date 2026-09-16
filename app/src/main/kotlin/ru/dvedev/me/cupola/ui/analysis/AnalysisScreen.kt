package ru.dvedev.me.cupola.ui.analysis

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import ru.dvedev.me.cupola.notation.Accidentals
import ru.dvedev.me.cupola.notation.NotationMode
import ru.dvedev.me.cupola.notation.Note
import ru.dvedev.me.cupola.notation.NoteNames
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.safeDrawingPadding
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
fun AnalysisScreen(vm: AnalysisViewModel, onSettings: () -> Unit) {
    val liveMetrics by vm.uiMetrics.collectAsStateWithLifecycle()
    val metrics = if (vm.paused) vm.frozenMetrics else liveMetrics
    val liveNote by vm.displayNote.collectAsStateWithLifecycle()
    val displayNote = if (vm.paused) (vm.frozenNote ?: liveNote) else liveNote
    val session by vm.session.state.collectAsStateWithLifecycle()
    val inputSilent by vm.inputSilent.collectAsStateWithLifecycle()
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
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
    vm.spectrogram.setRange(settings.freqMinHz.toDouble(), settings.freqMaxHz.toDouble())
    // zones fold by a tap on their header (owner 2026‑09‑16): graphs full-screen, or the note alone
    var noteFolded by rememberSaveable { mutableStateOf(false) }
    var spectrogramFolded by rememberSaveable { mutableStateOf(false) }
    var spectrumFolded by rememberSaveable { mutableStateOf(false) }

    // floating controls fade to near-invisible 5 s after the last touch anywhere on the screen (owner 2026‑09‑16)
    var lastTouch by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var controlsFaded by remember { mutableStateOf(false) }
    LaunchedEffect(lastTouch) {
        controlsFaded = false
        kotlinx.coroutines.delay(5000)
        controlsFaded = true
    }
    val controlsAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (controlsFaded) 0.08f else 1f,
        animationSpec = androidx.compose.animation.core.tween(if (controlsFaded) 700 else 150),
        label = "controls",
    )

    BoxWithConstraints(
        Modifier.fillMaxSize().background(c.panel).safeDrawingPadding().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    if (e.changes.any { it.pressed && !it.previousPressed }) lastTouch = System.nanoTime()
                }
            }
        },
    ) {
        val landscape = maxWidth > maxHeight && maxWidth >= 600.dp
        // a phone held sideways: the note and the arc side by side in a narrower column, the
        // spectrogram takes the rest, the spectrum only if there is room
        val shortLandscape = landscape && maxHeight < 480.dp
        val roomForSpectrum = maxHeight >= 400.dp
        val narrow = maxWidth < 420.dp
        Column(Modifier.fillMaxSize()) {
            val noteZone: @Composable (Modifier, Boolean) -> Unit = { mod, compact ->
                NoteZone(
                    metrics = metrics, display = displayNote, session = session, targetNote = vm.targetNote,
                    notation = notation, accidentals = accidentals, hintsEnabled = settings.hints, pointsAnimation = settings.pointsAnimation,
                    onTapNote = { vm.toggleTarget(it) }, onLongPressArc = {}, onGiveTone = { vm.playTargetTone() }, onPickNote = { pickerOpen = true }, inputSilent = inputSilent,
                    modifier = mod, compact = compact,
                    collapsed = noteFolded, onToggle = { noteFolded = !noteFolded },
                )
            }
            val spectrogramZone: @Composable (Modifier) -> Unit = { mod ->
                SpectrogramZone(
                    history = vm.spectrogram, band = band, targetNote = vm.targetNote, harmonics = harmonics,
                    paused = vm.paused, viewEnd = vm.viewEnd, onScroll = vm::scrollBy,
                    visibleColumns = vm.spectrogramSpan, onZoom = vm::zoomSpectrogram,
                    collapsed = spectrogramFolded, onToggle = { spectrogramFolded = !spectrogramFolded },
                    contrastPct = settings.spectrogramContrast, a4Hz = settings.a4Hz.toDouble(),
                    modifier = mod,
                )
            }
            val spectrumZone: @Composable (Modifier, Boolean) -> Unit = { mod, legend ->
                SpectrumZone(
                    snapshot = vm.spectrum, band = band, sharePct = displayNote.ringSharePct, humpDb = displayNote.humpDb,
                    paused = vm.paused, topDb = { vm.spectrogram.topDb }, logScale = settings.logFrequencyAxis,
                    fMin = settings.freqMinHz.toDouble(), fMax = settings.freqMaxHz.toDouble(),
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
        // floating round controls (owner 2026‑09‑16: no top bar): ★ start/stop the game (training
        // mode only), pause, settings — half-transparent over the bottom-right corner of the graphs
        Row(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 16.dp).alpha(controlsAlpha), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (settings.trainingMode) {
                FloatingRoundButton(active = session.active, onClick = { if (session.active) vm.stopSession() else startSession() }) { ink ->
                    if (session.active) {
                        // stop: a rounded square
                        androidx.compose.foundation.Canvas(Modifier.size(14.dp)) { drawRoundRect(ink, cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())) }
                    } else {
                        // a drawn five-point star, sized like the gear and the pause bars (a ★ glyph sat small and off-centre)
                        androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
                            val cx = size.width / 2
                            val cy = size.height / 2 + size.height * 0.04f
                            val outer = size.minDimension * 0.5f
                            val inner = outer * 0.42f
                            val star = androidx.compose.ui.graphics.Path()
                            for (i in 0 until 10) {
                                val r = if (i % 2 == 0) outer else inner
                                val a = Math.toRadians(-90.0 + i * 36.0)
                                val x = cx + (r * kotlin.math.cos(a)).toFloat()
                                val y = cy + (r * kotlin.math.sin(a)).toFloat()
                                if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
                            }
                            star.close()
                            drawPath(star, ink)
                        }
                    }
                }
            }
            FloatingRoundButton(active = vm.paused, onClick = { vm.togglePause() }) { ink ->
                androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
                    if (vm.paused) {
                        val p = androidx.compose.ui.graphics.Path().apply { moveTo(size.width * 0.2f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.2f, size.height); close() }
                        drawPath(p, ink)
                    } else {
                        val w = size.width * 0.3f
                        drawRect(ink, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.12f, 0f), size = androidx.compose.ui.geometry.Size(w, size.height))
                        drawRect(ink, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.58f, 0f), size = androidx.compose.ui.geometry.Size(w, size.height))
                    }
                }
            }
            FloatingRoundButton(active = false, onClick = onSettings) { ink ->
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Settings, contentDescription = stringResource(R.string.action_settings), tint = ink, modifier = Modifier.size(22.dp))
            }
        }
    }

    if (pickerOpen) NotePickerDialog(current = vm.targetNote, notation = notation, accidentals = accidentals, onPick = { vm.pinTarget(it); pickerOpen = false }, onClear = { vm.toggleTarget(null); pickerOpen = false }, onDismiss = { pickerOpen = false })
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

/** Note picker (owner 2026‑09‑16): C2…C6 in a grid, international name large, Russian name small. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun NotePickerDialog(current: Note?, notation: NotationMode, accidentals: Accidentals, onPick: (Note) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    val c = CupolaTheme.colors
    val t = CupolaTheme.type
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pick_note_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // each octave in its own thicker frame, every note an outlined chip (owner 2026‑09‑16)
                val chip = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                val frame = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                for (octaveStart in 36..72 step 12) {
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = 10.dp).border(2.dp, c.goldInk.copy(alpha = 0.7f), frame).padding(8.dp),
                    ) {
                        for (midi in octaveStart until octaveStart + 12) {
                            val n = Note(midi)
                            val selected = current?.midi == midi
                            Column(
                                Modifier.width(56.dp).clip(chip).border(1.dp, if (selected) c.violet else c.goldInk.copy(alpha = 0.55f), chip)
                                    .background(if (selected) c.violet.copy(alpha = 0.25f) else c.panel2)
                                    .clickable { onPick(n) }.padding(vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(NoteNames.en(n, accidentals), style = t.stats, color = c.ink)
                                Text(NoteNames.ruShort(n, accidentals), style = t.axis, color = c.dim)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { if (current != null) TextButton(onClick = onClear) { Text(stringResource(R.string.pick_note_clear)) } },
    )
}

/** A half-transparent round button floating over the graphs; [active] tints it violet. */
@Composable
private fun FloatingRoundButton(active: Boolean, onClick: () -> Unit, content: @Composable (ink: androidx.compose.ui.graphics.Color) -> Unit) {
    val c = CupolaTheme.colors
    Box(
        Modifier.size(44.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (active) c.violet.copy(alpha = 0.25f) else c.panel.copy(alpha = 0.7f))
            .border(1.dp, (if (active) c.violet else c.line2).copy(alpha = 0.7f), androidx.compose.foundation.shape.CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content(if (active) c.violetInk else c.mut.copy(alpha = 0.85f)) }
}
