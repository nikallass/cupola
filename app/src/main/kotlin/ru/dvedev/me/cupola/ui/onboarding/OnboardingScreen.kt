package ru.dvedev.me.cupola.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.dvedev.me.cupola.AppGraph
import ru.dvedev.me.cupola.settings.Language
import ru.dvedev.me.cupola.R
import ru.dvedev.me.cupola.dsp.metrics.VoiceType
import ru.dvedev.me.cupola.ui.analysis.voiceTypeName
import ru.dvedev.me.cupola.ui.components.Badge
import ru.dvedev.me.cupola.ui.components.Label
import ru.dvedev.me.cupola.ui.components.PillButton
import ru.dvedev.me.cupola.ui.components.PillStyle
import ru.dvedev.me.cupola.ui.components.ZoneHeader
import ru.dvedev.me.cupola.ui.theme.CupolaDimens
import ru.dvedev.me.cupola.ui.theme.CupolaTheme

private const val STEPS = 4

/**
 * First-run flow (SPEC §15.5, T-059): «всё относительно» → microphone → voice type →
 * room noise (or later). Marks `onboardingDone` when finished.
 */
@Composable
fun OnboardingScreen(graph: AppGraph, onRoomNoise: () -> Unit, onFinished: () -> Unit, onLanguageChanged: () -> Unit = {}) {
    val c = CupolaTheme.colors
    val t = CupolaTheme.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by graph.settingsState.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableIntStateOf(1) }
    var granted by rememberSaveable {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val requestMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) step = 3
    }
    fun finish(then: () -> Unit) {
        scope.launch {
            graph.settings.update { it.copy(onboardingDone = true) }
            then()
        }
    }

    Column(Modifier.fillMaxSize().background(c.panel).systemBarsPadding()) {
        ZoneHeader(
            left = {
                Label(stringResource(R.string.app_name), color = c.ink)
                Spacer(Modifier.padding(4.dp))
                Badge(stringResource(R.string.ob_step, step, STEPS))
            },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CupolaDimens.paddingH).widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (step) {
                1 -> {
                    // language first (owner 2026‑09‑16): the rest of the flow is read in the chosen one
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (l in Language.entries) {
                            PillButton(
                                stringResource(when (l) { Language.SYSTEM -> R.string.lang_system; Language.RU -> R.string.lang_ru; Language.EN -> R.string.lang_en }),
                                onClick = { if (l != settings.language) scope.launch { graph.settings.update { it.copy(language = l) }; onLanguageChanged() } },
                                style = PillStyle.Outline, active = l == settings.language,
                            )
                        }
                    }
                    Text(stringResource(R.string.ob_1_title), style = t.title, color = c.ink)
                    Text(stringResource(R.string.ob_1_body), style = t.body, color = c.mut)
                    Text(stringResource(R.string.ob_1_soft), style = t.body, color = c.mut)
                    Text(stringResource(R.string.ob_1_haptics), style = t.body, color = c.dim)
                    PillButton(stringResource(R.string.ob_next), onClick = { step = if (granted) 3 else 2 }, style = PillStyle.Primary)
                }
                2 -> {
                    Text(stringResource(R.string.ob_2_title), style = t.title, color = c.ink)
                    Text(stringResource(R.string.mic_rationale), style = t.body, color = c.mut)
                    Text(stringResource(R.string.ob_2_body), style = t.body, color = c.mut)
                    PillButton(stringResource(R.string.mic_allow), onClick = { requestMic.launch(Manifest.permission.RECORD_AUDIO) }, style = PillStyle.Primary)
                }
                3 -> {
                    Text(stringResource(R.string.ob_3_title), style = t.title, color = c.ink)
                    Text(stringResource(R.string.ob_3_body), style = t.body, color = c.mut)
                    VoiceType.entries.forEach { v ->
                        val selected = v == settings.voiceType
                        Row(
                            Modifier.fillMaxWidth().clickable { scope.launch { graph.settings.update { it.copy(voiceType = v, useCustomBand = false) } } }
                                .background(if (selected) c.panel2 else c.panel).padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(voiceTypeName(v), style = t.body, color = if (selected) c.violetInk else c.ink)
                            Spacer(Modifier.weight(1f))
                            Label("%.1f–%.1f ".format(v.band.loHz / 1000, v.band.hiHz / 1000) + stringResource(R.string.unit_khz))
                        }
                    }
                    PillButton(stringResource(R.string.ob_next), onClick = { step = 4 }, style = PillStyle.Primary)
                }
                else -> {
                    Text(stringResource(R.string.ob_4_title), style = t.title, color = c.ink)
                    Text(stringResource(R.string.ob_4_body), style = t.body, color = c.mut)
                    Text(stringResource(R.string.noise_intro_body), style = t.body, color = c.dim)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton(stringResource(R.string.ob_measure_now), onClick = { finish(onRoomNoise) }, style = PillStyle.Primary)
                        PillButton(stringResource(R.string.ob_later), onClick = { finish(onFinished) }, style = PillStyle.Outline)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
