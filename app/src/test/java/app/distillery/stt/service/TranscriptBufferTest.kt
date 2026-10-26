// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptBufferTest {

    @Test
    fun revisesOpenPhraseAndJoinsInOrder() {
        val b = TranscriptBuffer()
        assertTrue(b.update(10, "Hello", false))
        assertTrue(b.update(10, " Hello world. ", true))
        assertTrue(b.update(11, "How", false))
        assertTrue(b.update(11, "How are you?", false))
        assertEquals("Hello world. How are you?", b.fullText())
    }

    @Test
    fun reportsNoChangeForSameText() {
        val b = TranscriptBuffer()
        b.update(1, "same", false)
        assertFalse(b.update(1, "  same ", false))
    }

    @Test
    fun skipsEmptyLines() {
        val b = TranscriptBuffer()
        b.update(1, "", false)
        b.update(2, null, true)
        b.update(3, "words", true)
        assertEquals("words", b.fullText())
        assertTrue(b.isCompleted(2))
    }

    @Test
    fun collapsesWhitespace() {
        val b = TranscriptBuffer()
        b.update(1, "a\n\tb   c", true)
        assertEquals("a b c", b.textOf(1))
    }
}
