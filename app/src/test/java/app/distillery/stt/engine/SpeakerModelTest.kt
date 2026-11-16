// SPDX-License-Identifier: Apache-2.0
package app.distillery.stt.engine

import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Checks the rules for the speaker model download: only the two pyannote
 * community-1 files, only from Moonshine's own site over HTTPS and its
 * community-1 folder.
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
