// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.service

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class OffensiveWordsTest {

    private val words = OffensiveWords(listOf("shit", "ass", "fuck", "two girls one cup"))

    @Test fun masksWholeWordsLikeAndroid() {
        assertEquals("Oh s***, that's it.", words.mask("Oh shit, that's it."))
        assertEquals("F****** hell", words.mask("FUCKING hell"))
        assertEquals("f*** off and f******", words.mask("fuck off and fuckers"))
        assertEquals("t** g**** o** c**", words.mask("two girls one cup"))
    }

    @Test fun leavesWordsThatOnlyContainOne() {
        assertEquals("Assess the class in Scunthorpe", words.mask("Assess the class in Scunthorpe"))
    }

    @Test fun theShippedListLoads() {
        val shipped = File("src/main/assets/${OffensiveWords.ASSET}").inputStream().use { OffensiveWords.load(it) }
        assertEquals("You b****.", shipped.mask("You bitch."))
        assertEquals("A classic passage.", shipped.mask("A classic passage."))
    }
}
