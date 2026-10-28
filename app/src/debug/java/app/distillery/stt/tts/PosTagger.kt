// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * English part-of-speech tagger: an averaged perceptron giving Penn Treebank
 * tags (NN, VB, VBD, JJ, …), used to pick between a heteronym's readings
 * ("I read" now vs. "I read" yesterday, "a record" vs. "to record").
 *
 * The model (assets/tts/pos-tagger.tsv.gzip) is trained by
 * tools/train-pos-tagger.py on the Universal Dependencies English Web Treebank
 * and is CC BY-SA 4.0. [features] must match `features()` in that script
 * exactly, or the weights mean nothing.
 */
class PosTagger private constructor(
    private val classes: Array<String>,
    private val tagdict: Map<String, String>,
    private val weights: Map<String, IntArray>, // feature → class index, weight pairs, flattened
) {

    /** One tag per token. Tokens are words and punctuation, clitics split off ("do", "n't"). */
    fun tag(tokens: List<String>): List<String> {
        val context = ArrayList<String>(tokens.size + 4)
        context += START
        tokens.mapTo(context) { normalize(it) }
        context += END
        var prev = START[0]
        var prev2 = START[1]
        val scores = IntArray(classes.size)
        return tokens.mapIndexed { i, word ->
            val tag = tagdict[word] ?: run {
                scores.fill(0)
                for (f in features(i, word, context, prev, prev2)) {
                    val w = weights[f] ?: continue
                    var k = 0
                    while (k < w.size) {
                        scores[w[k]] += w[k + 1]
                        k += 2
                    }
                }
                // Highest score; ties go to the alphabetically last tag, as in Python's max.
                var best = 0
                for (c in 1 until classes.size) {
                    if (scores[c] > scores[best] || (scores[c] == scores[best] && classes[c] > classes[best])) best = c
                }
                classes[best]
            }
            prev2 = prev
            prev = tag
            tag
        }
    }

    companion object {
        private val START = listOf("-START-", "-START2-")
        private val END = listOf("-END-", "-END2-")

        fun normalize(word: String): String = when {
            '-' in word && !word.startsWith("-") -> "!HYPHEN"
            word.length == 4 && word.all { it.isDigit() } -> "!YEAR"
            word.isNotEmpty() && word[0].isDigit() -> "!DIGITS"
            else -> word.lowercase()
        }

        /** [i] indexes the tokens; [context] has the two START markers in front. */
        fun features(i: Int, word: String, context: List<String>, prev: String, prev2: String): Set<String> {
            val j = i + START.size
            return linkedSetOf(
                "bias",
                "i suffix ${word.takeLast(3)}",
                "i pref1 ${word.take(1)}",
                "i-1 tag $prev",
                "i-2 tag $prev2",
                "i tag+i-2 tag $prev $prev2",
                "i word ${context[j]}",
                "i-1 tag+i word $prev ${context[j]}",
                "i-1 word ${context[j - 1]}",
                "i-1 suffix ${context[j - 1].takeLast(3)}",
                "i-2 word ${context[j - 2]}",
                "i+1 word ${context[j + 1]}",
                "i+1 suffix ${context[j + 1].takeLast(3)}",
                "i+2 word ${context[j + 2]}",
                "i cap ${if (word.firstOrNull()?.isUpperCase() == true) "1" else "0"}",
            )
        }

        fun load(gzipped: InputStream): PosTagger {
            var classes = emptyArray<String>()
            val tagdict = HashMap<String, String>()
            val weights = HashMap<String, IntArray>(150_000)
            GZIPInputStream(gzipped).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val parts = line.split('\t')
                    when (parts[0]) {
                        "C" -> classes = parts[1].split(' ').toTypedArray()
                        "T" -> tagdict[parts[1]] = parts[2]
                        "F" -> {
                            val pairs = parts[2].split(',')
                            val arr = IntArray(pairs.size * 2)
                            pairs.forEachIndexed { k, p ->
                                arr[2 * k] = p.substringBefore(':').toInt()
                                arr[2 * k + 1] = p.substringAfter(':').toInt()
                            }
                            weights[parts[1]] = arr
                        }
                    }
                }
            }
            return PosTagger(classes, tagdict, weights)
        }

        private val clitic = Regex("""(?i)^(.+?)(n't|'s|'re|'ll|'ve|'d|'m)$""")

        /** Splits clitics off the way the treebank does: "don't" → "do", "n't"; "Maine's" → "Maine", "'s". */
        fun split(word: String): List<String> {
            val m = clitic.find(word) ?: return listOf(word)
            return listOf(m.groupValues[1], m.groupValues[2])
        }
    }
}
