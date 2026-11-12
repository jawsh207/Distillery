// SPDX-License-Identifier: Apache-2.0
package app.distillery.stt.transcribe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.distillery.stt.DistilleryApplication
import app.distillery.stt.Notifications
import app.distillery.stt.R
import app.distillery.stt.engine.SttModel
import app.distillery.stt.feature.TtsFeature
import app.distillery.stt.media.MediaJobs
import app.distillery.stt.ui.MainActivity
import app.distillery.stt.ui.components.BodyText
import app.distillery.stt.ui.components.DistilleryScreen
import app.distillery.stt.ui.components.SectionCard
import app.distillery.stt.ui.components.Tone
import app.distillery.stt.ui.theme.DistilleryTheme

/**
 * Turns an audio or video file into text. Reached by sharing a file to
 * Distillery, or from the Speech to text tab.
 */
class TranscribeActivity : ComponentActivity() {

    private var shared by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) shared = sharedFile(intent)
        setContent {
            DistilleryTheme {
                TranscribeScreen(initial = shared, onDiscard = { shared = null }, onBack = ::finish)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedFile(intent)?.let { shared = it }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        Notifications.clearWorkDone(this)
    }

    override fun onStop() {
        visible = false
        super.onStop()
    }

    private fun sharedFile(intent: Intent?): Uri? =
        if (intent?.action == Intent.ACTION_SEND) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else null

    companion object {
        /** Whether this screen is showing, so a finished transcript only notifies otherwise. */
        @Volatile
        var visible = false
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun TranscribeScreen(initial: Uri?, onDiscard: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as DistilleryApplication
    val state by FileTranscription.state.collectAsStateWithLifecycle()
    val otherJob by MediaJobs.running.collectAsStateWithLifecycle()
    val installed by app.modelStore.installed.collectAsStateWithLifecycle()
    var picked by rememberSaveable { mutableStateOf<Uri?>(null) }
    val file = picked ?: initial.takeIf { state !is FileTranscription.State.Running }
    var model by rememberSaveable {
        mutableStateOf(app.settings.chosenModel.takeIf { it in installed } ?: installed.firstOrNull())
    }
    if (model !in installed) model = installed.firstOrNull()
    var timestamps by rememberSaveable { mutableStateOf(false) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            FileTranscription.clear()
            picked = uri
        }
    }
    val choose = { pick.launch(arrayOf("audio/*", "video/*")) }
    val discard = {
        FileTranscription.clear()
        picked = null
        onDiscard()
    }

    // A newly shared file replaces whatever was on screen.
    LaunchedEffect(initial) {
        if (initial == null) return@LaunchedEffect
        if (FileTranscription.state.value is FileTranscription.State.Running) {
            Toast.makeText(context, "Finish or cancel the current transcription first", Toast.LENGTH_LONG).show()
            onDiscard()
        } else {
            FileTranscription.clear()
            picked = null
        }
    }

    DistilleryScreen(title = "Transcribe", subtitle = "Audio and video files, on your phone", onBack = onBack) {
        if (installed.isEmpty()) {
            item(key = "no-model") {
                SectionCard("Download a speech model first", R.drawable.ic_download, tone = Tone.Action) {
                    BodyText("Transcribing uses the same speech models as dictation.")
                    Button(onClick = { openDownloads(context) }, shapes = ButtonDefaults.shapes()) { Text("Go to Downloads") }
                }
            }
            return@DistilleryScreen
        }

        when (val s = state) {
            is FileTranscription.State.Running -> item(key = "running") {
                SectionCard("Transcribing ${s.name}", R.drawable.ic_graphic_eq) {
                    if (s.fraction != null) {
                        LinearWavyProgressIndicator(progress = { s.fraction }, modifier = Modifier.fillMaxWidth())
                        BodyText("${(s.fraction * 100).toInt()}% · ${s.model.displayName} model")
                    } else {
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                        BodyText("${s.model.displayName} model")
                    }
                    val live = FileTranscription.render(s.segments, timestamps = false)
                    if (live.isNotEmpty()) {
                        Text(
                            live.takeLast(600),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BodyText("You can leave this screen; it carries on, and a notification says when it's done.")
                    OutlinedButton(onClick = MediaJobs::cancel, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                }
            }

            is FileTranscription.State.Done -> {
                item(key = "done") {
                    val text = FileTranscription.render(s.segments, timestamps)
                    SectionCard(s.name, R.drawable.ic_description, tone = Tone.Good) {
                        BodyText(
                            "${FileTranscription.clock(s.seconds)} of audio, transcribed in " +
                                "${FileTranscription.clock(s.tookMs / 1000f)} with the ${s.model.displayName} model.",
                        )
                        if (text.isEmpty()) {
                            BodyText("No speech was found in this file.")
                            TextButton(onClick = discard) { Text("Discard") }
                        } else {
                            SelectionContainer {
                                Text(text, style = MaterialTheme.typography.bodyLarge)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Timestamps", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                Switch(checked = timestamps, onCheckedChange = { timestamps = it })
                            }
                            TranscriptActions(name = s.name, text = text, onDiscard = discard)
                        }
                    }
                }
                item(key = "another") {
                    TextButton(onClick = choose) { Text("Transcribe another file") }
                }
            }

            is FileTranscription.State.Failed -> item(key = "failed") {
                SectionCard("Couldn't transcribe ${s.name}", R.drawable.ic_warning, tone = Tone.Action) {
                    BodyText(s.message)
                    Button(onClick = { discard(); choose() }, shapes = ButtonDefaults.shapes()) {
                        Text("Choose another file")
                    }
                    TextButton(onClick = discard) { Text("Discard") }
                }
            }

            FileTranscription.State.Idle -> item(key = "start") {
                val name = remember(file) { file?.let { displayName(context, it) } }
                SectionCard(name ?: "Choose a recording", R.drawable.ic_graphic_eq) {
                    BodyText(
                        if (file == null) {
                            "Pick an audio or video file, or share one to Distillery from another app. " +
                                "It's transcribed on your phone; nothing is uploaded."
                        } else {
                            "Choose a model. Bigger models are more accurate but slower."
                        },
                    )
                    if (file != null) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SttModel.entries.filter { it in installed }.forEach { m ->
                                FilterChip(
                                    selected = m == model,
                                    onClick = { model = m },
                                    label = { Text(m.displayName) },
                                )
                            }
                        }
                        Button(
                            onClick = {
                                val m = model ?: return@Button
                                if (!FileTranscription.start(context, file, name ?: "the file", m)) {
                                    Toast.makeText(context, "Wait for ${otherJob?.title?.lowercase() ?: "the current job"} to finish", Toast.LENGTH_LONG).show()
                                } else {
                                    picked = null
                                }
                            },
                            enabled = model != null,
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("Transcribe") }
                        TextButton(onClick = choose) { Text("Choose a different file") }
                    } else {
                        Button(onClick = choose, shapes = ButtonDefaults.shapes()) { Text("Choose a file") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun TranscriptActions(name: String, text: String, onDiscard: () -> Unit) {
    val context = LocalContext.current
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
            }.onSuccess {
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Couldn't save: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Transcript", text))
        }, shapes = ButtonDefaults.shapes()) {
            Icon(painterResource(R.drawable.ic_copy), contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Copy", modifier = Modifier.padding(start = 8.dp))
        }
        FilledTonalButton(onClick = {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
                .putExtra(Intent.EXTRA_SUBJECT, name)
            context.startActivity(Intent.createChooser(send, null))
        }, shapes = ButtonDefaults.shapes()) {
            Icon(painterResource(R.drawable.ic_share), contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Share", modifier = Modifier.padding(start = 8.dp))
        }
        FilledTonalButton(onClick = { save.launch(name.substringBeforeLast('.') + ".txt") }, shapes = ButtonDefaults.shapes()) {
            Icon(painterResource(R.drawable.ic_save), contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Save", modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = onDiscard, shapes = ButtonDefaults.shapes()) {
            Icon(painterResource(R.drawable.ic_delete), contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Discard", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

private fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Recording"

/** Opens the app on the Downloads tab. */
internal fun openDownloads(context: Context) {
    context.startActivity(
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, TtsFeature.VOICES_ROUTE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
    )
}
