// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.distillery.stt.R
import app.distillery.stt.DistilleryApplication
import app.distillery.stt.engine.DownloadState
import app.distillery.stt.engine.SttModel
import app.distillery.stt.ui.components.BodyText
import kotlinx.coroutines.launch
import java.util.Locale

/** What the Downloads tab says about speech models, above their cards. */
const val MODELS_INTRO =
    "Each model downloads once from Moonshine AI and then runs entirely on your phone. " +
        "Bigger models are more accurate but need more memory and a faster processor."

/** What the Downloads tab says about speech models, below their cards. */
const val MODELS_FOOTNOTE =
    "Accuracy is word error rate on the Open ASR Leaderboard (lower is better), as published by Moonshine. " +
        "Distillery only offers models whose license is MIT and refuses any other download."

/** One speech model on the Downloads tab: download, use or delete it. */
@Composable
fun ModelItem(model: SttModel) {
    val context = LocalContext.current
    val app = context.applicationContext as DistilleryApplication
    val installed by app.modelStore.installed.collectAsStateWithLifecycle()
    val settingsVersion by app.settings.changes.collectAsStateWithLifecycle(0)
    val wifiOnly = remember(settingsVersion) { app.settings.wifiOnlyDownloads }
    val active = remember(settingsVersion, installed) { app.modelStore.resolveModel(app.settings.chosenModel) }
    val stateFlow = remember(model) { app.downloads.state(model) }
    val state by stateFlow.collectAsStateWithLifecycle(DownloadState.Idle)
    val bytes = remember(installed) { if (model in installed) app.modelStore.bytesOnDisk(model) else 0L }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }

    ModelCard(
        model = model,
        isInstalled = model in installed,
        isActive = model == active,
        isRecommended = model == SttModel.SMALL,
        state = state,
        wifiOnly = wifiOnly,
        bytesOnDisk = bytes,
        onDownload = {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            // First model, or the chosen one isn't installed: make this one the choice.
            if (!app.modelStore.isInstalled(app.settings.chosenModel)) app.settings.chosenModel = model
            app.downloads.start(model)
        },
        onCancel = { app.downloads.cancel(model) },
        onUse = {
            app.settings.chosenModel = model
            app.engine.preload(model)
        },
        onDelete = { confirmDelete = true },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
            title = { Text("Delete ${model.displayName}?") },
            text = {
                Text(
                    if (model == active) {
                        "This is the model in use. Voice input won't work until you download a model again."
                    } else {
                        "You can download it again later."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        app.engine.unloadNow()
                        app.modelStore.delete(model)
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ModelCard(
    model: SttModel,
    isInstalled: Boolean,
    isActive: Boolean,
    isRecommended: Boolean,
    state: DownloadState,
    wifiOnly: Boolean,
    bytesOnDisk: Long,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        shape = RoundedCornerShape(28.dp),
        // Same background for every model; the check mark shows which one is in use.
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.displayName,
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    modifier = Modifier.weight(1f),
                )
                if (isActive) {
                    Icon(
                        painterResource(R.drawable.ic_check_circle),
                        contentDescription = "In use",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            BodyText(model.summary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoChip("${model.parameters} parameters")
                InfoChip(String.format(Locale.US, "%.2f%% WER", model.werPercent))
                InfoChip("${model.license.spdx} license")
                if (isRecommended) InfoChip("Recommended")
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
                state is DownloadState.Waiting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (wifiOnly) "Waiting for Wi-Fi…" else "Waiting for a connection…",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                }
                isInstalled -> {
                    Text(
                        "Downloaded · ${Formatter.formatShortFileSize(context, bytesOnDisk)}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isActive) {
                            Button(onClick = onUse, shapes = ButtonDefaults.shapes()) { Text("Use this model") }
                        }
                        OutlinedButton(onClick = onDelete, shapes = ButtonDefaults.shapes()) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Delete", modifier = Modifier.padding(start = 8.dp))
                        }
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
                        Text(
                            if (state is DownloadState.Failed) "Try again" else "Download",
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoChip(text: String) {
    AssistChip(
        onClick = {},
        label = { Text(text) },
        colors = AssistChipDefaults.assistChipColors(
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}
