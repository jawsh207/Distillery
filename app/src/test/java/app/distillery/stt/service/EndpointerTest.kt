// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.service

import app.distillery.stt.service.Endpointer.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointerTest {

    private fun feed(e: Endpointer, ms: Long, stepMs: Long = 20): Decision {
        var left = ms
        var d = Decision.CONTINUE
        while (left > 0) {
            e.onAudio(minOf(stepMs, left))
            left -= stepMs
            d = e.decide()
            if (d != Decision.CONTINUE) return d
        }
        return d
    }

    @Test
    fun timesOutWhenNobodySpeaks() {
        val e = Endpointer(pauseToStopMs = 1000, noSpeechTimeoutMs = 6000)
        assertEquals(Decision.CONTINUE, feed(e, 5_980))
        assertEquals(Decision.NO_SPEECH_TIMEOUT, feed(e, 40))
        assertFalse(e.speechStarted)
    }

    @Test
    fun stopsAfterPauseFollowingCompletedPhrase() {
        val e = Endpointer(pauseToStopMs = 1000, noSpeechTimeoutMs = 6000)
        feed(e, 500)
        e.onLineStarted(1)
        assertEquals(Decision.CONTINUE, feed(e, 3_000))
        e.onLineCompleted(1)
        assertEquals(Decision.CONTINUE, feed(e, 980))
        assertEquals(Decision.END_OF_SPEECH, feed(e, 40))
    }

    @Test
    fun newPhraseResetsThePause() {
        val e = Endpointer(pauseToStopMs = 1000, noSpeechTimeoutMs = 6000)
        e.onLineStarted(1)
        feed(e, 1000)
        e.onLineCompleted(1)
        feed(e, 800)
        e.onLineStarted(2)
        assertEquals(Decision.CONTINUE, feed(e, 2_000))
        e.onLineCompleted(2)
        assertEquals(Decision.END_OF_SPEECH, feed(e, 1_100))
    }

    @Test
    fun neverStopsOnPauseWhileAPhraseIsOpen() {
        val e = Endpointer(pauseToStopMs = 200, noSpeechTimeoutMs = 6000)
        e.onLineStarted(1)
        e.onLineStarted(2)
        e.onLineCompleted(1)
        assertEquals(Decision.CONTINUE, feed(e, 10_000))
    }

    @Test
    fun noAutoStopWhenPauseIsNull() {
        val e = Endpointer(pauseToStopMs = null, noSpeechTimeoutMs = Long.MAX_VALUE)
        e.onLineStarted(1)
        e.onLineCompleted(1)
        assertEquals(Decision.CONTINUE, feed(e, 60_000))
    }

    @Test
    fun respectsMinimumLength() {
        val e = Endpointer(pauseToStopMs = 200, noSpeechTimeoutMs = 6000, minimumLengthMs = 5_000)
        e.onLineStarted(1)
        feed(e, 1000)
        e.onLineCompleted(1)
        assertEquals(Decision.CONTINUE, feed(e, 3_900))
        assertEquals(Decision.END_OF_SPEECH, feed(e, 200))
    }

    @Test
    fun segmentedSessionEndsAtMinimumLength() {
        val e = Endpointer(
            pauseToStopMs = null,
            noSpeechTimeoutMs = Long.MAX_VALUE,
            minimumLengthMs = 2_000,
            stopAtMinimumLength = true,
        )
        assertEquals(Decision.CONTINUE, feed(e, 1_980))
        assertEquals(Decision.END_OF_SPEECH, feed(e, 20))
    }

    @Test
    fun hardCapEndsSession() {
        val e = Endpointer(pauseToStopMs = null, noSpeechTimeoutMs = Long.MAX_VALUE, maxDurationMs = 1_000)
        e.onLineStarted(1)
        assertEquals(Decision.END_OF_SPEECH, feed(e, 1_000))
    }

    @Test
    fun phraseThatStartsAndCompletesTogetherCountsAsSpeech() {
        val e = Endpointer(pauseToStopMs = 500, noSpeechTimeoutMs = 1_000)
        feed(e, 400)
        e.onLineCompleted(7)
        assertTrue(e.speechStarted)
        assertEquals(Decision.END_OF_SPEECH, feed(e, 520))
    }
}
