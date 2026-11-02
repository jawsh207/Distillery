// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.service

import java.io.InputStream

/**
 * Masks offensive words in recognized text the way Android's recognizers do:
 * the first letter stays and the rest become asterisks ("f***").
 *
 * The words are the English list from "List of Dirty, Naughty, Obscene, and
 * Otherwise Bad Words" (github.com/LDNOOBW, CC BY 4.0), in assets/stt. Whole
 * words and phrases only, ignoring case, with common endings ("-s", "-ed",
 * "-ing", "-er"), so "assess" or "Scunthorpe" are left alone.
 */
internal class OffensiveWords(words: Collection<String>) {

    private val pattern: Regex? = words
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .sortedByDescending { it.length }
        .takeIf { it.isNotEmpty() }
        ?.joinToString("|") { word -> word.split(' ').joinToString("""\s+""") { Regex.escape(it) } }
        ?.let { Regex("""(?<![\p{L}\p{N}])(?:$it)(?:s|es|ed|ing|in'|er|ers)?(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE) }

    fun mask(text: String): String = pattern?.replace(text) { m -> maskWords(m.value) } ?: text

    private fun maskWords(phrase: String): String {
        val out = StringBuilder(phrase.length)
        var first = true
        for (c in phrase) {
            when {
                c.isWhitespace() -> {
                    first = true
                    out.append(c)
                }
                first && c.isLetterOrDigit() -> {
                    first = false
                    out.append(c)
                }
                c.isLetterOrDigit() -> out.append('*')
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    companion object {
        const val ASSET = "stt/offensive-words-en.txt"

        fun load(input: InputStream): OffensiveWords = OffensiveWords(input.bufferedReader().readLines())
    }
}
