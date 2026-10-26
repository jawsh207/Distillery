// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why the last voice input request failed, shown in the app so problems can be reported. */
data class Problem(val summary: String, val detail: String, val atMillis: Long = System.currentTimeMillis())

object Diagnostics {
    private val _lastProblem = MutableStateFlow<Problem?>(null)
    val lastProblem: StateFlow<Problem?> = _lastProblem.asStateFlow()

    fun report(summary: String, detail: String) {
        _lastProblem.value = Problem(summary, detail)
    }

    fun report(summary: String, error: Throwable) = report(summary, describe(error))

    fun clear() {
        _lastProblem.value = null
    }

    /** Exception class and message for the whole cause chain. */
    fun describe(error: Throwable): String = generateSequence(error) { it.cause.takeIf { c -> c !== it } }
        .take(6)
        .joinToString("\ncaused by: ") { "${it.javaClass.name}: ${it.message ?: "(no message)"}" }
}
