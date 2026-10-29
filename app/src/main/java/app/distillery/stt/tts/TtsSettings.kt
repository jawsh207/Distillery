// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Text-to-speech preferences. */
class TtsSettings(context: Context) {

    private val prefs = context.getSharedPreferences("distillery_tts", Context.MODE_PRIVATE)
    private val _defaultVoice = MutableStateFlow(read())

    /** The voice used when an app doesn't pick one. */
    val defaultVoice: StateFlow<KokoroVoice> = _defaultVoice.asStateFlow()

    fun setDefaultVoice(voice: KokoroVoice) {
        prefs.edit().putString(KEY_VOICE, voice.id).apply()
        _defaultVoice.value = voice
    }

    private fun read() = KokoroVoice.fromId(prefs.getString(KEY_VOICE, null)) ?: KokoroVoice.DEFAULT

    private companion object {
        const val KEY_VOICE = "default_voice"
    }
}
