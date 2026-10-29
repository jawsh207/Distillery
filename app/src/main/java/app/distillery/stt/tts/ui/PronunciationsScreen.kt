// SPDX-License-Identifier: GPL-3.0-or-later
// ListItem(headlineContent = …) is deprecated on the Material 3 1.6 line; it's
// kept because it exists on every 1.5.x release this app targets.
@file:Suppress("DEPRECATION")

package app.distillery.stt.tts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.distillery.stt.R
import app.distillery.stt.tts.TtsRuntime
import app.distillery.stt.ui.components.BodyText
import app.distillery.stt.ui.components.DistilleryScreen

/** Lets the user fix how words are said: "Bangor" sounds like "BANG gore". */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PronunciationsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { TtsRuntime.get(context) }
    val user by runtime.pronunciations.user.collectAsStateWithLifecycle()
    val defaultVoice by runtime.settings.defaultVoice.collectAsStateWithLifecycle()
    val installed by runtime.store.installed.collectAsStateWithLifecycle()
    val speaker = remember { EngineSpeaker(context) }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }
    val voice = defaultVoice.takeIf { it in installed } ?: installed.firstOrNull()

    var word by rememberSaveable { mutableStateOf("") }
    var soundsLike by rememberSaveable { mutableStateOf("") }

    fun listen(w: String) {
        if (voice != null) speaker.speak(w, voice, 1f)
    }

    DistilleryScreen(title = "Pronunciations", subtitle = "Text to speech", onBack = onBack) {
        item {
            BodyText(
                "If a word is said wrong, spell how it sounds using ordinary words, with the stressed part in capitals: " +
                    "Bangor → BANG gore, Gloucester → GLOSS ter. Plurals and possessives (Bangor's) follow on their own.",
            )
        }
        item {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = word,
                        onValueChange = { word = it.trim() },
                        label = { Text("Word") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = soundsLike,
                        onValueChange = { soundsLike = it },
                        label = { Text("Sounds like") },
                        placeholder = { Text("BANG gore") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                runtime.pronunciations.put(word, soundsLike)
                                listen(word)
                                word = ""
                                soundsLike = ""
                            },
                            enabled = word.isNotBlank() && soundsLike.isNotBlank() && ' ' !in word,
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("Save and listen") }
                        OutlinedButton(
                            onClick = { listen(word) },
                            enabled = word.isNotBlank() && speaker.ready && voice != null,
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("Hear it now") }
                    }
                    if (voice == null) BodyText("Download a voice to listen.")
                }
            }
        }
        if (user.isNotEmpty()) {
            item { SectionTitle("Yours") }
            item {
                EntryList(user.toSortedMap(String.CASE_INSENSITIVE_ORDER), onListen = ::listen, onDelete = runtime.pronunciations::remove)
            }
        }
        if (runtime.pronunciations.builtIn.isNotEmpty()) {
            item { SectionTitle("Built in") }
            item { EntryList(runtime.pronunciations.builtIn.toSortedMap(), onListen = ::listen, onDelete = null) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun EntryList(entries: Map<String, String>, onListen: (String) -> Unit, onDelete: ((String) -> Unit)?) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            entries.forEach { (w, s) ->
                ListItem(
                    headlineContent = { Text(w.replaceFirstChar { it.uppercaseChar() }) },
                    supportingContent = { Text("sounds like “$s”") },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { onListen(w) }) {
                                Icon(painterResource(R.drawable.ic_volume_up), contentDescription = "Listen to $w", modifier = Modifier.size(22.dp))
                            }
                            if (onDelete != null) {
                                IconButton(onClick = { onDelete(w) }) {
                                    Icon(painterResource(R.drawable.ic_delete), contentDescription = "Delete $w")
                                }
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                )
            }
        }
    }
}
