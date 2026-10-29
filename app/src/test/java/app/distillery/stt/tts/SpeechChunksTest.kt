// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechChunksTest {

    @Test fun longFirstSentenceStartsWithItsFirstClause() {
        val chunks = SpeechChunks.chunks(
            listOf(
                "When the storm finally passed over the harbor, the boats came back in one by one.",
                "Everyone cheered.",
            ),
        )
        assertEquals(
            listOf(
                SpeechChunk("When the storm finally passed over the harbor,", SpeechChunks.CLAUSE_PAUSE_MS),
                SpeechChunk("the boats came back in one by one.", SpeechChunks.SENTENCE_PAUSE_MS),
                SpeechChunk("Everyone cheered.", SpeechChunks.SENTENCE_PAUSE_MS),
            ),
            chunks,
        )
    }

    @Test fun shortSentencesStayWhole() {
        assertEquals(
            listOf(SpeechChunk("Hi, there.", SpeechChunks.SENTENCE_PAUSE_MS)),
            SpeechChunks.chunks(listOf("Hi, there.")),
        )
    }

    @Test fun silenceIsTrimmedToTheSetPause() {
        val rate = 1000
        val samples = FloatArray(1000).also { for (i in 400 until 500) it[i] = 0.5f }
        val tidy = SpeechChunks.tidy(samples, rate, pauseMs = 200)
        // 100 voiced samples, 25 ms margin each side, then 200 ms of pause.
        assertEquals(100 + 25 + 25 + 200, tidy.size)
        assertEquals(0.5f, tidy[25], 0f)
    }
}
