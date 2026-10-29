// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

import java.io.InputStream

/**
 * English part-of-speech tagger: an averaged perceptron giving Penn Treebank
 * tags (NN, VB, VBD, JJ, …), used to pick between a heteronym's readings
 * ("I read" now vs. "I read" yesterday, "a record" vs. "to record").
 *
 * The model (assets/tts/pos-tagger.bin.gzip) is trained by
 * tools/train-pos-tagger.py on the Universal Dependencies English Web Treebank
 * and is CC BY-SA 4.0. [features] must match `features()` in that script
 * exactly, or the weights mean nothing.
 */
class PosTagger private constructor(private val model: PackedPerceptron) {

    private val classes = model.classes

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
            val tag = model.tagdict[word]?.let { classes[it] } ?: run {
                scores.fill(0)
                for (f in features(i, word, context, prev, prev2)) model.add(f, scores)
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

        /** Reads the packed model (see tools/perceptron_pack.py). */
        fun load(gzipped: InputStream): PosTagger = PosTagger(PackedPerceptron.load(gzipped))

        private val clitic = Regex("""(?i)^(.+?)(n't|'s|'re|'ll|'ve|'d|'m)$""")

        /** Splits clitics off the way the treebank does: "don't" → "do", "n't"; "Maine's" → "Maine", "'s". */
        fun split(word: String): List<String> {
            val m = clitic.find(word) ?: return listOf(word)
            return listOf(m.groupValues[1], m.groupValues[2])
        }
    }
}
