#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Trains the dev build's English part-of-speech tagger.

An averaged perceptron (Collins 2002; the feature set follows Matthew
Honnibal's well-known 2013 write-up), trained on the Universal Dependencies
English Web Treebank (CC BY-SA 4.0), predicting its Penn Treebank tags
(XPOS) — the tags misaki's lexicon keys its heteronyms by. The model is
written as app/src/debug/assets/tts/pos-tagger.bin.gzip and so is itself
CC BY-SA 4.0. The Kotlin side (PosTagger.kt) must compute exactly the same
features as `features()` here.

    python3 tools/train-pos-tagger.py /path/to/UD_English-EWT
"""
import gzip
import random
import sys
from collections import defaultdict
from pathlib import Path

ITERATIONS = 8
PRUNE_BELOW = 0.05  # averaged weights smaller than this are dropped
SCALE = 100  # weights are stored as integers, weight * SCALE
START = ["-START-", "-START2-"]
END = ["-END-", "-END2-"]


def read_conllu(path):
    sentences, words, tags = [], [], []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if not line:
            if words:
                sentences.append((words, tags))
            words, tags = [], []
        elif not line.startswith("#"):
            cols = line.split("\t")
            if "-" in cols[0] or "." in cols[0]:
                continue  # multiword token ranges and empty nodes
            words.append(cols[1])
            tags.append(cols[4])
    if words:
        sentences.append((words, tags))
    return sentences


def normalize(word):
    if "-" in word and word[0] != "-":
        return "!HYPHEN"
    if word.isdigit() and len(word) == 4:
        return "!YEAR"
    if word and word[0].isdigit():
        return "!DIGITS"
    return word.lower()


def features(i, word, context, prev, prev2):
    """i indexes context, which has the two START markers in front."""
    def add(name, *args):
        feats.add(" ".join((name,) + tuple(args)))

    feats = set()
    i += len(START)
    add("bias")
    add("i suffix", word[-3:])
    add("i pref1", word[:1])
    add("i-1 tag", prev)
    add("i-2 tag", prev2)
    add("i tag+i-2 tag", prev, prev2)
    add("i word", context[i])
    add("i-1 tag+i word", prev, context[i])
    add("i-1 word", context[i - 1])
    add("i-1 suffix", context[i - 1][-3:])
    add("i-2 word", context[i - 2])
    add("i+1 word", context[i + 1])
    add("i+1 suffix", context[i + 1][-3:])
    add("i+2 word", context[i + 2])
    add("i cap", "1" if word[:1].isupper() else "0")
    return feats


class Perceptron:
    def __init__(self):
        self.weights = defaultdict(dict)
        self.classes = set()
        self._totals = defaultdict(int)
        self._stamps = defaultdict(int)
        self.i = 0

    def predict(self, feats):
        scores = defaultdict(float)
        for f in feats:
            for c, w in self.weights.get(f, {}).items():
                scores[c] += w
        return max(self.classes, key=lambda c: (scores[c], c))

    def update(self, truth, guess, feats):
        self.i += 1
        if truth == guess:
            return
        for f in feats:
            ws = self.weights[f]
            for c, delta in ((truth, 1.0), (guess, -1.0)):
                key = (f, c)
                w = ws.get(c, 0.0)
                self._totals[key] += (self.i - self._stamps[key]) * w
                self._stamps[key] = self.i
                ws[c] = w + delta

    def average(self):
        for f, ws in self.weights.items():
            averaged = {}
            for c, w in ws.items():
                key = (f, c)
                total = self._totals[key] + (self.i - self._stamps[key]) * w
                avg = total / self.i
                if abs(avg) >= PRUNE_BELOW:
                    averaged[c] = avg
            self.weights[f] = averaged


def make_tagdict(sentences):
    counts = defaultdict(lambda: defaultdict(int))
    for words, tags in sentences:
        for w, t in zip(words, tags):
            counts[w][t] += 1
    tagdict = {}
    for w, tc in counts.items():
        tag, n = max(tc.items(), key=lambda x: x[1])
        if sum(tc.values()) >= 20 and n / sum(tc.values()) >= 0.97:
            tagdict[w] = tag
    return tagdict


def tag(model, tagdict, words):
    context = START + [normalize(w) for w in words] + END
    prev, prev2 = START
    out = []
    for i, w in enumerate(words):
        t = tagdict.get(w)
        if t is None:
            t = model.predict(features(i, w, context, prev, prev2))
        out.append(t)
        prev2, prev = prev, t
    return out


def accuracy(model, tagdict, sentences):
    right = total = 0
    for words, tags in sentences:
        guess = tag(model, tagdict, words)
        right += sum(g == t for g, t in zip(guess, tags))
        total += len(tags)
    return right / total


def main():
    ewt = Path(sys.argv[1])
    train = read_conllu(ewt / "en_ewt-ud-train.conllu")
    dev = read_conllu(ewt / "en_ewt-ud-dev.conllu")
    test = read_conllu(ewt / "en_ewt-ud-test.conllu")

    tagdict = make_tagdict(train)
    model = Perceptron()
    model.classes = {t for _, tags in train for t in tags}
    rng = random.Random(1)
    for it in range(ITERATIONS):
        for words, tags in train:
            context = START + [normalize(w) for w in words] + END
            prev, prev2 = START
            for i, (w, t) in enumerate(zip(words, tags)):
                guess = tagdict.get(w)
                if guess is None:
                    feats = features(i, w, context, prev, prev2)
                    guess = model.predict(feats)
                    model.update(t, guess, feats)
                prev2, prev = prev, guess
        rng.shuffle(train)
        print(f"iteration {it + 1} done", file=sys.stderr)
    model.average()

    # Quantize exactly as the app will read it, then measure that model.
    for f, ws in model.weights.items():
        model.weights[f] = {c: round(w * SCALE) / SCALE for c, w in ws.items() if round(w * SCALE) != 0}
    model.weights = {f: ws for f, ws in model.weights.items() if ws}
    print(f"dev accuracy {accuracy(model, tagdict, dev):.4f}, test accuracy {accuracy(model, tagdict, test):.4f}")

    classes = sorted(model.classes)
    index = {c: i for i, c in enumerate(classes)}
    out = Path(__file__).resolve().parent.parent / "app/src/debug/assets/tts/pos-tagger.bin.gzip"
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from perceptron_pack import pack
    weights = {f: {index[c]: round(w * SCALE) for c, w in ws.items()} for f, ws in model.weights.items()}
    pack(classes, {w: index[t] for w, t in tagdict.items()}, weights, out)
    print(f"{out.name}: {len(model.weights)} features, {len(tagdict)} tagdict words, {out.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
