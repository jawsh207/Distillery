# Contributing to Distillery

Thanks for helping. Distillery is licensed under the Apache License 2.0 (see [LICENSE](LICENSE)
and [NOTICE](NOTICE)); by contributing you agree your contribution is licensed the same way.

## Documentation is part of every change

1. **Every source file describes itself.** Each Kotlin file has a `/** … */` description of its
   main class, object or screen; each Python tool opens with a docstring; each shell script with a
   comment. Say in plain words what it is, why it exists, and anything surprising (an Android rule
   it works around, why it runs on the thread or in the process it does). When you change a file,
   re-read its description and fix whatever your change made wrong.
2. **Comments explain why, not what.** Android behaviours, Moonshine or Kokoro quirks, and why one
   approach was chosen over another. Don't narrate lines the code already says.
3. **Every source file starts with `// SPDX-License-Identifier: Apache-2.0`** (or `#` for Python
   and shell).
4. **Update [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md)** in the same change when you add,
   remove, rename or move a feature, screen, setting or file. It's the plain-language guide for
   people who don't read code, so keep it free of code and keep its table of screens accurate.
5. **Update [README.md](README.md)** for anything about installing, permissions, downloads or how a
   feature behaves, and [docs/BUILDING.md](docs/BUILDING.md) for anything about building.
6. **Only document what's there.** Check each claim against the code before writing it.

CI runs `python3 tools/check-docs.py` on every push and fails if a file has no description or
license header. Run it yourself before committing.

## Free and open source only

Distillery is FOSS and meant for F-Droid. Every library, model and data file it ships or downloads
must be under an OSI-approved license, listed on the app's licenses screen
(`ui/screens/Licenses.kt`) with its license text in `app/src/main/assets/licenses/`. Speech models
and voices are checked against `ModelLicensePolicy` and `TtsLicensePolicy` before they download.
No tracking, analytics or network use beyond the downloads the user asks for.

## Commits

A short summary line, then a few lines on what changed and why. Keep unrelated changes in separate
commits.

## Building and testing

`./gradlew assembleDebug testDebugUnitTest` builds and runs the unit tests; see
[docs/BUILDING.md](docs/BUILDING.md). Every push to `main` is built on GitHub Actions, which also
checks the APKs carry the text-to-speech engine and the right ONNX Runtime. Say plainly in a pull
request what you couldn't test on a real phone.
