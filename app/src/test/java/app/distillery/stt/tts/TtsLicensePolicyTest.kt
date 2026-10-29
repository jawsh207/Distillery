// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TtsLicensePolicyTest {

    @get:Rule val tmp = TemporaryFolder()

    private val base = "https://download.moonshine.ai/tts"

    private fun manifest(vararg names: String, baseUrl: String = base) =
        """{"groups":[{"base_url":"$baseUrl","files":[${names.joinToString(",") { "{\"name\":\"$it\"}" }}]}]}"""

    private val good = arrayOf(
        "kokoro/config.json",
        "kokoro/prosody.model.ort",
        "kokoro/decoder.weights.ort",
        "kokoro/voices/af_heart.kokorovoice",
        "en_us/dict_filtered_heteronyms.tsv",
        "en_us/g2p-config.json",
        "en_us/oov/model.ort",
        "en_us/oov/onnx-config.json",
    )

    @Test fun acceptsEnglishKokoroManifest() {
        val files = TtsLicensePolicy.verifyManifest(KokoroVoice.AF_HEART, manifest(*good))
        assertEquals(good.toList(), files)
    }

    @Test fun acceptsStringEntriesAndExplicitUrls() {
        val json = """{"groups":[{"base_url":"$base","files":["kokoro/config.json",
            {"name":"kokoro/voices/bm_george.kokorovoice","url":"$base/kokoro/voices/bm_george.kokorovoice"}]}]}"""
        assertEquals(2, TtsLicensePolicy.verifyManifest(KokoroVoice.BM_GEORGE, json).size)
    }

    @Test fun refusesZipVoiceAndPiper() {
        listOf("zipvoice/model.ort", "kokoro/zipvoice.json", "piper/en_US-lessac.onnx").forEach { name ->
            assertThrows(TtsPolicyException::class.java) {
                TtsLicensePolicy.verifyManifest(KokoroVoice.AF_HEART, manifest(*good, name))
            }
        }
    }

    @Test fun refusesOtherLanguagesAndOtherVoices() {
        listOf("de/dict.tsv", "zh_hans/dict.tsv", "kokoro/voices/ff_siwis.kokorovoice", "kokoro/voices/af_bella.kokorovoice")
            .forEach { name ->
                assertThrows(TtsPolicyException::class.java) {
                    TtsLicensePolicy.verifyManifest(KokoroVoice.AF_HEART, manifest(*good, name))
                }
            }
    }

    @Test fun refusesOtherHostsAndPaths() {
        listOf("http://download.moonshine.ai/tts", "https://example.com/tts", "https://download.moonshine.ai/model")
            .forEach { url ->
                assertThrows(TtsPolicyException::class.java) {
                    TtsLicensePolicy.verifyManifest(KokoroVoice.AF_HEART, manifest(*good, baseUrl = url))
                }
            }
        assertThrows(TtsPolicyException::class.java) {
            TtsLicensePolicy.verifyManifest(KokoroVoice.AF_HEART, manifest("kokoro/../../etc", *good))
        }
    }

    @Test fun requiresTheVoiceFile() {
        assertThrows(TtsPolicyException::class.java) {
            TtsLicensePolicy.verifyManifest(KokoroVoice.AF_HEART, manifest("kokoro/config.json"))
        }
    }

    @Test fun onlyEnglishVoicesAreListed() {
        assertTrue(KokoroVoice.entries.all { it.id.take(3) in setOf("af_", "am_", "bf_", "bm_") })
        KokoroVoice.entries.forEach { TtsLicensePolicy.requireAllowed(it) }
    }

    @Test fun deletingAVoiceKeepsSharedFiles() {
        val root = tmp.newFolder("tts")
        val store = VoiceStore(root)
        fun write(name: String) = java.io.File(root, name).apply { parentFile!!.mkdirs(); writeText("x") }
        val shared = listOf("kokoro/config.json", "en_us/g2p-config.json")
        (shared + "kokoro/voices/af_heart.kokorovoice" + "kokoro/voices/bm_george.kokorovoice").forEach { write(it) }
        store.markComplete(KokoroVoice.AF_HEART, shared + "kokoro/voices/af_heart.kokorovoice")
        store.markComplete(KokoroVoice.BM_GEORGE, shared + "kokoro/voices/bm_george.kokorovoice")

        store.delete(KokoroVoice.AF_HEART)
        assertEquals(setOf(KokoroVoice.BM_GEORGE), store.installed.value)
        assertTrue(shared.all { java.io.File(root, it).isFile })
        assertTrue(!java.io.File(root, "kokoro/voices/af_heart.kokorovoice").exists())

        store.delete(KokoroVoice.BM_GEORGE)
        assertTrue(store.installed.value.isEmpty())
        assertTrue(!root.exists())
    }
}
