// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

import android.content.Context
import java.io.File

/** Text-to-speech singletons shared by the TTS service, the download worker and the UI. */
class TtsRuntime private constructor(context: Context) {
    val store = VoiceStore(File(context.noBackupFilesDir, "tts"))
    val settings = TtsSettings(context)
    val downloads = VoiceDownloads(context)
    val synth = KokoroSynth(context, store)

    companion object {
        @Volatile private var instance: TtsRuntime? = null

        fun get(context: Context): TtsRuntime =
            instance ?: synchronized(this) {
                instance ?: TtsRuntime(context.applicationContext).also { instance = it }
            }
    }
}
