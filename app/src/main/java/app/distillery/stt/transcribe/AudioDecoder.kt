// SPDX-License-Identifier: Apache-2.0
package app.distillery.stt.transcribe

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/** Thrown when a file has no audio Android can decode. */
class UnsupportedAudioException(message: String) : IOException(message)

/**
 * Decodes the first audio track of a file with Android's own codecs (MP3,
 * AAC/M4A, Ogg Vorbis, Opus, FLAC, WAV, AMR, and the audio of MP4, WebM and
 * 3GP videos) into mono float samples, a few hundred milliseconds at a time.
 * Nothing is resampled: Moonshine does that itself.
 */
object AudioDecoder {

    private const val TIMEOUT_US = 10_000L

    /**
     * Calls [onAudio] with each block of mono samples and its sample rate, and
     * [onProgress] with the position and duration in microseconds (duration 0
     * when the file doesn't say). Stops early when [cancelled] returns true.
     */
    fun decode(
        file: File,
        cancelled: () -> Boolean,
        onProgress: (positionUs: Long, durationUs: Long) -> Unit,
        onAudio: (samples: FloatArray, sampleRate: Int) -> Unit,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw UnsupportedAudioException("This file has no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            val decoder = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw UnsupportedAudioException("Android can't decode $mime audio")
            }
            codec = decoder
            // Ask for float output; decoders that can't still give 16-bit PCM.
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            decoder.configure(format, null, null, 0)
            decoder.start()

            var sampleRate = format.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, 16_000)
            var channels = format.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var inputDone = false
            val info = MediaCodec.BufferInfo()

            while (!cancelled()) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val out = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = decoder.outputFormat
                        sampleRate = f.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = f.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        encoding = f.getIntegerOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    }
                    out >= 0 -> {
                        if (info.size > 0) {
                            val buffer = decoder.getOutputBuffer(out)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val mono = toMono(buffer.order(ByteOrder.nativeOrder()), encoding, channels)
                            if (mono.isNotEmpty()) onAudio(mono, sampleRate)
                            onProgress(info.presentationTimeUs, durationUs)
                        }
                        decoder.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** Averages the channels of interleaved PCM into one. */
    private fun toMono(buffer: java.nio.ByteBuffer, encoding: Int, channels: Int): FloatArray {
        val ch = channels.coerceAtLeast(1)
        return when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = buffer.asFloatBuffer()
                val frames = floats.remaining() / ch
                FloatArray(frames) { i ->
                    var sum = 0f
                    for (c in 0 until ch) sum += floats.get(i * ch + c)
                    sum / ch
                }
            }
            else -> {
                val shorts = buffer.asShortBuffer()
                val frames = shorts.remaining() / ch
                FloatArray(frames) { i ->
                    var sum = 0f
                    for (c in 0 until ch) sum += shorts.get(i * ch + c)
                    sum / ch / 32768f
                }
            }
        }
    }

    private fun MediaFormat.getIntegerOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback
}
