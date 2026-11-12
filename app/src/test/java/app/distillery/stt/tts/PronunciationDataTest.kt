// SPDX-License-Identifier: Apache-2.0
package app.distillery.stt.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class PronunciationDataTest {

    private val manifest = File("src/main/assets/tts/data.properties").inputStream().use {
        PronunciationData.Manifest.parse(it)
    }

    @Test
    fun manifestMatchesTheRepository() {
        val files = File("../tts-data").listFiles()!!.filter { it.isFile && !it.name.startsWith(".") }
        assertEquals(files.map { it.name }.toSet(), manifest.entries.keys)
        for (file in files) {
            val entry = manifest.entries.getValue(file.name)
            assertEquals(file.name, file.length(), entry.size)
            val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(file.name, sha, entry.sha256)
        }
        assertTrue(manifest.tag.startsWith("tts-data-"))
    }

    @Test
    fun everyAccentsFilesAreListed() {
        val data = PronunciationData(File("build/tmp/pronunciation-test"), manifest)
        for (accent in Accent.entries) {
            for (name in data.filesFor(accent)) assertTrue(name, name in manifest.entries)
        }
    }

    @Test
    fun onlyCheckedFilesCount() {
        val root = File("build/tmp/pronunciation-test-${System.nanoTime()}")
        val data = PronunciationData(root, manifest)
        assertFalse(data.isPresent(Accent.AMERICAN))
        data.dir.mkdirs()
        for (name in data.filesFor(Accent.AMERICAN)) {
            File("../tts-data", name).copyTo(File(data.dir, name))
        }
        // Copied but not recorded as checked: still missing.
        assertFalse(data.isPresent(Accent.AMERICAN))
        for (name in data.filesFor(Accent.AMERICAN)) {
            File(data.dir, "$name.sha256").writeText(manifest.entries.getValue(name).sha256)
        }
        assertTrue(data.isPresent(Accent.AMERICAN))
        assertFalse(data.isPresent(Accent.BRITISH))

        // The last American voice going leaves the shared tagger for British voices.
        data.deleteFor(Accent.AMERICAN, last = false)
        assertFalse(File(data.dir, "lexicon-us.tsv.gzip").exists())
        assertTrue(File(data.dir, PronunciationData.TAGGER).exists())
        root.deleteRecursively()
    }
}
