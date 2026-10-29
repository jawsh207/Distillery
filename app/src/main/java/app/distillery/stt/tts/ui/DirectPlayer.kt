// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.distillery.stt.tts.KokoroSynth
import app.distillery.stt.tts.KokoroVoice
import app.distillery.stt.tts.TextNormalizer
import app.distillery.stt.tts.TtsLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs Kokoro directly in the app and plays the result, skipping Android's
 * text-to-speech system. If this works and the system path doesn't, the
 * problem is between the framework and the engine service, not Kokoro.
 */
class DirectPlayer(private val synth: KokoroSynth, private val scope: CoroutineScope) {

    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null
    private var track: AudioTrack? = null

    fun play(voice: KokoroVoice, text: String, speed: Float) {
        if (busy) return
        busy = true
        status = "Working… (see the engine log below)"
        job = scope.launch(Dispatchers.IO) {
            try {
                TtsLog.add("Direct test: ${voice.id}, ${text.length} chars")
                val started = SystemClock.elapsedRealtime()
                val spoken = TextNormalizer.normalize(text)
                if (spoken != text) TtsLog.add("Read as: ${spoken.take(400)}")
                val (samples, rate) = synth.synthesize(voice, spoken, speed)
                val madeMs = SystemClock.elapsedRealtime() - started
                if (samples.isEmpty() || rate <= 0) {
                    status = "Kokoro returned no audio (rate $rate)"
                    return@launch
                }
                status = "Made ${samples.size * 1000L / rate} ms of audio in $madeMs ms. Playing…"
                playSamples(samples, rate)
                status = "Direct test worked: made in $madeMs ms"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TtsLog.error("Direct test failed", e)
                status = "Failed: ${e::class.java.simpleName}: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    private fun playSamples(samples: FloatArray, rate: Int) {
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 4)
            .build()
        track = t
        try {
            t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            t.play()
            val endMs = SystemClock.elapsedRealtime() + samples.size * 1000L / rate + 300
            while (SystemClock.elapsedRealtime() < endMs && t.playState == AudioTrack.PLAYSTATE_PLAYING) {
                Thread.sleep(50)
            }
        } finally {
            runCatching { t.stop() }
            t.release()
            track = null
        }
    }

    fun stop() {
        runCatching { track?.stop() }
    }
}
