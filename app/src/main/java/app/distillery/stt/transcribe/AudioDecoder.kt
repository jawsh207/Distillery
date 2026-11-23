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
 * Decodes a file's audio with Android's own codecs (MP3, AAC/M4A, Ogg Vorbis,
 * Opus, FLAC, WAV, AMR, and the audio of MP4, MKV, WebM and 3GP videos) into
 * mono float samples, a few hundred milliseconds at a time, using the first
 * audio track the phone can decode. Nothing is resampled: Moonshine does that
 * itself. Formats a phone may have no decoder for (Dolby, DTS) are named in
 * the error so people know what to convert.
 */
object AudioDecoder {

    private const val TIMEOUT_US = 10_000L

    /**
     * Calls [onAudio] with each block of mono samples and its sample rate, and
     * [onProgress] with the position and duration in microseconds (duration 0
     * when the file doesn't say). Stops early when [cancelled] returns true.
     *
     * Files can have several audio tracks (videos often carry Dolby or DTS audio
     * beside a stereo track), and some decoders refuse float output, so each
     * track is tried in order, with float and then 16-bit output, until one
     * decodes. A decoder that fails after producing audio is reported rather than
     * starting over on another track, which would repeat what was already heard.
     */
    fun decode(
        file: File,
        cancelled: () -> Boolean,
        onProgress: (positionUs: Long, durationUs: Long) -> Unit,
        onAudio: (samples: FloatArray, sampleRate: Int) -> Unit,
    ) {
        val tracks = audioTracks(file)
        if (tracks.isEmpty()) throw UnsupportedAudioException("This file has no audio track")
        for ((track, mime) in tracks) {
            for (floatOutput in listOf(true, false)) {
                var produced = false
                try {
                    decodeTrack(file, track, floatOutput, cancelled, onProgress) { samples, rate ->
                        produced = true
                        onAudio(samples, rate)
                    }
                    return
                } catch (e: UnsupportedAudioException) {
                    break // no decoder for this track at all: try the next track
                } catch (e: IllegalStateException) { // MediaCodec.CodecException is one
                    if (produced) {
                        throw UnsupportedAudioException("Android's ${describe(mime)} decoder stopped partway through this file")
                    }
                } catch (e: IllegalArgumentException) {
                    if (produced) throw e
                }
            }
        }
        val formats = tracks.map { describe(it.second) }.distinct().joinToString(" or ")
        throw UnsupportedAudioException(
            "This phone can't decode the file's $formats audio. Convert it to M4A or MP3, or pick another file.",
        )
    }

    /** The file's audio tracks: index and MIME type. */
    private fun audioTracks(file: File): List<Pair<Int, String>> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            return (0 until extractor.trackCount).mapNotNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.takeIf { it.startsWith("audio/") }?.let { i to it }
            }
        } catch (e: IOException) {
            throw UnsupportedAudioException("Android can't read this file")
        } finally {
            extractor.release()
        }
    }

    private fun decodeTrack(
        file: File,
        track: Int,
        floatOutput: Boolean,
        cancelled: () -> Boolean,
        onProgress: (positionUs: Long, durationUs: Long) -> Unit,
        onAudio: (samples: FloatArray, sampleRate: Int) -> Unit,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            val decoder = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw UnsupportedAudioException("Android can't decode ${describe(mime)} audio")
            }
            codec = decoder
            if (floatOutput) format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
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

    /** A MIME type as people know the format: "audio/ac3" → "Dolby Digital (AC-3)". */
    fun describe(mime: String): String = when (mime.lowercase()) {
        "audio/ac3" -> "Dolby Digital (AC-3)"
        "audio/eac3", "audio/eac3-joc" -> "Dolby Digital Plus (E-AC-3)"
        "audio/ac4" -> "Dolby AC-4"
        "audio/true-hd" -> "Dolby TrueHD"
        "audio/vnd.dts", "audio/vnd.dts.hd", "audio/vnd.dts.uhd" -> "DTS"
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        "audio/raw" -> "PCM"
        "audio/3gpp" -> "AMR"
        "audio/amr-wb" -> "AMR-WB"
        "audio/alac" -> "ALAC"
        else -> mime.removePrefix("audio/").uppercase()
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
