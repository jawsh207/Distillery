// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.service

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opens audio a caller hands over with [android.speech.RecognizerIntent.EXTRA_AUDIO_SOURCE],
 * such as an audio file to transcribe.
 *
 * Callers can pass either an encoded recording (AMR, AAC/M4A/3GP, MP3, Ogg
 * Opus/Vorbis, FLAC, WAV: whatever the phone's decoders handle) or raw PCM as
 * the RecognizerIntent docs describe. Distillery tells them apart by the file's
 * signature and by whether the caller named a PCM encoding:
 *
 *  - a recognized container signature is always decoded;
 *  - a regular file with no PCM encoding given is decoded if Android can, else read as PCM;
 *  - anything else is raw PCM with the caller's encoding, rate and channel count.
 *
 * Encoded audio arriving over a pipe is copied to a private temporary file
 * first, since the platform decoders need random access. The copy is deleted
 * as soon as the session ends.
 */
internal object RecordingInput {

    private const val TAG = "DistilleryRecording"
    private const val HEAD_BYTES = 12

    fun open(audio: ExternalAudio, cacheDir: File): AudioInput {
        val fd = audio.descriptor.fileDescriptor
        val regularFile = isRegularFile(fd)

        if (regularFile) {
            val head = preadHead(fd)
            val signature = ContainerSignature.detect(head, allowFrameSync = !audio.explicitPcm)
            if (signature != null || audio.wantsDecoding) {
                return DecodedInput.open(audio.descriptor, onClose = {})
            }
            if (!audio.explicitPcm) {
                try {
                    return DecodedInput.open(audio.descriptor, onClose = {})
                } catch (e: AudioInputException) {
                    Log.i(TAG, "Not a decodable recording; reading it as raw PCM")
                    rewind(fd)
                }
            }
            return pcm(audio, audio.descriptor.inputStream())
        }

        // A pipe or socket: look at the first bytes without losing them.
        val stream = audio.descriptor.inputStream()
        val head = try {
            stream.readNBytes(HEAD_BYTES)
        } catch (e: IOException) {
            stream.close()
            throw AudioInputException("Couldn't read the audio stream", e)
        }
        val signature = ContainerSignature.detect(head, allowFrameSync = !audio.explicitPcm)
        if (signature == null && !audio.wantsDecoding) {
            return pcm(audio, SequenceInputStream(head.inputStream(), stream))
        }

        // Encoded audio on a pipe: spool it to a private file the decoder can seek.
        val spool = File.createTempFile("recording", ".audio", cacheDir)
        try {
            spool.outputStream().use { out ->
                out.write(head)
                stream.use { it.copyTo(out) }
            }
        } catch (e: IOException) {
            spool.delete()
            throw AudioInputException("Couldn't read the recording", e)
        }
        val spoolDescriptor = ParcelFileDescriptor.open(
            spool,
            ParcelFileDescriptor.MODE_READ_ONLY,
        )
        return try {
            DecodedInput.open(spoolDescriptor, onClose = { spool.delete() })
        } catch (e: AudioInputException) {
            spoolDescriptor.close()
            spool.delete()
            throw e
        }
    }

    private fun pcm(audio: ExternalAudio, stream: InputStream) =
        PcmStreamInput(stream, audio.encoding, audio.sampleRate, audio.channelCount)

    private fun rewind(fd: FileDescriptor) {
        try {
            Os.lseek(fd, 0, OsConstants.SEEK_SET)
        } catch (_: ErrnoException) {
        }
    }

    private fun isRegularFile(fd: FileDescriptor): Boolean = try {
        OsConstants.S_ISREG(Os.fstat(fd).st_mode)
    } catch (e: ErrnoException) {
        false
    }

    private fun preadHead(fd: FileDescriptor): ByteArray = try {
        val buffer = ByteArray(HEAD_BYTES)
        val n = Os.pread(fd, buffer, 0, buffer.size, 0)
        buffer.copyOf(n.coerceAtLeast(0))
    } catch (e: ErrnoException) {
        ByteArray(0)
    }
}

/** Recognizes common audio container signatures from a file's first bytes. */
internal object ContainerSignature {

    fun detect(head: ByteArray, allowFrameSync: Boolean): String? {
        fun ascii(at: Int, s: String) =
            head.size >= at + s.length && s.indices.all { head[at + it] == s[it].code.toByte() }
        return when {
            ascii(0, "RIFF") && ascii(8, "WAVE") -> "wav"
            ascii(0, "#!AMR") -> "amr"
            ascii(0, "OggS") -> "ogg"
            ascii(0, "fLaC") -> "flac"
            ascii(0, "ID3") -> "mp3"
            ascii(4, "ftyp") -> "mp4" // M4A, 3GP, MP4
            ascii(0, "\u001aEß£") -> "webm"
            // MPEG audio / ADTS AAC frame sync. Raw PCM can start with these
            // bytes too, so only trust it when the caller didn't say "PCM".
            allowFrameSync && head.size >= 2 &&
                (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0 -> "mpeg"
            else -> null
        }
    }
}

/** Decodes a seekable recording with the platform's MediaExtractor and MediaCodec. */
internal class DecodedInput private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val extractor: MediaExtractor,
    private val codec: MediaCodec,
    private var sampleRate: Int,
    private var channels: Int,
    private val onClose: () -> Unit,
) : AudioInput {

    override val isLive = false

    private var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
    private var inputDone = false
    private var outputDone = false
    private val stopped = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val info = MediaCodec.BufferInfo()

    override fun read(): AudioFrame? {
        try {
            while (!outputDone) {
                if (stopped.get()) return null
                if (!inputDone) feedInput()
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index >= 0 -> {
                        val samples = if (info.size > 0) codec.getOutputBuffer(index)?.let(::toMono) else null
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (samples != null && samples.isNotEmpty()) return AudioFrame(samples, sampleRate)
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> updateFormat(codec.outputFormat)
                    else -> Unit // INFO_TRY_AGAIN_LATER: loop and feed more input
                }
            }
            return null
        } catch (e: IllegalStateException) {
            // Includes MediaCodec.CodecException.
            throw AudioInputException("Decoding failed", e)
        }
    }

    private fun feedInput() {
        val index = codec.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return
        val buffer = codec.getInputBuffer(index) ?: return
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private fun updateFormat(format: MediaFormat) {
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
    }

    private fun toMono(buffer: java.nio.ByteBuffer): FloatArray {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val ordered = buffer.slice().order(ByteOrder.nativeOrder())
        return if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val floats = ordered.asFloatBuffer()
            val frames = floats.remaining() / channels
            FloatArray(frames) { f ->
                var sum = 0f
                for (c in 0 until channels) sum += floats.get(f * channels + c)
                sum / channels
            }
        } else {
            val shorts = ordered.asShortBuffer()
            val frames = shorts.remaining() / channels
            FloatArray(frames) { f ->
                var sum = 0f
                for (c in 0 until channels) sum += shorts.get(f * channels + c) / 32768f
                sum / channels
            }
        }
    }

    override fun interrupt() {
        stopped.set(true)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            codec.stop()
        } catch (_: IllegalStateException) {
        }
        codec.release()
        extractor.release()
        try {
            descriptor.close()
        } catch (_: IOException) {
        }
        onClose()
    }

    companion object {
        private const val TIMEOUT_US = 10_000L

        /** Throws [AudioInputException] if there's no audio track Android can decode. */
        fun open(descriptor: ParcelFileDescriptor, onClose: () -> Unit): DecodedInput {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(descriptor.fileDescriptor)
                val track = (0 until extractor.trackCount).firstOrNull { i ->
                    extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: throw AudioInputException("No audio track")
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME)!!
                val codec = MediaCodec.createDecoderByType(mime)
                try {
                    codec.configure(format, null, null, 0)
                    codec.start()
                } catch (e: Exception) {
                    codec.release()
                    throw AudioInputException("No decoder for $mime", e)
                }
                return DecodedInput(
                    descriptor = descriptor,
                    extractor = extractor,
                    codec = codec,
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1),
                    onClose = onClose,
                )
            } catch (e: AudioInputException) {
                extractor.release()
                throw e
            } catch (e: IOException) {
                extractor.release()
                throw AudioInputException("Not a recording Android can read", e)
            } catch (e: IllegalArgumentException) {
                extractor.release()
                throw AudioInputException("Unsupported recording", e)
            }
        }
    }
}
