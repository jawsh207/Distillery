// SPDX-License-Identifier: Apache-2.0
package app.distillery.stt.transcribe

import app.distillery.stt.transcribe.FileTranscription.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks how a transcribed file is written out: paragraphs at long pauses and
 * at each change of speaker (labelled "Speaker 1", "Speaker 2"), optional
 * timestamps, and SRT subtitles with correct times, short cues that never
 * overlap, and the speaker named where it changes.
 */
class FileTranscriptionTest {

    @Test
    fun paragraphsAtLongPauses() {
        val text = FileTranscription.render(
            listOf(Segment(0f, 2f, "Hello there."), Segment(2.5f, 4f, "How are you?"), Segment(9f, 10f, "Later.")),
            timestamps = false,
        )
        assertEquals("Hello there. How are you?\n\nLater.", text)
    }

    @Test
    fun timestampsStartEachParagraph() {
        val text = FileTranscription.render(
            listOf(Segment(0f, 2f, "One."), Segment(75.4f, 77f, "Two.")),
            timestamps = true,
        )
        assertEquals("[0:00] One.\n\n[1:15] Two.", text)
    }

    @Test
    fun aNewSpeakerStartsALabelledParagraph() {
        val text = FileTranscription.render(
            listOf(
                Segment(0f, 1f, "Hi.", speaker = 0),
                Segment(1.2f, 2f, "Welcome.", speaker = 0),
                Segment(2.2f, 3f, "Thanks.", speaker = 1),
                Segment(3.2f, 4f, "Shall we?", speaker = 0),
            ),
            timestamps = false,
        )
        assertEquals("Speaker 1: Hi. Welcome.\n\nSpeaker 2: Thanks.\n\nSpeaker 1: Shall we?", text)
    }

    @Test
    fun srtTimes() {
        assertEquals("00:00:00,000", FileTranscription.srtTime(0f))
        assertEquals("00:01:15,250", FileTranscription.srtTime(75.25f))
        assertEquals("01:02:03,500", FileTranscription.srtTime(3723.5f))
    }

    @Test
    fun subtitlesAreNumberedTimedCues() {
        val srt = FileTranscription.srt(listOf(Segment(1f, 3f, "Hello there."), Segment(4f, 5.5f, "Bye.")))
        assertEquals(
            "1\n00:00:01,000 --> 00:00:03,000\nHello there.\n\n" +
                "2\n00:00:04,000 --> 00:00:05,500\nBye.\n\n",
            srt,
        )
    }

    @Test
    fun longPhrasesSplitIntoShortCuesThatDontOverlap() {
        val words = (1..40).joinToString(" ") { "word$it" }
        val srt = FileTranscription.srt(listOf(Segment(0f, 20f, words)))
        val cues = srt.trim().split("\n\n")
        assertTrue("expected several cues, got ${cues.size}", cues.size > 1)
        var lastEnd = 0f
        for (cue in cues) {
            val lines = cue.split('\n')
            val (start, end) = lines[1].split(" --> ").map(::seconds)
            assertTrue(start >= lastEnd - 0.001f && end > start)
            lastEnd = end
            val text = lines.drop(2)
            assertTrue("at most two lines: $text", text.size <= 2)
            assertTrue("lines of 42 or fewer: $text", text.all { it.length <= 42 })
        }
    }

    @Test
    fun subtitlesNameTheSpeakerWhereItChanges() {
        val srt = FileTranscription.srt(
            listOf(Segment(0f, 1f, "Hi.", 0), Segment(1f, 2f, "Again.", 0), Segment(2f, 3f, "Hello.", 1)),
        )
        assertTrue(srt.contains("Speaker 1: Hi."))
        assertTrue(srt.contains("\nAgain.\n"))
        assertTrue(srt.contains("Speaker 2: Hello."))
    }

    private fun seconds(srtTime: String): Float {
        val (h, m, rest) = srtTime.trim().split(':')
        val (s, ms) = rest.split(',')
        return h.toInt() * 3600 + m.toInt() * 60 + s.toInt() + ms.toInt() / 1000f
    }
}
