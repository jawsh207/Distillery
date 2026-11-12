// SPDX-License-Identifier: Apache-2.0
package app.distillery.stt.engine

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/** User settings, shared by the app UI and the recognition service (same process). */
class DistillerySettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("distillery", Context.MODE_PRIVATE)

    /** The model the user picked. May not be installed; see [ModelStore.resolveModel]. */
    var chosenModel: SttModel
        get() = SttModel.fromId(prefs.getString(KEY_MODEL, null)) ?: SttModel.SMALL
        set(value) = prefs.edit().putString(KEY_MODEL, value.id).apply()

    /**
     * How long to wait after Moonshine's voice activity detector closes a
     * phrase before dictation stops on its own. The detector itself already
     * needs about half a second of quiet to close a phrase, so the pause a
     * person actually makes is roughly this plus 0.5 s.
     */
    var pauseToStopMs: Long
        get() = prefs.getLong(KEY_PAUSE_MS, DEFAULT_PAUSE_MS)
        set(value) = prefs.edit().putLong(KEY_PAUSE_MS, value.coerceIn(MIN_PAUSE_MS, MAX_PAUSE_MS)).apply()

    /** Give up with ERROR_SPEECH_TIMEOUT if nobody speaks for this long. */
    var noSpeechTimeoutMs: Long
        get() = prefs.getLong(KEY_NO_SPEECH_MS, DEFAULT_NO_SPEECH_MS)
        set(value) = prefs.edit().putLong(KEY_NO_SPEECH_MS, value).apply()

    /**
     * Keep the punctuation Moonshine adds (the default). Off removes it from
     * every result, for keyboards that add their own or plain-text use.
     */
    var punctuation: Boolean
        get() = prefs.getBoolean(KEY_PUNCTUATION, true)
        set(value) = prefs.edit().putBoolean(KEY_PUNCTUATION, value).apply()

    /**
     * Mask offensive words when the app doesn't say (Android's documented
     * default is to mask). An app's own EXTRA_MASK_OFFENSIVE_WORDS wins.
     */
    var maskOffensiveWords: Boolean
        get() = prefs.getBoolean(KEY_MASK_OFFENSIVE, true)
        set(value) = prefs.edit().putBoolean(KEY_MASK_OFFENSIVE, value).apply()

    /**
     * Show the Test rows, which most people never need. Revealed by tapping a
     * tab's own button eight times in a row ([MainTabs]); the same again hides them.
     */
    var showSttTest: Boolean
        get() = prefs.getBoolean(KEY_SHOW_STT_TEST, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_STT_TEST, value).apply()

    var showTtsTest: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TTS_TEST, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_TTS_TEST, value).apply()

    var wifiOnlyDownloads: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    /**
     * Emits a new number whenever any setting changes (and once on collection),
     * so UI can re-read the values above.
     */
    val changes: Flow<Int> = callbackFlow {
        var version = 0
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(++version) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(version)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    companion object {
        private const val KEY_MODEL = "model"
        private const val KEY_PAUSE_MS = "pause_to_stop_ms"
        private const val KEY_NO_SPEECH_MS = "no_speech_timeout_ms"
        private const val KEY_WIFI_ONLY = "wifi_only_downloads"
        private const val KEY_PUNCTUATION = "punctuation"
        private const val KEY_MASK_OFFENSIVE = "mask_offensive_words"
        private const val KEY_SHOW_STT_TEST = "show_stt_test"
        private const val KEY_SHOW_TTS_TEST = "show_tts_test"

        const val DEFAULT_PAUSE_MS = 1_000L
        const val MIN_PAUSE_MS = 200L
        const val MAX_PAUSE_MS = 4_000L
        const val DEFAULT_NO_SPEECH_MS = 6_000L
    }
}
