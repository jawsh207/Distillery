// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.service

import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent
import app.distillery.stt.engine.DistillerySettings
import java.util.Locale

/**
 * Audio supplied by the caller through [RecognizerIntent.EXTRA_AUDIO_SOURCE]:
 * an encoded recording or a raw PCM stream. See [RecordingInput].
 */
internal data class ExternalAudio(
    val descriptor: ParcelFileDescriptor,
    /** AudioFormat encoding; PCM_16BIT when the caller didn't say. */
    val encoding: Int,
    val sampleRate: Int,
    val channelCount: Int,
    /** The caller explicitly named a PCM encoding. */
    val explicitPcm: Boolean,
    /** The caller named a compressed encoding (MP3, AAC, Opus, ...). */
    val wantsDecoding: Boolean,
)

/** The options from a recognizer intent that Distillery acts on, with defaults filled in. */
internal data class RecognitionRequest(
    val language: String?,
    val partialResults: Boolean,
    /** Quiet after a phrase closes before stopping; null means don't stop on a pause. */
    val pauseToStopMs: Long?,
    val minimumLengthMs: Long,
    val noSpeechTimeoutMs: Long,
    val maxDurationMs: Long,
    val segmented: Boolean,
    val stopAtMinimumLength: Boolean,
    val externalAudio: ExternalAudio?,
) {
    val isEnglish: Boolean get() = isEnglishTag(language)

    companion object {
        /**
         * Moonshine's detector needs about this much quiet before it closes a
         * phrase, so it's subtracted from a caller's requested silence length.
         */
        const val VAD_CLOSE_LAG_MS = 500L

        const val MAX_DICTATION_MS = 5 * 60_000L
        const val MAX_SEGMENTED_MS = 60 * 60_000L
        const val MAX_RECORDING_MS = 3 * 60 * 60_000L

        private val PCM_ENCODINGS = setOf(
            AudioFormat.ENCODING_PCM_8BIT,
            AudioFormat.ENCODING_PCM_16BIT,
            AudioFormat.ENCODING_PCM_FLOAT,
            AudioFormat.ENCODING_DEFAULT,
        )

        fun from(intent: Intent, settings: DistillerySettings): RecognitionRequest {
            val extras = intent.extras ?: Bundle.EMPTY

            val callerSilence = extras.longOrNull(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS)
            val pause = callerSilence
                ?.let { (it - VAD_CLOSE_LAG_MS).coerceAtLeast(DistillerySettings.MIN_PAUSE_MS) }
                ?: settings.pauseToStopMs
            val minimumLength = extras.longOrNull(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS) ?: 0L

            val segmentedBy = extras.getString(RecognizerIntent.EXTRA_SEGMENTED_SESSION)
            val segmented = segmentedBy != null

            // A recording is transcribed to the end: pauses and
            // leading silence don't stop it unless the caller asks for that.
            val external = externalAudio(extras)
            val isRecording = external != null
            val recordingPause = callerSilence?.let { (it - VAD_CLOSE_LAG_MS).coerceAtLeast(DistillerySettings.MIN_PAUSE_MS) }

            return RecognitionRequest(
                language = extras.getString(RecognizerIntent.EXTRA_LANGUAGE),
                partialResults = extras.getBoolean(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false),
                pauseToStopMs = when (segmentedBy) {
                    null -> if (isRecording) recordingPause else pause
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS -> pause
                    // Ends when the audio ends, or at the minimum length.
                    else -> null
                },
                minimumLengthMs = minimumLength,
                noSpeechTimeoutMs = if (segmented || isRecording) Long.MAX_VALUE else settings.noSpeechTimeoutMs,
                maxDurationMs = when {
                    isRecording -> MAX_RECORDING_MS
                    segmented -> MAX_SEGMENTED_MS
                    else -> MAX_DICTATION_MS
                },
                segmented = segmented,
                stopAtMinimumLength = segmentedBy == RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                externalAudio = external,
            )
        }

        private fun externalAudio(extras: Bundle): ExternalAudio? {
            val descriptor = extras.getParcelable(
                RecognizerIntent.EXTRA_AUDIO_SOURCE,
                ParcelFileDescriptor::class.java,
            ) ?: return null
            val named = extras.containsKey(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING)
            val encoding = extras.getInt(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            return ExternalAudio(
                descriptor = descriptor,
                encoding = if (encoding == AudioFormat.ENCODING_DEFAULT) AudioFormat.ENCODING_PCM_16BIT else encoding,
                sampleRate = extras.getInt(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000),
                channelCount = extras.getInt(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1),
                explicitPcm = named && encoding in PCM_ENCODINGS,
                wantsDecoding = named && encoding !in PCM_ENCODINGS,
            )
        }

        fun isEnglishTag(tag: String?): Boolean {
            if (tag.isNullOrBlank()) return true
            return Locale.forLanguageTag(tag.trim().replace('_', '-')).language == "en"
        }

        /** Callers put these extras as Int or Long; accept either. */
        @Suppress("DEPRECATION")
        private fun Bundle.longOrNull(key: String): Long? = (get(key) as? Number)?.toLong()
    }
}
