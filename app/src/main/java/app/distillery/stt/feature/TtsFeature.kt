// SPDX-License-Identifier: GPL-3.0-or-later
// Slider(value = …) is deprecated on the Material 3 1.6 line; kept because it
// exists on every 1.5.x release this app targets.
@file:Suppress("DEPRECATION")

package app.distillery.stt.feature

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.font.FontFamily
import app.distillery.stt.tts.TtsLog
import app.distillery.stt.tts.ui.DirectPlayer
import kotlinx.coroutines.delay
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.distillery.stt.R
import app.distillery.stt.tts.TtsRuntime
import app.distillery.stt.tts.ui.EngineSpeaker
import app.distillery.stt.tts.ui.PronunciationsScreen
import app.distillery.stt.tts.ui.VoicesScreen
import app.distillery.stt.ui.components.BodyText
import app.distillery.stt.ui.components.SectionCard
import app.distillery.stt.ui.components.Tone
import app.distillery.stt.ui.screens.OssComponent
import app.distillery.stt.ui.screens.OssGroup
import java.util.Locale

/**
 * Text to speech: its screens, cards and license entries, kept together so the
 * rest of the UI only needs these few hooks.
 */
object TtsFeature {
    const val ENABLED = true
    const val VOICES_ROUTE = "voices"
    const val PRONUNCIATIONS_ROUTE = "pronunciations"

    val licenseGroups: List<OssGroup> = listOf(
        OssGroup(
            "Text to speech (voices downloaded on request)",
            listOf(
                OssComponent(
                    "kokoro", "Kokoro-82M and its English voices", "hexgrad",
                    "Apache-2.0", "Apache-2.0.txt",
                ),
                OssComponent(
                    "misaki", "misaki English lexicons", "hexgrad · Kokoro's own pronunciation dictionaries",
                    "Apache-2.0", "Apache-2.0-misaki.txt",
                ),
                OssComponent(
                    "ud-english-ewt", "Part-of-speech tagger", "Distillery's own, trained on the Universal Dependencies English Web Treebank (Stanford University)",
                    "CC-BY-SA-4.0", "CC-BY-SA-4.0-ud-english-ewt.txt",
                ),
                OssComponent(
                    "word-guesser", "Word guesser", "Distillery's own letter-to-sound model, trained on misaki's lexicons",
                    "Apache-2.0", "Apache-2.0-misaki.txt",
                ),
            ),
        ),
    )

    fun handlesRoute(route: String): Boolean = route == VOICES_ROUTE || route == PRONUNCIATIONS_ROUTE

    fun NavGraphBuilder.destinations(onBack: () -> Unit, onNavigate: (String) -> Unit) {
        composable(VOICES_ROUTE) {
            VoicesScreen(onBack = onBack, onOpenPronunciations = { onNavigate(PRONUNCIATIONS_ROUTE) })
        }
        composable(PRONUNCIATIONS_ROUTE) { PronunciationsScreen(onBack = onBack) }
    }

    fun LazyListScope.homeItems(onNavigate: (String) -> Unit) {
        item(key = "tts-home") { HomeCard(onOpenVoices = { onNavigate(VOICES_ROUTE) }) }
    }

    fun LazyListScope.testItems(onNavigate: (String) -> Unit) {
        item(key = "tts-test") {
            ListenCard(
                onOpenVoices = { onNavigate(VOICES_ROUTE) },
                onOpenPronunciations = { onNavigate(PRONUNCIATIONS_ROUTE) },
            )
        }
    }
}

// --------------------------------------------------------------------- home

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomeCard(onOpenVoices: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { TtsRuntime.get(context) }
    val installed by runtime.store.installed.collectAsStateWithLifecycle()
    val defaultVoice by runtime.settings.defaultVoice.collectAsStateWithLifecycle()
    var isDefaultEngine by remember { mutableStateOf(isDefaultEngine(context)) }
    LifecycleResumeEffect(Unit) {
        isDefaultEngine = isDefaultEngine(context)
        onPauseOrDispose { }
    }
    val ready = installed.isNotEmpty() && isDefaultEngine

    SectionCard(
        title = "Text to speech",
        icon = R.drawable.ic_volume_up,
        tone = if (ready) Tone.Good else Tone.Neutral,
    ) {
        BodyText(
            when {
                installed.isEmpty() -> "Download an English voice to use Distillery as your phone's text-to-speech engine. Speech is made on your phone."
                isDefaultEngine -> "Distillery reads text aloud for apps, with the ${defaultVoice.displayName} voice. ${installed.size} voice${if (installed.size == 1) "" else "s"} downloaded."
                else -> "${installed.size} voice${if (installed.size == 1) "" else "s"} downloaded. To use Distillery everywhere:"
            },
        )
        if (installed.isNotEmpty() && !isDefaultEngine) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "Tap Open text-to-speech settings.",
                    "Tap Preferred engine and choose “Distillery (Kokoro, on-device)”.",
                    "Come back here; this card shows a check.",
                ).forEachIndexed { i, step ->
                    Row {
                        Text("${i + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 10.dp, top = 2.dp))
                        BodyText(step)
                    }
                }
            }
        }
        if (installed.isEmpty()) {
            Button(onClick = onOpenVoices, shapes = ButtonDefaults.shapes()) { Text("Choose a voice") }
        } else {
            FilledTonalButton(onClick = onOpenVoices, shapes = ButtonDefaults.shapes()) { Text("Voices") }
            Button(
                onClick = { openTtsSettings(context) },
                enabled = !isDefaultEngine,
                shapes = ButtonDefaults.shapes(),
            ) {
                if (!isDefaultEngine) {
                    Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Text(
                    if (isDefaultEngine) "Distillery is the preferred engine" else "Open text-to-speech settings",
                    modifier = if (isDefaultEngine) Modifier else Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

private fun isDefaultEngine(context: Context): Boolean =
    runCatching { Settings.Secure.getString(context.contentResolver, "tts_default_synth") }
        .getOrNull() == context.packageName

private fun openTtsSettings(context: Context) {
    val intents = listOf(
        Intent("com.android.settings.TTS_SETTINGS"),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in intents) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
}

// --------------------------------------------------------------------- test

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ListenCard(onOpenVoices: () -> Unit, onOpenPronunciations: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { TtsRuntime.get(context) }
    val installed by runtime.store.installed.collectAsStateWithLifecycle()
    val defaultVoice by runtime.settings.defaultVoice.collectAsStateWithLifecycle()
    val speaker = remember { EngineSpeaker(context) }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }
    val scope = rememberCoroutineScope()
    val direct = remember { DirectPlayer(runtime.synth, scope) }
    LifecycleResumeEffect(Unit) {
        onPauseOrDispose {
            speaker.stop()
            direct.stop()
        }
    }

    var text by rememberSaveable {
        mutableStateOf("Distillery turns text into speech right on your phone. No internet needed, and nothing leaves the device.")
    }
    var rate by remember { mutableFloatStateOf(1f) }

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Text to speech", style = MaterialTheme.typography.titleLarge)
            BodyText("Speaks through android.speech.tts with Distillery as the engine, the way other apps call it.")
            if (installed.isEmpty()) {
                BodyText("No voice downloaded yet.")
                FilledTonalButton(onClick = onOpenVoices, shapes = ButtonDefaults.shapes()) { Text("Download a voice") }
                return@Column
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Text") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Speed ${String.format(Locale.US, "%.1f", rate)}×",
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.5f..2f, steps = 14)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val voice = defaultVoice.takeIf { it in installed } ?: installed.first()
                        speaker.speak(text, voice, rate)
                    },
                    enabled = speaker.ready && !speaker.speaking && text.isNotBlank(),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(painterResource(R.drawable.ic_volume_up), contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Listen", modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedButton(onClick = speaker::stop, enabled = speaker.speaking, shapes = ButtonDefaults.shapes()) {
                    Text("Stop")
                }
            }
            Text(
                buildString {
                    append(speaker.status)
                    speaker.firstAudioMs?.let { append(" · first audio ${seconds(it)}") }
                    speaker.totalMs?.let { append(" · total ${seconds(it)}") }
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BodyText("Voice: ${(defaultVoice.takeIf { it in installed } ?: installed.first()).displayName}. The first sentence after a pause also loads the model.")

            TextButton(onClick = onOpenPronunciations) { Text("Fix how a word is said") }

            // Kokoro in the app itself, without Android's TTS system.
            Text("Direct engine test", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            BodyText("Runs Kokoro inside the app and plays the audio, skipping Android's text-to-speech system.")
            FilledTonalButton(
                onClick = {
                    val voice = defaultVoice.takeIf { it in installed } ?: installed.first()
                    direct.play(voice, text, rate)
                },
                enabled = !direct.busy && text.isNotBlank(),
                shapes = ButtonDefaults.shapes(),
            ) { Text(if (direct.busy) "Working…" else "Test engine directly") }
            direct.status?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            EngineLog()
        }
    }
}

/** The engine's recent steps, the one in progress with a running timer, and Copy. */
@Composable
private fun EngineLog() {
    val context = LocalContext.current
    val entries by TtsLog.entries.collectAsStateWithLifecycle()
    val phase by TtsLog.phase.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(phase) {
        while (phase != null) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }

    Text("Engine log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    phase?.let { p ->
        Text(
            "Now: ${p.text} · ${seconds((now - p.sinceMs).coerceAtLeast(0))}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        val start = entries.firstOrNull()?.atMs ?: 0L
        Text(
            if (entries.isEmpty()) "Nothing yet." else entries.takeLast(25).joinToString("\n") {
                "+${seconds(it.atMs - start)}  ${it.text}"
            },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Distillery TTS log", TtsLog.asText()))
            },
            enabled = entries.isNotEmpty(),
            shapes = ButtonDefaults.shapes(),
        ) {
            Icon(painterResource(R.drawable.ic_copy), contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Copy log", modifier = Modifier.padding(start = 8.dp))
        }
        TextButton(onClick = TtsLog::clear, enabled = entries.isNotEmpty()) { Text("Clear") }
    }
}

private fun seconds(ms: Long) = String.format(Locale.US, "%.2f s", ms / 1000f)
