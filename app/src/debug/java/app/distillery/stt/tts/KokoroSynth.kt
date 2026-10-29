// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

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
    private val guessers = HashMap<Accent, WordGuesser?>()
    private val unknownWords = LinkedHashSet<String>()
    private var tagger: PosTagger? = null

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

    /** Loads [voice] and everything pronouncing needs (lexicon, tagger), ahead of time. */
    fun warmUp(voice: KokoroVoice) = synchronized(lock) {
        unload?.cancel(false)
        try {
            ensureLoaded(voice)
            lexicons.getOrPut(voice.accent) { loadLexicon(voice.accent) }
            if (tagger == null) tagger = loadTagger()
        } finally {
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
            // Distillery always works out the phonemes itself: Moonshine's own
            // pronunciation data is never installed, only stand-ins (StandIns).
            val phonemes = phonemize(voice, text)
            if (phonemes.none { it.isLetter() }) return@synchronized FloatArray(0) to SAMPLE_RATE
            TtsLog.begin("Synthesizing ${text.length} characters")
            val started = SystemClock.elapsedRealtime()
            val options = listOf(TranscriberOption("speed", String.format(Locale.US, "%.2f", speed)))
            val result = tts.synthesizeFromPhonemes(phonemes, options)
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
        val posTagger = this.tagger ?: loadTagger().also { this.tagger = it }
        val phonemes = Pronouncer(lexicon, customPronunciations(), posTagger) { word ->
            unknownWords += word
            guess(accent, word)
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
            val suffix = if (british) "gb" else "us"
            val heteronyms = appContext.assets.open("tts/heteronyms-$suffix.tsv.gzip").use { Lexicon.loadHeteronyms(it) }
            val lexicon = appContext.assets.open("tts/lexicon-$suffix.tsv.gzip").use {
                Lexicon.load(it, british, heteronyms)
            }
            TtsLog.add("Lexicon: ${lexicon.size} words in ${SystemClock.elapsedRealtime() - started} ms")
            return lexicon
        } finally {
            TtsLog.end()
        }
    }

    /** The part-of-speech tagger, or null if it can't load (then heteronyms get their usual reading). */
    private fun loadTagger(): PosTagger? = try {
        val started = SystemClock.elapsedRealtime()
        appContext.assets.open("tts/pos-tagger.tsv.gzip").use { PosTagger.load(it) }
            .also { TtsLog.add("Part-of-speech tagger loaded in ${SystemClock.elapsedRealtime() - started} ms") }
    } catch (e: Exception) {
        TtsLog.error("Couldn't load the part-of-speech tagger", e)
        null
    }

    /** Distillery's own guess for a word the lexicon doesn't list. */
    private fun guess(accent: Accent, word: String): String {
        val guesser = guessers.getOrPut(accent) {
            val name = if (accent == Accent.BRITISH) "gb" else "us"
            try {
                val started = SystemClock.elapsedRealtime()
                appContext.assets.open("tts/guesser-$name.tsv.gzip").use { WordGuesser.load(it) }
                    .also { TtsLog.add("Word guesser loaded in ${SystemClock.elapsedRealtime() - started} ms") }
            } catch (e: Exception) {
                TtsLog.error("Couldn't load the word guesser", e)
                null
            }
        }
        return guesser?.guess(word).orEmpty()
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
        val replaced = StandIns.install(store.root, appContext.assets)
        if (replaced > 0) TtsLog.add("Put Distillery's stand-ins in place of Moonshine's pronunciation files ($replaced)")
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
        guessers.clear()
        lexicons.clear()
        tagger = null
    }

    companion object {
        private const val TAG = "DistilleryTts"
        /** Kokoro is freed after this long unused; reloading takes seconds, so not too soon. */
        const val IDLE_UNLOAD_MS = 5 * 60_000L
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
