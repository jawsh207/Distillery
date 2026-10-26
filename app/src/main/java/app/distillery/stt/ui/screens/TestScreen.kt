// SPDX-License-Identifier: GPL-3.0-or-later
// ListItem(headlineContent = …) and Slider(value = …) are deprecated on the
// Material 3 1.6 line in favour of new overloads; they're kept here because they
// exist on every 1.5.x release this app targets.
@file:Suppress("DEPRECATION")

package app.distillery.stt.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.distillery.stt.R
import app.distillery.stt.DistilleryApplication
import app.distillery.stt.ui.VoiceInputSetup
import app.distillery.stt.ui.components.BodyText
import app.distillery.stt.ui.components.DistilleryScreen
import java.util.Locale

/**
 * A test bench that uses SpeechRecognizer exactly like other apps do (live
 * dictation, or a recording handed over with EXTRA_AUDIO_SOURCE), so you can watch
 * partial results stream in and check every callback.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TestScreen(onBack: () -> Unit, onOpenModels: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as DistilleryApplication
    val tester = remember { RecognizerTester(context.applicationContext) }
    DisposableEffect(tester) { onDispose { tester.destroyRecognizer() } }

    val installed by app.modelStore.installed.collectAsStateWithLifecycle()
    var micGranted by remember { mutableStateOf(false) }
    var defaultName by remember { mutableStateOf<String?>(null) }
    var distilleryIsDefault by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        micGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val current = VoiceInputSetup.currentDefault(context)
        distilleryIsDefault = current == VoiceInputSetup.component(context)
        defaultName = current?.let { component ->
            runCatching {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(component.packageName, 0),
                ).toString()
            }.getOrDefault(component.packageName)
        }
        onPauseOrDispose { tester.cancel() }
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micGranted = it }
    val recordingPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) tester.transcribeRecording(uri, displayName(context, uri))
    }

    DistilleryScreen(title = "Test voice input", subtitle = "SpeechRecognizer, the way other apps call it", onBack = onBack) {
        item {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    ListItem(
                        headlineContent = { Text("Recognizer") },
                        supportingContent = {
                            Text(
                                when {
                                    tester.targetDistilleryDirectly -> "Distillery, by component name"
                                    distilleryIsDefault -> "System default: Distillery"
                                    defaultName != null -> "System default: $defaultName (not Distillery)"
                                    else -> "System default: unknown"
                                },
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    )
                    ListItem(
                        headlineContent = { Text("Use Distillery directly") },
                        supportingContent = { Text("createSpeechRecognizer(context, component)") },
                        trailingContent = {
                            Switch(
                                checked = tester.targetDistilleryDirectly,
                                onCheckedChange = { tester.targetDistilleryDirectly = it },
                                enabled = !tester.listening,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    )
                    ListItem(
                        headlineContent = { Text("Partial results") },
                        supportingContent = { Text("EXTRA_PARTIAL_RESULTS") },
                        trailingContent = {
                            Switch(
                                checked = tester.partialResults,
                                onCheckedChange = { tester.partialResults = it },
                                enabled = !tester.listening,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    )
                }
            }
        }

        if (installed.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No model downloaded yet", style = MaterialTheme.typography.titleMedium)
                        BodyText("Distillery will answer with ERROR_LANGUAGE_UNAVAILABLE until a model is installed.")
                        FilledTonalButton(onClick = onOpenModels) { Text("Download a model") }
                    }
                }
            }
        }

        // The "text field" a keyboard would be typing into.
        item {
            val composingColor = MaterialTheme.colorScheme.primary
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    val empty = tester.committed.isEmpty() && tester.composing.isEmpty()
                    Text(
                        if (empty) {
                            buildAnnotatedString { append("Your words appear here as you speak…") }
                        } else {
                            buildAnnotatedString {
                                append(tester.committed)
                                if (tester.composing.isNotEmpty()) {
                                    if (tester.committed.isNotEmpty()) append(" ")
                                    withStyle(SpanStyle(color = composingColor, textDecoration = TextDecoration.Underline)) {
                                        append(tester.composing)
                                    }
                                }
                            }
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (empty) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tester.status,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = tester::clear, enabled = !tester.listening) { Text("Clear") }
                    }
                }
            }
        }

        // Microphone
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!micGranted) {
                    BodyText("The test acts as the calling app, so it needs microphone access as well.")
                    Button(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }, shapes = ButtonDefaults.shapes()) {
                        Text("Allow microphone")
                    }
                } else {
                    MicButton(tester)
                    if (tester.listening) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = tester::stop, shapes = ButtonDefaults.shapes()) { Text("Stop listening") }
                            OutlinedButton(onClick = tester::cancel, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                        }
                    }
                }
            }
        }

        // Recordings, handed over with EXTRA_AUDIO_SOURCE
        item {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Transcribe a recording", style = MaterialTheme.typography.titleMedium)
                    BodyText(
                        "Sends an audio file to the recognizer as EXTRA_AUDIO_SOURCE, the way an app hands over " +
                            "a recording. AMR, M4A/3GP, MP3, Ogg, FLAC and WAV all work.",
                    )
                    FilledTonalButton(
                        onClick = { recordingPicker.launch(arrayOf("audio/*")) },
                        enabled = !tester.listening,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Icon(painterResource(R.drawable.ic_description), contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Choose audio file", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        // Streaming metrics
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Metric("Partials", tester.partialCount.toString(), Modifier.weight(1f))
                Metric("First partial", tester.firstPartialMs?.let { seconds(it) } ?: "–", Modifier.weight(1f))
                Metric("Final lag", tester.finalAfterEndMs?.let { seconds(it) } ?: "–", Modifier.weight(1f))
                Metric("Total", tester.totalMs?.let { seconds(it) } ?: "–", Modifier.weight(1f))
            }
        }

        item {
            Text(
                "Callbacks (newest first)",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )
        }
        if (tester.events.isEmpty()) {
            item { BodyText("Nothing yet.", modifier = Modifier.padding(start = 4.dp)) }
        }
        items(tester.events.size) { index ->
            val event = tester.events[index]
            Row(Modifier.padding(horizontal = 4.dp)) {
                Text(
                    "+${seconds(event.atMs)}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(64.dp),
                )
                Column {
                    Text(event.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    if (event.detail.isNotEmpty()) {
                        Text(event.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MicButton(tester: RecognizerTester) {
    val level by animateFloatAsState(if (tester.listening) tester.level else 0f, label = "level")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(160.dp)) {
        // Halo that breathes with the input level from onRmsChanged.
        Box(
            Modifier
                .size(112.dp)
                .scale(1f + level * 0.4f)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        )
        if (tester.listening) {
            ContainedLoadingIndicator(
                modifier = Modifier
                    .size(112.dp)
                    .clickable(onClick = tester::stop),
            )
        } else {
            LargeFloatingActionButton(
                onClick = tester::start,
                shape = MaterialShapes.Cookie9Sided.toShape(),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(painterResource(R.drawable.ic_mic), contentDescription = "Start listening", modifier = Modifier.size(36.dp))
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun displayName(context: android.content.Context, uri: android.net.Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "recording"

private fun seconds(ms: Long) = String.format(Locale.US, "%.2fs", ms / 1000.0)
