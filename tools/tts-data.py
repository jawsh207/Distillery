#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the manifest of the pronunciation data the app downloads.

The lexicons, heteronym lists, word guessers and part-of-speech tagger in
tts-data/ aren't in the APK: they come with the first voice of each accent,
from a GitHub release of this repository (.github/workflows/tts-data.yml
publishes it). The APK carries only app/src/main/assets/tts/data.properties:
the release's tag and each file's SHA-256 and size, which the app checks every
download against.

    python3 tools/tts-data.py           # rewrite the manifest
    python3 tools/tts-data.py --check   # fail if it doesn't match tts-data/
"""
import hashlib
import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
DATA = REPO / "tts-data"
MANIFEST = REPO / "app/src/main/assets/tts/data.properties"


def manifest():
    files = sorted(p for p in DATA.iterdir() if p.is_file() and not p.name.startswith("."))
    lines = []
    for p in files:
        lines.append(f"file.{p.name}={hashlib.sha256(p.read_bytes()).hexdigest()} {p.stat().st_size}")
    tag = "tts-data-" + hashlib.sha256("\n".join(lines).encode()).hexdigest()[:8]
    return "\n".join(
        ["# Pronunciation data the app downloads; written by tools/tts-data.py.", f"tag={tag}"] + lines
    ) + "\n"


def main():
    text = manifest()
    if "--check" in sys.argv:
        if not MANIFEST.is_file() or MANIFEST.read_text() != text:
            print(f"{MANIFEST.relative_to(REPO)} doesn't match tts-data/; run tools/tts-data.py", file=sys.stderr)
            return 1
        print(text.splitlines()[1])
        return 0
    MANIFEST.write_text(text)
    print(text, end="")
    return 0


if __name__ == "__main__":
    sys.exit(main())
