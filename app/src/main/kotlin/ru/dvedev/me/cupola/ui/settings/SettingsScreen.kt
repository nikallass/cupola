package ru.dvedev.me.cupola.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.dvedev.me.cupola.AppGraph
import ru.dvedev.me.cupola.isTabletDevice
import ru.dvedev.me.cupola.R
import ru.dvedev.me.cupola.audio.AudioSourcePreference
import ru.dvedev.me.cupola.audio.EngineState
import ru.dvedev.me.cupola.audio.ProcessingState
import ru.dvedev.me.cupola.dsp.metrics.VoiceType
import ru.dvedev.me.cupola.notation.Accidentals
import ru.dvedev.me.cupola.notation.NotationMode
import ru.dvedev.me.cupola.settings.Language
import ru.dvedev.me.cupola.settings.Settings
import ru.dvedev.me.cupola.ui.analysis.voiceTypeName
import ru.dvedev.me.cupola.ui.components.Badge
import ru.dvedev.me.cupola.ui.components.Label
import ru.dvedev.me.cupola.ui.components.PillButton
import ru.dvedev.me.cupola.ui.components.PillStyle
import ru.dvedev.me.cupola.ui.components.ZoneHeader
import ru.dvedev.me.cupola.ui.theme.CupolaDimens
import ru.dvedev.me.cupola.ui.theme.CupolaTheme
import ru.dvedev.me.cupola.ui.theme.ThemeMode
import java.text.DateFormat
import java.util.Date

/** Settings (SPEC §15.5, T-060). Every field has a «?» with a plain explanation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(graph: AppGraph, onBack: () -> Unit, onRoomNoise: () -> Unit, onTokens: () -> Unit, onLanguageChanged: () -> Unit) {
    val s by graph.settingsState.collectAsStateWithLifecycle()
    val roomNoise by graph.roomNoiseState.collectAsStateWithLifecycle()
    val engineState by graph.engine.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val c = CupolaTheme.colors
    val t = CupolaTheme.type
    fun update(f: (Settings) -> Settings) = scope.launch { graph.settings.update(f) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    val tablet = isTabletDevice(LocalContext.current)

    Column(Modifier.fillMaxSize().background(c.panel).systemBarsPadding()) {
        ZoneHeader(
            left = {
                Label(stringResource(R.string.action_settings), color = c.ink)
            },
            right = { PillButton(stringResource(R.string.action_back), onClick = onBack, style = PillStyle.Outline) },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsSection(stringResource(R.string.settings_voice))
            ChoiceRow(
                stringResource(R.string.settings_voice_type), stringResource(R.string.settings_voice_type_help),
                value = if (s.useCustomBand) null else s.voiceType,
                options = VoiceType.entries + listOf<VoiceType?>(null),
                label = { v -> if (v == null) stringResource(R.string.voice_custom) else voiceTypeName(v) },
                onSelect = { v -> update { it.copy(useCustomBand = v == null, voiceType = v ?: it.voiceType) } },
            )
            if (s.useCustomBand) {
                StepperRow(
                    stringResource(R.string.settings_band_lo), stringResource(R.string.settings_band_help), "${s.customLoHz} " + stringResource(R.string.unit_hz),
                    onDecrement = { update { it.copy(customLoHz = (it.customLoHz - Settings.CUSTOM_STEP_HZ).coerceAtLeast(Settings.CUSTOM_MIN_HZ)) } },
                    onIncrement = { update { it.copy(customLoHz = (it.customLoHz + Settings.CUSTOM_STEP_HZ).coerceAtMost(it.customHiHz - Settings.CUSTOM_MIN_WIDTH_HZ)) } },
                )
                StepperRow(
                    stringResource(R.string.settings_band_hi), stringResource(R.string.settings_band_help), "${s.customHiHz} " + stringResource(R.string.unit_hz),
                    onDecrement = { update { it.copy(customHiHz = (it.customHiHz - Settings.CUSTOM_STEP_HZ).coerceAtLeast(it.customLoHz + Settings.CUSTOM_MIN_WIDTH_HZ)) } },
                    onIncrement = { update { it.copy(customHiHz = (it.customHiHz + Settings.CUSTOM_STEP_HZ).coerceAtMost(Settings.CUSTOM_MAX_HZ)) } },
                )
            }
            SettingRow(stringResource(R.string.settings_room_noise), stringResource(R.string.settings_room_noise_help)) {
                FlowRow(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val rn = roomNoise
                    Text(
                        if (rn == null) stringResource(R.string.no_room_noise)
                        else "%.0f dBFS · ".format(rn.rmsDbfs) + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(rn.createdAtEpochMs)),
                        style = t.sub, color = c.dim, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically),
                    )
                    PillButton(stringResource(R.string.settings_measure_noise), onClick = onRoomNoise, style = PillStyle.Primary)
                    if (rn != null) PillButton(stringResource(R.string.settings_forget_noise), onClick = { scope.launch { graph.settings.clearRoomNoise() } }, style = PillStyle.Outline)
                }
            }
            StepperRow(
                stringResource(R.string.settings_a4), stringResource(R.string.settings_a4_help), "${s.a4Hz} " + stringResource(R.string.unit_hz),
                onDecrement = { update { it.copy(a4Hz = (it.a4Hz - 1).coerceAtLeast(415)) } },
                onIncrement = { update { it.copy(a4Hz = (it.a4Hz + 1).coerceAtMost(466)) } },
            )

            SettingsSection(stringResource(R.string.settings_display))
            ChoiceRow(
                stringResource(R.string.settings_notation), stringResource(R.string.settings_notation_help), s.notation, NotationMode.entries,
                label = { m -> stringResource(when (m) { NotationMode.RU -> R.string.notation_ru; NotationMode.EN -> R.string.notation_en; NotationMode.BOTH -> R.string.notation_both }) },
                onSelect = { m -> update { it.copy(notation = m) } },
            )
            ChoiceRow(
                stringResource(R.string.settings_accidentals), stringResource(R.string.settings_accidentals_help), s.accidentals, Accidentals.entries,
                label = { a -> if (a == Accidentals.SHARPS) "♯" else "♭" },
                onSelect = { a -> update { it.copy(accidentals = a) } },
            )
            ChoiceRow(
                stringResource(R.string.settings_language), stringResource(R.string.settings_language_help), s.language, Language.entries,
                label = { l -> stringResource(when (l) { Language.SYSTEM -> R.string.lang_system; Language.RU -> R.string.lang_ru; Language.EN -> R.string.lang_en }) },
                onSelect = { l -> scope.launch { graph.settings.update { it.copy(language = l) }; onLanguageChanged() } },
            )
            ChoiceRow(
                stringResource(R.string.settings_theme), stringResource(R.string.settings_theme_help), s.theme, ThemeMode.entries,
                label = { m -> stringResource(when (m) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark }) },
                onSelect = { m -> update { it.copy(theme = m) } },
            )
            SwitchRow(stringResource(R.string.settings_log_axis), stringResource(R.string.settings_log_axis_help), s.logFrequencyAxis) { v -> update { it.copy(logFrequencyAxis = v) } }
            ChoiceRow(
                stringResource(R.string.settings_fft), stringResource(R.string.settings_fft_help), s.fftSize, Settings.FFT_SIZES,
                label = { n -> n.toString() },
                onSelect = { n -> update { it.copy(fftSize = n) } },
            )

            SettingsSection(stringResource(R.string.settings_audio))
            ChoiceRow(
                stringResource(R.string.settings_source), stringResource(R.string.settings_source_help), s.audioSource, AudioSourcePreference.entries,
                label = { p -> when (p) { AudioSourcePreference.AUTO -> stringResource(R.string.source_auto); AudioSourcePreference.UNPROCESSED -> "UNPROCESSED"; AudioSourcePreference.VOICE_RECOGNITION -> "VOICE_RECOGNITION" } },
                onSelect = { p -> scope.launch { graph.settings.update { it.copy(audioSource = p) }; if (graph.engine.isRunning) { graph.engine.stop(); graph.engine.start(p) } } },
            )
            SettingRow(stringResource(R.string.settings_source_status), stringResource(R.string.settings_source_status_help)) {
                val st = engineState
                if (st is EngineState.Running) Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    Badge(
                        when (st.source.processing) {
                            ProcessingState.DISABLED -> stringResource(R.string.processing_disabled)
                            ProcessingState.NOT_GUARANTEED -> stringResource(R.string.processing_not_guaranteed)
                            ProcessingState.UNVERIFIED -> stringResource(R.string.processing_unverified)
                        },
                    )
                    Text("${st.source.sourceName} · ${st.source.sampleRate} " + stringResource(R.string.unit_hz), style = t.sub, color = c.dim, modifier = Modifier.padding(top = 4.dp))
                } else {
                    Text("—", style = t.sub, color = c.dim)
                }
            }

            SettingsSection(stringResource(R.string.settings_feedback))
            SwitchRow(stringResource(R.string.settings_haptics), stringResource(R.string.settings_haptics_help), s.haptics ?: !tablet) { v -> update { it.copy(haptics = v) } }
            SwitchRow(stringResource(R.string.settings_points_anim), stringResource(R.string.settings_points_anim_help), s.pointsAnimation) { v -> update { it.copy(pointsAnimation = v) } }
            SwitchRow(stringResource(R.string.settings_hints), stringResource(R.string.settings_hints_help), s.hints) { v -> update { it.copy(hints = v) } }

            SettingsSection(stringResource(R.string.settings_advanced))
            Text(
                if (advancedOpen) stringResource(R.string.settings_advanced_hide) else stringResource(R.string.settings_advanced_show),
                style = t.body, color = c.violetInk,
                modifier = Modifier.fillMaxWidth().clickable { advancedOpen = !advancedOpen }.padding(horizontal = CupolaDimens.paddingH, vertical = 10.dp),
            )
            if (advancedOpen) {
                StepperRow(stringResource(R.string.settings_cents_ok), stringResource(R.string.settings_cents_help), "±${s.centsOk} ¢",
                    onDecrement = { update { it.copy(centsOk = (it.centsOk - 1).coerceAtLeast(3)) } }, onIncrement = { update { it.copy(centsOk = (it.centsOk + 1).coerceAtMost(it.centsWarn - 1)) } })
                StepperRow(stringResource(R.string.settings_cents_warn), stringResource(R.string.settings_cents_help), "±${s.centsWarn} ¢",
                    onDecrement = { update { it.copy(centsWarn = (it.centsWarn - 1).coerceAtLeast(it.centsOk + 1)) } }, onIncrement = { update { it.copy(centsWarn = (it.centsWarn + 1).coerceAtMost(49)) } })
                StepperRow(stringResource(R.string.settings_confidence), stringResource(R.string.settings_confidence_help), "%.2f".format(s.confidenceMin),
                    onDecrement = { update { it.copy(confidenceMin = (it.confidenceMin - 0.05).coerceAtLeast(0.3)) } }, onIncrement = { update { it.copy(confidenceMin = (it.confidenceMin + 0.05).coerceAtMost(0.95)) } })
                StepperRow(stringResource(R.string.settings_ring_share), stringResource(R.string.settings_ring_share_help), "${s.ringShareFullPct} %",
                    onDecrement = { update { it.copy(ringShareFullPct = (it.ringShareFullPct - 1).coerceAtLeast(4)) } }, onIncrement = { update { it.copy(ringShareFullPct = (it.ringShareFullPct + 1).coerceAtMost(40)) } })
                StepperRow(stringResource(R.string.settings_ring_hump), stringResource(R.string.settings_ring_hump_help), "${s.ringHumpFullDb} dB",
                    onDecrement = { update { it.copy(ringHumpFullDb = (it.ringHumpFullDb - 1).coerceAtLeast(2)) } }, onIncrement = { update { it.copy(ringHumpFullDb = (it.ringHumpFullDb + 1).coerceAtMost(20)) } })
                StepperRow(stringResource(R.string.settings_w_ring), stringResource(R.string.settings_weights_help), "%.2f".format(s.ringWeight),
                    onDecrement = { update { it.copy(ringWeight = (it.ringWeight - 0.05).coerceAtLeast(0.0)) } }, onIncrement = { update { it.copy(ringWeight = (it.ringWeight + 0.05).coerceAtMost(1.0)) } })
                StepperRow(stringResource(R.string.settings_w_pitch), stringResource(R.string.settings_weights_help), "%.2f".format(s.pitchWeight),
                    onDecrement = { update { it.copy(pitchWeight = (it.pitchWeight - 0.05).coerceAtLeast(0.0)) } }, onIncrement = { update { it.copy(pitchWeight = (it.pitchWeight + 0.05).coerceAtMost(1.0)) } })
                StepperRow(stringResource(R.string.settings_w_steady), stringResource(R.string.settings_weights_help), "%.2f".format(s.steadyWeight),
                    onDecrement = { update { it.copy(steadyWeight = (it.steadyWeight - 0.05).coerceAtLeast(0.0)) } }, onIncrement = { update { it.copy(steadyWeight = (it.steadyWeight + 0.05).coerceAtMost(1.0)) } })
                Row(Modifier.padding(horizontal = CupolaDimens.paddingH, vertical = 10.dp)) {
                    PillButton(stringResource(R.string.settings_reset_advanced), onClick = { scope.launch { graph.settings.resetAdvanced() } }, style = PillStyle.Outline)
                    Spacer(Modifier.width(10.dp))
                    PillButton(stringResource(R.string.settings_tokens), onClick = onTokens, style = PillStyle.Outline)
                }
            }
            Spacer(Modifier.padding(12.dp))
        }
    }
}
