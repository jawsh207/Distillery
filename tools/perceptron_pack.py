#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Packs a perceptron model into the binary form the app loads quickly.

Text models took seconds to parse on a phone, so the tagger and word guesser
ship as gzipped binary instead. Feature names are replaced by their 64-bit
FNV-1a hash (the app hashes features the same way), sorted for binary search.

Layout, big-endian:
    "DPM1"
    int classes, then each: short length + UTF-8 label
    int tagdict entries, then each: short length + UTF-8 word, short class
    int features; long[features] hashes (ascending, signed);
    int[features + 1] offsets into the pairs
    int pairs; short[pairs] classes; int[pairs] weights

    python3 tools/perceptron_pack.py tagger in.tsv.gzip out.bin.gzip
    python3 tools/perceptron_pack.py guesser in.tsv.gzip out.bin.gzip
"""
import gzip
import struct
import sys

MASK = (1 << 64) - 1


def fnv1a64(text):
    h = 0xCBF29CE484222325
    for b in text.encode("utf-8"):
        h ^= b
        h = (h * 0x100000001B3) & MASK
    return h - (1 << 64) if h >= 1 << 63 else h


def pack(classes, tagdict, weights, out_path):
    """weights: feature name → {class index: int weight}."""
    by_hash = {}
    for name, ws in weights.items():
        h = fnv1a64(name)
        if h in by_hash:
            raise SystemExit(f"hash collision between {by_hash[h][0]!r} and {name!r}")
        by_hash[h] = (name, ws)
    hashes = sorted(by_hash)
    offsets, pair_classes, pair_weights = [0], [], []
    for h in hashes:
        for c, w in sorted(by_hash[h][1].items()):
            pair_classes.append(c)
            pair_weights.append(w)
        offsets.append(len(pair_classes))

    def utf(s):
        b = s.encode("utf-8")
        return struct.pack(">H", len(b)) + b

    parts = [b"DPM1", struct.pack(">i", len(classes))]
    parts += [utf(c) for c in classes]
    parts.append(struct.pack(">i", len(tagdict)))
    for word, c in sorted(tagdict.items()):
        parts.append(utf(word) + struct.pack(">h", c))
    parts.append(struct.pack(">i", len(hashes)))
    parts.append(struct.pack(f">{len(hashes)}q", *hashes))
    parts.append(struct.pack(f">{len(offsets)}i", *offsets))
    parts.append(struct.pack(">i", len(pair_classes)))
    parts.append(struct.pack(f">{len(pair_classes)}h", *pair_classes))
    parts.append(struct.pack(f">{len(pair_weights)}i", *pair_weights))
    with open(out_path, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0, filename="") as gz:
        gz.write(b"".join(parts))


def read_text_model(kind, path):
    classes, tagdict, weights = [], {}, {}
    for line in gzip.open(path, "rt", encoding="utf-8"):
        line = line.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if parts[0] == "C":
            classes = parts[1].split(" ") if kind == "tagger" else parts[1:]
        elif parts[0] == "T":
            tagdict[parts[1]] = parts[2]
        elif parts[0] == "F":
            weights[parts[1]] = {int(a): int(b) for a, b in (x.split(":") for x in parts[2].split(","))}
    index = {c: i for i, c in enumerate(classes)}
    return classes, {w: index[t] for w, t in tagdict.items()}, weights


if __name__ == "__main__":
    kind, src, dst = sys.argv[1:4]
    pack(*read_text_model(kind, src), dst)
    print(dst)
