// SPDX-License-Identifier: Apache-2.0
// ListItem(headlineContent = …) is deprecated on the Material 3 1.6 line; kept
// because it exists on every 1.5.x release this app targets.
@file:Suppress("DEPRECATION")

package app.distillery.stt.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.distillery.stt.DistilleryApplication
import app.distillery.stt.engine.SttModel
import app.distillery.stt.tts.Accent
import app.distillery.stt.tts.KokoroVoice
import app.distillery.stt.tts.TtsRuntime
import app.distillery.stt.tts.ui.EngineSpeaker
import app.distillery.stt.tts.ui.VoiceItem
import app.distillery.stt.tts.ui.voicesIntro
import app.distillery.stt.ui.components.BodyText
import app.distillery.stt.ui.components.DistilleryScreen
import app.distillery.stt.ui.components.LicensesAction
import app.distillery.stt.ui.components.ListHeading

/**
 * The Downloads tab: everything Distillery downloads, speech models and voices,
 * with the space they take and the one Wi-Fi setting that covers both.
 */
@Composable
fun DownloadsTab(onOpenLicenses: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as DistilleryApplication
    val tts = remember { TtsRuntime.get(context) }
    val models by app.modelStore.installed.collectAsStateWithLifecycle()
    val voices by tts.store.installed.collectAsStateWithLifecycle()
    val settingsVersion by app.settings.changes.collectAsStateWithLifecycle(0)
    val wifiOnly = remember(settingsVersion) { app.settings.wifiOnlyDownloads }
    val modelBytes = remember(models) { models.sumOf { app.modelStore.bytesOnDisk(it) } }
    val voiceBytes = remember(voices) { if (voices.isEmpty()) 0L else tts.store.bytesOnDisk() }

    // One connection to the engine for every voice's Listen button.
    val speaker = remember { EngineSpeaker(context) }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }

    DistilleryScreen(
        title = "Downloads",
        subtitle = "Speech models and voices",
        actions = { LicensesAction(onOpenLicenses) },
    ) {
        item(key = "summary") {
            BodyText(
                if (models.isEmpty() && voices.isEmpty()) {
                    "Nothing downloaded yet. Everything here downloads once and then runs entirely on your phone."
                } else {
                    "${Formatter.formatShortFileSize(context, modelBytes + voiceBytes)} in use: " +
                        "${Formatter.formatShortFileSize(context, modelBytes)} for speech to text, " +
                        "${Formatter.formatShortFileSize(context, voiceBytes)} for text to speech."
                },
            )
        }
        item(key = "wifi") {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                ListItem(
                    headlineContent = { Text("Download over Wi-Fi only") },
                    supportingContent = { Text("Models and voices wait for an unmetered connection") },
                    trailingContent = {
                        Switch(checked = wifiOnly, onCheckedChange = { app.settings.wifiOnlyDownloads = it })
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
        }

        // ------------------------------------------------------- speech models
        item(key = "models-heading") { ListHeading("Speech models · speech to text") }
        item(key = "models-intro") { BodyText(MODELS_INTRO, modifier = Modifier.padding(horizontal = 4.dp)) }
        SttModel.entries.forEach { model ->
            item(key = "model-${model.id}") { ModelItem(model) }
        }
        item(key = "models-footnote") {
            Text(
                MODELS_FOOTNOTE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        // -------------------------------------------------------------- voices
        item(key = "voices-heading") { ListHeading("Voices · text to speech", Modifier.padding(top = 12.dp)) }
        item(key = "voices-intro") { BodyText(voicesIntro(voices.size), modifier = Modifier.padding(horizontal = 4.dp)) }
        Accent.entries.forEach { accent ->
            item(key = "accent-${accent.name}") {
                Text(
                    accent.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
            KokoroVoice.entries.filter { it.accent == accent }.forEach { voice ->
                item(key = "voice-${voice.id}") { VoiceItem(voice, speaker) }
            }
        }
    }
}
