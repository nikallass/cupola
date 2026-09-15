package ru.dvedev.me.cupola.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import ru.dvedev.me.cupola.audio.AudioSourcePreference
import ru.dvedev.me.cupola.dsp.calibration.Calibration
import ru.dvedev.me.cupola.dsp.metrics.RingBand
import ru.dvedev.me.cupola.dsp.metrics.VoiceType
import ru.dvedev.me.cupola.notation.Accidentals
import ru.dvedev.me.cupola.notation.NotationMode
import ru.dvedev.me.cupola.ui.theme.ThemeMode

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "cupola_settings")

/**
 * DataStore-backed settings and calibrations (SPEC §15.2: no Room in v0.1).
 * Calibrations are JSON blobs keyed by [Settings.calibrationKey].
 */
class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore
    private val localePrefs = context.applicationContext.getSharedPreferences(LOCALE_PREFS, Context.MODE_PRIVATE)

    val settings: Flow<Settings> = store.data.map { it.toSettings() }

    suspend fun current(): Settings = settings.first()

    suspend fun update(transform: (Settings) -> Settings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs.write(next)
        }
        // language is also mirrored synchronously for Activity.attachBaseContext
        val lang = current().language
        localePrefs.edit().putString(KEY_LANGUAGE, lang.name).apply()
    }

    /** Synchronous read for [android.app.Activity.attachBaseContext]. */
    fun languageSync(): Language = runCatching { Language.valueOf(localePrefs.getString(KEY_LANGUAGE, null) ?: "") }.getOrDefault(Language.SYSTEM)

    val calibrations: Flow<Map<String, Calibration>> = store.data.map { prefs ->
        prefs.asMap().entries
            .filter { it.key.name.startsWith(CAL_PREFIX) }
            .mapNotNull { (k, v) -> (v as? String)?.let { json -> parseCalibration(json)?.let { k.name.removePrefix(CAL_PREFIX) to it } } }
            .toMap()
    }

    suspend fun calibration(key: String): Calibration? = calibrations.first()[key]

    suspend fun saveCalibration(key: String, calibration: Calibration) {
        store.edit { it[stringPreferencesKey(CAL_PREFIX + key)] = calibration.toJson() }
    }

    suspend fun resetAdvanced() {
        update { s ->
            val d = Settings()
            s.copy(
                centsOk = d.centsOk, centsWarn = d.centsWarn, confidenceMin = d.confidenceMin, splK = d.splK,
                ringWeight = d.ringWeight, pitchWeight = d.pitchWeight, steadyWeight = d.steadyWeight,
            )
        }
    }

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        fun <T : Enum<T>> enum(key: String, default: T, values: Array<T>): T =
            this[stringPreferencesKey(key)]?.let { name -> values.firstOrNull { it.name == name } } ?: default
        return Settings(
            voiceType = enum(K_VOICE, d.voiceType, VoiceType.entries.toTypedArray()),
            customLoHz = this[intPreferencesKey(K_CUSTOM_LO)] ?: d.customLoHz,
            customHiHz = this[intPreferencesKey(K_CUSTOM_HI)] ?: d.customHiHz,
            useCustomBand = this[booleanPreferencesKey(K_USE_CUSTOM)] ?: d.useCustomBand,
            a4Hz = this[intPreferencesKey(K_A4)] ?: d.a4Hz,
            notation = enum(K_NOTATION, d.notation, NotationMode.entries.toTypedArray()),
            accidentals = enum(K_ACCIDENTALS, d.accidentals, Accidentals.entries.toTypedArray()),
            language = enum(K_LANGUAGE, d.language, Language.entries.toTypedArray()),
            theme = enum(K_THEME, d.theme, ThemeMode.entries.toTypedArray()),
            logFrequencyAxis = this[booleanPreferencesKey(K_LOG_AXIS)] ?: d.logFrequencyAxis,
            fftSize = this[intPreferencesKey(K_FFT)] ?: d.fftSize,
            audioSource = enum(K_SOURCE, d.audioSource, AudioSourcePreference.entries.toTypedArray()),
            haptics = this[booleanPreferencesKey(K_HAPTICS)],
            pointsAnimation = this[booleanPreferencesKey(K_POINTS_ANIM)] ?: d.pointsAnimation,
            hints = this[booleanPreferencesKey(K_HINTS)] ?: d.hints,
            centsOk = this[intPreferencesKey(K_CENTS_OK)] ?: d.centsOk,
            centsWarn = this[intPreferencesKey(K_CENTS_WARN)] ?: d.centsWarn,
            confidenceMin = this[doublePreferencesKey(K_CONF)] ?: d.confidenceMin,
            splK = this[doublePreferencesKey(K_SPL_K)] ?: d.splK,
            ringWeight = this[doublePreferencesKey(K_W_RING)] ?: d.ringWeight,
            pitchWeight = this[doublePreferencesKey(K_W_PITCH)] ?: d.pitchWeight,
            steadyWeight = this[doublePreferencesKey(K_W_STEADY)] ?: d.steadyWeight,
            onboardingDone = this[booleanPreferencesKey(K_ONBOARDING)] ?: d.onboardingDone,
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.write(s: Settings) {
        this[stringPreferencesKey(K_VOICE)] = s.voiceType.name
        this[intPreferencesKey(K_CUSTOM_LO)] = s.customLoHz
        this[intPreferencesKey(K_CUSTOM_HI)] = s.customHiHz
        this[booleanPreferencesKey(K_USE_CUSTOM)] = s.useCustomBand
        this[intPreferencesKey(K_A4)] = s.a4Hz
        this[stringPreferencesKey(K_NOTATION)] = s.notation.name
        this[stringPreferencesKey(K_ACCIDENTALS)] = s.accidentals.name
        this[stringPreferencesKey(K_LANGUAGE)] = s.language.name
        this[stringPreferencesKey(K_THEME)] = s.theme.name
        this[booleanPreferencesKey(K_LOG_AXIS)] = s.logFrequencyAxis
        this[intPreferencesKey(K_FFT)] = s.fftSize
        this[stringPreferencesKey(K_SOURCE)] = s.audioSource.name
        val h = s.haptics
        if (h == null) remove(booleanPreferencesKey(K_HAPTICS)) else this[booleanPreferencesKey(K_HAPTICS)] = h
        this[booleanPreferencesKey(K_POINTS_ANIM)] = s.pointsAnimation
        this[booleanPreferencesKey(K_HINTS)] = s.hints
        this[intPreferencesKey(K_CENTS_OK)] = s.centsOk
        this[intPreferencesKey(K_CENTS_WARN)] = s.centsWarn
        this[doublePreferencesKey(K_CONF)] = s.confidenceMin
        this[doublePreferencesKey(K_SPL_K)] = s.splK
        this[doublePreferencesKey(K_W_RING)] = s.ringWeight
        this[doublePreferencesKey(K_W_PITCH)] = s.pitchWeight
        this[doublePreferencesKey(K_W_STEADY)] = s.steadyWeight
        this[booleanPreferencesKey(K_ONBOARDING)] = s.onboardingDone
    }

    companion object {
        private const val LOCALE_PREFS = "cupola_locale"
        private const val KEY_LANGUAGE = "language"
        private const val CAL_PREFIX = "calibration."
        private const val K_VOICE = "voiceType"
        private const val K_CUSTOM_LO = "customLoHz"
        private const val K_CUSTOM_HI = "customHiHz"
        private const val K_USE_CUSTOM = "useCustomBand"
        private const val K_A4 = "a4Hz"
        private const val K_NOTATION = "notation"
        private const val K_ACCIDENTALS = "accidentals"
        private const val K_LANGUAGE = "language"
        private const val K_THEME = "theme"
        private const val K_LOG_AXIS = "logFrequencyAxis"
        private const val K_FFT = "fftSize"
        private const val K_SOURCE = "audioSource"
        private const val K_HAPTICS = "haptics"
        private const val K_POINTS_ANIM = "pointsAnimation"
        private const val K_HINTS = "hints"
        private const val K_CENTS_OK = "centsOk"
        private const val K_CENTS_WARN = "centsWarn"
        private const val K_CONF = "confidenceMin"
        private const val K_SPL_K = "splK"
        private const val K_W_RING = "ringWeight"
        private const val K_W_PITCH = "pitchWeight"
        private const val K_W_STEADY = "steadyWeight"
        private const val K_ONBOARDING = "onboardingDone"

        fun Calibration.toJson(): String = JSONObject()
            .put("lo", band.loHz).put("hi", band.hiHz)
            .put("noise", noiseFloorDbfs).put("ring", ringRatioDb).put("spl", splDbfs)
            .put("voiced", voicedShare).put("createdAt", createdAtEpochMs)
            .toString()

        fun parseCalibration(json: String): Calibration? = runCatching {
            val o = JSONObject(json)
            Calibration(
                band = RingBand(o.getDouble("lo"), o.getDouble("hi")),
                noiseFloorDbfs = o.getDouble("noise"),
                ringRatioDb = o.getDouble("ring"),
                splDbfs = o.getDouble("spl"),
                voicedShare = o.optDouble("voiced", 1.0),
                createdAtEpochMs = o.getLong("createdAt"),
            )
        }.getOrNull()
    }
}
