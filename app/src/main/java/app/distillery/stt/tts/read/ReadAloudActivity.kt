// SPDX-License-Identifier: Apache-2.0
package app.distillery.stt.tts.read

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.distillery.stt.Notifications
import app.distillery.stt.R
import app.distillery.stt.media.MediaJobs
import app.distillery.stt.transcribe.openDownloads
import app.distillery.stt.tts.KokoroVoice
import app.distillery.stt.tts.TtsRuntime
import app.distillery.stt.ui.components.BodyText
import app.distillery.stt.ui.components.DistilleryScreen
import app.distillery.stt.ui.components.SectionCard
import app.distillery.stt.ui.components.Tone
import app.distillery.stt.ui.theme.DistilleryTheme
import java.util.Locale

/**
 * Reads text aloud. Reached from "Read aloud" in any app's text selection
 * menu, by sharing text to Distillery, or from the Text to speech tab.
 */
class ReadAloudActivity : ComponentActivity() {

    /** Text that arrived with an intent and hasn't been loaded yet. */
    private var incoming by mutableStateOf<Incoming?>(null)

    data class Incoming(val text: String?, val title: String?, val linkOnly: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) incoming = read(intent)
        setContent {
            DistilleryTheme {
                ReadAloudScreen(
                    incoming = incoming,
                    onIncomingHandled = { incoming = null },
                    onBack = ::finish,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)?.let { incoming = it }
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

    private fun read(intent: Intent?): Incoming? {
        val text: String? = when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                ?: intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(::readTextFile)
            else -> return null
        }
        val title = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: intent.getStringExtra(Intent.EXTRA_TITLE)
        val trimmed = text?.trim().orEmpty()
        return Incoming(trimmed.ifEmpty { null }, title, linkOnly = LINK.matches(trimmed))
    }

    /** A shared text file, up to [MAX_FILE_BYTES]. */
    private fun readTextFile(uri: Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readNBytes(MAX_FILE_BYTES)
            String(bytes, Charsets.UTF_8)
        }
    }.getOrNull()

    companion object {
        /** Whether this screen is showing, so a saved file only notifies otherwise. */
        @Volatile
        var visible = false

        private const val MAX_FILE_BYTES = 1 shl 20
        private val LINK = Regex("""^https?://\S+$""")
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ReadAloudScreen(incoming: ReadAloudActivity.Incoming?, onIncomingHandled: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { TtsRuntime.get(context) }
    val installed by runtime.store.installed.collectAsStateWithLifecycle()
    val state by ReadAloud.state.collectAsStateWithLifecycle()
    val export by AudioExport.state.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf(false) }
    var linkOnly by rememberSaveable { mutableStateOf(false) }
    var askFormat by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(incoming, installed) {
        val inc = incoming ?: return@LaunchedEffect
        if (installed.isEmpty()) return@LaunchedEffect
        onIncomingHandled()
        linkOnly = inc.linkOnly
        if (!inc.linkOnly && inc.text != null) {
            editing = false
            ReadAloud.load(context, inc.text, inc.title)
            ReadAloud.play(context)
        }
    }

    val current = state
    // Keep the sentence being read in view.
    LaunchedEffect(current?.index, current?.status) {
        val s = current ?: return@LaunchedEffect
        if (editing || s.status != ReadAloud.Status.Playing) return@LaunchedEffect
        val paragraph = s.sentences.getOrNull(s.index)?.paragraph ?: return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo.map { it.key }
        if ("p$paragraph" !in visible) {
            // The cards above the text come first in the list.
            val above = listOf(linkOnly, export != AudioExport.State.Idle, s.error != null).count { it }
            val before = s.sentences.map { it.paragraph }.distinct().indexOf(paragraph)
            listState.animateScrollToItem(above + before.coerceAtLeast(0))
        }
    }

    fun startExport(uri: Uri?, format: AudioExport.Format) {
        val s = ReadAloud.state.value
        if (uri != null && s != null) {
            val name = "${fileTitle(s)}.${format.extension}"
            if (!AudioExport.start(context, uri, name, format, s.sentences.map { it.text }, s.voice, s.speed)) {
                Toast.makeText(context, "Wait for the current transcription or export to finish", Toast.LENGTH_LONG).show()
            }
        }
    }
    val saveM4a = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(AudioExport.Format.M4A.mime)) {
        startExport(it, AudioExport.Format.M4A)
    }
    val saveWav = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(AudioExport.Format.WAV.mime)) {
        startExport(it, AudioExport.Format.WAV)
    }

    val reading = current != null && !editing
    DistilleryScreen(
        title = "Read aloud",
        subtitle = current?.title?.takeIf { reading } ?: "Text to speech, on your phone",
        onBack = onBack,
        listState = listState,
        actions = {
            if (reading) {
                IconButton(onClick = {
                    ReadAloud.stopPlayback(context)
                    draft = current!!.text
                    editing = true
                }) { Icon(painterResource(R.drawable.ic_edit), contentDescription = "Edit text") }
                IconButton(onClick = { askFormat = true }) {
                    Icon(painterResource(R.drawable.ic_save), contentDescription = "Save as audio")
                }
            }
        },
        bottomBar = { if (reading && installed.isNotEmpty()) PlayerBar(current!!, installed) },
    ) {
        if (installed.isEmpty()) {
            item(key = "no-voice") {
                SectionCard("Download a voice first", R.drawable.ic_download, tone = Tone.Action) {
                    BodyText("Read aloud uses the same Kokoro voices as the text-to-speech engine.")
                    Button(onClick = { openDownloads(context) }, shapes = ButtonDefaults.shapes()) { Text("Go to Downloads") }
                }
            }
            return@DistilleryScreen
        }

        if (linkOnly) {
            item(key = "link") {
                SectionCard("That's a link", R.drawable.ic_info) {
                    BodyText(
                        "Distillery reads text, not web pages, and doesn't go online to fetch them. " +
                            "Open the page, select the text you want, and choose Read aloud.",
                    )
                    TextButton(onClick = { linkOnly = false }) { Text("OK") }
                }
            }
        }

        exportStatus(export)

        if (!reading) {
            item(key = "editor") {
                SectionCard("What should Distillery read?", R.drawable.ic_volume_up) {
                    BodyText("Paste or type text here. In other apps, select text and choose Read aloud, or share it to Distillery.")
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        minLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                ReadAloud.load(context, draft.trim(), current?.title?.takeIf { draft == current.text })
                                editing = false
                                ReadAloud.play(context)
                            },
                            enabled = draft.isNotBlank(),
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("Read aloud") }
                        OutlinedButton(onClick = {
                            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                            clip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { draft = it }
                        }, shapes = ButtonDefaults.shapes()) { Text("Paste") }
                        if (current != null) TextButton(onClick = { editing = false }) { Text("Cancel") }
                    }
                }
            }
            return@DistilleryScreen
        }

        val s = current!!
        s.error?.let { error ->
            item(key = "error") {
                SectionCard("Couldn't read aloud", R.drawable.ic_warning, tone = Tone.Action) { BodyText(error) }
            }
        }
        val paragraphs = s.sentences.withIndex().groupBy { it.value.paragraph }
        paragraphs.forEach { (paragraph, sentences) ->
            item(key = "p$paragraph") {
                val highlight = MaterialTheme.colorScheme.primaryContainer
                val onHighlight = MaterialTheme.colorScheme.onPrimaryContainer
                val text = buildAnnotatedString {
                    sentences.forEachIndexed { k, (index, sentence) ->
                        if (k > 0) append(' ')
                        val style = if (index == s.index && s.status != ReadAloud.Status.Ready) {
                            SpanStyle(background = highlight, color = onHighlight)
                        } else {
                            SpanStyle()
                        }
                        withLink(
                            LinkAnnotation.Clickable(
                                "s$index",
                                styles = TextLinkStyles(style = style),
                            ) { ReadAloud.seek(context, index) },
                        ) { append(sentence.text) }
                    }
                }
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }

    if (askFormat) {
        AlertDialog(
            onDismissRequest = { askFormat = false },
            icon = { Icon(painterResource(R.drawable.ic_save), contentDescription = null) },
            title = { Text("Save as audio") },
            text = {
                Text(
                    "The whole text, with the ${current?.voice?.displayName} voice at ${speedLabel(current?.speed ?: 1f)}. " +
                        "M4A is small and plays almost everywhere; WAV is about ten times larger and plays on anything.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askFormat = false
                    current?.let { saveM4a.launch("${fileTitle(it)}.m4a") }
                }) { Text("M4A") }
            },
            dismissButton = {
                TextButton(onClick = {
                    askFormat = false
                    current?.let { saveWav.launch("${fileTitle(it)}.wav") }
                }) { Text("WAV") }
            },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.exportStatus(export: AudioExport.State) {
    when (export) {
        is AudioExport.State.Saving -> item(key = "export") {
            SectionCard("Saving audio", R.drawable.ic_save) {
                LinearWavyProgressIndicator(progress = { export.fraction }, modifier = Modifier.fillMaxWidth())
                BodyText("${(export.fraction * 100).toInt()}%. You can leave this screen; it carries on.")
                OutlinedButton(onClick = MediaJobs::cancel) { Text("Cancel") }
            }
        }
        is AudioExport.State.Saved -> item(key = "export") {
            SectionCard("Saved ${export.name}", R.drawable.ic_save, tone = Tone.Good) {
                TextButton(onClick = AudioExport::clear) { Text("OK") }
            }
        }
        is AudioExport.State.Failed -> item(key = "export") {
            SectionCard("Couldn't save the audio", R.drawable.ic_warning, tone = Tone.Action) {
                BodyText(export.message)
                TextButton(onClick = AudioExport::clear) { Text("OK") }
            }
        }
        AudioExport.State.Idle -> Unit
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerBar(state: ReadAloud.State, installed: Set<KokoroVoice>) {
    val context = LocalContext.current
    var voices by remember { mutableStateOf(false) }
    val playing = state.status == ReadAloud.Status.Playing || state.status == ReadAloud.Status.Preparing
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                when (state.status) {
                    ReadAloud.Status.Preparing -> "Preparing…"
                    ReadAloud.Status.Finished -> "Finished"
                    else -> "Sentence ${state.index + 1} of ${state.sentences.size}"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconButton(onClick = { ReadAloud.previous(context) }) {
                    Icon(painterResource(R.drawable.ic_skip_previous), contentDescription = "Previous sentence")
                }
                FilledIconButton(
                    onClick = { ReadAloud.toggle(context) },
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = if (playing) "Pause" else "Play",
                        modifier = Modifier.size(32.dp),
                    )
                }
                IconButton(onClick = { ReadAloud.next(context) }) {
                    Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Next sentence")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReadAloud.SPEEDS.forEach { speed ->
                    FilterChip(
                        selected = state.speed == speed,
                        onClick = { ReadAloud.setSpeed(context, speed) },
                        label = { Text(speedLabel(speed)) },
                    )
                }
            }
            Box {
                FilledTonalButton(onClick = { voices = true }, shapes = ButtonDefaults.shapes()) {
                    Text("Voice: ${state.voice.displayName}")
                }
                DropdownMenu(expanded = voices, onDismissRequest = { voices = false }) {
                    KokoroVoice.entries.filter { it in installed }.forEach { voice ->
                        DropdownMenuItem(
                            text = { Text("${voice.displayName} · ${voice.accent.label}") },
                            onClick = {
                                voices = false
                                ReadAloud.setVoice(context, voice)
                            },
                            modifier = if (voice == state.voice) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

private fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else String.format(Locale.US, "%s×", speed.toString().trimEnd('0'))

/** A file name from the title or the first words, without characters file systems refuse. */
private fun fileTitle(state: ReadAloud.State): String {
    val base = state.title?.takeIf { it.isNotBlank() }
        ?: state.sentences.firstOrNull()?.text?.split(' ')?.take(6)?.joinToString(" ")
        ?: "Speech"
    return base.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), " ").trim().take(60).ifEmpty { "Speech" }
}
