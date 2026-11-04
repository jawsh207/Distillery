#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Runs Distillery's models on Distillery's ONNX Runtime.

Moonshine's Linux library (from `pip install moonshine-voice`) is pointed at
the runtime built for Linux from the same operator list as the Android one,
then made to do what the app does: transcribe a recording with each English
streaming model, and speak from phonemes with Kokoro, its English
pronunciation files replaced by Distillery's stand-ins. A missing operator
fails here, when the model loads, rather than on a phone.

    python3 tools/onnxruntime/smoke-test.py --runtime libonnxruntime.so --wav beckett.wav --cache DIR
"""
import argparse
import pathlib
import shutil
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
STANDINS = {  # as StandIns.kt
    "en_us/dict_filtered_heteronyms.tsv": "dict.tsv",
    "en_us/g2p-config.json": "g2p-config.json",
    "en_us/oov/model.ort": "model.ort",
    "en_us/oov/onnx-config.json": "onnx-config.json",
}
PHONEMES = "həlˈoʊ, ðɪs ɪz dɪstˈɪləɹi ɹˈiːdɪŋ ɐlˈaʊd."


def use_runtime(runtime):
    """Replaces the runtime bundled with moonshine_voice by ours."""
    import moonshine_voice

    package = pathlib.Path(moonshine_voice.__file__).parent
    # Wheels repaired by auditwheel keep shared libraries in moonshine_voice.libs/.
    places = [package] + sorted(package.parent.glob("moonshine_voice*.libs"))
    bundled = [
        p for place in places for p in place.rglob("libonnxruntime*.so*")
        if p.is_file() and not p.is_symlink()
    ]
    if not bundled:
        import subprocess

        for lib in package.rglob("libmoonshine*.so*"):
            print(subprocess.run(["readelf", "-d", str(lib)], capture_output=True, text=True).stdout)
        listing = "\n".join(str(p.relative_to(package.parent)) for place in places for p in place.rglob("*.so*"))
        raise SystemExit(f"No libonnxruntime in {places}; shared libraries:\n{listing}")
    for p in bundled:
        print(f"replacing {p.relative_to(package.parent)} ({p.stat().st_size // 1024} KB)")
        shutil.copyfile(runtime, p)


def loaded_runtime():
    for line in open("/proc/self/maps"):
        if "libonnxruntime" in line:
            return line.split()[-1]
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runtime", type=pathlib.Path, required=True)
    ap.add_argument("--wav", type=pathlib.Path, required=True)
    ap.add_argument("--cache", type=pathlib.Path, required=True)
    args = ap.parse_args()
    use_runtime(args.runtime)

    from moonshine_voice.download import get_model_for_language
    from moonshine_voice.moonshine_api import ModelArch
    from moonshine_voice.transcriber import Transcriber
    from moonshine_voice.tts import TextToSpeech
    from moonshine_voice.utils import load_wav_file

    failures = []
    audio, rate = load_wav_file(args.wav)
    for arch in (ModelArch.TINY_STREAMING, ModelArch.SMALL_STREAMING, ModelArch.MEDIUM_STREAMING):
        try:
            path, arch = get_model_for_language("en", arch, cache_root=args.cache / "stt")
            with Transcriber(path, arch) as transcriber:
                transcript = transcriber.transcribe_without_streaming(audio, rate)
            text = " ".join(line.text for line in transcript.lines).strip()
            print(f"{arch.name}: {text[:100]!r}")
            if len(text.split()) < 5:
                failures.append(f"{arch.name}: too little text")
        except Exception as e:  # noqa: BLE001
            failures.append(f"{arch.name}: {e}")

    root = args.cache / "tts"
    for path, name in STANDINS.items():
        target = root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(REPO / "app/src/main/assets/tts/standin" / name, target)
    for voice in ("af_heart", "bf_emma"):
        try:
            tts = TextToSpeech().language("en_us").voice(f"kokoro_{voice}").models_from(root, download=True)
            tts.load()
            samples, sample_rate = tts.synthesize_from_phonemes(PHONEMES)
            tts.close()
            seconds = len(samples) / sample_rate
            print(f"kokoro_{voice}: {seconds:.2f} s of audio")
            if seconds < 1:
                failures.append(f"kokoro_{voice}: only {seconds:.2f} s of audio")
        except Exception as e:  # noqa: BLE001
            failures.append(f"kokoro_{voice}: {e}")

    used = loaded_runtime()
    print(f"runtime in use: {used}")
    if used is None or pathlib.Path(used).stat().st_size != args.runtime.stat().st_size:
        failures.append(f"the runtime in use isn't the one under test: {used}")
    for path, name in STANDINS.items():
        if (root / path).read_bytes() != (REPO / "app/src/main/assets/tts/standin" / name).read_bytes():
            failures.append(f"{path} was replaced by a download")
    if failures:
        print("FAILED:\n  " + "\n  ".join(failures))
        return 1
    print("All models loaded and ran.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
