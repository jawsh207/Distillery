// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * English word → Kokoro phonemes, from misaki's gold and silver dictionaries
 * (github.com/hexgrad/misaki, Apache-2.0). misaki is the G2P Kokoro was trained
 * with, so these are written in Kokoro's own phoneme set and cover far more
 * names and places than CMUdict.
 *
 * Words that aren't listed are tried as a listed word plus a regular ending
 * ("Maine's", "dogs", "walked", "walking"), following misaki's own rules; only
 * after that does Distillery fall back to Moonshine's pronunciation model.
 */
class Lexicon(
    private val entries: Map<String, String>,
    private val british: Boolean,
    /** Words with several readings: word → (Penn tag, or VERB/NOUN/ADJ/ADV/DEFAULT) → phonemes. */
    private val heteronyms: Map<String, Map<String, String>> = emptyMap(),
) {

    val size: Int get() = entries.size

    /** The word as listed, or in lower case, or capitalized. */
    fun lookup(word: String): String? {
        entries[word]?.let { return it }
        val lower = word.lowercase()
        if (lower != word) entries[lower]?.let { return it }
        val capitalized = lower.replaceFirstChar { it.uppercaseChar() }
        if (capitalized != word) entries[capitalized]?.let { return it }
        return null
    }

    /**
     * Listed, or a listed word with a regular ending. [tag] is the word's
     * part of speech (Penn Treebank), when known, to pick a heteronym's reading.
     */
    fun phonemes(word: String, tag: String? = null): String? {
        if (word.endsWith("'") && word.length > 2) return phonemes(word.dropLast(1), tag) // "dogs'"
        if (tag != null) reading(word, tag)?.let { return it }
        return lookup(word) ?: stemS(word) ?: stemEd(word) ?: stemIng(word)
    }

    /** misaki's rule: the exact tag if listed, else its family (VBD → VERB), else the default. */
    private fun reading(word: String, tag: String): String? {
        val readings = heteronyms[word] ?: heteronyms[word.lowercase()] ?: return null
        return readings[tag] ?: readings[parentTag(tag)] ?: readings["DEFAULT"]
    }

    private fun parentTag(tag: String): String = when {
        tag.startsWith("VB") -> "VERB"
        tag.startsWith("NN") -> "NOUN"
        tag.startsWith("RB") -> "ADV"
        tag.startsWith("JJ") -> "ADJ"
        else -> tag
    }

    private fun known(word: String) = lookup(word) != null

    // https://en.wiktionary.org/wiki/-s
    private fun withS(stem: String): String {
        val last = stem.lastOrNull() ?: return stem
        return when (last) {
            in "ptkfθ" -> stem + "s"
            in "szʃʒʧʤ" -> stem + (if (british) "ɪ" else "ᵻ") + "z"
            else -> stem + "z"
        }
    }

    private fun stemS(word: String): String? {
        if (word.length < 3 || !word.endsWith("s")) return null
        val stem = when {
            !word.endsWith("ss") && known(word.dropLast(1)) -> word.dropLast(1)
            (word.endsWith("'s") || (word.length > 4 && word.endsWith("es") && !word.endsWith("ies"))) &&
                known(word.dropLast(2)) -> word.dropLast(2)
            word.length > 4 && word.endsWith("ies") && known(word.dropLast(3) + "y") -> word.dropLast(3) + "y"
            else -> return null
        }
        return lookup(stem)?.let(::withS)
    }

    // https://en.wiktionary.org/wiki/-ed
    private fun withEd(stem: String): String = when {
        stem.lastOrNull()?.let { it in "pkfθʃsʧ" } == true -> stem + "t"
        stem.lastOrNull() == 'd' -> stem + (if (british) "ɪ" else "ᵻ") + "d"
        stem.lastOrNull() != 't' -> stem + "d"
        british || stem.length < 2 -> stem + "ɪd"
        stem[stem.length - 2] in US_TAUS -> stem.dropLast(1) + "ɾᵻd"
        else -> stem + "ᵻd"
    }

    private fun stemEd(word: String): String? {
        if (word.length < 4 || !word.endsWith("d")) return null
        val stem = when {
            !word.endsWith("dd") && known(word.dropLast(1)) -> word.dropLast(1)
            word.length > 4 && word.endsWith("ed") && !word.endsWith("eed") && known(word.dropLast(2)) -> word.dropLast(2)
            else -> return null
        }
        return lookup(stem)?.let(::withEd)
    }

    // https://en.wiktionary.org/wiki/-ing
    private fun withIng(stem: String): String? = when {
        british && stem.lastOrNull()?.let { it in "əː" } == true -> null
        !british && stem.length > 1 && stem.last() == 't' && stem[stem.length - 2] in US_TAUS -> stem.dropLast(1) + "ɾɪŋ"
        else -> stem + "ɪŋ"
    }

    private val doubled = Regex("""([bcdgklmnprstvxz])\1ing$|cking$""")

    private fun stemIng(word: String): String? {
        if (word.length < 5 || !word.endsWith("ing")) return null
        val stem = when {
            word.length > 5 && known(word.dropLast(3)) -> word.dropLast(3)
            known(word.dropLast(3) + "e") -> word.dropLast(3) + "e"
            word.length > 5 && doubled.containsMatchIn(word) && known(word.dropLast(4)) -> word.dropLast(4)
            else -> return null
        }
        return lookup(stem)?.let(::withIng)
    }

    companion object {
        private const val US_TAUS = "AIOWYiuæɑəɛɪɹʊʌ"

        /** Reads a gzipped word<TAB>TAG=phonemes;TAG=phonemes file (see tools/build-tts-lexicon.py). */
        fun loadHeteronyms(gzipped: InputStream): Map<String, Map<String, String>> {
            val map = HashMap<String, Map<String, String>>(2_000)
            GZIPInputStream(gzipped).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    map[line.substring(0, tab)] = line.substring(tab + 1).split(';')
                        .filter { '=' in it }
                        .associate { it.substringBefore('=') to it.substringAfter('=') }
                }
            }
            return map
        }

        /** Reads a gzipped word<TAB>phonemes file (see tools/build-tts-lexicon.py). */
        fun load(gzipped: InputStream, british: Boolean, heteronyms: Map<String, Map<String, String>> = emptyMap()): Lexicon {
            val map = HashMap<String, String>(400_000)
            GZIPInputStream(gzipped).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val tab = line.indexOf('\t')
                    if (tab > 0) map[line.substring(0, tab)] = line.substring(tab + 1)
                }
            }
            return Lexicon(map, british, heteronyms)
        }
    }
}
