// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class PronouncerTest {

    private val lexicon = Lexicon(
        mapOf(
            "Maine" to "mˈAn", "dog" to "dˈɔɡ", "walk" to "wˈɔk", "rain" to "ɹˈAn", "apple" to "ˈæpᵊl",
            "bang" to "bˈæŋ", "gore" to "ɡˈɔɹ", "is" to "ɪz", "in" to "ɪn", "Bangor" to "bˈæŋɡəɹ",
            "box" to "bˈɑks", "city" to "sˈɪɾi", "F" to "ˈɛf", "B" to "bˈi", "I" to "ˈI",
        ),
        british = false,
    )
    private val pronouncer = Pronouncer(lexicon, mapOf("bangor" to "BANG gore")) { "<$it>" }

    @Test fun regularEndings() {
        assertEquals("mˈAnz", lexicon.phonemes("Maine's"))
        assertEquals("dˈɔɡz", lexicon.phonemes("dogs"))
        assertEquals("dˈɔɡz", lexicon.phonemes("dogs'"))
        assertEquals("bˈɑksᵻz", lexicon.phonemes("boxes"))
        assertEquals("sˈɪɾiz", lexicon.phonemes("cities"))
        assertEquals("wˈɔkt", lexicon.phonemes("walked"))
        assertEquals("ɹˈAnɪŋ", lexicon.phonemes("raining"))
    }

    @Test fun customPronunciationsWinAndKeepOneStress() {
        assertEquals("bˈæŋɡɔɹ", pronouncer.word("Bangor"))
        assertEquals("bˈæŋɡɔɹz", pronouncer.word("Bangor's"))
    }

    @Test fun sentencesKeepPunctuationAndPickTheAndA() {
        assertEquals("mˈAnz ɪz ɪn bˈæŋɡɔɹ, mˈAn.", pronouncer.phonemize("Maine's is in Bangor, Maine."))
        assertEquals("ði ˈæpᵊl ðə dˈɔɡ", pronouncer.phonemize("the apple the dog"))
        assertEquals("ə dˈɔɡ, ˈɛf bˈi ˈI", pronouncer.phonemize("a dog, F B I"))
        assertEquals("<Skowhegan>", pronouncer.phonemize("Skowhegan"))
    }
}
