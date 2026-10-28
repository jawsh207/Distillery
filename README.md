<img src="fastlane/metadata/android/en-US/images/icon.png" alt="" width="96" align="right">

# Distillery

On-device speech-to-text for Android, powered by [Moonshine](https://github.com/moonshine-ai/moonshine).

Distillery is a system **speech recognition service** (`android.speech.RecognitionService`). Any app
that uses Android's `SpeechRecognizer` can use it: keyboards and apps get live dictation in the
background with no popup or app switch, and apps can hand it an audio recording to transcribe.

- Fully on device: no network during recognition, no Google services, no account.
- Live dictation with partial results while you speak, and automatic stop after a pause.
- Recording transcription: AMR, AAC/M4A/3GP, MP3, Ogg Opus/Vorbis, FLAC, WAV or raw PCM.
- Moonshine English streaming models: **Tiny**, **Small** (default) or **Medium**.
- Material 3 Expressive UI with the system's dynamic colors.
- Android 16+ (minSdk 36), Kotlin, Jetpack Compose. Package `app.distillery.stt`.
- GPL-3.0-or-later. Every dependency and model is OSI-licensed; all licenses are shown in the app.

## Using Distillery from another app

### Live dictation

Standard `SpeechRecognizer` code works once Distillery is the default voice input:

```kotlin
val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
recognizer.setRecognitionListener(listener)
recognizer.startListening(
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true),
)
```

To use Distillery whatever the default is, name it:

```kotlin
SpeechRecognizer.createSpeechRecognizer(
    context,
    ComponentName("app.distillery.stt", "app.distillery.stt.service.DistilleryRecognitionService"),
)
```

Apps targeting Android 11+ need this in their manifest to see recognition services:

```xml
<queries>
    <intent><action android:name="android.speech.RecognitionService" /></intent>
</queries>
```

### Transcribing a recording

Pass the file as `EXTRA_AUDIO_SOURCE`. Leave out the encoding extras for an encoded file; Distillery
recognizes the format and decodes it with the phone's own decoders.

```kotlin
val file = ParcelFileDescriptor.open(audioFile, ParcelFileDescriptor.MODE_READ_ONLY)
recognizer.setRecognitionListener(object : RecognitionListener {
    override fun onResults(results: Bundle) {
        val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        file.close()
        // use the transcript
    }
    override fun onError(error: Int) { file.close() /* retry later on ERROR_RECOGNIZER_BUSY */ }
    // ...
})
recognizer.startListening(
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, file),
)
```

How recordings are handled:

- The whole recording is transcribed. Pauses and leading silence don't end it (unless the caller sets
  `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS`), and there's no no-speech timeout. A recording
  with no speech ends with `ERROR_NO_MATCH`.
- It's read as fast as the phone can recognize it, not in real time.
- 8 kHz telephone audio is fine; Moonshine resamples internally. Stereo is downmixed.
- Encoded audio may also arrive over a pipe; Distillery copies it to a private temporary file for the
  decoder and deletes it when done.
- For raw PCM, set `EXTRA_AUDIO_SOURCE_ENCODING` (8-bit, 16-bit or float),
  `EXTRA_AUDIO_SOURCE_SAMPLING_RATE` and `EXTRA_AUDIO_SOURCE_CHANNEL_COUNT`, as the platform documents.
- `EXTRA_SEGMENTED_SESSION` with `EXTRA_AUDIO_SOURCE` returns one `onSegmentResults` per phrase, then
  `onEndOfSegmentedSession`, if you want the text broken up.
- No microphone is opened. Distillery creates the caller's attribution context for these requests, which
  should let callers without `RECORD_AUDIO` use it; verify this on a device if your app lacks the
  permission.
- One session runs at a time. If dictation is in progress, the request fails with
  `ERROR_RECOGNIZER_BUSY`; retry later.

### What Distillery sends back

| Callback | When |
| --- | --- |
| `onReadyForSpeech` | Audio source is open. Microphone audio is buffered from here even while the model loads. |
| `onRmsChanged` | Every ~50 ms, roughly −2 to 10 dB. |
| `onBeginningOfSpeech` | Moonshine opened the first phrase. |
| `onPartialResults` | Each time the running text changes (only with `EXTRA_PARTIAL_RESULTS`). Holds the whole text so far, not a delta. |
| `onEndOfSpeech` | Pause detected, `stopListening()` called, or the recording ended. |
| `onResults` | Final text, one candidate in `RESULTS_RECOGNITION`. |
| `onSegmentResults` / `onEndOfSegmentedSession` | Instead of `onResults` when `EXTRA_SEGMENTED_SESSION` is set. |
| `onError` | See below. Exactly one of `onResults`/`onError`/`onEndOfSegmentedSession` ends each session, and nothing is sent after `cancel()`. |

| Error | Meaning |
| --- | --- |
| `ERROR_SPEECH_TIMEOUT` | Live dictation: nobody spoke within 6 s. |
| `ERROR_NO_MATCH` | Finished, but no words were recognized. |
| `ERROR_LANGUAGE_NOT_SUPPORTED` | `EXTRA_LANGUAGE` isn't English. |
| `ERROR_LANGUAGE_UNAVAILABLE` | No model downloaded yet (Distillery also posts a notification). |
| `ERROR_INSUFFICIENT_PERMISSIONS` | Live dictation: the caller or Distillery lacks microphone permission. |
| `ERROR_AUDIO` | The microphone failed, or the recording couldn't be read or decoded. |
| `ERROR_RECOGNIZER_BUSY` | Another session is running. |
| `ERROR_SERVER` | The engine failed unexpectedly. |

Distillery also answers `checkRecognitionSupport` and `triggerModelDownload`.

## Making Distillery the default voice input

The app walks through this. Open the system speech settings and pick
**Distillery (Moonshine, on-device)**:

Settings › System › **Languages** (called **Language & region** on newer Android 16 releases and on
GrapheneOS) › **Speech** › **Voice input**.

This is not the *Default digital assistant* setting, which lists only full assistant apps. Android
has no shortcut that opens Voice input directly, so the app's button opens the Languages page.

If the phone's digital assistant keeps resetting it, set the assistant to None, or use ADB:

```sh
adb shell settings put secure voice_recognition_service app.distillery.stt/.service.DistilleryRecognitionService
```

### About `createOnDeviceSpeechRecognizer()`

Android routes `SpeechRecognizer.createOnDeviceSpeechRecognizer()` to the single service named by the
phone maker in `config_defaultOnDeviceSpeechRecognitionService`; apps and users can't change it (the
platform only allows a 60-second override for testing, behind a system permission). Distillery is a
complete on-device recognizer and works if a ROM points that setting at it, but on a stock phone apps
should use `createSpeechRecognizer()`, by default or by component as above.

## How it works

```
calling app ──SpeechRecognizer──▶ system_server ──binds──▶ DistilleryRecognitionService
                                                            │
                          RecognitionSession (one per startListening)
          ┌─────────────────────────────┴───────────────────────────────┐
 audio reader (IO thread)                                  recognizer (Moonshine thread)
 microphone via the caller's attribution context,          Transcriber.addAudio → phrase events
 or a recording decoded with MediaExtractor/MediaCodec     → onBeginningOfSpeech / onPartialResults
 → frames queue, onRmsChanged                              Endpointer → stop → onResults
```

- `service/DistilleryRecognitionService.kt`: the Android-facing service (request parsing, permission and
  model checks, support and download queries).
- `service/RecognitionSession.kt` runs one session; `Endpointer.kt` decides when speech is over;
  `TranscriptBuffer.kt` assembles Moonshine's phrases into text.
- `service/AudioInput.kt` reads the microphone or raw PCM; `RecordingInput.kt` detects and decodes
  recordings.
- `engine/SpeechEngine.kt` keeps the loaded model in memory between sessions (released after 3 idle
  minutes) and confines every Moonshine call to one thread.
- `engine/ModelLicensePolicy.kt` allows only the MIT-licensed English streaming models and checks each
  download manifest before fetching anything.

## Models and licensing

| Model | Parameters | WER (Open ASR avg.) | License |
| --- | --- | --- | --- |
| Tiny Streaming (English) | 34 M | 12.00% | MIT |
| Small Streaming (English) | 123 M | 7.84% | MIT |
| Medium Streaming (English) | 245 M | 6.65% | MIT |

Figures and licenses are from Moonshine v0.1.5 (`docs/models/available-models.md` and `LICENSE`).
Moonshine's LICENSE states that every streaming speech model and every English model is MIT; the only
non-MIT speech models are legacy *non-streaming* models for other languages, under the non-commercial
Moonshine AI Community License. Distillery never offers, downloads or loads those: the catalog is an
explicit allowlist, and `ModelLicensePolicy` rejects any download manifest that isn't HTTPS from
`download.moonshine.ai` in the requested English streaming model's own folder.

Models aren't bundled. The app downloads the one you pick (Wi-Fi only by default) into
`noBackupFilesDir`, using Moonshine's resumable downloader. Network access exists only for this.

## Building

See [docs/BUILDING.md](docs/BUILDING.md), including the from-source build for F-Droid.

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

## License

Distillery is free software: you can redistribute it and/or modify it under the terms of the GNU
General Public License as published by the Free Software Foundation, either version 3 of the License,
or (at your option) any later version. See [LICENSE](LICENSE).

Third-party licenses are listed in the app (Licenses screen) and their texts are in
`app/src/main/assets/licenses/`.
