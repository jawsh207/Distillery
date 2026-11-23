// SPDX-License-Identifier: Apache-2.0
package app.distillery.stt.engine

import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Checks the rules for the speaker model download: only the two pyannote
 * community-1 files, only from Moonshine's own site over HTTPS and its
 * community-1 folder, whether the manifest lists files by name or, as
 * Moonshine's library does, as objects with their own URLs.
 */
class SpeakerModelTest {

    private fun manifest(base: String, vararg files: String) =
        """{"groups":[{"base_url":"$base","files":[${files.joinToString(",") { "\"$it\"" }}]}]}"""

    @Test
    fun acceptsMoonshinesManifest() {
        SpeakerModel.verifyManifest(
            manifest("https://download.moonshine.ai/model/diarization-community1", "segmentation.ort", "embedding.ort"),
        )
    }

    /** A manifest in the shape Moonshine's library writes: each file an object. */
    private fun objectManifest(base: String, vararg files: Pair<String, String>) =
        """{"groups":[{"base_url":"$base","files":[""" +
            files.joinToString(",") { (name, url) ->
                """{"name":"$name","url":"$url","size":null,"checksum":"","checksum_type":""}"""
            } + "]}]}"

    @Test
    fun acceptsMoonshinesObjectManifest() {
        val base = "https://download.moonshine.ai/model/diarization-community1"
        SpeakerModel.verifyManifest(
            objectManifest(base, "segmentation.ort" to "$base/segmentation.ort", "embedding.ort" to "$base/embedding.ort"),
        )
    }

    @Test
    fun refusesAFileThatPointsElsewhere() {
        val base = "https://download.moonshine.ai/model/diarization-community1"
        assertThrows(ModelPolicyException::class.java) {
            SpeakerModel.verifyManifest(
                objectManifest(base, "segmentation.ort" to "$base/segmentation.ort", "embedding.ort" to "https://example.com/embedding.ort"),
            )
        }
    }

    @Test
    fun refusesOtherHostsFoldersAndFiles() {
        val files = arrayOf("segmentation.ort", "embedding.ort")
        listOf(
            manifest("http://download.moonshine.ai/model/diarization-community1", *files),
            manifest("https://example.com/model/diarization-community1", *files),
            manifest("https://download.moonshine.ai/model/other", *files),
            manifest("https://download.moonshine.ai/model/diarization-community1", "segmentation.ort"),
            manifest("https://download.moonshine.ai/model/diarization-community1", *files, "extra.ort"),
        ).forEach { json ->
            assertThrows(ModelPolicyException::class.java) { SpeakerModel.verifyManifest(json) }
        }
    }
}
