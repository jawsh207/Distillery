// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.engine

import ai.moonshine.voice.JNI

/** License a model's weights are published under. Only [MIT] may be used. */
enum class ModelLicense(val spdx: String, val isOpenSource: Boolean) {
    MIT("MIT", isOpenSource = true),

    /** Moonshine AI Community License: non-commercial, not OSI-approved. Never used. */
    MOONSHINE_COMMUNITY("LicenseRef-Moonshine-Community", isOpenSource = false),
}

/**
 * The speech models Distillery can use: Moonshine's English streaming models.
 *
 * Figures are from Moonshine's published model table (docs/models/available-models.md,
 * v0.1.5). WER is the Open ASR Leaderboard average for the floating-point model;
 * the quantized files Distillery downloads score slightly higher.
 *
 * License: Moonshine's LICENSE (v0.1.5) states that every streaming
 * speech-to-text model and every English model is MIT; only the legacy
 * non-streaming models for other languages are under the Community License.
 * All three entries here are English *and* streaming, so they're MIT on both
 * counts. [ModelLicensePolicy] enforces this at download and load time.
 */
enum class SttModel(
    /** Stable key used for preferences, directories and work names. */
    val id: String,
    /** Moonshine architecture constant passed to the native loader. */
    val arch: Int,
    /** The model's folder on Moonshine's CDN; download manifests must point here. */
    val cdnFolder: String,
    val displayName: String,
    val parameters: String,
    val werPercent: Double,
    val summary: String,
    val license: ModelLicense,
    val language: String,
    val isStreaming: Boolean,
) {
    TINY(
        id = "tiny-streaming-en",
        arch = JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING,
        cdnFolder = "tiny-streaming-en",
        displayName = "Tiny",
        parameters = "34 million",
        werPercent = 12.00,
        summary = "Fastest and smallest. Good for older phones.",
        license = ModelLicense.MIT,
        language = "en",
        isStreaming = true,
    ),
    SMALL(
        id = "small-streaming-en",
        arch = JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING,
        cdnFolder = "small-streaming-en",
        displayName = "Small",
        parameters = "123 million",
        werPercent = 7.84,
        summary = "Balanced speed and accuracy. Recommended.",
        license = ModelLicense.MIT,
        language = "en",
        isStreaming = true,
    ),
    MEDIUM(
        id = "medium-streaming-en",
        arch = JNI.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING,
        cdnFolder = "medium-streaming-en",
        displayName = "Medium",
        parameters = "245 million",
        werPercent = 6.65,
        summary = "Most accurate. Best on recent, fast phones.",
        license = ModelLicense.MIT,
        language = "en",
        isStreaming = true,
    );

    companion object {
        fun fromId(id: String?): SttModel? = entries.firstOrNull { it.id == id }

        /** Order used when the chosen model isn't installed and another one is. */
        val fallbackOrder = listOf(SMALL, TINY, MEDIUM)
    }
}
