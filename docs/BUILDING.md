# Building Distillery

## Requirements

- JDK 17 or newer
- Android SDK with platform 37 (the app targets and runs on Android 16+)
- For the from-source build only: Android NDK 28.2.13676358, CMake 3.22+, Ninja, Python 3

## Quick build

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

This uses `ai.moonshine:moonshine-voice` (MIT) from Maven Central, and Distillery's own build of
ONNX Runtime 1.23.2, which Gradle downloads from this repository's releases and checks against the
SHA-256 sums in `third_party/onnxruntime/libraries.properties`.

## ONNX Runtime

Moonshine's AAR carries an ONNX Runtime cut down to the operators of Moonshine's own models, and
its 0.1.5 build lacks some that Kokoro needs. Distillery replaces it with ONNX Runtime 1.23.2 (the
version Moonshine's library is linked against) built from source with only the operators
Distillery's models use. `.github/workflows/onnxruntime.yml` does all of it:

1. `tools/onnxruntime/generate-ops-config.py` reads every model the app can load (the three English
   streaming speech models, Kokoro, the pronunciation stand-in and Moonshine's built-in voice
   detector) and writes `third_party/onnxruntime/distillery-operators.config`.
2. `tools/onnxruntime/build.sh` builds a minimal ONNX Runtime with only those operators for
   Android arm64-v8a and x86_64, and for Linux.
3. `tools/onnxruntime/smoke-test.py` runs every model on the Linux build through Moonshine's own
   library, so a missing operator fails there rather than on a phone.
4. The Android libraries are published as an `onnxruntime-…` release, and their checksums are
   committed to `third_party/onnxruntime/libraries.properties`.

To build the libraries yourself (Android NDK 28.2.13676358, CMake 3.28+, Python 3 with
`flatbuffers`; ONNX Runtime 1.23 doesn't build with Ubuntu 26.04's GCC):

```sh
tools/onnxruntime/build.sh android-arm64-v8a ort/arm64-v8a
tools/onnxruntime/build.sh android-x86_64 ort/x86_64      # only for debug builds
./gradlew assembleRelease -Pdistillery.ortDir=ort
```

## From-source build (F-Droid, reproducible builds)

Settings include Moonshine's Android binding as a composite build when
`distillery.moonshineSource` points at a Moonshine checkout, and substitute it for the Maven artifact.

```sh
# 1. Moonshine at the tag matching moonshineVoice in gradle/libs.versions.toml
git submodule add https://github.com/moonshine-ai/moonshine third_party/moonshine
git -C third_party/moonshine checkout v0.1.5

# 2. Distillery's ONNX Runtime, from source (see above)
export ANDROID_HOME=/path/to/sdk
tools/onnxruntime/build.sh android-arm64-v8a ort/arm64-v8a

# 3. Build the app against both
./gradlew assembleRelease -Pdistillery.moonshineSource=third_party/moonshine -Pdistillery.ortDir=ort
```

`docs/fdroid/app.distillery.stt.yml` is a starting point for the fdroiddata recipe. Expect
the F-Droid reviewers to adjust it (for example, to move the ONNX Runtime checkout into a srclib).

## Notes for F-Droid

- No proprietary dependencies: AndroidX, Jetpack Compose, Kotlin, OkHttp and Moonshine are all
  Apache-2.0 or MIT. There are no Google Play Services, Firebase or analytics.
- `dependenciesInfo` is disabled, so the APK carries no Google-signed dependency blob.
- The app downloads MIT-licensed speech models and Kokoro voices from `download.moonshine.ai`
  when the user asks, and with the first voice of each accent, Distillery's own pronunciation data
  (`tts-data/`) from this repository's GitHub releases, checked against
  `app/src/main/assets/tts/data.properties`. Recognition and speech never use the network.
- Version code and name live in `app/build.gradle.kts`; store text is in `fastlane/metadata/`.

## Material 3 version

The app pins `androidx.compose.material3:material3` to the 1.5 line (see `libs.versions.toml`),
because `MaterialExpressiveTheme`, `MotionScheme.expressive()`, the flexible top app bars, wavy
progress and loading indicators are internal in the 1.4 stable release. A few classic APIs used
here (`ListItem(headlineContent = …)`, `Slider(value = …)`) are deprecated on the 1.6 line; the files
that use them suppress the warning and can move to the new overloads when 1.6 is stable.

## Release signing

Release APKs are signed with the key in `distillery-release.jks` (alias `distillery`). The
keystore is **not** in the repository. Keep a backup somewhere safe: Android only installs updates
signed with the same key, so losing it means users must uninstall to upgrade.

### GitHub Actions

`.github/workflows/release.yml` builds and signs the APK and publishes a GitHub release tagged
`v<versionName>`. It runs when `main` is pushed to the `release` branch, when a `v*` tag is
pushed, or by hand from the Actions tab. It needs four repository secrets
(Settings › Secrets and variables › Actions):

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 distillery-release.jks` (macOS: `base64 -i distillery-release.jks`) |
| `RELEASE_KEYSTORE_PASSWORD` | the keystore password |
| `RELEASE_KEY_ALIAS` | `distillery` |
| `RELEASE_KEY_PASSWORD` | the key password (same as the keystore password) |

To release: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (a short summary for users; it's
also shown in F-Droid). The release page shows that summary followed by every commit since the
previous release, generated automatically. Then

```sh
git push origin main:release
```

### Local builds

Create `keystore.properties` in the project root (it's git-ignored):

```properties
storeFile=/path/to/distillery-release.jks
storePassword=...
keyAlias=distillery
keyPassword=...
```

then run `./gradlew assembleRelease`. Without signing values the release build is left unsigned.
