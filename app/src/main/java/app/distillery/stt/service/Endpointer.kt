// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.service

/**
 * Decides when a dictation session is over, from Moonshine's phrase events
 * and the amount of audio fed so far. Time is audio time, not wall-clock time,
 * so a slow phone or a model that's still loading never cuts a speaker off.
 *
 * Moonshine's voice activity detector opens a phrase ("line") when speech
 * starts and closes it after about half a second of quiet. Dictation ends
 * once no phrase is open and [pauseToStopMs] more audio has gone by.
 *
 * Not thread-safe; the session drives it from the engine thread only.
 */
internal class Endpointer(
    /** Extra quiet after a phrase closes before stopping; null disables auto-stop. */
    private val pauseToStopMs: Long?,
    /** Give up if no speech has started after this much audio. */
    private val noSpeechTimeoutMs: Long,
    /** Never stop on a pause before this much audio. */
    private val minimumLengthMs: Long = 0,
    /** Stop when this much audio has been heard, whatever else is going on. */
    private val maxDurationMs: Long = Long.MAX_VALUE,
    /** Stop as soon as [minimumLengthMs] is reached (segmented sessions). */
    private val stopAtMinimumLength: Boolean = false,
) {
    enum class Decision { CONTINUE, END_OF_SPEECH, NO_SPEECH_TIMEOUT }

    var audioMs: Long = 0
        private set

    val speechStarted: Boolean get() = firstSpeechAtMs != null

    private var firstSpeechAtMs: Long? = null
    private val openLines = HashSet<Long>()
    private var lastPhraseClosedAtMs: Long? = null

    fun onAudio(durationMs: Long) {
        audioMs += durationMs
    }

    fun onLineStarted(lineId: Long) {
        if (firstSpeechAtMs == null) firstSpeechAtMs = audioMs
        openLines += lineId
        lastPhraseClosedAtMs = null
    }

    fun onLineCompleted(lineId: Long) {
        // A line can start and complete in the same pass; count it as speech.
        if (firstSpeechAtMs == null) firstSpeechAtMs = audioMs
        openLines -= lineId
        if (openLines.isEmpty()) lastPhraseClosedAtMs = audioMs
    }

    fun decide(): Decision {
        if (audioMs >= maxDurationMs) return Decision.END_OF_SPEECH
        if (stopAtMinimumLength && minimumLengthMs > 0 && audioMs >= minimumLengthMs) {
            return Decision.END_OF_SPEECH
        }
        if (firstSpeechAtMs == null) {
            return if (audioMs >= noSpeechTimeoutMs) Decision.NO_SPEECH_TIMEOUT else Decision.CONTINUE
        }
        val pause = pauseToStopMs ?: return Decision.CONTINUE
        val closedAt = lastPhraseClosedAtMs ?: return Decision.CONTINUE
        if (openLines.isNotEmpty() || audioMs < minimumLengthMs) return Decision.CONTINUE
        return if (audioMs - closedAt >= pause) Decision.END_OF_SPEECH else Decision.CONTINUE
    }
}
