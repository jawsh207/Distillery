// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Custom pronunciations: word → "sounds like" respelling ("Bangor" → "BANG gore").
 *
 * Distillery ships a few fixes (assets/tts/pronunciations.tsv); the user's own
 * entries are kept in preferences and win over the built-in ones.
 */
class PronunciationStore(context: Context) {

    private val prefs = context.getSharedPreferences("distillery_tts", Context.MODE_PRIVATE)

    val builtIn: Map<String, String> = runCatching {
        context.assets.open("tts/pronunciations.tsv").bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") && '\t' in it }
                .associate { it.substringBefore('\t').trim().lowercase() to it.substringAfter('\t').trim() }
        }
    }.getOrDefault(emptyMap())

    private val _user = MutableStateFlow(readUser())

    /** The user's entries, word as typed → sounds like. */
    val user: StateFlow<Map<String, String>> = _user.asStateFlow()

    /** Everything, keyed by lower-case word, user entries winning. */
    fun all(): Map<String, String> = builtIn + _user.value.mapKeys { it.key.lowercase() }

    fun put(word: String, soundsLike: String) = save(_user.value + (word.trim() to soundsLike.trim()))

    fun remove(word: String) = save(_user.value - word)

    private fun save(map: Map<String, String>) {
        prefs.edit().putString(KEY, JSONObject(map).toString()).apply()
        _user.value = map
    }

    private fun readUser(): Map<String, String> = runCatching {
        val json = JSONObject(prefs.getString(KEY, null) ?: return emptyMap())
        json.keys().asSequence().associateWith { json.getString(it) }
    }.getOrDefault(emptyMap())

    private companion object {
        const val KEY = "pronunciations"
    }
}
