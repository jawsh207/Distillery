# How Distillery works

A plain-language guide for anyone who wants to know what Distillery does and where, without reading
code. File names are given so developers can find things; everything else is explained in words.

Distillery does two jobs on the phone itself, in English: it turns speech into text, and text into
speech. Nothing you say or type is sent anywhere. The only time it uses the internet is to download
the speech models, voices and pronunciation data you ask for.

## Speech to text

**Dictation.** Once Distillery is your phone's voice input, any keyboard or app that uses Android's
speech recognizer reaches it. Distillery records from the microphone on that app's behalf and runs a
Moonshine speech model on the phone. Words appear as you speak, and the model keeps revising the
phrase you're in the middle of until you pause. Dictation stops by itself after a pause following a
finished phrase (you choose how long), never in the middle of one, and gives up if nobody speaks.

The model stays loaded for a few minutes after you finish, so the next dictation starts quickly,
then it's released to free memory.

**Settings** (Speech to text tab › Dictation):
- *Stop after a pause*: how long a pause ends dictation.
- *Punctuation*: keep the punctuation and capitals Moonshine adds, or take them out.
- *Mask offensive words*: on by default, as Android recommends; a masked word keeps its first
  letter ("f***"). An app can ask for masking on or off, and its choice wins.

Apps can also ask for things in their request: no closing period while you're still speaking, no
closing period at all (for searches and commands), or both a formatted and a plain version of the
result.

**Recordings handed over by apps.** An app can give Distillery a recording instead of the microphone
(for example a recording it made itself); Distillery transcribes it the same way and sends the text
back. This happens in the background, without opening Distillery.

**Transcribe.** Share an audio or video file to Distillery, or choose *Transcribe a file* on the
Speech to text tab. Android's own decoders read the file, so common formats work (MP3, M4A, Ogg,
Opus, FLAC, WAV, AMR, and the sound of MP4, WebM and 3GP videos). It uses its own copy of the
speech model, so it never holds up dictation in other apps, and it keeps going when you switch apps,
with its progress in a notification. The text is split into paragraphs at long pauses, can show a
timestamp for each, and can be copied, shared, saved as a text file or discarded.

## Text to speech

**The engine.** Once Distillery is the phone's preferred text-to-speech engine, apps that read aloud
use it. Kokoro, a small open speech model, makes the sound; Distillery decides how each word is said
first:

1. The text is rewritten the way it's read aloud: "$1.50" becomes "one dollar and fifty cents",
   "Sept. 5" becomes "September fifth", and so on for times, dates, phone numbers, units and
   abbreviations.
2. Each word is looked up in this order: your own pronunciations (and a few built-in fixes), then a
   dictionary of English pronunciations, then, for words spelled the same but said differently
   ("I read" now or yesterday), a small grammar tagger picks the right one. Words in none of these
   (mostly names) are guessed letter by letter by a model trained on the dictionary.
3. Kokoro turns the result into speech, a sentence or clause at a time, and Distillery makes the next
   piece while the current one plays. Pauses are half a second after a sentence and a quarter after
   a comma.

**Voices.** 28 English Kokoro voices, American and British. Apps that don't pick a voice get the one
you chose; speech rate and pitch are Android's own settings (Text to speech tab › Speech rate and
pitch).

**Pronunciations** (Text to speech tab): fix how a word or name is said by writing how it sounds
("Bangor" sounds like "BANG gore").

**Read aloud.** Select text in any app and choose *Read aloud*, share text to Distillery, or paste it
in on the Text to speech tab. Distillery reads it sentence by sentence, highlighting the one it's
on; tap any sentence to jump to it. Pause, skip, speed and voice are below the text, and the same
controls are in the notification and on the lock screen, so it keeps reading with the screen off.
It pauses when headphones are unplugged or another app starts playing sound. A shared link isn't
read: Distillery doesn't go online to fetch web pages.

**Save as audio.** From Read aloud, save the whole text as an M4A file (small, plays almost
everywhere) or WAV (larger, plays on anything). It keeps going in the background like Transcribe.

## Downloads

The Downloads tab holds everything Distillery downloads, with the space it uses and a *Download over
Wi-Fi only* setting (on by default).

- **Speech models** (Tiny, Small, Medium) come from Moonshine's download site. Bigger is more
  accurate but slower and needs more memory.
- **Voices** come from Moonshine's download site too. The first voice also brings the Kokoro model
  (about 90 MB); each voice after that is small.
- **Pronunciation data** (the dictionary, guesser and grammar tagger for each accent, about 5 MB)
  comes with the first voice of each accent, from Distillery's own GitHub releases.

Before anything downloads, Distillery checks it's allowed: only English models and voices, only
under open licenses, only from those two places, and the pronunciation data must match the
checksums built into the app. Deleting the last voice of an accent deletes that accent's data.

## Hidden test screens

Each of the first two tabs has a test screen for checking how other apps reach Distillery. They're
hidden: tap the tab's own button in the bottom bar eight times in a row to show its Test row, and
eight more to hide it again.

## Where things are

Paths are under `app/src/main/java/app/distillery/stt/`.

| Screen or part | File |
|---|---|
| Tab bar (Speech to text, Text to speech, Downloads) | `ui/MainTabs.kt` |
| Speech to text tab | `ui/screens/SpeechToTextTab.kt` |
| Text to speech tab | `feature/TtsFeature.kt` |
| Downloads tab | `ui/screens/DownloadsTab.kt`, with `ui/screens/ModelItem.kt` and `tts/ui/VoiceItem.kt` |
| Transcribe | `transcribe/TranscribeActivity.kt` |
| Read aloud | `tts/read/ReadAloudActivity.kt` |
| Pronunciations | `tts/ui/PronunciationsScreen.kt` |
| Test speech to text (hidden) | `ui/screens/TestScreen.kt` |
| Test text to speech (hidden) | `feature/TtsFeature.kt`, with `tts/ui/EngineSpeaker.kt` and `tts/ui/DirectPlayer.kt` |
| Licenses | `ui/screens/LicensesScreen.kt` and `ui/screens/Licenses.kt` |
| Dictation for other apps | `service/DistilleryRecognitionService.kt`, `service/RecognitionSession.kt` |
| Text to speech for other apps | `tts/DistilleryTtsService.kt`, `tts/KokoroSynth.kt` |
| How words are said | `tts/TextNormalizer.kt`, `tts/Pronouncer.kt` |
| Transcribing a file | `transcribe/FileTranscription.kt`, `transcribe/AudioDecoder.kt` |
| Reading aloud and saving audio | `tts/read/ReadAloud.kt`, `tts/read/ReadAloudService.kt`, `tts/read/AudioExport.kt` |
| Downloads and what's allowed | `engine/ModelDownloadWorker.kt`, `engine/ModelLicensePolicy.kt`, `tts/VoiceDownloads.kt`, `tts/TtsLicensePolicy.kt`, `tts/PronunciationData.kt` |
| Notifications | `Notifications.kt` |
| Settings | `engine/DistillerySettings.kt`, `tts/TtsSettings.kt` |

How the app is built, including Distillery's own ONNX Runtime, is in [BUILDING.md](BUILDING.md).
