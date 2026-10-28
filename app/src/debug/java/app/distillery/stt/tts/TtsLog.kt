// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the text-to-speech engine is doing, for the test screen: a short event
 * log and the step it's on right now, so a stuck or failing step is visible
 * without logcat. Everything also goes to logcat under "DistilleryTts".
 */
object TtsLog {

    data class Entry(val atMs: Long, val text: String)

    /** The step in progress, and when it started (elapsedRealtime). */
    data class Phase(val text: String, val sinceMs: Long)

    private const val TAG = "DistilleryTts"
    private const val MAX_ENTRIES = 80

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _phase = MutableStateFlow<Phase?>(null)
    val phase: StateFlow<Phase?> = _phase.asStateFlow()

    fun add(text: String) {
        Log.i(TAG, text)
        _entries.update { (it + Entry(SystemClock.elapsedRealtime(), text)).takeLast(MAX_ENTRIES) }
    }

    fun error(text: String, t: Throwable) {
        Log.e(TAG, text, t)
        val trace = generateSequence(t) { it.cause }.take(4).joinToString("\n") { cause ->
            "${cause::class.java.name}: ${cause.message}\n" +
                cause.stackTrace.take(4).joinToString("\n") { "  at $it" }
        }
        _entries.update { (it + Entry(SystemClock.elapsedRealtime(), "$text\n$trace")).takeLast(MAX_ENTRIES) }
    }

    fun begin(step: String) {
        add("$step…")
        _phase.value = Phase(step, SystemClock.elapsedRealtime())
    }

    fun end() {
        _phase.value = null
    }

    fun clear() {
        _entries.value = emptyList()
    }

    fun asText(): String = _entries.value.joinToString("\n") { it.text }
}
