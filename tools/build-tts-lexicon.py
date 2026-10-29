#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Builds Distillery's English pronunciation lexicons from misaki.

misaki (github.com/hexgrad/misaki, Apache-2.0) is the G2P that Kokoro was
trained with; its gold and silver dictionaries are written in Kokoro's own
phoneme set. This flattens them to word<TAB>phonemes, gold over silver, taking
each word's DEFAULT reading where misaki has several by part of speech, and
gzips them into app/src/main/assets/tts/. Words with several readings
("read", "lead", "record") also go to heteronyms-<accent>.tsv.gzip as
word<TAB>TAG=phonemes;TAG=phonemes, for the part-of-speech tagger to choose from.

    python3 tools/build-tts-lexicon.py /path/to/misaki
"""
import gzip
import json
import sys
from pathlib import Path

misaki = Path(sys.argv[1])
out_dir = Path(__file__).resolve().parent.parent / "app/src/main/assets/tts"
out_dir.mkdir(parents=True, exist_ok=True)

for accent in ("us", "gb"):
    merged = {}
    heteronyms = {}
    for tier in ("silver", "gold"):  # gold last, so it wins
        data = json.loads((misaki / f"misaki/data/{accent}_{tier}.json").read_text(encoding="utf-8"))
        for word, value in data.items():
            if isinstance(value, dict):
                # misaki's VBP readings ("read", "wound") are past-tense ones that make
                # up for its tagger calling past verbs VBP; Distillery's tagger means
                # present tense by VBP, so those would be wrong here.
                readings = {t: p for t, p in value.items() if isinstance(p, str) and p and t not in ("None", "VBP")}
                if len(set(readings.values())) > 1 and "DEFAULT" in readings:
                    heteronyms[word] = readings
                else:
                    heteronyms.pop(word, None)
                value = value.get("DEFAULT")
            else:
                heteronyms.pop(word, None)
            if isinstance(value, str) and value and "\t" not in word and "\n" not in word:
                merged[word] = value
    lines = "".join(f"{w}\t{p}\n" for w, p in sorted(merged.items()))
    target = out_dir / f"lexicon-{accent}.tsv.gzip"
    # mtime=0 keeps the file byte-for-byte reproducible.
    with open(target, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0, filename="") as gz:
        gz.write(lines.encode("utf-8"))
    print(f"{target.name}: {len(merged)} words, {target.stat().st_size // 1024} KB")
    lines = "".join(
        f"{w}\t" + ";".join(f"{t}={p}" for t, p in sorted(r.items())) + "\n" for w, r in sorted(heteronyms.items())
    )
    target = out_dir / f"heteronyms-{accent}.tsv.gzip"
    with open(target, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0, filename="") as gz:
        gz.write(lines.encode("utf-8"))
    print(f"{target.name}: {len(heteronyms)} words")
