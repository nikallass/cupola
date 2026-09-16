package ru.dvedev.me.cupola

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.dvedev.me.cupola.analysis.AnalysisViewModel
import ru.dvedev.me.cupola.settings.applyLanguage
import ru.dvedev.me.cupola.ui.analysis.AnalysisScreen
import ru.dvedev.me.cupola.ui.analysis.CentsThresholds
import ru.dvedev.me.cupola.ui.analysis.LocalCentsThresholds
import ru.dvedev.me.cupola.ui.components.PillButton
import ru.dvedev.me.cupola.ui.components.PillStyle
import ru.dvedev.me.cupola.ui.onboarding.OnboardingScreen
import ru.dvedev.me.cupola.ui.settings.SettingsScreen
import ru.dvedev.me.cupola.ui.theme.CupolaTheme
import ru.dvedev.me.cupola.ui.theme.resolvesToDark

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(applyLanguage(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by appGraph.settingsState.collectAsStateWithLifecycle()
            val dark = settings.theme.resolvesToDark()
            LaunchedEffect(dark) { applySystemBars(dark) }
            CupolaTheme(dark = dark) {
                CompositionLocalProvider(LocalCentsThresholds provides CentsThresholds(settings.centsOk.toDouble(), settings.centsWarn.toDouble())) {
                    Root(onLanguageChanged = { recreate() })
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Full screen like a game (owner 2026‑09‑16): status and navigation bars hidden, a swipe from the edge shows them for a moment. */
    private fun hideSystemBars() {
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }

    private fun applySystemBars(dark: Boolean) {
        val style = if (dark) {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        } else {
            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        hideSystemBars()
    }
}

private enum class Screen { ONBOARDING, ANALYSIS, SETTINGS }

@Composable
private fun Root(onLanguageChanged: () -> Unit) {
    val context = LocalContext.current
    val graph = context.appGraph
    val vm: AnalysisViewModel = viewModel { AnalysisViewModel(graph) }
    val settings by graph.settingsState.collectAsStateWithLifecycle()
    var granted by rememberSaveable {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    // Analysis runs while the app is visible (SPEC §15.5); in a session the service keeps it (T-042).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, granted) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    ru.dvedev.me.cupola.audio.MicJournal.add("activity ON_START")
                    graph.activityVisible = true
                    // re-read the permission: on some OEMs the grant dialog is a full activity and the flag set on our callbacks may be stale
                    granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (granted) vm.startListening()
                }
                Lifecycle.Event.ON_STOP -> { ru.dvedev.me.cupola.audio.MicJournal.add("activity ON_STOP"); graph.activityVisible = false; vm.stopListeningIfIdle() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (granted && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) vm.startListening()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val c = CupolaTheme.colors
    var screen by rememberSaveable { mutableStateOf(if (settings.onboardingDone) Screen.ANALYSIS else Screen.ONBOARDING) }
    var returnTo by rememberSaveable { mutableStateOf(Screen.ANALYSIS) }
    if (screen == Screen.ONBOARDING) {
        OnboardingScreen(
            graph,
            onFinished = {
                granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                screen = Screen.ANALYSIS
            },
            onLanguageChanged = onLanguageChanged,
        )
        return
    }
    if (!granted) {
        Box(Modifier.fillMaxSize().background(c.panel).safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.mic_rationale), style = CupolaTheme.type.body, color = c.mut)
                PillButton(stringResource(R.string.mic_allow), onClick = { requestPermission.launch(Manifest.permission.RECORD_AUDIO) }, style = PillStyle.Primary, modifier = Modifier.padding(top = 16.dp))
            }
        }
        return
    }
    when (screen) {
        Screen.ONBOARDING -> Unit // handled above
        Screen.ANALYSIS -> AnalysisScreen(vm, onSettings = { screen = Screen.SETTINGS })
        Screen.SETTINGS -> {
            BackHandler { screen = Screen.ANALYSIS }
            SettingsScreen(
                graph,
                onBack = { screen = Screen.ANALYSIS },
                onLanguageChanged = onLanguageChanged,
            )
        }
    }
}
