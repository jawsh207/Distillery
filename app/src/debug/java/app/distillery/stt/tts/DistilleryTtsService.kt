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

    override fun onDestroy() {
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
        stopRequested = true
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopRequested = false
        if (!isEnglish(request.language)) {
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
            return
        }
        val voice = KokoroVoice.fromVoiceName(request.voiceName)?.takeIf { runtime.store.isInstalled(it) }
            ?: defaultVoice(Accent.fromCountry(request.country))
        if (voice == null) {
            callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET)
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

        var started = false
        try {
            for (sentence in sentences) {
                if (stopRequested) return
                val (samples, rate) = runtime.synth.synthesize(voice, sentence, speed)
                if (stopRequested) return
                if (!started) {
                    if (callback.start(rate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return
                    started = true
                }
                if (!deliver(samples, callback)) return
            }
            callback.done()
        } catch (e: VoiceNotInstalledException) {
            callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET)
        } catch (e: Throwable) {
            Log.e(TAG, "Synthesis failed", e)
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
        }
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
