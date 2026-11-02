// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.distillery.stt.R
import app.distillery.stt.engine.DownloadState
import app.distillery.stt.tts.KokoroVoice
import app.distillery.stt.tts.TtsRuntime

private const val PREVIEW_TEXT = "Hello! This is how I sound when Distillery reads to you."

/** What the Downloads tab says about voices, above their cards. */
fun voicesIntro(installedCount: Int): String =
    if (installedCount == 0) {
        "Your first voice also downloads the Kokoro model and English pronunciation data (roughly 90 MB). " +
            "Each voice after that is small. Everything runs on your phone."
    } else {
        "The voice marked with a check is the one apps get when they don't choose one. " +
            "Your first voice also brought the Kokoro model, which is deleted with your last voice."
    }

/**
 * One voice on the Downloads tab: download, listen, make it the default or delete it.
 * [speaker] is shared by the tab's voices, so one connection to the engine plays them all.
 */
@Composable
fun VoiceItem(voice: KokoroVoice, speaker: EngineSpeaker) {
    val context = LocalContext.current
    val runtime = remember { TtsRuntime.get(context) }
    val installed by runtime.store.installed.collectAsStateWithLifecycle()
    val defaultVoice by runtime.settings.defaultVoice.collectAsStateWithLifecycle()
    val stateFlow = remember(voice) { runtime.downloads.state(voice) }
    val state by stateFlow.collectAsStateWithLifecycle(DownloadState.Idle)
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var confirmDelete by remember { mutableStateOf(false) }

    VoiceCard(
        voice = voice,
        isInstalled = voice in installed,
        isDefault = voice == defaultVoice && voice in installed,
        state = state,
        canPreview = speaker.ready && !speaker.speaking,
        onDownload = {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (installed.isEmpty()) runtime.settings.setDefaultVoice(voice)
            runtime.downloads.start(voice)
        },
        onCancel = { runtime.downloads.cancel(voice) },
        onUse = { runtime.settings.setDefaultVoice(voice) },
        onListen = { speaker.speak(PREVIEW_TEXT, voice, 1f) },
        onDelete = { confirmDelete = true },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
            title = { Text("Delete ${voice.displayName}?") },
            text = {
                Text(
                    if (installed.size == 1) {
                        "It's the only voice, so the Kokoro model is deleted too. You can download it again later."
                    } else {
                        "You can download it again later."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    runtime.synth.unloadNow()
                    runtime.store.delete(voice)
                    if (runtime.settings.defaultVoice.value == voice) {
                        runtime.store.installed.value.firstOrNull()?.let(runtime.settings::setDefaultVoice)
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VoiceCard(
    voice: KokoroVoice,
    isInstalled: Boolean,
    isDefault: Boolean,
    state: DownloadState,
    canPreview: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onUse: () -> Unit,
    onListen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(voice.displayName, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${voice.gender.label} · Kokoro ${voice.id}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isDefault) {
                    Icon(
                        painterResource(R.drawable.ic_check_circle),
                        contentDescription = "Default voice",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            when {
                state is DownloadState.Running -> {
                    val fraction = state.fraction
                    if (fraction != null) {
                        LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            fraction?.let { "Downloading · ${(it * 100).toInt()}%" } ?: "Downloading…",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                }
                state is DownloadState.Waiting -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Waiting for a connection…", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                isInstalled -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onListen, enabled = canPreview, shapes = ButtonDefaults.shapes()) {
                        Icon(painterResource(R.drawable.ic_volume_up), contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Listen", modifier = Modifier.padding(start = 8.dp))
                    }
                    Button(onClick = onUse, enabled = !isDefault, shapes = ButtonDefaults.shapes()) {
                        Text(if (isDefault) "Default" else "Make default")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(painterResource(R.drawable.ic_delete), contentDescription = "Delete ${voice.displayName}")
                    }
                }
                else -> {
                    if (state is DownloadState.Failed) {
                        Text(
                            "Download failed: ${state.message}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    FilledTonalButton(onClick = onDownload, shapes = ButtonDefaults.shapes()) {
                        Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Download", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}
