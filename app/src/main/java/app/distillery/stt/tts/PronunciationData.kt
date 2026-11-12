// SPDX-License-Identifier: Apache-2.0
package app.distillery.stt.tts

import android.content.res.AssetManager
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Properties

/**
 * Distillery's pronunciation data: the lexicons, heteronym lists and word
 * guessers for each accent, and the part-of-speech tagger they share.
 *
 * They aren't in the APK. They come with the first voice of each accent, from
 * a GitHub release of Distillery's repository, and every file is checked
 * against the SHA-256 and size in assets/tts/data.properties
 * (tools/tts-data.py writes it). They live in [dir] under the voice store,
 * each beside a `.sha256` file recording what was checked, so an app update
 * that changes one file downloads only that file again.
 */
class PronunciationData(storeRoot: File, private val manifest: Manifest) {

    val dir = File(storeRoot, "distillery-data")

    /** One file: its name, expected SHA-256 and size. */
    data class Entry(val name: String, val sha256: String, val size: Long)

    data class Manifest(val tag: String, val entries: Map<String, Entry>) {
        companion object {
            const val ASSET = "tts/data.properties"

            fun load(assets: AssetManager): Manifest = assets.open(ASSET).use { parse(it) }

            fun parse(input: InputStream): Manifest {
                val props = Properties().apply { load(input) }
                val tag = props.getProperty("tag") ?: throw IOException("data.properties has no tag")
                val entries = props.stringPropertyNames().filter { it.startsWith("file.") }.associate { key ->
                    val name = key.removePrefix("file.")
                    val (sha, size) = props.getProperty(key).trim().split(' ')
                    name to Entry(name, sha, size.toLong())
                }
                return Manifest(tag, entries)
            }
        }
    }

    /** The files [accent] needs. */
    fun filesFor(accent: Accent): List<String> {
        val suffix = suffix(accent)
        return listOf("lexicon-$suffix.tsv.gzip", "heteronyms-$suffix.tsv.gzip", "guesser-$suffix.bin.gzip", TAGGER)
    }

    /** Whether every file [accent] needs is here and is the version the app expects. */
    fun isPresent(accent: Accent): Boolean = missing(accent).isEmpty()

    fun missing(accent: Accent): List<Entry> = filesFor(accent).map(::entry).filterNot(::isCurrent)

    /** Opens a downloaded file; [name] is one of [filesFor]. */
    fun open(name: String): InputStream = File(dir, name).inputStream().buffered(1 shl 16)

    /**
     * Downloads what [accent] is missing, reporting bytes done and the total.
     * @throws TtsPolicyException if a file doesn't match its checksum.
     */
    fun download(accent: Accent, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }) {
        val todo = missing(accent)
        if (todo.isEmpty()) return
        dir.mkdirs()
        val total = todo.sumOf { it.size }
        var before = 0L
        for (entry in todo) {
            fetch(entry) { onProgress(before + it, total) }
            before += entry.size
        }
    }

    /** Deletes the files only [accent] uses, and the tagger too when [last] is true. */
    fun deleteFor(accent: Accent, last: Boolean) {
        for (name in filesFor(accent)) {
            if (name == TAGGER && !last) continue
            File(dir, name).delete()
            File(dir, "$name.sha256").delete()
        }
    }

    private fun entry(name: String): Entry =
        manifest.entries[name] ?: throw IllegalStateException("$name isn't in ${Manifest.ASSET}")

    private fun isCurrent(entry: Entry): Boolean {
        val file = File(dir, entry.name)
        val recorded = File(dir, "${entry.name}.sha256")
        return file.isFile && file.length() == entry.size && recorded.isFile && recorded.readText().trim() == entry.sha256
    }

    private fun fetch(entry: Entry, onBytes: (Long) -> Unit) {
        val url = "$BASE_URL/${manifest.tag}/${entry.name}"
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        val partial = File(dir, "${entry.name}.partial")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Couldn't download ${entry.name}: HTTP ${connection.responseCode}")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        if (Thread.interrupted()) throw InterruptedException()
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (done + n > entry.size) throw TtsPolicyException("${entry.name} is larger than expected")
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        onBytes(done)
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (done != entry.size || sha != entry.sha256) {
                throw TtsPolicyException("${entry.name} didn't match its checksum")
            }
            val target = File(dir, entry.name)
            if (!partial.renameTo(target)) throw IOException("Couldn't save ${entry.name}")
            File(dir, "${entry.name}.sha256").writeText(sha)
        } finally {
            partial.delete()
            connection.disconnect()
        }
    }

    companion object {
        /** Distillery's own releases; nothing else is downloaded from here. */
        const val BASE_URL = "https://github.com/jawsh207/Distillery/releases/download"
        const val TAGGER = "pos-tagger.bin.gzip"

        fun suffix(accent: Accent) = if (accent == Accent.BRITISH) "gb" else "us"
    }
}
