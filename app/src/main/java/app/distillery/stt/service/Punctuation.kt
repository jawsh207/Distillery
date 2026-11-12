// SPDX-License-Identifier: Apache-2.0
package app.distillery.stt.service

/**
 * Adjusts the punctuation in recognized text.
 *
 * Moonshine punctuates and capitalizes what it hears, and that's kept by
 * default. It's taken out where it gets in the way:
 *  - [trimTrailing]: the closing mark of partial results, when the app asks
 *    (RecognizerIntent.EXTRA_HIDE_PARTIAL_TRAILING_PUNCTUATION), so live text
 *    doesn't flash a period that goes away as the speaker carries on; and the
 *    end of search-style results (LANGUAGE_MODEL_WEB_SEARCH), where "Weather in
 *    Bangor." would break a search or a command;
 *  - [stripAll]: everything, when the user turns punctuation off.
 *
 * Marks that are part of a word or number stay: "don't", "well-known",
 * "3.5", "1,000". Capitals stay too.
 */
internal object Punctuation {

    private val trailing = Regex("""[\s.,!?;:…]+$""")

    /** Characters that are always punctuation here. */
    private const val MARKS = ".,!?;:…\"“”„«»()[]{}—–"

    fun trimTrailing(text: String): String = text.replace(trailing, "")

    fun stripAll(text: String): String {
        val out = StringBuilder(text.length)
        for (i in text.indices) {
            val c = text[i]
            val prev = text.getOrNull(i - 1)
            val next = text.getOrNull(i + 1)
            val keep = when {
                // Inside a number: 3.5, 1,000, 10:30
                (c == '.' || c == ',' || c == ':') && prev?.isDigit() == true && next?.isDigit() == true -> true
                // Inside a word: don't, well-known
                (c == '\'' || c == '’' || c == '-') && prev?.isLetterOrDigit() == true && next?.isLetterOrDigit() == true -> true
                c == '\'' || c == '’' || c == '-' || c == '‘' -> false
                c in MARKS -> false
                else -> true
            }
            out.append(if (keep) c else ' ')
        }
        return out.toString().replace(Regex("""\s+"""), " ").trim()
    }

    /** What a caller's request and the user's setting mean for one result. */
    data class Style(
        /** The user turned punctuation off: remove it everywhere. */
        val off: Boolean,
        /** Remove the closing mark of partial results. */
        val hidePartialTrailing: Boolean,
        /** Search or command: remove the closing mark of every result. */
        val search: Boolean,
    ) {
        fun partial(text: String): String = when {
            off -> stripAll(text)
            hidePartialTrailing || search -> trimTrailing(text)
            else -> text
        }

        fun final(text: String): String = when {
            off -> stripAll(text)
            search -> trimTrailing(text)
            else -> text
        }
    }
}
