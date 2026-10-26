// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.engine

import app.distillery.stt.service.RecognitionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLicensePolicyTest {

    @Test
    fun everyCatalogModelIsEnglishStreamingMit() {
        for (model in SttModel.entries) {
            ModelLicensePolicy.requireAllowed(model)
            assertEquals(ModelLicense.MIT, model.license)
            assertEquals("en", model.language)
            assertTrue(model.isStreaming)
            assertTrue(model.cdnFolder.endsWith("-streaming-en"))
        }
    }

    @Test
    fun acceptsManifestFromTheModelsOwnFolder() {
        val json = """
            {"groups":[{"base_url":"https://download.moonshine.ai/model/small-streaming-en/quantized_26_08_21",
              "files":["frontend.ort","encoder.ort","adapter.ort","cross_kv.ort","decoder_kv.ort",
                       "streaming_config.json","tokenizer.bin"]}]}
        """.trimIndent()
        assertEquals(7, ModelLicensePolicy.verifyManifest(SttModel.SMALL, json))
    }

    @Test(expected = ModelPolicyException::class)
    fun rejectsCommunityLicensedNonEnglishModel() {
        val json = """{"groups":[{"base_url":"https://download.moonshine.ai/model/base-ar/quantized/base-ar","files":["a.ort"]}]}"""
        ModelLicensePolicy.verifyManifest(SttModel.SMALL, json)
    }

    @Test(expected = ModelPolicyException::class)
    fun rejectsADifferentEnglishModelThanRequested() {
        val json = """{"groups":[{"base_url":"https://download.moonshine.ai/model/medium-streaming-en/q","files":["a.ort"]}]}"""
        ModelLicensePolicy.verifyManifest(SttModel.TINY, json)
    }

    @Test(expected = ModelPolicyException::class)
    fun rejectsOtherHosts() {
        val json = """{"groups":[{"base_url":"https://example.com/model/tiny-streaming-en/q","files":["a.ort"]}]}"""
        ModelLicensePolicy.verifyManifest(SttModel.TINY, json)
    }

    @Test(expected = ModelPolicyException::class)
    fun rejectsPlainHttp() {
        val json = """{"groups":[{"base_url":"http://download.moonshine.ai/model/tiny-streaming-en/q","files":["a.ort"]}]}"""
        ModelLicensePolicy.verifyManifest(SttModel.TINY, json)
    }

    @Test(expected = ModelPolicyException::class)
    fun rejectsPathTraversal() {
        val json = """{"groups":[{"base_url":"https://download.moonshine.ai/model/tiny-streaming-en/q","files":["../x.ort"]}]}"""
        ModelLicensePolicy.verifyManifest(SttModel.TINY, json)
    }

    @Test
    fun englishTags() {
        assertTrue(RecognitionRequest.isEnglishTag(null))
        assertTrue(RecognitionRequest.isEnglishTag("en"))
        assertTrue(RecognitionRequest.isEnglishTag("en-US"))
        assertTrue(RecognitionRequest.isEnglishTag("en_GB"))
        assertFalse(RecognitionRequest.isEnglishTag("de-DE"))
        assertFalse(RecognitionRequest.isEnglishTag("ar"))
    }
}
