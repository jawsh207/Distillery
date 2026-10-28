// SPDX-License-Identifier: GPL-3.0-or-later
// ListItem(headlineContent = …) and Slider(value = …) are deprecated on the
// Material 3 1.6 line in favour of new overloads; they're kept here because they
// exist on every 1.5.x release this app targets.
@file:Suppress("DEPRECATION")

package app.distillery.stt.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.distillery.stt.Notifications
import app.distillery.stt.R
import app.distillery.stt.DistilleryApplication
import app.distillery.stt.engine.Diagnostics
import app.distillery.stt.engine.DistillerySettings
import app.distillery.stt.engine.Problem
import app.distillery.stt.ui.VoiceInputSetup
import app.distillery.stt.ui.components.BodyText
import app.distillery.stt.ui.components.SectionCard
import app.distillery.stt.ui.components.Tone
import app.distillery.stt.ui.components.DistilleryScreen
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    onOpenModels: () -> Unit,
    onOpenTest: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as DistilleryApplication
    val installed by app.modelStore.installed.collectAsStateWithLifecycle()
    val settingsVersion by app.settings.changes.collectAsStateWithLifecycle(0)

    var micGranted by remember { mutableStateOf(hasMic(context)) }
    var isDefault by remember { mutableStateOf(VoiceInputSetup.isDefault(context)) }
    LifecycleResumeEffect(Unit) {
        micGranted = hasMic(context)
        isDefault = VoiceInputSetup.isDefault(context)
        if (micGranted && installed.isNotEmpty()) Notifications.clearAttention(context)
        onPauseOrDispose { }
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        micGranted = it
    }

    val problem by Diagnostics.lastProblem.collectAsStateWithLifecycle()
    var modelCheck by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val activeModel = remember(settingsVersion, installed) {
        app.modelStore.resolveModel(app.settings.chosenModel)
    }

    DistilleryScreen(title = "Distillery", subtitle = "On-device speech to text, powered by Moonshine") {
        // -------------------------------------------------- last failure, if any
        if (problem != null || modelCheck != null) {
            item {
                ProblemCard(
                    problem = problem,
                    check = modelCheck,
                    checking = checking,
                    canCheck = activeModel != null,
                    onCheck = {
                        val model = activeModel ?: return@ProblemCard
                        checking = true
                        scope.launch {
                            modelCheck = try {
                                val ms = app.engine.check(model)
                                Diagnostics.clear()
                                "${model.displayName} model loaded in $ms ms. Voice input should work now."
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                Diagnostics.report("Couldn't load the ${model.displayName} model", e)
                                null
                            } finally {
                                checking = false
                            }
                        }
                    },
                    onDismiss = {
                        Diagnostics.clear()
                        modelCheck = null
                    },
                )
            }
        }

        // ---------------------------------------------------------- microphone
        item {
            SectionCard(
                title = "Microphone",
                icon = R.drawable.ic_mic,
                tone = if (micGranted) Tone.Good else Tone.Action,
            ) {
                if (micGranted) {
                    BodyText("Allowed. Distillery records only while an app is asking for voice input, and the audio never leaves your phone.")
                } else {
                    BodyText("Distillery records on behalf of the app or keyboard that asks for voice input, so it needs microphone access too.")
                }
                Button(
                    onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    enabled = !micGranted,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(if (micGranted) "Microphone allowed" else "Allow microphone")
                }
                if (!micGranted) {
                    TextButton(onClick = { VoiceInputSetup.openAppSettings(context) }) {
                        Text("Open app settings")
                    }
                }
            }
        }

        // --------------------------------------------------------------- model
        item {
            SectionCard(
                title = "Speech model",
                icon = R.drawable.ic_graphic_eq,
                tone = if (activeModel != null) Tone.Good else Tone.Action,
            ) {
                if (activeModel != null) {
                    BodyText(
                        "Using Moonshine ${activeModel.displayName} (English, streaming, MIT). " +
                            "${installed.size} of 3 models downloaded.",
                    )
                    FilledTonalButton(onClick = onOpenModels, shapes = ButtonDefaults.shapes()) { Text("Change model") }
                } else {
                    BodyText("Download a model to get started. It's a one-time download; recognition runs offline after that.")
                    Button(onClick = onOpenModels, shapes = ButtonDefaults.shapes()) { Text("Choose a model") }
                }
            }
        }

        // ------------------------------------------------------- default input
        item {
            DefaultVoiceInputCard(isDefault = isDefault, context = context)
        }

        // ------------------------------------------------------------ dictation
        item {
            DictationSettingsCard(app.settings)
        }

        // ----------------------------------------------------------- recordings
        item {
            SectionCard(title = "Recordings", icon = R.drawable.ic_description) {
                BodyText(
                    "Apps can also hand Distillery an audio recording to transcribe. It's read to the end " +
                        "and transcribed on this phone, without using the microphone.",
                )
            }
        }

        // ---------------------------------------------------------------- more
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    NavRow("Test", "Dictation and recordings, the way apps call Distillery", R.drawable.ic_mic, onOpenTest)
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                    NavRow("Open-source licenses", "Distillery, Moonshine, models and libraries", R.drawable.ic_description, onOpenLicenses)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DefaultVoiceInputCard(isDefault: Boolean, context: Context) {
    var showHelp by rememberSaveable { mutableStateOf(false) }
    SectionCard(
        title = "Default voice input",
        icon = if (isDefault) R.drawable.ic_check_circle else R.drawable.ic_info,
        tone = if (isDefault) Tone.Good else Tone.Action,
    ) {
        if (isDefault) {
            BodyText("Distillery is your phone's voice input. Keyboards and apps that use Android's speech recognizer now use Distillery, with no popup.")
        } else {
            BodyText("Make Distillery the voice input so keyboards and apps use it:")
            Steps(
                "Tap Open language settings.",
                "Under Speech, tap Voice input.",
                "Choose “Distillery (Moonshine, on-device)”.",
                "Come back here; this card shows a check.",
            )
            Button(onClick = { VoiceInputSetup.openVoiceInputSettings(context) }, shapes = ButtonDefaults.shapes()) {
                Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Open language settings", modifier = Modifier.padding(start = 8.dp))
            }
        }
        TextButton(onClick = { showHelp = !showHelp }) {
            Text(if (showHelp) "Hide details" else "Not listed, or the choice doesn't stick?")
        }
        AnimatedVisibility(showHelp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BodyText(
                    "It's under Settings › System › Languages (Language & region on newer versions) › Speech › Voice input. " +
                        "That's a different setting from the default digital assistant, which Distillery isn't. " +
                        "On some phones the digital assistant controls voice input and resets it; set Digital assistant app to None, " +
                        "or set it from a computer with ADB:",
                )
                CodeBlock(VoiceInputSetup.adbCommand(context), context)
                BodyText(
                    "A keyboard or app can also use Distillery directly, whatever the default, by naming it when it creates the recognizer:",
                )
                CodeBlock(VoiceInputSetup.directUseSnippet(context), context)
                BodyText(
                    "Apps that call createOnDeviceSpeechRecognizer() always get the recognizer chosen by the phone's maker; " +
                        "Android doesn't let users or apps change that one.",
                )
            }
        }
    }
}

@Composable
private fun DictationSettingsCard(settings: DistillerySettings) {
    var pause by remember { mutableFloatStateOf(settings.pauseToStopMs / 1000f) }
    SectionCard(title = "Dictation", icon = R.drawable.ic_stop) {
        Text("Stop after a pause", style = MaterialTheme.typography.titleMedium)
        BodyText(
            "Dictation ends on its own after about " +
                String.format(Locale.US, "%.1f", pause + 0.5f) +
                " seconds of quiet. Apps can ask for a different length.",
        )
        Slider(
            value = pause,
            onValueChange = { pause = it },
            onValueChangeFinished = { settings.pauseToStopMs = (pause * 1000).toLong() },
            valueRange = DistillerySettings.MIN_PAUSE_MS / 1000f..DistillerySettings.MAX_PAUSE_MS / 1000f,
        )
    }
}

@Composable
private fun Steps(vararg steps: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, step ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    "${i + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(end = 10.dp, top = 2.dp),
                )
                BodyText(step)
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String, context: Context) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Distillery", code))
                },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Icon(painterResource(R.drawable.ic_copy), contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Copy", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun NavRow(title: String, subtitle: String, icon: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    )
}

private fun hasMic(context: Context) =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** The reason the last voice input request failed, with a way to check the model. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProblemCard(
    problem: Problem?,
    check: String?,
    checking: Boolean,
    canCheck: Boolean,
    onCheck: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    SectionCard(
        title = if (problem != null) "Voice input failed" else "Model check",
        icon = if (problem != null) R.drawable.ic_warning else R.drawable.ic_check_circle,
        tone = if (problem != null) Tone.Action else Tone.Good,
    ) {
        if (problem != null) {
            Text(problem.summary, style = MaterialTheme.typography.titleMedium)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    problem.detail,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        } else if (check != null) {
            BodyText(check)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (problem != null && canCheck) {
                Button(onClick = onCheck, enabled = !checking, shapes = ButtonDefaults.shapes()) {
                    Text(if (checking) "Checking…" else "Check model")
                }
            }
            if (problem != null) {
                OutlinedButton(
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                            ClipData.newPlainText("Distillery error", "${problem.summary}\n${problem.detail}"),
                        )
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text("Copy") }
            }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
