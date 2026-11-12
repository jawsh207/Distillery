// SPDX-License-Identifier: Apache-2.0
package app.distillery.stt.tts.read

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import app.distillery.stt.Notifications
import app.distillery.stt.media.MediaJobs
import app.distillery.stt.tts.KokoroVoice
import app.distillery.stt.tts.SpeechChunks
import app.distillery.stt.tts.TextNormalizer
import app.distillery.stt.tts.TtsRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Saves a text as speech in an audio file: M4A (AAC, made by Android's own
 * encoder, plays practically everywhere) or WAV (larger, plays on anything).
 * Runs as a [MediaJobs] job, so it carries on when the user switches apps.
 */
object AudioExport {

    private const val TAG = "DistilleryExport"
    private const val AAC_BITRATE = 64_000

    enum class Format(val label: String, val mime: String, val extension: String) {
        M4A("M4A", "audio/mp4", "m4a"),
        WAV("WAV", "audio/wav", "wav"),
    }

    sealed interface State {
        data object Idle : State
        data class Saving(val fraction: Float) : State
        data class Saved(val name: String) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun clear() {
        if (_state.value !is State.Saving) _state.value = State.Idle
    }

    /** Saves [sentences] to [target]. Returns false if another transcription or export is running. */
    fun start(
        context: Context,
        target: Uri,
        name: String,
        format: Format,
        sentences: List<String>,
        voice: KokoroVoice,
        speed: Float,
    ): Boolean {
        val app = context.applicationContext
        val screen = Intent(app, ReadAloudActivity::class.java)
        return MediaJobs.start(app, "Saving $name", screen) { report ->
            _state.value = State.Saving(0f)
            val temp = File(app.cacheDir, "export/speech.${format.extension}")
            try {
                temp.parentFile!!.mkdirs()
                temp.delete()
                withContext(Dispatchers.IO) {
                    val synth = TtsRuntime.get(app).synth
                    var out: AudioWriter? = null
                    try {
                        sentences.forEachIndexed { i, sentence ->
                            for (chunk in SpeechChunks.chunks(listOf(TextNormalizer.normalize(sentence)))) {
                                coroutineContext.ensureActive()
                                val (samples, rate) = runInterruptible { synth.synthesize(voice, chunk.text, speed) }
                                if (samples.isEmpty() || rate <= 0) continue
                                val w = out ?: (if (format == Format.WAV) WavWriter(temp, rate) else AacWriter(temp, rate)).also { out = it }
                                w.write(SpeechChunks.tidy(samples, rate, chunk.pauseMs))
                            }
                            val fraction = (i + 1f) / sentences.size
                            report(fraction)
                            _state.value = State.Saving(fraction)
                        }
                    } finally {
                        out?.close()
                    }
                    if (out == null) throw IllegalStateException("There was nothing to say")
                    app.contentResolver.openOutputStream(target, "wt")!!.use { to ->
                        temp.inputStream().use { it.copyTo(to, 1 shl 16) }
                    }
                }
                _state.value = State.Saved(name)
                if (!ReadAloudActivity.visible) {
                    Notifications.showWorkDone(app, "Audio saved", name, PendingIntent.getActivity(
                        app, 3, screen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ))
                }
            } catch (e: CancellationException) {
                runCatching { android.provider.DocumentsContract.deleteDocument(app.contentResolver, target) }
                _state.value = State.Idle
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Saving audio failed", e)
                runCatching { android.provider.DocumentsContract.deleteDocument(app.contentResolver, target) }
                _state.value = State.Failed(e.message ?: e::class.java.simpleName)
            } finally {
                temp.delete()
            }
        }
    }

    private interface AudioWriter : Closeable {
        fun write(samples: FloatArray)
    }

    /** 16-bit mono WAV; the sizes in the header are filled in on close. */
    private class WavWriter(file: File, private val rate: Int) : AudioWriter {
        private val out = RandomAccessFile(file, "rw")
        private var dataBytes = 0L

        init {
            out.write(ByteArray(44))
        }

        override fun write(samples: FloatArray) {
            val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) bytes.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            out.write(bytes.array())
            dataBytes += bytes.capacity()
        }

        override fun close() {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt((36 + dataBytes).toInt()); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(dataBytes.toInt())
            }
            out.seek(0)
            out.write(header.array())
            out.close()
        }
    }

    /** AAC-LC in an MP4 container (.m4a), with Android's encoder. */
    private class AacWriter(file: File, private val rate: Int) : AudioWriter {
        private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private var track = -1
        private var framesIn = 0L
        private val info = MediaCodec.BufferInfo()

        init {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        }

        override fun write(samples: FloatArray) {
            var offset = 0
            while (offset < samples.size) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!.order(ByteOrder.nativeOrder())
                    buffer.clear()
                    val n = minOf(buffer.remaining() / 2, samples.size - offset)
                    for (k in 0 until n) buffer.putShort((samples[offset + k].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                    codec.queueInputBuffer(index, 0, n * 2, framesIn * 1_000_000L / rate, 0)
                    framesIn += n
                    offset += n
                }
                drain(false)
            }
        }

        private fun drain(endOfStream: Boolean) {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && track >= 0) muxer.writeSampleData(track, buffer, info)
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    else -> if (!endOfStream) return
                }
            }
        }

        override fun close() {
            try {
                var index: Int
                do { index = codec.dequeueInputBuffer(10_000) } while (index < 0)
                codec.queueInputBuffer(index, 0, 0, framesIn * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(true)
            } finally {
                runCatching { codec.stop() }
                codec.release()
                runCatching { if (track >= 0) muxer.stop() }
                muxer.release()
            }
        }
    }
}
