package ru.dvedev.me.cupola.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.dvedev.me.cupola.audio.EngineState
import ru.dvedev.me.cupola.dsp.FrameMetrics
import ru.dvedev.me.cupola.notation.NoteNames
import ru.dvedev.me.cupola.ui.components.Label
import ru.dvedev.me.cupola.ui.components.ZoneHeader
import ru.dvedev.me.cupola.ui.theme.CupolaDimens
import ru.dvedev.me.cupola.ui.theme.CupolaTheme

/**
 * Developer readout of the live pipeline (T-040/T-041 acceptance on the device). Not a
 * user-facing screen; replaced by the Analysis zones in T-051…T-054.
 */
@Composable
fun LiveReadout(
    metrics: FrameMetrics?,
    engineState: EngineState,
    overruns: Long,
    readErrors: Long,
    latencyMs: Double,
) {
    val c = CupolaTheme.colors
    val t = CupolaTheme.type
    Column(Modifier.fillMaxWidth().background(c.panel)) {
        ZoneHeader(
            left = { Label("живой поток") },
            right = {
                Label(
                    when (engineState) {
                        is EngineState.Idle -> "микрофон выкл"
                        is EngineState.Running -> "${engineState.source.sourceName} · ${engineState.source.sampleRate} Гц · ${engineState.source.processing}"
                        is EngineState.Error -> "ошибка: ${engineState.message}"
                    },
                    color = if (engineState is EngineState.Error) c.bad else c.dim,
                )
            },
        )
        Column(Modifier.padding(horizontal = CupolaDimens.paddingH, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val m = metrics
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (m != null && m.voiced) NoteNames.ru(m.note) else "—", style = t.note, color = c.ink)
                Text(if (m != null && m.voiced) NoteNames.en(m.note) else "", style = t.noteEn, color = c.dim)
                Text(
                    if (m != null && m.voiced) NoteNames.cents(m.cents) else "",
                    style = t.cents,
                    color = when {
                        m == null || !m.voiced -> c.dim
                        kotlin.math.abs(m.cents) <= 10 -> c.ok
                        kotlin.math.abs(m.cents) <= 25 -> c.warn
                        else -> c.bad
                    },
                )
            }
            if (m != null) {
                Text(
                    "f0 %.1f Гц · conf %.2f · voice %s · gate %s".format(m.f0Hz, m.confidence, if (m.voice) "да" else "нет", m.gate),
                    style = t.sub, color = c.mut,
                )
                Text(
                    "SPL %.1f dBFS · floor %.1f · ring %.1f dB · share %.1f %% · SPR %.1f".format(m.splDbfs, m.noiseFloorDbfs, m.ringRatioDb, m.ringSharePct, m.peakSprDb),
                    style = t.sub, color = c.mut,
                )
                Text(
                    "обертонов %d · pitchSD %.1f · drift %.1f ¢/с · вибрато %s %.1f Гц ±%.0f ¢".format(
                        m.overtoneCount, m.pitchSd, m.driftCentsPerSec, m.vibrato.kind, m.vibrato.rateHz, m.vibrato.extentCents,
                    ),
                    style = t.sub, color = c.mut,
                )
                Text(
                    "ring %.2f · pitch %.2f · steady %.2f · score %.2f · streak %.1f с".format(m.ring, m.pitch, m.steady, m.score, m.streakSeconds),
                    style = t.sub, color = c.mut,
                )
                Text("t %.1f с · overruns %d · readErrors %d · latency %.1f мс".format(m.timeSec, overruns, readErrors, latencyMs), style = t.axis, color = c.dim)
            }
        }
    }
}
