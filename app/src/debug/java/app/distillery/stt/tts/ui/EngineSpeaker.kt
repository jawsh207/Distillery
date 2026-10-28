// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts.ui

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.distillery.stt.tts.KokoroVoice

/**
 * Speaks through android.speech.tts.TextToSpeech bound to Distillery's own
 * engine, the same path any other app takes, and times it.
 */
class EngineSpeaker(context: Context) {

    var ready by mutableStateOf(false)
        private set
    var speaking by mutableStateOf(false)
        private set
    var status by mutableStateOf("Connecting to the engine…")
        private set
    var firstAudioMs by mutableStateOf<Long?>(null)
        private set
    var totalMs by mutableStateOf<Long?>(null)
        private set

    private var startedAt = 0L
    private var gotAudio = false

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext, { result ->
        ready = result == TextToSpeech.SUCCESS
        status = if (ready) "Ready" else "Couldn't connect to the engine ($result)"
    }, context.packageName)

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                status = "Synthesizing…"
            }

            override fun onBeginSynthesis(utteranceId: String?, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                status = "Speaking ($sampleRateInHz Hz)"
            }

            override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) {
                if (!gotAudio) {
                    gotAudio = true
                    firstAudioMs = SystemClock.elapsedRealtime() - startedAt
                }
            }

            override fun onDone(utteranceId: String?) {
                speaking = false
                totalMs = SystemClock.elapsedRealtime() - startedAt
                status = "Done"
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                speaking = false
                status = "Stopped"
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onError(utteranceId, TextToSpeech.ERROR)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                speaking = false
                status = when (errorCode) {
                    TextToSpeech.ERROR_NOT_INSTALLED_YET -> "No voice downloaded yet"
                    TextToSpeech.ERROR_SYNTHESIS -> "Synthesis failed (see logcat: DistilleryTts)"
                    else -> "Error $errorCode"
                }
            }
        })
    }

    /** @param rate 1.0 is normal speed. */
    fun speak(text: String, voice: KokoroVoice?, rate: Float) {
        if (!ready) return
        if (voice != null) {
            tts.voices?.firstOrNull { it.name == voice.voiceName }?.let { tts.setVoice(it) }
        }
        tts.setSpeechRate(rate)
        firstAudioMs = null
        totalMs = null
        gotAudio = false
        startedAt = SystemClock.elapsedRealtime()
        speaking = true
        status = "Loading the voice…"
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "distillery-test")
        if (result != TextToSpeech.SUCCESS) {
            speaking = false
            status = "speak() returned $result"
        }
    }

    fun stop() {
        tts.stop()
    }

    fun shutdown() {
        tts.shutdown()
    }
}
