// SPDX-License-Identifier: Apache-2.0
// ListItem(headlineContent = …) is deprecated on the Material 3 1.6 line; kept
// because it exists on every 1.5.x release this app targets.
@file:Suppress("DEPRECATION")

package app.distillery.stt.ui.screens

import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import app.distillery.stt.R
import app.distillery.stt.tts.Gender
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

/**
 * The Downloads tab: everything Distillery downloads, speech models and voices,
 * with the space they take and the one Wi-Fi setting that covers both.
 */
@OptIn(ExperimentalLayoutApi::class)
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

    // Open until something in the section is downloaded, then folded away.
    var modelsOpen by rememberSaveable { mutableStateOf(models.isEmpty()) }
    var voicesOpen by rememberSaveable { mutableStateOf(voices.isEmpty()) }
    var american by rememberSaveable { mutableStateOf(false) }
    var british by rememberSaveable { mutableStateOf(false) }
    var female by rememberSaveable { mutableStateOf(false) }
    var male by rememberSaveable { mutableStateOf(false) }

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
        item(key = "models-heading") {
            SectionHeading(
                title = "Speech models",
                summary = "${models.size} of ${SttModel.entries.size} downloaded · speech to text",
                expanded = modelsOpen,
                onToggle = { modelsOpen = !modelsOpen },
            )
        }
        if (modelsOpen) {
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
        }

        // -------------------------------------------------------------- voices
        item(key = "voices-heading") {
            SectionHeading(
                title = "Voices",
                summary = "${voices.size} of ${KokoroVoice.entries.size} downloaded · text to speech",
                expanded = voicesOpen,
                onToggle = { voicesOpen = !voicesOpen },
            )
        }
        if (voicesOpen) {
            item(key = "voices-intro") { BodyText(voicesIntro(voices.size), modifier = Modifier.padding(horizontal = 4.dp)) }
            item(key = "voice-filters") {
                // Within a row, none picked means all; across rows, a voice must match both.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                    FilterChip(selected = american, onClick = { american = !american }, label = { Text("American") })
                    FilterChip(selected = british, onClick = { british = !british }, label = { Text("British") })
                    FilterChip(selected = female, onClick = { female = !female }, label = { Text("Female") })
                    FilterChip(selected = male, onClick = { male = !male }, label = { Text("Male") })
                }
            }
            val shown = KokoroVoice.entries.filter { v ->
                (american == british || (american && v.accent == Accent.AMERICAN) || (british && v.accent == Accent.BRITISH)) &&
                    (female == male || (female && v.gender == Gender.FEMALE) || (male && v.gender == Gender.MALE))
            }
            val (downloaded, available) = shown.partition { it in voices }
            if (shown.isEmpty()) {
                item(key = "voices-none") { BodyText("No voices match.", modifier = Modifier.padding(horizontal = 4.dp)) }
            }
            if (downloaded.isNotEmpty()) {
                item(key = "group-downloaded") { VoiceGroupLabel("Downloaded") }
                downloaded.forEach { voice -> item(key = "voice-${voice.id}") { VoiceItem(voice, speaker) } }
            }
            Accent.entries.forEach { accent ->
                val group = available.filter { it.accent == accent }
                if (group.isEmpty()) return@forEach
                item(key = "group-${accent.name}") { VoiceGroupLabel(accent.label) }
                group.forEach { voice -> item(key = "voice-${voice.id}") { VoiceItem(voice, speaker) } }
            }
        }
    }
}

/** A section's title and summary, tapped to fold or unfold it. */
@Composable
private fun SectionHeading(title: String, summary: String, expanded: Boolean, onToggle: () -> Unit) {
    val turn by animateFloatAsState(if (expanded) 90f else 0f, label = "chevron")
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        ListItem(
            headlineContent = { Text(title, style = MaterialTheme.typography.titleLarge) },
            supportingContent = { Text(summary) },
            trailingContent = {
                Icon(
                    painterResource(R.drawable.ic_chevron_right),
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.rotate(turn),
                )
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun VoiceGroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}
