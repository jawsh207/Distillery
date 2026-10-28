// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import app.distillery.stt.feature.TtsFeature
import app.distillery.stt.ui.MainActivity

/**
 * Answers the system's voice data checks: CHECK_TTS_DATA (which voices are
 * installed) and INSTALL_TTS_DATA (opens the Voices screen to download one).
 */
class VoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            TextToSpeech.Engine.ACTION_CHECK_TTS_DATA -> {
                val installed = TtsRuntime.get(this).store.installed.value
                val accents = Accent.entries
                val available = accents.filter { a -> installed.any { it.accent == a } }.map { tag(it) }
                val missing = accents.filter { a -> installed.none { it.accent == a } }.map { tag(it) }
                val data = Intent()
                    .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, ArrayList(available))
                    .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, ArrayList(missing))
                setResult(
                    if (available.isEmpty()) TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL else TextToSpeech.Engine.CHECK_VOICE_DATA_PASS,
                    data,
                )
            }
            else -> startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN, TtsFeature.VOICES_ROUTE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        finish()
    }

    private fun tag(accent: Accent) = "eng-${accent.iso3Country}"
}
