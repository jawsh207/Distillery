// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Uses the real model and lexicon from the dev build's assets. */
class PosTaggerTest {

    private val assets = File("src/debug/assets/tts")
    private val tagger by lazy { File(assets, "pos-tagger.bin.gzip").inputStream().use { PosTagger.load(it) } }
    private val pronouncer by lazy {
        val heteronyms = File(assets, "heteronyms-us.tsv.gzip").inputStream().use { Lexicon.loadHeteronyms(it) }
        val lexicon = File(assets, "lexicon-us.tsv.gzip").inputStream().use { Lexicon.load(it, false, heteronyms) }
        Pronouncer(lexicon, emptyMap(), tagger) { "<$it>" }
    }

    @Test fun tagsASentence() {
        assertEquals(
            listOf("PRP", "VBP", "VBN", "PRP", "RB", "."),
            tagger.tag(listOf("I", "have", "read", "it", "twice", ".")),
        )
    }

    @Test fun splitsClitics() {
        assertEquals(listOf("do", "n't"), PosTagger.split("don't"))
        assertEquals(listOf("Maine", "'s"), PosTagger.split("Maine's"))
        assertEquals(listOf("read"), PosTagger.split("read"))
    }

    @Test fun picksHeteronymReadings() {
        val p = pronouncer.phonemize("Please record the show and play the record.")
        assertTrue(p, p.contains("ɹəkˈɔɹd") && p.contains("ɹˈɛkəɹd"))
        assertTrue(pronouncer.phonemize("I have read it twice.").contains("ɹˈɛd"))
        assertTrue(pronouncer.phonemize("I will present the present.").let { it.contains("pɹizˈɛnt") && it.contains("pɹˈɛzᵊnt") })
    }
}
