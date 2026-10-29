#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Trains the dev build's guesser for words that aren't in the lexicon.

Letter-to-sound in two steps, all in plain Python:

1. Alignment. Every word in misaki's gold and silver lexicons
   (github.com/hexgrad/misaki, Apache-2.0) is lined up letter by letter with
   its phonemes, each letter standing for nothing, one phoneme or two
   ("x" → "ks"), by hard expectation-maximisation.
2. Classification. An averaged perceptron predicts each letter's phonemes,
   left to right, from the letters around it and the phonemes it just chose.

The result is written to app/src/debug/assets/tts/guesser-<accent>.tsv.gzip.
WordGuesser.kt reads it and must compute exactly the same features as
`features()` here.

    python3 tools/train-word-guesser.py /path/to/misaki [us|gb]
"""
import gzip
import json
import math
import os
import random
import sys
from collections import defaultdict
from pathlib import Path

ITERATIONS = int(os.environ.get("GUESSER_ITERATIONS", "6"))
EM_ROUNDS = 4
PRUNE_BELOW = 0.1
SCALE = 100
PAD = "#"
STRESS = "ˈˌ"
HELD_OUT = 2000


def units(phonemes):
    """Splits misaki phonemes into units; stress marks go with the next phoneme, ː with the last."""
    out, pending = [], ""
    for ch in phonemes:
        if ch in STRESS:
            pending += ch
        elif ch == "ː" and out:
            out[-1] += ch
        else:
            out.append(pending + ch)
            pending = ""
    return out


def load_words(misaki, accent):
    merged = {}
    for tier in ("gold", "silver"):  # gold first, so it wins
        data = json.loads((misaki / f"misaki/data/{accent}_{tier}.json").read_text(encoding="utf-8"))
        for word, value in data.items():
            if isinstance(value, dict):
                value = value.get("DEFAULT")
            if not isinstance(value, str) or not value:
                continue
            key = word.lower()
            if not (2 <= len(key) <= 20) or not all("a" <= c <= "z" or c == "'" for c in key):
                continue
            u = units(value)
            if not u or len(u) > 2 * len(key) or " " in value:
                continue
            merged.setdefault(key, u)
    return sorted(merged.items())


def strip(u):
    return u.lstrip(STRESS)


def align(words):
    """Hard EM: each letter takes 0, 1 or 2 phoneme units."""
    def prior(k):
        return math.log((0.15, 0.7, 0.15)[k])

    logp = None
    alignments = {}
    for rnd in range(EM_ROUNDS):
        counts = defaultdict(lambda: defaultdict(float))
        for word, us in words:
            plain = [strip(u) for u in us]
            m, n = len(word), len(plain)
            NEG = -1e18
            best = [[NEG] * (n + 1) for _ in range(m + 1)]
            back = [[0] * (n + 1) for _ in range(m + 1)]
            best[0][0] = 0.0
            for i in range(m):
                lo = max(0, n - 2 * (m - i))
                for j in range(lo, min(n, 2 * i) + 1):
                    if best[i][j] == NEG:
                        continue
                    for k in (0, 1, 2):
                        if j + k > n:
                            break
                        chunk = "".join(plain[j:j + k])
                        if logp is None:
                            s = prior(k)
                        else:
                            s = logp[word[i]].get(chunk, -14.0)
                        v = best[i][j] + s
                        if v > best[i + 1][j + k]:
                            best[i + 1][j + k] = v
                            back[i + 1][j + k] = k
            if best[m][n] == NEG:
                continue
            ks, j = [], n
            for i in range(m, 0, -1):
                k = back[i][j]
                ks.append(k)
                j -= k
            ks.reverse()
            alignments[word] = ks
            j = 0
            for i, k in enumerate(ks):
                counts[word[i]]["".join(plain[j:j + k])] += 1
                j += k
        logp = {}
        for letter, cs in counts.items():
            total = sum(cs.values())
            logp[letter] = {c: math.log(v / total) for c, v in cs.items()}
        print(f"alignment round {rnd + 1}: {len(alignments)} words", file=sys.stderr)
    return alignments


def labels_for(word, us, ks):
    out, j = [], 0
    for k in ks:
        out.append("".join(us[j:j + k]) or "_")
        j += k
    return out


def features(word, i, p1, p2):
    w = PAD * 4 + word + PAD * 4
    c = i + 4

    def l(o):
        return w[c + o]

    return (
        "bias",
        "l0 " + l(0),
        "l-1 " + l(-1),
        "l-2 " + l(-2),
        "l-3 " + l(-3),
        "l+1 " + l(1),
        "l+2 " + l(2),
        "l+3 " + l(3),
        "l-1l0 " + l(-1) + l(0),
        "l0l+1 " + l(0) + l(1),
        "l-1l0l+1 " + l(-1) + l(0) + l(1),
        "l-2l-1l0 " + l(-2) + l(-1) + l(0),
        "l0l+1l+2 " + l(0) + l(1) + l(2),
        "l-2..l+2 " + w[c - 2:c + 3],
        "l-3..l+1 " + w[c - 3:c + 2],
        "l-1..l+3 " + w[c - 1:c + 4],
        "p1 " + p1,
        "p2p1 " + p2 + " " + p1,
        "p1l0 " + p1 + " " + l(0),
        "p1l0l+1 " + p1 + " " + l(0) + l(1),
    )


class Perceptron:
    def __init__(self, n_classes):
        self.n = n_classes
        self.w = defaultdict(dict)
        self.totals = defaultdict(float)
        self.stamps = defaultdict(int)
        self.i = 0

    def predict(self, feats):
        scores = defaultdict(float)
        for f in feats:
            ws = self.w.get(f)
            if ws:
                for c, v in ws.items():
                    scores[c] += v
        # Same rule as a dense argmax over all classes, ties to the lowest index
        # (classes with no weights here score 0), which is what the app does.
        absent = 0
        while absent in scores:
            absent += 1
        if not scores:
            return 0
        best = max(scores, key=lambda c: (scores[c], -c))
        if absent < self.n and (scores[best] < 0 or (scores[best] == 0 and absent < best)):
            return absent
        return best

    def update(self, truth, guess, feats):
        self.i += 1
        if truth == guess:
            return
        for f in feats:
            ws = self.w[f]
            for c, d in ((truth, 1.0), (guess, -1.0)):
                key = (f, c)
                v = ws.get(c, 0.0)
                self.totals[key] += (self.i - self.stamps[key]) * v
                self.stamps[key] = self.i
                ws[c] = v + d

    def average(self):
        for f, ws in self.w.items():
            new = {}
            for c, v in ws.items():
                key = (f, c)
                avg = (self.totals[key] + (self.i - self.stamps[key]) * v) / self.i
                q = round(avg * SCALE)
                if abs(avg) >= PRUNE_BELOW and q != 0:
                    new[c] = q
            self.w[f] = new
        self.w = {f: ws for f, ws in self.w.items() if ws}


def guess(model, classes, word):
    p1 = p2 = PAD
    out = []
    for i in range(len(word)):
        c = model.predict(features(word, i, p1, p2))
        lab = classes[c]
        out.append(lab)
        p2, p1 = p1, lab
    return fix_stress("".join(x for x in out if x != "_"))


VOWELS = set("AIOWYaeiouæɑɒɔəɛɜɪʊʌᵻᵊQ")


def fix_stress(ph):
    """Exactly one primary stress: keep the first and demote the rest, or promote
    the first secondary stress, or put one before the first vowel."""
    if "ˈ" in ph:
        first = ph.index("ˈ")
        return ph[:first + 1] + ph[first + 1:].replace("ˈ", "ˌ")
    if "ˌ" in ph:
        return ph.replace("ˌ", "ˈ", 1)
    for k, ch in enumerate(ph):
        if ch in VOWELS:
            return ph[:k] + "ˈ" + ph[k:]
    return ph


def edit_distance(a, b):
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


def main():
    misaki = Path(sys.argv[1])
    accent = sys.argv[2] if len(sys.argv) > 2 else "us"
    words = load_words(misaki, accent)
    if os.environ.get("GUESSER_SUBSET"):  # quick end-to-end check of the script
        words = random.Random(3).sample(words, int(os.environ["GUESSER_SUBSET"]))
    print(f"{len(words)} words", file=sys.stderr)
    alignments = align(words)

    data = [(w, labels_for(w, us, alignments[w])) for w, us in words if w in alignments]
    rng = random.Random(7)
    rng.shuffle(data)
    test, train = data[:HELD_OUT], data[HELD_OUT:]

    classes = sorted({lab for _, labs in data for lab in labs})
    index = {c: k for k, c in enumerate(classes)}
    model = Perceptron(len(classes))
    for it in range(ITERATIONS):
        for word, labs in train:
            p1 = p2 = PAD
            for i, lab in enumerate(labs):
                feats = features(word, i, p1, p2)
                g = model.predict(feats)
                model.update(index[lab], g, feats)
                p2, p1 = p1, classes[g]  # train on its own choices, as it runs
        rng.shuffle(train)
        print(f"iteration {it + 1} done", file=sys.stderr)
    model.average()

    right = errs = total = 0
    for word, labs in test:
        truth = fix_stress("".join(x for x in labs if x != "_"))
        got = guess(model, classes, word)
        right += got == truth
        errs += edit_distance(units(got), units(truth))
        total += len(units(truth))
    print(f"held-out words: {right / len(test):.3f} exactly right, phoneme error rate {errs / total:.3f}")

    out = Path(__file__).resolve().parent.parent / f"app/src/debug/assets/tts/guesser-{accent}.tsv.gzip"
    lines = [f"# Distillery word guesser ({accent}): averaged perceptron trained on misaki's lexicon (Apache-2.0).",
             "C\t" + "\t".join(classes)]
    for f in sorted(model.w):
        lines.append("F\t" + f + "\t" + ",".join(f"{c}:{v}" for c, v in sorted(model.w[f].items())))
    with open(out, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0, filename="") as gz:
        gz.write(("\n".join(lines) + "\n").encode("utf-8"))
    print(f"{out.name}: {len(classes)} labels, {len(model.w)} features, {out.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
