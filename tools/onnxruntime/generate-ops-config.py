#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Lists the ONNX Runtime operators Distillery's models use.

Distillery's ONNX Runtime is built with only these operators
(--include_ops_by_config), which is most of why it is a fraction of the stock
library's size. That is only safe if the list covers every model the app can
load, so it is made from the models themselves:

  * the three English streaming speech models, every file Moonshine's catalog
    lists for them;
  * Kokoro, every ONNX Runtime file the catalog lists for each voice, less the
    English pronunciation files Distillery replaces with its stand-ins;
  * the stand-in pronunciation model (app/src/main/assets/tts/standin/model.ort);
  * the Silero voice activity detector compiled into Moonshine's library.

The catalog comes from Moonshine's own library (`pip install moonshine-voice`),
so the list follows whatever its version hands the app.

    python3 tools/onnxruntime/generate-ops-config.py --moonshine-src DIR --cache DIR
"""
import argparse
import json
import pathlib
import re
import sys
import urllib.request

REPO = pathlib.Path(__file__).resolve().parents[2]
OUTPUT = REPO / "third_party/onnxruntime/distillery-operators.config"
STANDIN = REPO / "app/src/main/assets/tts/standin/model.ort"
KOKORO_VOICES = REPO / "app/src/main/java/app/distillery/stt/tts/KokoroVoice.kt"

# Model architecture numbers (moonshine_voice.ModelArch): tiny, small and medium streaming.
STT_ARCHS = {"tiny-streaming-en": 2, "small-streaming-en": 4, "medium-streaming-en": 5}
# Paths Distillery fills with its stand-ins, so the app never downloads them.
STANDIN_PATHS = ("en_us/oov/model.ort",)


def ort_urls(manifest_json):
    urls = []
    for group in json.loads(manifest_json).get("groups", []):
        base = group.get("base_url", "")
        for entry in group.get("files", []):
            if isinstance(entry, str):
                name, url = entry, f"{base}/{entry}"
            else:
                name = entry.get("name", "")
                url = entry.get("url") or f"{base}/{name}"
            if url.endswith(".ort") and not any(url.endswith("/" + p) for p in STANDIN_PATHS):
                urls.append(url)
    return urls


def catalog_urls():
    from moonshine_voice.moonshine_api import (
        moonshine_get_stt_dependencies_string,
        moonshine_get_tts_dependencies_string,
    )

    urls = set()
    for arch in STT_ARCHS.values():
        urls.update(ort_urls(moonshine_get_stt_dependencies_string("en", {"model_arch": arch})))
    voices = re.findall(r'^\s+[A-Z_]+\("([a-z]{2}_[a-z]+)"', KOKORO_VOICES.read_text(), re.MULTILINE)
    if not voices:
        raise SystemExit(f"No voices found in {KOKORO_VOICES}")
    for voice in voices:
        urls.update(ort_urls(moonshine_get_tts_dependencies_string("en_us", {"voice": f"kokoro_{voice}"})))
    return sorted(urls), voices


def fetch(url, cache):
    target = cache / url.split("://", 1)[1]
    if not (target.is_file() and target.stat().st_size > 0):
        target.parent.mkdir(parents=True, exist_ok=True)
        print(f"  fetching {url}", flush=True)
        partial = target.with_suffix(".partial")
        with urllib.request.urlopen(url) as response, open(partial, "wb") as out:
            while chunk := response.read(1 << 20):
                out.write(chunk)
        partial.rename(target)
    return target


def silero(moonshine_src, cache):
    """The voice activity model, which Moonshine compiles in as a C array."""
    text = (moonshine_src / "core/silero-vad-model-data.h").read_text()
    found = []
    for name, body in re.findall(r"unsigned\s+char\s+(\w+)\s*\[\]\s*=\s*\{(.*?)\}\s*;", text, re.DOTALL):
        data = bytes.fromhex("".join(body.replace("0x", "").replace(",", "").split()))
        if data[4:8] == b"ORTM":  # ONNX Runtime format
            target = cache / "embedded" / f"{name}.ort"
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            found.append((target, f"moonshine core/silero-vad-model-data.h:{name}"))
    if not found:
        raise SystemExit("No ONNX Runtime model found in silero-vad-model-data.h")
    return found


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--moonshine-src", type=pathlib.Path, required=True)
    ap.add_argument("--cache", type=pathlib.Path, required=True)
    ap.add_argument("--models-list", type=pathlib.Path, help="also write the model paths here, one per line")
    args = ap.parse_args()

    urls, voices = catalog_urls()
    print(f"{len(urls)} model files from the catalog ({len(voices)} voices)")
    models = [(fetch(u, args.cache), u) for u in urls]
    models.append((STANDIN, "app/src/main/assets/tts/standin/model.ort"))
    models += silero(args.moonshine_src, args.cache)

    from onnxruntime.tools.ort_format_model import utils

    raw = args.cache / "operators.config"
    utils.create_config_from_models([p for p, _ in models], raw, enable_type_reduction=False)
    body = [line for line in raw.read_text().splitlines() if line and not line.startswith("#")]
    header = [
        "# Operators Distillery's models use; its ONNX Runtime is built with only these.",
        "# Made by tools/onnxruntime/generate-ops-config.py; don't edit by hand.",
        "#",
        "# Models:",
    ] + [f"# - {name}" for name in sorted({n for _, n in models})]
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text("\n".join(header + body) + "\n")
    if args.models_list:
        args.models_list.write_text("".join(f"{p}\n" for p, _ in models))
    print(f"{OUTPUT.relative_to(REPO)}: {sum(len(l.split(';')[2].split(',')) for l in body)} operators")


if __name__ == "__main__":
    sys.exit(main())
