package ru.dvedev.me.cupola.settings

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Synchronous mirror of [Settings.language] for `Activity.attachBaseContext`. */
fun readLanguageSync(context: Context): Language {
    val prefs = context.getSharedPreferences("cupola_locale", Context.MODE_PRIVATE)
    return runCatching { Language.valueOf(prefs.getString("language", null) ?: "") }.getOrDefault(Language.SYSTEM)
}

/** Wraps [base] in a context whose locale follows the app language setting. */
fun applyLanguage(base: Context): Context {
    val locale = when (readLanguageSync(base)) {
        Language.SYSTEM -> return base
        Language.RU -> Locale("ru")
        Language.EN -> Locale("en")
    }
    Locale.setDefault(locale)
    val config = Configuration(base.resources.configuration)
    config.setLocale(locale)
    return base.createConfigurationContext(config)
}
