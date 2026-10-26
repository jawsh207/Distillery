// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.service

/**
 * Collects Moonshine's phrases ("lines") into the text a caller should see.
 *
 * Moonshine keeps revising the open phrase while someone speaks and freezes it
 * when the phrase completes. The session text is all phrases, in the order they
 * started, joined with single spaces. Not thread-safe; engine thread only.
 */
internal class TranscriptBuffer {

    private val order = ArrayList<Long>()
    private val text = HashMap<Long, String>()
    private val completed = HashSet<Long>()

    /** Records the latest text for a line. Returns true if the session text changed. */
    fun update(lineId: Long, lineText: String?, isComplete: Boolean): Boolean {
        val clean = normalize(lineText)
        if (lineId !in text) order += lineId
        val previous = text.put(lineId, clean)
        if (isComplete) completed += lineId
        return previous != clean
    }

    fun isCompleted(lineId: Long): Boolean = lineId in completed

    fun textOf(lineId: Long): String = text[lineId].orEmpty()

    /** Everything heard so far, including the phrase still in progress. */
    fun fullText(): String = order.mapNotNull { text[it]?.takeIf(String::isNotEmpty) }.joinToString(" ")

    private fun normalize(s: String?): String = s.orEmpty().trim().replace(WHITESPACE, " ")

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
