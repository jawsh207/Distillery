// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.engine

import org.json.JSONObject
import java.net.URI

/** Thrown when a model, or a file list for one, isn't allowed by the policy. */
class ModelPolicyException(message: String) : SecurityException(message)

/**
 * Keeps Distillery to open-source models only.
 *
 * Distillery is GPL-3.0 and must not ship, download or load anything under the
 * Moonshine AI Community License. Moonshine's LICENSE (v0.1.5) says the only
 * non-MIT speech models are the legacy *non-streaming* models for languages
 * other than English. So a model is allowed only when it is all of:
 *
 *  - in [SttModel] (an explicit allowlist, not "whatever Moonshine offers"),
 *  - English,
 *  - a streaming architecture,
 *  - recorded as MIT.
 *
 * Downloads are checked a second time against the manifest Moonshine's native
 * catalog returns: every file must come over HTTPS from download.moonshine.ai
 * and live in that model's own folder, so a catalog change can't quietly swap in
 * a different model.
 */
object ModelLicensePolicy {

    const val CDN_HOST = "download.moonshine.ai"

    /** Checks the catalog entry itself. Called before every download and load. */
    fun requireAllowed(model: SttModel) {
        if (model.license != ModelLicense.MIT || !model.license.isOpenSource) {
            throw ModelPolicyException("${model.id} is not MIT-licensed")
        }
        if (model.language != "en") {
            throw ModelPolicyException("${model.id} is not an English model")
        }
        if (!model.isStreaming) {
            throw ModelPolicyException("${model.id} is not a streaming model")
        }
    }

    /**
     * Checks the download manifest for [model], as returned by
     * `Transcriber.getSttDependencies`. Shape:
     * `{"groups":[{"base_url":"https://...","files":["a","b"]}]}`.
     *
     * @return the number of files the manifest lists.
     */
    fun verifyManifest(model: SttModel, manifestJson: String): Int {
        requireAllowed(model)
        val groups = JSONObject(manifestJson).optJSONArray("groups")
            ?: throw ModelPolicyException("Download manifest has no file groups")
        if (groups.length() == 0) throw ModelPolicyException("Download manifest is empty")

        var fileCount = 0
        for (g in 0 until groups.length()) {
            val group = groups.getJSONObject(g)
            val baseUrl = group.optString("base_url")
            verifyBaseUrl(model, baseUrl)
            val files = group.optJSONArray("files")
                ?: throw ModelPolicyException("Manifest group has no files")
            for (f in 0 until files.length()) {
                val name = files.getString(f)
                if (name.isBlank() || name.startsWith("/") || name.split('/').any { it == ".." }) {
                    throw ModelPolicyException("Unexpected file name in manifest: $name")
                }
                fileCount++
            }
        }
        return fileCount
    }

    internal fun verifyBaseUrl(model: SttModel, baseUrl: String) {
        val uri = try {
            URI(baseUrl)
        } catch (e: Exception) {
            throw ModelPolicyException("Bad model URL: $baseUrl")
        }
        if (uri.scheme != "https" || uri.host != CDN_HOST) {
            throw ModelPolicyException("Model must come from https://$CDN_HOST, not $baseUrl")
        }
        // e.g. /model/small-streaming-en/quantized_26_08_21
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        if (segments.size < 2 || segments[0] != "model" || segments[1] != model.cdnFolder) {
            throw ModelPolicyException(
                "Manifest for ${model.id} points at ${uri.path}, not /model/${model.cdnFolder}/",
            )
        }
    }
}
