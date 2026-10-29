// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

import android.content.res.AssetManager
import java.io.File

/**
 * Distillery's own stand-ins for Moonshine's English pronunciation files.
 *
 * Moonshine's speech engine won't start without an English dictionary and an
 * out-of-vocabulary model under en_us/, though Distillery never has it
 * pronounce anything: it hands Kokoro phonemes it worked out itself (lexicon,
 * tagger, [WordGuesser]). So these small placeholders (assets/tts/standin,
 * made by tools/make-g2p-standin.py) go where Moonshine's files would, and
 * Moonshine's own data, whose license isn't confirmed, is never downloaded or
 * kept.
 */
object StandIns {

    /** Where Moonshine looks, relative to the voice folder → our asset. */
    private val files = mapOf(
        "en_us/dict_filtered_heteronyms.tsv" to "tts/standin/dict.tsv",
        "en_us/g2p-config.json" to "tts/standin/g2p-config.json",
        "en_us/oov/model.ort" to "tts/standin/model.ort",
        "en_us/oov/onnx-config.json" to "tts/standin/onnx-config.json",
    )

    val paths: Set<String> get() = files.keys

    /**
     * Puts the stand-ins in place under [root], replacing anything else there
     * (such as Moonshine's own files from an earlier version).
     *
     * @return the number of files written.
     */
    fun install(root: File, assets: AssetManager): Int {
        var written = 0
        for ((path, asset) in files) {
            val target = File(root, path)
            val bytes = assets.open(asset).use { it.readBytes() }
            if (target.isFile && target.readBytes().contentEquals(bytes)) continue
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            written++
        }
        return written
    }

    /** True when every file under en_us/ is one of ours, unchanged. */
    fun verify(root: File, assets: AssetManager): Boolean {
        val enUs = File(root, "en_us")
        val ours = files.mapValues { (_, asset) -> assets.open(asset).use { it.readBytes() } }
        val present = enUs.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
        return present == ours.keys && ours.all { (path, bytes) -> File(root, path).readBytes().contentEquals(bytes) }
    }
}
