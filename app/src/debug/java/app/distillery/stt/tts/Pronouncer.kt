// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

/**
 * Turns one sentence of (already normalized) English into Kokoro phonemes.
 *
 * For each word, in order:
 *  1. a custom pronunciation (Distillery's built-in fixes, then the user's),
 *     written as a "sounds like" respelling such as "BANG gore";
 *  2. the misaki [Lexicon], including regular endings ("Maine's");
 *  3. Moonshine's own pronunciation model, via [fallback].
 *
 * Moonshine's English G2P works word by word too, so doing it here loses no
 * context, and it lets punctuation through to Kokoro as pause cues (Moonshine
 * drops it).
 */
class Pronouncer(
    private val lexicon: Lexicon,
    private val custom: Map<String, String>,
    private val fallback: (String) -> String,
) {

    private data class Piece(val text: String, val isWord: Boolean, var phonemes: String = "")

    fun phonemize(text: String): String {
        val pieces = token.findAll(text.replace('’', '\'').replace('‘', '\'')).map { m ->
            val v = m.value
            Piece(v, isWord = v.first().isLetterOrDigit())
        }.toList()

        for ((i, p) in pieces.withIndex()) {
            if (!p.isWord) {
                p.phonemes = punctuation[p.text] ?: ""
                continue
            }
            p.phonemes = when {
                p.text == "a" -> "ə"
                p.text == "A" && !isLetterSequence(pieces, i) -> "ə"
                p.text.equals("the", ignoreCase = true) -> "the" // decided below, once the next word is known
                else -> word(p.text)
            }
        }
        // "the" is "ði" before a vowel sound, "ðə" otherwise.
        for ((i, p) in pieces.withIndex()) {
            if (p.phonemes != "the") continue
            val next = pieces.drop(i + 1).firstOrNull { it.isWord }?.phonemes.orEmpty()
            p.phonemes = if (next.trimStart('ˈ', 'ˌ').firstOrNull()?.let { it in VOWELS } == true) "ði" else "ðə"
        }

        val out = StringBuilder()
        for (p in pieces) {
            if (p.phonemes.isEmpty()) continue
            if (p.isWord && out.isNotEmpty()) out.append(' ')
            out.append(p.phonemes)
        }
        return out.toString().trim()
    }

    /** One word, through custom entries, the lexicon, hyphen parts and finally Moonshine. */
    fun word(w: String): String {
        custom[w.lowercase()]?.let { return respell(it) }
        if (w.endsWith("'s") || w.endsWith("s'")) {
            custom[w.dropLast(2).lowercase()]?.let { return possessive(respell(it)) }
        }
        lexicon.phonemes(w)?.let { return it }
        if ('-' in w) return w.split('-').filter { it.isNotEmpty() }.joinToString(" ") { word(it) }
        return fallback(w)
    }

    private fun possessive(stem: String): String {
        val last = stem.lastOrNull() ?: return stem
        return when (last) {
            in "ptkfθ" -> stem + "s"
            in "szʃʒʧʤ" -> stem + "ᵻz"
            else -> stem + "z"
        }
    }

    /**
     * A "sounds like" respelling → phonemes: each part is looked up, then the
     * parts are joined into one word with a single main stress, on the part
     * written in capitals (or the first part if none is).
     */
    fun respell(soundsLike: String): String {
        val parts = soundsLike.trim().split(Regex("""[\s-]+""")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        val stressed = parts.indexOfFirst { it.length > 1 && it.all { c -> !c.isLetter() || c.isUpperCase() } }
            .takeIf { it >= 0 } ?: 0
        return parts.mapIndexed { i, part ->
            val ph = (lexicon.phonemes(part) ?: fallback(part)).replace("ˈ", "ˌ")
            if (i == stressed) {
                if ('ˌ' in ph) ph.replaceFirst("ˌ", "ˈ") else addStress(ph)
            } else {
                ph.replace("ˌ", "")
            }
        }.joinToString("")
    }

    private fun addStress(ph: String): String {
        val at = ph.indexOfFirst { it in VOWELS }
        return if (at < 0) ph else ph.substring(0, at) + "ˈ" + ph.substring(at)
    }

    /** "A" in "F B A" or "A B C" is the letter, not the article. */
    private fun isLetterSequence(pieces: List<Piece>, i: Int): Boolean {
        fun single(p: Piece?) = p != null && p.isWord && p.text.length == 1 && p.text[0].isUpperCase()
        return single(pieces.getOrNull(i - 1)) || single(pieces.getOrNull(i + 1))
    }

    companion object {
        private const val VOWELS = "AIOWYaeiouæɑɒɔəɛɜɪʊʌᵻᵊ"

        private val token = Regex("""[A-Za-z0-9]+(?:['-][A-Za-z0-9]+)*'?|[,.!?;:—…]""")

        /** Punctuation Kokoro knows; it shapes pauses and intonation. */
        private val punctuation = mapOf(
            "," to ",", "." to ".", "!" to "!", "?" to "?", ";" to ";", ":" to ":", "—" to "—", "…" to "…",
        )
    }
}
