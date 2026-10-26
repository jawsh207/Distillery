// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.service

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresPermission
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

/** Couldn't open or read the audio source; reported as ERROR_AUDIO. */
internal class AudioInputException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Mono float samples in [-1, 1] at [sampleRate]. */
internal class AudioFrame(val samples: FloatArray, val sampleRate: Int)

/** An audio source read in short frames from one thread. */
internal interface AudioInput : Closeable {
    /**
     * True for live audio (the microphone), which must be drained promptly.
     * Recordings are false: they're read as fast as they decode, with
     * back-pressure from the recognizer.
     */
    val isLive: Boolean

    /** Blocks for the next frame. Returns null at the end of the audio. */
    fun read(): AudioFrame?

    /** Makes a pending or later [read] return null soon; safe from any thread. */
    fun interrupt() {}
}

/**
 * Microphone capture for one session.
 *
 * Opened through the caller's attribution context, as RecognitionService
 * documents, so the privacy indicator and the permission check apply to the
 * app that asked for speech input, with Distillery recorded as the proxy.
 */
internal class MicrophoneInput @RequiresPermission(Manifest.permission.RECORD_AUDIO) constructor(
    attributionContext: Context,
) : AudioInput {

    override val isLive = true

    private val frame = ShortArray(FRAME_SAMPLES)
    private val record: AudioRecord

    init {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) throw AudioInputException("Microphone doesn't support 16 kHz mono")

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        record = try {
            AudioRecord.Builder()
                .setContext(attributionContext)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format)
                // Two seconds of headroom so a slow inference pass never drops audio.
                .setBufferSizeInBytes(maxOf(minBuffer, SAMPLE_RATE * 2 * 2))
                .build()
        } catch (e: UnsupportedOperationException) {
            throw AudioInputException("Couldn't create the recorder", e)
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw AudioInputException("Microphone failed to initialize")
        }
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            record.release()
            throw AudioInputException("Microphone failed to start", e)
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            throw AudioInputException("Microphone is in use or unavailable")
        }
    }

    override fun read(): AudioFrame? {
        val count = record.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
        if (count < 0) throw AudioInputException("Microphone read failed ($count)")
        return AudioFrame(FloatArray(count) { frame[it] / 32768f }, SAMPLE_RATE)
    }

    override fun close() {
        try {
            record.stop()
        } catch (_: IllegalStateException) {
        }
        record.release()
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /** 20 ms frames: frequent level updates, cheap to hand over. */
        const val FRAME_SAMPLES = SAMPLE_RATE / 50
    }
}

/**
 * Raw PCM supplied by the caller ([android.speech.RecognizerIntent.EXTRA_AUDIO_SOURCE]
 * with the AUDIO_SOURCE_ENCODING / SAMPLING_RATE / CHANNEL_COUNT extras):
 * 8-bit, 16-bit or float, any channel count, downmixed to mono.
 */
internal class PcmStreamInput(
    private val stream: InputStream,
    private val encoding: Int,
    private val sampleRate: Int,
    channelCount: Int,
) : AudioInput {

    override val isLive = false

    private val channels = channelCount.coerceAtLeast(1)
    private val bytesPerSample = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_16BIT -> 2
        AudioFormat.ENCODING_PCM_FLOAT -> 4
        else -> throw AudioInputException("Unsupported PCM encoding $encoding")
    }
    private val frameBytes = channels * bytesPerSample
    private val buffer = ByteArray((sampleRate / 50).coerceAtLeast(1) * frameBytes)
    private val closed = AtomicBoolean(false)

    init {
        if (sampleRate <= 0) throw AudioInputException("Bad sample rate $sampleRate")
    }

    override fun read(): AudioFrame? {
        if (closed.get()) return null
        val count = try {
            stream.readNBytes(buffer, 0, buffer.size)
        } catch (e: IOException) {
            // Closing the stream from interrupt() lands here; treat it as the end.
            return null
        }
        if (count <= 0) return null
        val frames = count / frameBytes
        return AudioFrame(FloatArray(frames) { f -> mix(f * frameBytes) }, sampleRate)
    }

    private fun mix(offset: Int): Float {
        var sum = 0f
        for (c in 0 until channels) sum += pcmSample(buffer, offset + c * bytesPerSample, encoding)
        return sum / channels
    }

    // Closing unblocks a read stuck on a pipe.
    override fun interrupt() = close()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                stream.close()
            } catch (_: IOException) {
            }
        }
    }
}

/** One little-endian PCM sample from [bytes] at [i], as a float in [-1, 1]. */
internal fun pcmSample(bytes: ByteArray, i: Int, encoding: Int): Float = when (encoding) {
    AudioFormat.ENCODING_PCM_8BIT -> ((bytes[i].toInt() and 0xFF) - 128) / 128f
    AudioFormat.ENCODING_PCM_16BIT ->
        ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort() / 32768f
    else -> Float.fromBits(
        (bytes[i].toInt() and 0xFF) or
            ((bytes[i + 1].toInt() and 0xFF) shl 8) or
            ((bytes[i + 2].toInt() and 0xFF) shl 16) or
            ((bytes[i + 3].toInt() and 0xFF) shl 24),
    )
}

/** For descriptor-backed inputs: closes the descriptor when the stream is done. */
internal fun ParcelFileDescriptor.inputStream(): InputStream = ParcelFileDescriptor.AutoCloseInputStream(this)

/** Maps a frame's level to the rough -2..10 dB range apps expect from onRmsChanged. */
internal fun rmsDb(samples: FloatArray): Float {
    if (samples.isEmpty()) return -2f
    var sum = 0.0
    for (s in samples) sum += s * s
    val rms = sqrt(sum / samples.size).coerceAtLeast(1e-6)
    val dbfs = 20 * log10(rms) // -120..0
    return (((dbfs + 60.0) / 60.0) * 12.0 - 2.0).toFloat().coerceIn(-2f, 10f)
}
