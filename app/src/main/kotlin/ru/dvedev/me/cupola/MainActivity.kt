package ru.dvedev.me.cupola

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.dvedev.me.cupola.analysis.AnalysisViewModel
import ru.dvedev.me.cupola.ui.components.PillButton
import ru.dvedev.me.cupola.ui.components.PillStyle
import ru.dvedev.me.cupola.ui.debug.LiveReadout
import ru.dvedev.me.cupola.ui.preview.TokensPreviewScreen
import ru.dvedev.me.cupola.ui.theme.CupolaTheme
import ru.dvedev.me.cupola.ui.theme.ThemeMode
import ru.dvedev.me.cupola.ui.theme.resolvesToDark

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Theme mode lives in memory until settings land in T-060 (DataStore).
            var mode by rememberSaveable { mutableStateOf(ThemeMode.SYSTEM) }
            val dark = mode.resolvesToDark()
            LaunchedEffect(dark) { applySystemBars(dark) }
            CupolaTheme(dark = dark) {
                Root(mode = mode, onModeChange = { mode = it })
            }
        }
    }

    private fun applySystemBars(dark: Boolean) {
        val style = if (dark) {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        } else {
            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}

@Composable
private fun Root(mode: ThemeMode, onModeChange: (ThemeMode) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val vm: AnalysisViewModel = viewModel { AnalysisViewModel(context.appGraph.engine) }
    var granted by rememberSaveable {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    // Analysis runs while the app is visible (SPEC §15.5); the session case is T-042.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, granted) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (granted) vm.startListening()
                Lifecycle.Event.ON_STOP -> vm.stopListening()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (granted && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) vm.startListening()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val c = CupolaTheme.colors
    var showTokens by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.panel).statusBarsPadding().verticalScroll(rememberScrollState())) {
        if (!granted) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Куполу нужен микрофон, чтобы слышать голос. Звук не покидает устройство.", style = CupolaTheme.type.body, color = c.mut)
                    PillButton("Разрешить микрофон", onClick = { requestPermission.launch(Manifest.permission.RECORD_AUDIO) }, style = PillStyle.Primary, modifier = Modifier.padding(top = 16.dp))
                }
            }
        } else {
            val metrics by vm.metrics.collectAsStateWithLifecycle()
            val engineState by vm.engineState.collectAsStateWithLifecycle()
            LiveReadout(metrics, engineState, vm.overruns, vm.readErrors, vm.processingLatencyMs)
            PillButton(
                if (showTokens) "Скрыть токены" else "Показать токены",
                onClick = { showTokens = !showTokens },
                style = PillStyle.Outline,
                modifier = Modifier.padding(14.dp),
            )
            if (showTokens) TokensPreviewScreen(mode = mode, onModeChange = onModeChange)
        }
    }
}
