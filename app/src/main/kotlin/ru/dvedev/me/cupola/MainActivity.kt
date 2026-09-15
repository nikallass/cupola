package ru.dvedev.me.cupola

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
                TokensPreviewScreen(mode = mode, onModeChange = { mode = it })
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
