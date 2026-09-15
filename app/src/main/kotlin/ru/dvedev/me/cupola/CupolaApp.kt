package ru.dvedev.me.cupola

import android.app.Application
import android.content.Context
import ru.dvedev.me.cupola.audio.AudioEngine
import ru.dvedev.me.cupola.dsp.AnalyzerConfig

/**
 * Manual dependency graph (SPEC §15.2: no Hilt). One instance per process, reachable
 * through [Context.appGraph]; screens get what they need from here via their ViewModels.
 */
class AppGraph(app: Application) {
    val engine: AudioEngine by lazy { AudioEngine(app, AnalyzerConfig()) }
}

class CupolaApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

val Context.appGraph: AppGraph get() = (applicationContext as CupolaApp).graph
