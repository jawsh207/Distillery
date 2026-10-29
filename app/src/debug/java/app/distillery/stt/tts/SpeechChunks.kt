// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

import kotlin.math.abs

/** One piece of text to synthesize, and the pause that should follow it. */
data class SpeechChunk(val text: String, val pauseMs: Int)

/**
 * How text is cut up for speaking, and how Kokoro's audio is tidied.
 *
 * Kokoro makes a whole piece before any of it can play, so the first piece is
 * kept short (the first clause of a long first sentence) to start sooner;
 * later pieces are made while earlier ones play. Kokoro pads each piece with
 * silence of its own, which stacked up into long gaps, so that's trimmed and
 * replaced by a pause of a set length.
 */
object SpeechChunks {

    const val SENTENCE_PAUSE_MS = 500
    const val CLAUSE_PAUSE_MS = 250

    private const val FIRST_CLAUSE_MIN = 18
    private const val LONG_FIRST_SENTENCE = 70
    private const val LONG_SENTENCE = 260

    private val clauseBreak = Regex("""(?<=[,;:—])\s+""")

    fun chunks(sentences: List<String>): List<SpeechChunk> {
        val out = mutableListOf<SpeechChunk>()
        sentences.forEachIndexed { i, sentence ->
            val s = sentence.trim()
            if (s.isEmpty()) return@forEachIndexed
            val split = (i == 0 && s.length > LONG_FIRST_SENTENCE) || s.length > LONG_SENTENCE
            val parts = if (split) splitClauses(s, firstOnly = s.length <= LONG_SENTENCE) else listOf(s)
            parts.forEachIndexed { k, part ->
                out += SpeechChunk(part, if (k == parts.lastIndex) SENTENCE_PAUSE_MS else CLAUSE_PAUSE_MS)
            }
        }
        return out
    }

    /** Splits at clause punctuation; with [firstOnly], only the first clause is split off. */
    private fun splitClauses(s: String, firstOnly: Boolean): List<String> {
        val pieces = s.split(clauseBreak).filter { it.isNotBlank() }
        if (pieces.size < 2) return listOf(s)
        // Join tiny pieces onto the next, so nothing is a word or two on its own.
        val merged = mutableListOf<String>()
        var pending = ""
        for (p in pieces) {
            pending = if (pending.isEmpty()) p else "$pending $p"
            if (pending.length >= FIRST_CLAUSE_MIN) {
                merged += pending
                pending = ""
            }
        }
        if (pending.isNotEmpty()) {
            if (merged.isEmpty()) merged += pending else merged[merged.lastIndex] = merged.last() + " " + pending
        }
        if (merged.size < 2 || !firstOnly) return merged
        return listOf(merged.first(), merged.drop(1).joinToString(" "))
    }

    /**
     * Trims leading and trailing silence, then adds [pauseMs] of silence at the
     * end, so every gap is the length it should be.
     */
    fun tidy(samples: FloatArray, rate: Int, pauseMs: Int, threshold: Float = 0.006f): FloatArray {
        if (samples.isEmpty() || rate <= 0) return samples
        var first = samples.indexOfFirst { abs(it) > threshold }
        if (first < 0) return FloatArray(rate * pauseMs / 1000)
        var last = samples.indexOfLast { abs(it) > threshold }
        val margin = rate * 25 / 1000 // keep the soft edges of the first and last sounds
        first = (first - margin).coerceAtLeast(0)
        last = (last + margin).coerceAtMost(samples.lastIndex)
        val voiced = last - first + 1
        val out = FloatArray(voiced + rate * pauseMs / 1000)
        System.arraycopy(samples, first, out, 0, voiced)
        return out
    }
}
