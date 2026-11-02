// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.service

import org.junit.Assert.assertEquals
import org.junit.Test

class PunctuationTest {

    @Test fun trimsTheClosingMark() {
        assertEquals("Weather in Bangor", Punctuation.trimTrailing("Weather in Bangor."))
        assertEquals("Is it raining", Punctuation.trimTrailing("Is it raining? "))
        assertEquals("Call Mom", Punctuation.trimTrailing("Call Mom!…"))
        assertEquals("It's 3.5 miles", Punctuation.trimTrailing("It's 3.5 miles"))
    }

    @Test fun stripsPunctuationButKeepsWordsAndNumbers() {
        assertEquals(
            "Hello Sarah It's a well-known place about 3.5 miles or 1,000 feet at 10:30 OK",
            Punctuation.stripAll("Hello, Sarah! It's a “well-known” place — about 3.5 miles (or 1,000 feet) at 10:30. OK?"),
        )
        assertEquals("rock n roll", Punctuation.stripAll("rock 'n' roll"))
    }

    @Test fun stylesFollowTheRequestAndSetting() {
        val text = "Turn on the lights."
        val keep = Punctuation.Style(off = false, hidePartialTrailing = false, search = false)
        assertEquals(text, keep.partial(text))
        assertEquals(text, keep.final(text))

        val hide = keep.copy(hidePartialTrailing = true)
        assertEquals("Turn on the lights", hide.partial(text))
        assertEquals(text, hide.final(text))

        val search = keep.copy(search = true)
        assertEquals("Turn on the lights", search.partial(text))
        assertEquals("Turn on the lights", search.final(text))

        val off = keep.copy(off = true)
        assertEquals("Turn on the lights", off.final("Turn on, the lights."))
    }
}
