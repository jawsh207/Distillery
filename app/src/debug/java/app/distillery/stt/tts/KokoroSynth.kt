// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

import ai.moonshine.voice.GraphemeToPhonemizer
import ai.moonshine.voice.TextToSpeech
import ai.moonshine.voice.TranscriberOption
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Thrown when a voice is asked for that hasn't been downloaded. */
class VoiceNotInstalledException(voice: KokoroVoice) : IllegalStateException("${voice.id} isn't downloaded")

/**
 * Holds one loaded Kokoro synthesizer and turns text into audio with it.
 *
 * Kokoro needs a few hundred MB of memory once loaded, so the synthesizer is
 * freed after [IDLE_UNLOAD_MS] without use. Switching voices reloads it.
 */
class KokoroSynth(
    context: Context,
    private val store: VoiceStore,
    /** Custom pronunciations, word (lower case) → "sounds like" respelling. */
    private val customPronunciations: () -> Map<String, String> = { emptyMap() },
) {

    private val appContext = context.applicationContext
    private val lock = Any()
    private val timer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "distillery-tts-idle").apply { isDaemon = true }
    }

    private val lexicons = HashMap<Accent, Lexicon>()
    private val g2ps = HashMap<Accent, GraphemeToPhonemizer>()
    private val unknownWords = LinkedHashSet<String>()

    private var engine: TextToSpeech? = null
    private var engineVoice: KokoroVoice? = null
    private var unload: ScheduledFuture<*>? = null

    /** Milliseconds the last load took, for the test screen. */
    @Volatile var lastLoadMs: Long? = null
        private set

    /** Loads [voice] ahead of time so the first sentence starts sooner. */
    fun preload(voice: KokoroVoice) {
        synchronized(lock) {
            ensureLoaded(voice)
            scheduleUnload()
        }
    }

    /**
     * Synthesizes [text] as one utterance.
     *
     * @param speed speaking-rate multiplier; 1.0 is Kokoro's normal pace.
     * @return mono float samples and their sample rate.
     */
    fun synthesize(voice: KokoroVoice, text: String, speed: Float): Pair<FloatArray, Int> = synchronized(lock) {
        unload?.cancel(false)
        try {
            val tts = ensureLoaded(voice)
            val phonemes = try {
                phonemize(voice, text)
            } catch (e: Throwable) {
                TtsLog.error("Pronouncing failed; letting Moonshine pronounce it", e)
                null
            }
            if (phonemes != null && phonemes.none { it.isLetter() }) return@synchronized FloatArray(0) to SAMPLE_RATE
            TtsLog.begin("Synthesizing ${text.length} characters")
            val started = SystemClock.elapsedRealtime()
            val options = listOf(TranscriberOption("speed", String.format(Locale.US, "%.2f", speed)))
            val result = if (phonemes != null) tts.synthesizeFromPhonemes(phonemes, options) else tts.synthesize(text, options)
            val samples = result.samples ?: FloatArray(0)
            val audioMs = if (result.sampleRateHz > 0) samples.size * 1000L / result.sampleRateHz else 0L
            TtsLog.add(
                "Made $audioMs ms of audio (${samples.size} samples at ${result.sampleRateHz} Hz) " +
                    "in ${SystemClock.elapsedRealtime() - started} ms",
            )
            samples to result.sampleRateHz
        } catch (e: Throwable) {
            TtsLog.error("Synthesis failed", e)
            throw e
        } finally {
            TtsLog.end()
            scheduleUnload()
        }
    }

    /** Kokoro phonemes for one sentence, logging words the lexicon didn't know. */
    fun phonemize(voice: KokoroVoice, text: String): String {
        val accent = voice.accent
        val lexicon = lexicons.getOrPut(accent) { loadLexicon(accent) }
        unknownWords.clear()
        val phonemes = Pronouncer(lexicon, customPronunciations()) { word ->
            unknownWords += word
            fallbackIpa(accent, word)
        }.phonemize(text)
        if (unknownWords.isNotEmpty()) TtsLog.add("Not in the lexicon (guessed): ${unknownWords.joinToString()}")
        TtsLog.add("Phonemes: ${phonemes.take(300)}")
        return phonemes
    }

    private fun loadLexicon(accent: Accent): Lexicon {
        val british = accent == Accent.BRITISH
        TtsLog.begin("Loading the ${accent.label} lexicon")
        val started = SystemClock.elapsedRealtime()
        try {
            val lexicon = appContext.assets.open("tts/lexicon-${if (british) "gb" else "us"}.tsv.gz").use {
                Lexicon.load(it, british)
            }
            TtsLog.add("Lexicon: ${lexicon.size} words in ${SystemClock.elapsedRealtime() - started} ms")
            return lexicon
        } finally {
            TtsLog.end()
        }
    }

    /** Moonshine's pronunciation (CMUdict, then its word model) for a word misaki doesn't list. */
    private fun fallbackIpa(accent: Accent, word: String): String {
        val g2p = g2ps.getOrPut(accent) {
            GraphemeToPhonemizer(if (accent == Accent.BRITISH) "en_gb" else "en_us", store.root.absolutePath, null)
        }
        return g2p.toIpa(word).trim()
    }

    fun unloadNow() = synchronized(lock) {
        unload?.cancel(false)
        release()
    }

    private fun ensureLoaded(voice: KokoroVoice): TextToSpeech {
        TtsLicensePolicy.requireAllowed(voice)
        if (!store.isInstalled(voice)) throw VoiceNotInstalledException(voice)
        engine?.let { if (engineVoice == voice) return it }
        release()
        val missing = store.missingFiles(voice)
        TtsLog.add(
            "Voice ${voice.id}: ${store.filesFor(voice).size} files in ${store.root}" +
                (if (missing.isEmpty()) "" else "; MISSING: ${missing.joinToString()}") +
                store.filesFor(voice).joinToString("") { name ->
                    "\n  $name ${java.io.File(store.root, name).length() / 1024} KB"
                },
        )
        TtsLog.begin("Loading Kokoro with ${voice.id}")
        val started = SystemClock.elapsedRealtime()
        val tts = TextToSpeech(appContext)
            .language(TtsLicensePolicy.LANGUAGE)
            .voice(voice.moonshineId)
            .modelsFrom(store.root)
            // Dev builds: have the native library log its calls, so a failure
            // shows up with its reason in the engine log below.
            .options(listOf(TranscriberOption("log_api_calls", "true")))
        try {
            tts.load()
        } catch (e: Throwable) {
            TtsLog.error("Loading ${voice.id} failed", e)
            TtsLog.add("Native log:\n" + TtsLog.nativeLog())
            TtsLog.end()
            runCatching { tts.close() }
            throw e
        }
        lastLoadMs = SystemClock.elapsedRealtime() - started
        TtsLog.add("Loaded ${voice.id} in $lastLoadMs ms")
        TtsLog.end()
        engine = tts
        engineVoice = voice
        return tts
    }

    private fun scheduleUnload() {
        unload?.cancel(false)
        unload = timer.schedule(Runnable { synchronized(lock) { release() } }, IDLE_UNLOAD_MS, TimeUnit.MILLISECONDS)
    }

    private fun release() {
        engine?.let {
            TtsLog.add("Unloading ${engineVoice?.id}")
            runCatching { it.close() }
        }
        engine = null
        engineVoice = null
        g2ps.values.forEach { runCatching { it.close() } }
        g2ps.clear()
        lexicons.clear()
    }

    companion object {
        private const val TAG = "DistilleryTts"
        const val IDLE_UNLOAD_MS = 90_000L
        const val SAMPLE_RATE = 24_000

        /** Splits text into sentences with Moonshine's splitter; falls back to the whole text. */
        fun sentences(text: String): List<String> = try {
            TextToSpeech.splitUtterances(TtsLicensePolicy.LANGUAGE, text).filter { it.isNotBlank() }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Sentence split failed; speaking the text as one piece", e)
            listOf(text)
        }.ifEmpty { if (text.isBlank()) emptyList() else listOf(text) }
    }
}
