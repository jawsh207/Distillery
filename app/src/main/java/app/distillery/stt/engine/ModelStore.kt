// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Where downloaded models live on disk.
 *
 * [root] is noBackupFilesDir/models: models are large, re-downloadable and
 * shouldn't end up in device backups. A model counts as installed only once its download
 * finished and wrote [COMPLETE_MARKER], so a half-finished download is never
 * handed to the native loader.
 */
class ModelStore(private val root: File) {

    private val _installed = MutableStateFlow(scanInstalled())

    /** Models whose download has completed. */
    val installed: StateFlow<Set<SttModel>> = _installed.asStateFlow()

    fun dirFor(model: SttModel): File = File(root, model.id)

    fun isInstalled(model: SttModel): Boolean = File(dirFor(model), COMPLETE_MARKER).isFile

    /**
     * The model the recognizer should use: the user's choice if it's installed,
     * otherwise any installed model, otherwise null.
     */
    fun resolveModel(chosen: SttModel): SttModel? {
        if (isInstalled(chosen)) return chosen
        return SttModel.fallbackOrder.firstOrNull { isInstalled(it) }
    }

    fun markComplete(model: SttModel) {
        File(dirFor(model), COMPLETE_MARKER).writeText(System.currentTimeMillis().toString())
        refresh()
    }

    fun delete(model: SttModel) {
        dirFor(model).deleteRecursively()
        refresh()
    }

    fun bytesOnDisk(model: SttModel): Long =
        dirFor(model).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun refresh() {
        _installed.value = scanInstalled()
    }

    private fun scanInstalled(): Set<SttModel> =
        SttModel.entries.filterTo(mutableSetOf()) { isInstalled(it) }

    companion object {
        const val COMPLETE_MARKER = ".distillery-complete"
    }
}
