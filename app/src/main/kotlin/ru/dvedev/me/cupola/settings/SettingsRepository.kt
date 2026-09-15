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
import ru.dvedev.me.cupola.dsp.metrics.RoomNoise
import ru.dvedev.me.cupola.dsp.metrics.VoiceType
import ru.dvedev.me.cupola.notation.Accidentals
import ru.dvedev.me.cupola.notation.NotationMode
import ru.dvedev.me.cupola.ui.theme.ThemeMode

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "cupola_settings")

/**
 * DataStore-backed settings and the room noise profile (SPEC §15.2: no Room in v0.1).
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

    /** The measured room noise profile, or null. */
    val roomNoise: Flow<RoomNoise?> = store.data.map { prefs -> prefs[stringPreferencesKey(K_ROOM_NOISE)]?.let { parseRoomNoise(it) } }

    suspend fun saveRoomNoise(noise: RoomNoise) {
        store.edit { it[stringPreferencesKey(K_ROOM_NOISE)] = noise.toJson() }
    }

    suspend fun clearRoomNoise() {
        store.edit { it.remove(stringPreferencesKey(K_ROOM_NOISE)) }
    }

    suspend fun resetAdvanced() {
        update { s ->
            val d = Settings()
            s.copy(
                centsOk = d.centsOk, centsWarn = d.centsWarn, confidenceMin = d.confidenceMin,
                ringShareFullPct = d.ringShareFullPct, ringHumpFullDb = d.ringHumpFullDb,
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
            ringShareFullPct = this[intPreferencesKey(K_RING_SHARE)] ?: d.ringShareFullPct,
            ringHumpFullDb = this[intPreferencesKey(K_RING_HUMP)] ?: d.ringHumpFullDb,
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
        this[intPreferencesKey(K_RING_SHARE)] = s.ringShareFullPct
        this[intPreferencesKey(K_RING_HUMP)] = s.ringHumpFullDb
        this[doublePreferencesKey(K_W_RING)] = s.ringWeight
        this[doublePreferencesKey(K_W_PITCH)] = s.pitchWeight
        this[doublePreferencesKey(K_W_STEADY)] = s.steadyWeight
        this[booleanPreferencesKey(K_ONBOARDING)] = s.onboardingDone
    }

    companion object {
        private const val LOCALE_PREFS = "cupola_locale"
        private const val KEY_LANGUAGE = "language"
        private const val K_ROOM_NOISE = "roomNoise"
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
        private const val K_RING_SHARE = "ringShareFullPct"
        private const val K_RING_HUMP = "ringHumpFullDb"
        private const val K_W_RING = "ringWeight"
        private const val K_W_PITCH = "pitchWeight"
        private const val K_W_STEADY = "steadyWeight"
        private const val K_ONBOARDING = "onboardingDone"

        fun RoomNoise.toJson(): String = JSONObject()
            .put("sampleRate", sampleRate).put("fftSize", fftSize).put("rms", rmsDbfs).put("createdAt", createdAtEpochMs)
            .put("profile", profileDb.joinToString(",") { "%.1f".format(java.util.Locale.ROOT, it) })
            .toString()

        fun parseRoomNoise(json: String): RoomNoise? = runCatching {
            val o = JSONObject(json)
            val profile = o.getString("profile").split(',').map { it.toDouble() }.toDoubleArray()
            RoomNoise(
                sampleRate = o.getInt("sampleRate"),
                fftSize = o.getInt("fftSize"),
                profileDb = profile,
                rmsDbfs = o.getDouble("rms"),
                createdAtEpochMs = o.getLong("createdAt"),
            )
        }.getOrNull()
    }
}
