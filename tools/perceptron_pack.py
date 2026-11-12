#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Packs a perceptron model into the binary form the app loads quickly.

Text models took seconds to parse on a phone, so the tagger and word guesser
ship as gzipped binary instead. Feature names are replaced by a 32-bit hash
(64-bit FNV-1a, its two halves xored; the app hashes features the same way),
sorted as signed ints for binary search. Weights are 16-bit, rounded to steps
of STEP: only their order matters, and the coarser values compress better
while changing almost no predictions (checked when this format came in: the
word guessers and the tagger give the same answer for over 99.6% of words).

Layout, big-endian:
    "DPM2"
    int classes, then each: short length + UTF-8 label
    int tagdict entries, then each: short length + UTF-8 word, short class
    int features; int[features] hashes (ascending, signed);
    short[features] number of weights per feature
    int pairs; short[pairs] classes; short[pairs] weights

    python3 tools/perceptron_pack.py repack in.bin.gzip out.bin.gzip   (DPM1 or DPM2)
"""
import gzip
import struct
import sys

MASK = (1 << 64) - 1
STEP = 4


def fnv1a64(text):
    h = 0xCBF29CE484222325
    for b in text.encode("utf-8"):
        h ^= b
        h = (h * 0x100000001B3) & MASK
    return h - (1 << 64) if h >= 1 << 63 else h


def hash32(text):
    """Signed 32-bit: the two halves of fnv1a64 xored, as PackedPerceptron.hash32."""
    u = fnv1a64(text) & MASK
    v = (u ^ (u >> 32)) & 0xFFFFFFFF
    return v - (1 << 32) if v >= 1 << 31 else v


def pack(classes, tagdict, weights, out_path, step=STEP, hashed=False):
    """weights: feature name (or, with hashed, its 32-bit hash) → {class index: int weight}.

    Two features with the same hash can't both be kept; the one with the smaller
    weights is dropped (a handful in a model of 300,000 features).
    """
    by_hash, strength, dropped = {}, {}, 0
    for name, ws in weights.items():
        ws = {c: round(w / step) for c, w in ws.items()}
        ws = {c: w for c, w in ws.items() if w != 0}
        if not ws:
            continue
        h = name if hashed else hash32(name)
        s = sum(abs(w) for w in ws.values())
        if h in by_hash:
            dropped += 1
            if strength[h] >= s:
                continue
        by_hash[h] = ws
        strength[h] = s
    if dropped:
        print(f"{dropped} features dropped for sharing a hash", file=sys.stderr)
    hashes = sorted(by_hash)
    counts, pair_classes, pair_weights = [], [], []
    for h in hashes:
        items = sorted(by_hash[h].items())
        counts.append(len(items))
        for c, w in items:
            if not -32768 <= w <= 32767:
                raise SystemExit(f"weight {w} doesn't fit in 16 bits; use a larger step")
            pair_classes.append(c)
            pair_weights.append(w)

    def utf(s):
        b = s.encode("utf-8")
        return struct.pack(">H", len(b)) + b

    parts = [b"DPM2", struct.pack(">i", len(classes))]
    parts += [utf(c) for c in classes]
    parts.append(struct.pack(">i", len(tagdict)))
    for word, c in sorted(tagdict.items()):
        parts.append(utf(word) + struct.pack(">h", c))
    parts.append(struct.pack(">i", len(hashes)))
    parts.append(struct.pack(f">{len(hashes)}i", *hashes))
    parts.append(struct.pack(f">{len(counts)}H", *counts))
    parts.append(struct.pack(">i", len(pair_classes)))
    parts.append(struct.pack(f">{len(pair_classes)}h", *pair_classes))
    parts.append(struct.pack(f">{len(pair_weights)}h", *pair_weights))
    with open(out_path, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0, filename="", compresslevel=9) as gz:
        gz.write(b"".join(parts))


def read_packed(path):
    """Reads a DPM1 or DPM2 model: (classes, tagdict, {hash32: {class: weight}}, step it was packed with)."""
    b = gzip.open(path).read()
    o = 4

    def take(fmt, n=1):
        nonlocal o
        v = struct.unpack_from(f">{n}{fmt}", b, o)
        o += struct.calcsize(f">{n}{fmt}")
        return v if n != 1 else v[0]

    def utf():
        nonlocal o
        n = take("H")
        s = b[o:o + n].decode("utf-8")
        o += n
        return s

    magic = b[:4]
    classes = [utf() for _ in range(take("i"))]
    tagdict = {}
    for _ in range(take("i")):
        w = utf()
        tagdict[w] = take("h")
    n = take("i")
    weights = {}
    if magic == b"DPM1":
        hashes = take("q", n)
        offsets = take("i", n + 1)
        p = take("i")
        pc, pw = take("h", p), take("i", p)
        for k, h in enumerate(hashes):
            u = h & MASK
            v = (u ^ (u >> 32)) & 0xFFFFFFFF
            v = v - (1 << 32) if v >= 1 << 31 else v
            ws = {pc[j]: pw[j] for j in range(offsets[k], offsets[k + 1])}
            if v not in weights or sum(map(abs, weights[v].values())) < sum(map(abs, ws.values())):
                weights[v] = ws
        return classes, tagdict, weights, 1
    if magic != b"DPM2":
        raise SystemExit(f"{path}: not a packed model")
    hashes = take("i", n)
    counts = take("H", n)
    p = take("i")
    pc, pw = take("h", p), take("h", p)
    j = 0
    for h, c in zip(hashes, counts):
        weights[h] = {pc[k]: pw[k] for k in range(j, j + c)}
        j += c
    return classes, tagdict, weights, STEP


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
    if sys.argv[1] == "repack":
        src, dst = sys.argv[2:4]
        classes, tagdict, weights, step = read_packed(src)
        if step != 1:
            raise SystemExit(f"{src} is already DPM2")
        pack(classes, tagdict, weights, dst, hashed=True)
    else:
        kind, src, dst = sys.argv[1:4]
        pack(*read_text_model(kind, src), dst)
    print(dst)
