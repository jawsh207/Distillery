// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Guesses how to say a word that isn't in the lexicon (mostly names), letter
 * by letter: an averaged perceptron that picks each letter's phonemes from the
 * letters around it and the phonemes it has just chosen.
 *
 * Trained by tools/train-word-guesser.py on misaki's lexicons (Apache-2.0), in
 * Kokoro's phoneme set. [features] must match `features()` in that script.
 */
class WordGuesser private constructor(
    private val labels: Array<String>,
    private val weights: Map<String, IntArray>, // feature → class index, weight pairs, flattened
) {

    /** Kokoro phonemes for [word], or "" if it has no letters to go on. */
    fun guess(word: String): String {
        val w = word.lowercase().filter { it in 'a'..'z' || it == '\'' }
        if (w.isEmpty()) return ""
        val scores = IntArray(labels.size)
        var p1 = PAD
        var p2 = PAD
        val out = StringBuilder()
        for (i in w.indices) {
            scores.fill(0)
            for (f in features(w, i, p1, p2)) {
                val ws = weights[f] ?: continue
                var k = 0
                while (k < ws.size) {
                    scores[ws[k]] += ws[k + 1]
                    k += 2
                }
            }
            var best = 0
            for (c in 1 until scores.size) if (scores[c] > scores[best]) best = c
            val label = labels[best]
            if (label != "_") out.append(label)
            p2 = p1
            p1 = label
        }
        return fixStress(out.toString())
    }

    companion object {
        private const val PAD = "#"
        private const val VOWELS = "AIOWYaeiouæɑɒɔəɛɜɪʊʌᵻᵊQ"

        fun features(word: String, i: Int, p1: String, p2: String): Array<String> {
            val w = PAD.repeat(4) + word + PAD.repeat(4)
            val c = i + 4
            fun l(o: Int) = w[c + o].toString()
            return arrayOf(
                "bias",
                "l0 " + l(0),
                "l-1 " + l(-1),
                "l-2 " + l(-2),
                "l-3 " + l(-3),
                "l+1 " + l(1),
                "l+2 " + l(2),
                "l+3 " + l(3),
                "l-1l0 " + l(-1) + l(0),
                "l0l+1 " + l(0) + l(1),
                "l-1l0l+1 " + l(-1) + l(0) + l(1),
                "l-2l-1l0 " + l(-2) + l(-1) + l(0),
                "l0l+1l+2 " + l(0) + l(1) + l(2),
                "l-2..l+2 " + w.substring(c - 2, c + 3),
                "l-3..l+1 " + w.substring(c - 3, c + 2),
                "l-1..l+3 " + w.substring(c - 1, c + 4),
                "p1 $p1",
                "p2p1 $p2 $p1",
                "p1l0 $p1 " + l(0),
                "p1l0l+1 $p1 " + l(0) + l(1),
            )
        }

        /** Exactly one primary stress, as tools/train-word-guesser.py `fix_stress`. */
        fun fixStress(ph: String): String {
            val first = ph.indexOf('ˈ')
            if (first >= 0) return ph.substring(0, first + 1) + ph.substring(first + 1).replace('ˈ', 'ˌ')
            if ('ˌ' in ph) return ph.replaceFirst('ˌ', 'ˈ')
            val v = ph.indexOfFirst { it in VOWELS }
            return if (v < 0) ph else ph.substring(0, v) + "ˈ" + ph.substring(v)
        }

        fun load(gzipped: InputStream): WordGuesser {
            var labels = emptyArray<String>()
            val weights = HashMap<String, IntArray>(400_000)
            GZIPInputStream(gzipped).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || line.startsWith("#")) continue
                    if (line.startsWith("C\t")) {
                        labels = line.substring(2).split('\t').toTypedArray()
                        continue
                    }
                    val parts = line.split('\t')
                    if (parts.size != 3 || parts[0] != "F") continue
                    val pairs = parts[2].split(',')
                    val arr = IntArray(pairs.size * 2)
                    pairs.forEachIndexed { k, p ->
                        arr[2 * k] = p.substringBefore(':').toInt()
                        arr[2 * k + 1] = p.substringAfter(':').toInt()
                    }
                    weights[parts[1]] = arr
                }
            }
            return WordGuesser(labels, weights)
        }
    }
}
