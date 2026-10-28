// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * The system text-to-speech engine: apps reach it through
 * android.speech.tts.TextToSpeech once Distillery is chosen as the preferred
 * engine (or when they ask for it by package name).
 *
 * English only, Kokoro voices only, fully on the device. Text is spoken a
 * sentence at a time, so audio starts after the first sentence rather than the
 * whole text, and Stop takes effect between sentences.
 */
class DistilleryTtsService : TextToSpeechService() {

    private val runtime by lazy { TtsRuntime.get(this) }

    @Volatile private var stopRequested = false
    @Volatile private var currentAccent: Accent? = null

    override fun onCreate() {
        super.onCreate()
        TtsLog.add("Engine service started")
    }

    override fun onDestroy() {
        TtsLog.add("Engine service stopped")
        runtime.synth.unloadNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------- languages

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        if (!isEnglish(lang)) return TextToSpeech.LANG_NOT_SUPPORTED
        val installed = runtime.store.installed.value
        if (installed.isEmpty()) return TextToSpeech.LANG_MISSING_DATA
        val accent = Accent.fromCountry(country)
        return when {
            country.isNullOrEmpty() -> TextToSpeech.LANG_AVAILABLE
            accent != null && installed.any { it.accent == accent } -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            else -> TextToSpeech.LANG_AVAILABLE
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        if (result >= TextToSpeech.LANG_AVAILABLE) {
            currentAccent = Accent.fromCountry(country)?.takeIf { result == TextToSpeech.LANG_COUNTRY_AVAILABLE }
                ?: defaultVoice(null)?.accent
        }
        return result
    }

    override fun onGetLanguage(): Array<String> {
        val accent = currentAccent ?: defaultVoice(null)?.accent ?: Accent.AMERICAN
        return arrayOf("eng", accent.iso3Country, "")
    }

    // ---------------------------------------------------------------- voices

    override fun onGetVoices(): List<Voice> {
        val installed = runtime.store.installed.value
        return KokoroVoice.entries.map { voice ->
            val features = mutableSetOf<String>()
            if (voice !in installed) features += TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED
            Voice(
                voice.voiceName,
                voice.accent.locale,
                Voice.QUALITY_VERY_HIGH,
                Voice.LATENCY_NORMAL,
                false,
                features,
            )
        }
    }

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (KokoroVoice.fromVoiceName(voiceName) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int {
        val voice = KokoroVoice.fromVoiceName(voiceName) ?: return TextToSpeech.ERROR
        if (!runtime.store.isInstalled(voice)) return TextToSpeech.ERROR
        currentAccent = voice.accent
        return TextToSpeech.SUCCESS
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? {
        if (!isEnglish(lang)) return null
        return defaultVoice(Accent.fromCountry(country))?.voiceName
    }

    // -------------------------------------------------------------- speaking

    override fun onStop() {
        TtsLog.add("Stop requested")
        stopRequested = true
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopRequested = false
        TtsLog.add(
            "Request: ${request.charSequenceText?.length ?: 0} chars, language=${request.language}-${request.country}, " +
                "voice=${request.voiceName?.ifEmpty { null } ?: "(none)"}, rate=${request.speechRate}, caller uid ${request.callerUid}",
        )
        if (!isEnglish(request.language)) {
            TtsLog.add("Refused: not English")
            fail(callback, TextToSpeech.ERROR_INVALID_REQUEST, started = false)
            return
        }
        val voice = KokoroVoice.fromVoiceName(request.voiceName)?.takeIf { runtime.store.isInstalled(it) }
            ?: defaultVoice(Accent.fromCountry(request.country))
        if (voice == null) {
            TtsLog.add("Refused: no voice downloaded")
            fail(callback, TextToSpeech.ERROR_NOT_INSTALLED_YET, started = false)
            return
        }

        val text = request.charSequenceText?.toString().orEmpty()
        val speed = (request.speechRate / 100f).coerceIn(MIN_SPEED, MAX_SPEED)
        val sentences = KokoroSynth.sentences(text)
        if (sentences.isEmpty()) {
            callback.start(DEFAULT_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }

        TtsLog.add("Speaking with ${voice.id} at ${speed}x, ${sentences.size} sentence(s)")
        var started = false
        try {
            for ((index, sentence) in sentences.withIndex()) {
                if (stopRequested) return TtsLog.add("Stopped before sentence ${index + 1}")
                val (samples, rate) = runtime.synth.synthesize(voice, sentence, speed)
                if (stopRequested) return TtsLog.add("Stopped after sentence ${index + 1}")
                if (!started) {
                    val result = callback.start(rate, AudioFormat.ENCODING_PCM_16BIT, 1)
                    if (result != TextToSpeech.SUCCESS) return TtsLog.add("Playback refused to start ($result)")
                    started = true
                }
                if (!deliver(samples, callback)) return TtsLog.add("Playback stopped taking audio")
            }
            callback.done()
            TtsLog.add("Done")
        } catch (e: VoiceNotInstalledException) {
            TtsLog.add("Refused: ${e.message}")
            fail(callback, TextToSpeech.ERROR_NOT_INSTALLED_YET, started)
        } catch (e: Throwable) {
            Log.e(TAG, "Synthesis failed", e)
            fail(callback, TextToSpeech.ERROR_SYNTHESIS, started)
        }
    }

    /**
     * Reports a failure so the caller's onError actually fires. Android only
     * passes an error on to the app once synthesis has started, so start a
     * (silent) stream first if nothing was started yet.
     */
    private fun fail(callback: SynthesisCallback, code: Int, started: Boolean) {
        if (!started) callback.start(DEFAULT_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
        callback.error(code)
    }

    /** Writes float samples as 16-bit PCM in buffers the framework accepts. */
    private fun deliver(samples: FloatArray, callback: SynthesisCallback): Boolean {
        val maxBytes = callback.maxBufferSize.coerceAtLeast(2)
        val pcm = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            pcm[2 * i] = (v and 0xFF).toByte()
            pcm[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        var offset = 0
        while (offset < pcm.size) {
            if (stopRequested) return false
            val length = minOf(maxBytes - maxBytes % 2, pcm.size - offset)
            if (callback.audioAvailable(pcm, offset, length) != TextToSpeech.SUCCESS) return false
            offset += length
        }
        return true
    }

    /** The user's default voice if installed (and of [accent] when given), else any installed voice. */
    private fun defaultVoice(accent: Accent?): KokoroVoice? {
        val installed = runtime.store.installed.value
        val preferred = runtime.settings.defaultVoice.value
        return listOfNotNull(
            preferred.takeIf { it in installed && (accent == null || it.accent == accent) },
            installed.firstOrNull { accent != null && it.accent == accent },
            preferred.takeIf { it in installed },
            KokoroVoice.entries.firstOrNull { it in installed },
        ).firstOrNull()
    }

    private fun isEnglish(lang: String?): Boolean =
        lang != null && lang.lowercase(Locale.ROOT).let { it == "eng" || it == "en" }

    private companion object {
        const val TAG = "DistilleryTtsService"
        const val DEFAULT_SAMPLE_RATE = 24_000
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.5f
    }
}
