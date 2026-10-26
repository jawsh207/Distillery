// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.engine

import ai.moonshine.voice.Transcriber
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Owns the loaded Moonshine model.
 *
 * Loading a model takes a moment (longer for Medium), so the transcriber is
 * kept in memory between dictation sessions and released after
 * [IDLE_RELEASE_MS] without use. Every call into Moonshine happens on one
 * dedicated thread, [dispatcher], so the native transcriber is never touched
 * from two threads at once.
 */
class SpeechEngine(
    private val store: ModelStore,
    /** Loads a model directory into a native transcriber (replaceable in tests). */
    private val loader: (SttModel, String) -> Transcriber = { model, dir ->
        Transcriber().apply { loadFromFiles(dir, model.arch) }
    },
) {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            // Above normal so partial results keep up with speech, but below
            // the audio thread and the caller's UI.
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
            runnable.run()
        }, "distillery-moonshine")
    }

    /** The only thread allowed to call into Moonshine. */
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    // Confined to [dispatcher].
    private var transcriber: Transcriber? = null
    private var loadedModel: SttModel? = null
    private var releaseJob: Job? = null

    /**
     * Returns a transcriber for [model], loading it if needed. Must run on
     * [dispatcher]. Pair with [releaseLater] when the session ends.
     */
    fun acquire(model: SttModel): Transcriber {
        releaseJob?.cancel()
        releaseJob = null
        transcriber?.let { current ->
            if (loadedModel == model) return current
            current.close()
        }
        transcriber = null
        loadedModel = null

        ModelLicensePolicy.requireAllowed(model)
        check(store.isInstalled(model)) { "${model.id} is not installed" }

        val started = System.nanoTime()
        val loaded = loader(model, store.dirFor(model).absolutePath)
        Log.i(TAG, "Loaded ${model.id} in ${(System.nanoTime() - started) / 1_000_000} ms")
        transcriber = loaded
        loadedModel = model
        return loaded
    }

    /** Frees the model after a quiet period. Must run on [dispatcher]. */
    fun releaseLater() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            unload()
        }
    }

    /** Loads [model] ahead of time so the first dictation starts instantly. */
    fun preload(model: SttModel) {
        scope.launch {
            try {
                acquire(model)
                releaseLater()
            } catch (e: Exception) {
                Log.w(TAG, "Preload of ${model.id} failed", e)
            }
        }
    }

    /** Drops the loaded model, e.g. after it was deleted or replaced. */
    suspend fun unloadNow() = withContext(dispatcher) { unload() }

    private fun unload() {
        releaseJob = null
        transcriber?.close()
        transcriber = null
        loadedModel?.let { Log.i(TAG, "Released ${it.id}") }
        loadedModel = null
    }

    companion object {
        private const val TAG = "DistilleryEngine"
        const val IDLE_RELEASE_MS = 3 * 60_000L
    }
}
