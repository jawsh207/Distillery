// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Uses the real models from the dev build's assets. */
class WordGuesserTest {

    private val us by lazy { File("src/debug/assets/tts/guesser-us.bin.gzip").inputStream().use { WordGuesser.load(it) } }

    @Test fun oneMainStress() {
        assertEquals("bˈæŋɡˌɔɹ", WordGuesser.fixStress("bˈæŋɡˈɔɹ"))
        assertEquals("kˈæt", WordGuesser.fixStress("kæt"))
        assertEquals("ˈɑbˌi", WordGuesser.fixStress("ˌɑbˌi"))
    }

    @Test fun guessesPlausibly() {
        for (word in listOf("Skowhegan", "Kennebunkport", "Distillery", "blorptastic", "Madawaska")) {
            val ph = us.guess(word)
            assertTrue("$word → $ph", ph.isNotEmpty() && ph.count { it == 'ˈ' } == 1)
        }
        assertEquals("", us.guess("123"))
    }
}
