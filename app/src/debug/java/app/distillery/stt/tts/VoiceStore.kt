// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Where text-to-speech data lives: one shared folder ([root]) laid out the way
 * Moonshine's synthesizer expects (`kokoro/…`, `en_us/…`). Voices share the
 * model and pronunciation data; each adds only its own voice file.
 *
 * A voice counts as installed once its download finished and wrote a marker
 * listing the files it needs, so deleting one voice removes only the files no
 * other installed voice uses.
 */
class VoiceStore(val root: File) {

    private val markers = File(root, ".distillery-voices")
    private val _installed = MutableStateFlow(scan())

    val installed: StateFlow<Set<KokoroVoice>> = _installed.asStateFlow()

    fun isInstalled(voice: KokoroVoice): Boolean = marker(voice).isFile

    fun markComplete(voice: KokoroVoice, files: List<String>) {
        markers.mkdirs()
        marker(voice).writeText(files.joinToString("\n"))
        refresh()
    }

    @Synchronized
    fun delete(voice: KokoroVoice) {
        val mine = filesOf(voice)
        marker(voice).delete()
        val remaining = KokoroVoice.entries.filter { isInstalled(it) }
        if (remaining.isEmpty()) {
            root.deleteRecursively()
        } else {
            val stillUsed = remaining.flatMap { filesOf(it) }.toSet()
            mine.filter { it !in stillUsed }.forEach { File(root, it).delete() }
        }
        refresh()
    }

    fun bytesOnDisk(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun refresh() {
        _installed.value = scan()
    }

    private fun filesOf(voice: KokoroVoice): List<String> =
        marker(voice).takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }.orEmpty()

    private fun marker(voice: KokoroVoice) = File(markers, voice.id)

    private fun scan(): Set<KokoroVoice> = KokoroVoice.entries.filterTo(mutableSetOf()) { isInstalled(it) }
}
