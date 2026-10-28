// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Thrown when a voice download would fetch something the policy doesn't allow. */
class TtsPolicyException(message: String) : SecurityException(message)

/**
 * Keeps text to speech to the parts whose licenses are known to be open source,
 * or are still being confirmed, and never anything ruled out.
 *
 * Allowed, and why:
 *  - `kokoro/…`: the Kokoro-82M model, its config and its voice files.
 *    Apache-2.0 (hexgrad/Kokoro-82M).
 *  - `en_us/…`: Moonshine's English pronunciation (G2P) data: a dictionary
 *    built from CMUdict (BSD-2-Clause) plus a small model for words not in it.
 *    Moonshine hasn't yet stated the license of these files, which is why text
 *    to speech is in dev builds only.
 *
 * Ruled out, and refused even if a catalog change starts listing them: ZipVoice
 * voice cloning (trained on non-commercial data), Piper voices, and every
 * other language.
 *
 * Every file must come over HTTPS from download.moonshine.ai/tts/, and the only
 * voice file allowed is the one for the voice being downloaded.
 */
object TtsLicensePolicy {

    const val CDN_HOST = "download.moonshine.ai"
    const val CDN_PATH = "/tts/"

    /** Moonshine's language tag for the English synthesizer and G2P data. */
    const val LANGUAGE = "en_us"

    private val allowedRoots = setOf("kokoro", "en_us")
    private val forbiddenWords = listOf("zipvoice", "piper", "clone")

    fun requireAllowed(voice: KokoroVoice) {
        if (KokoroVoice.fromId(voice.id) != voice) throw TtsPolicyException("${voice.id} isn't an allowed voice")
        if (!voice.id.startsWith("af_") && !voice.id.startsWith("am_") &&
            !voice.id.startsWith("bf_") && !voice.id.startsWith("bm_")
        ) {
            throw TtsPolicyException("${voice.id} isn't an English Kokoro voice")
        }
    }

    /**
     * Checks the manifest from `TextToSpeech.getTtsDependencies` for [voice].
     * Shape: `{"groups":[{"base_url":"…","files":[{"name":"…","url":"…"} or "name"]}]}`.
     *
     * @return the relative paths of every file the manifest lists.
     */
    fun verifyManifest(voice: KokoroVoice, manifestJson: String): List<String> {
        requireAllowed(voice)
        val groups = JSONObject(manifestJson).optJSONArray("groups")
            ?: throw TtsPolicyException("Download manifest has no file groups")
        if (groups.length() == 0) throw TtsPolicyException("Download manifest is empty")

        val names = mutableListOf<String>()
        for (g in 0 until groups.length()) {
            val group = groups.getJSONObject(g)
            val baseUrl = group.optString("base_url")
            val files: JSONArray = group.optJSONArray("files")
                ?: throw TtsPolicyException("Manifest group has no files")
            for (f in 0 until files.length()) {
                val entry = files.opt(f)
                val (name, url) = when (entry) {
                    is JSONObject -> entry.optString("name") to entry.optString("url").ifEmpty { "$baseUrl/${entry.optString("name")}" }
                    is String -> entry to "$baseUrl/$entry"
                    else -> throw TtsPolicyException("Unexpected manifest entry: $entry")
                }
                verifyName(voice, name)
                verifyUrl(url)
                names += name
            }
        }
        if (names.none { isVoiceFile(it) }) {
            throw TtsPolicyException("Manifest for ${voice.id} has no voice file")
        }
        return names
    }

    internal fun verifyName(voice: KokoroVoice, name: String) {
        val segments = name.split('/')
        if (name.isBlank() || name.startsWith("/") || segments.any { it.isEmpty() || it == "." || it == ".." }) {
            throw TtsPolicyException("Unexpected file name in manifest: $name")
        }
        val lower = name.lowercase()
        if (forbiddenWords.any { it in lower }) {
            throw TtsPolicyException("$name is not allowed (ruled out by license)")
        }
        if (segments.size < 2 || segments[0] !in allowedRoots) {
            throw TtsPolicyException("$name is outside the English Kokoro and G2P folders")
        }
        if (isVoiceFile(name)) {
            val stem = segments.last().substringBeforeLast('.')
            if (stem != voice.id && stem != voice.moonshineId) {
                throw TtsPolicyException("$name is not the ${voice.id} voice")
            }
        }
    }

    internal fun verifyUrl(url: String) {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw TtsPolicyException("Bad download URL: $url")
        }
        if (uri.scheme != "https" || uri.host != CDN_HOST) {
            throw TtsPolicyException("Voice data must come from https://$CDN_HOST, not $url")
        }
        val path = uri.normalize().path.orEmpty()
        if (!path.startsWith(CDN_PATH)) {
            throw TtsPolicyException("Voice data must come from $CDN_PATH, not $path")
        }
    }

    internal fun isVoiceFile(name: String): Boolean {
        val segments = name.split('/')
        return segments.size >= 3 && segments[0] == "kokoro" && segments[1] == "voices"
    }
}
